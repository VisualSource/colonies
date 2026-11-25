package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Stable's stablemaster (the mod's EntityAIWorkStablemaster and AbstractEntityAIHerder), the mod's numbers unless
 * noted: it breeds the horses in its building with golden apples (up to two cavalry horses a stable level), trains grown
 * horses into cavalry horses, readies its cavalry horses for combat (feeding the hurt, working the unfit with leather)
 * and rounds up horses that strayed, leading them to the stalls on a lead. It never butchers. Unridden, its cavalry
 * horses stroll about the stable and come home 5 minutes after their rider got off (the mod's horse goals).
 */
public final class Stablemaster extends Job {
    private static final int DECIDE_TICKS = 80, STEP_TICKS = 10;        // the herder's decide delay; its own states' rate
    private static final double TRAIN_CHANCE = 0.25, READY_CHANCE = 0.40, ROUNDUP_CHANCE = 0.15;
    private static final int TRAIN_COOLDOWN = 50, ROUNDUP_COOLDOWN = 100; // in decisions
    private static final int MOUNTS_PER_LEVEL = 2;
    private static final double RECOVERY = 4.0, RECOVERY_SKILL_PAR = 20;  // BASE_COMBAT_READINESS_RECOVERY: 4 × athletics / 20 a leather
    private static final double FEED_HEAL = 0.2;                          // a meal heals a fifth of its health
    private static final int FEED_TEMPER = 3;
    private static final int ROUNDUP_RANGE = 30, HORSE_RANGE = 50;
    private static final int LINGER_TICKS = 6000, HOME_DISTANCE = 20;     // LINGER_AFTER_DISMOUNT, ReturnToStableGoal's start distance
    private static final int APPLES_WANTED = 16, APPLES_MIN = 2, SUPPLY_WANTED = 8, SUPPLY_MIN = 4; // ours: 2 × 8, as the herders ask
    private static final double REACH = 2.5;                              // ours
    private static final int CHASE_TRIES = 30;                            // ours, as the herders'
    /** The mod's feed tag: what horses eat. */
    private static final Set<Material> FEED = EnumSet.of(Material.WHEAT, Material.SUGAR, Material.HAY_BLOCK, Material.APPLE,
            Material.GOLDEN_CARROT, Material.GOLDEN_APPLE, Material.CARROT);
    /** The mod's leather tag, as items its horses are worked with: leather horse armor readies one at once. */
    private static final Set<Material> LEATHER = EnumSet.of(Material.LEATHER, Material.LEATHER_HORSE_ARMOR, Material.RABBIT_HIDE);

    private enum Act { NONE, TRAIN, READY, ROUNDUP, BREED }

    private Act act = Act.NONE;
    private AbstractHorse target, partner;
    private int tries, trainIn, roundupIn, nextStall; // trainIn, roundupIn: decisions to wait (saved); nextStall: the stalls in turn
    private boolean leading;

    public Stablemaster(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    public String title() {
        return "Stablemaster";
    }

    @Override
    public Skill[] skills() {
        return sk(Skill.ATHLETICS, Skill.DEXTERITY);
    }

    // ---- its horses ---------------------------------------------------------------------------------------------

    /** The stable's building, a little up and down (the mod's herding area: its corners). */
    private BoundingBox area() {
        int[] f = colony.footprint(hut);
        int y = hut.block.getY();
        return f == null ? BoundingBox.of(hut.block.getLocation(), 8, 8, 8) : new BoundingBox(f[0], y - 4, f[2], f[1], y + 8, f[3]);
    }

    /** Horses in its building. */
    private List<Horse> inStable() {
        List<Horse> out = new ArrayList<>();
        for (Entity e : hut.block.getWorld().getNearbyEntities(area())) if (e instanceof Horse h && !h.isDead()) out.add(h);
        return out;
    }

    /** Its cavalry horses about (home: this stable), within 50. */
    private List<AbstractHorse> mounts() {
        List<AbstractHorse> out = new ArrayList<>();
        for (Entity e : hut.block.getWorld().getNearbyEntities(hut.block.getLocation(), HORSE_RANGE, HORSE_RANGE, HORSE_RANGE))
            if (e instanceof AbstractHorse h && CavalryHorse.is(h) && hut.block.equals(CavalryHorse.home(h)) && !h.isDead()) out.add(h);
        return out;
    }

    /** Its stalls (the blueprint's "stall" tags), else its hut. */
    private Block nextStall() {
        List<Block> stalls = hut.workSpots(colony.style, "stall");
        return stalls.isEmpty() ? hut.block : stalls.get(nextStall++ % stalls.size());
    }

    /**
     * Its cavalry horses while nobody rides them (the mod's ReturnToStableGoal and CavalryStrollGoal): 5 minutes after
     * their rider got off, one more than 20 blocks off comes home to a stall; otherwise now and then a stroll, to a stall
     * in the stable.
     */
    @Override
    public void everySecond() {
        if (hut.level < 1) return;
        var rnd = ThreadLocalRandom.current();
        for (AbstractHorse h : mounts()) {
            if (!h.getPassengers().isEmpty() || CavalryHorse.reservedBy(h) != null || h.isLeashed()) continue;
            CavalryHorse.quiet(h); // goals come back when its chunk reloads
            boolean far = h.getLocation().distance(hut.block.getLocation()) > HOME_DISTANCE;
            if (far && CavalryHorse.sinceDismount(h) > LINGER_TICKS) { if (!h.getPathfinder().hasPath()) h.getPathfinder().moveTo(nextStall().getLocation().toCenterLocation(), 0.8); }
            else if (!h.getPathfinder().hasPath() && rnd.nextInt(10) == 0) {
                Location to = far ? h.getLocation().add(rnd.nextInt(-8, 9), 0, rnd.nextInt(-8, 9)) : nextStall().getLocation().toCenterLocation();
                h.getPathfinder().moveTo(to, 0.7);
            }
        }
    }

    // ---- its work -------------------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus(title() + ": idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (act != Act.NONE) { carryOut(w); return; }
        if (bagFull() && loaded()) { // what it picked up, to the hut
            if (!atHut(w)) { goToHut(w); return; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return; }
        }
        if (has(Material.LEAD) == 0) {
            if (!atHut(w)) { goToHut(w); return; }
            if (fetch(Material.LEAD, 1) == 0) { idle(w, "Needs a lead in the hut chest.", 100, hut.block); return; }
        }
        if (has(Material.GOLDEN_APPLE) < 2 && inChest(i -> i.getType() == Material.GOLDEN_APPLE) > 0) { // breeding takes 2
            if (!atHut(w)) { goToHut(w); return; }
            fetch(Material.GOLDEN_APPLE, APPLES_WANTED);
        }
        for (var kinds : List.of(FEED, LEATHER)) // what readying uses, when it has none on it
            if (has(kinds) == 0 && inChest(i -> kinds.contains(i.getType())) > 0) { if (!atHut(w)) { goToHut(w); return; } fetch(i -> kinds.contains(i.getType()), SUPPLY_WANTED); }
        note = null;
        decide(w);
    }

    /** The mod's decideWhatToDo: training, readying, rounding up, then the herder's breeding and picking up. */
    private void decide(Colonist w) {
        var rnd = ThreadLocalRandom.current();
        cooldown = DECIDE_TICKS;
        trainIn--;
        roundupIn--;
        List<AbstractHorse> mounts = mounts();
        int max = MOUNTS_PER_LEVEL * hut.level;
        if (trainIn <= 0 && rnd.nextDouble() < TRAIN_CHANCE) {
            trainIn = TRAIN_COOLDOWN;
            Horse raw = mounts.size() >= max ? null : inStable().stream()
                    .filter(h -> !CavalryHorse.is(h) && h.isAdult() && h.getPassengers().isEmpty() && h.getOwner() == null).findFirst().orElse(null);
            if (raw != null) { start(Act.TRAIN, raw); return; }
        } else if (rnd.nextDouble() < READY_CHANCE) {
            AbstractHorse needs = mounts.stream().filter(h -> h.getPassengers().isEmpty() && needsReadying(h)).findFirst().orElse(null);
            if (needs != null) { start(Act.READY, needs); return; }
        } else if (roundupIn <= 0 && rnd.nextDouble() < ROUNDUP_CHANCE) {
            roundupIn = ROUNDUP_COOLDOWN;
            AbstractHorse loose = strayed();
            if (loose != null) { start(Act.ROUNDUP, loose); return; }
        }
        if (rnd.nextDouble() < 0.1) for (Entity e : hut.block.getWorld().getNearbyEntities(area())) // the herder's 1 in 10: what lies about
            if (e instanceof Item i && i.isValid() && i.getLocation().distance(w.location()) < 16) { gather(List.of(i.getItemStack()), w); i.remove(); }
        if (hut.on(Settings.BREEDING) && mounts.size() < max && has(Material.GOLDEN_APPLE) >= 2) { // the mod's canBreedChildren
            List<Horse> ready = inStable().stream().filter(h -> h.isAdult() && h.canBreed() && !h.isLoveMode()).toList();
            if (ready.size() >= 2) { start(Act.BREED, ready.get(0)); partner = ready.get(1); return; }
        }
        w.setStatus(title() + ": tending the horses");
        w.wander(hut.block.getLocation().toCenterLocation());
    }

    /** It needs the stablemaster: research health not given yet, hurt, or not fit to fight. */
    private boolean needsReadying(AbstractHorse h) {
        return CavalryHorse.healthResearchDue(h, colony) || h.getHealth() < CavalryHorse.maxHealth(h) || CavalryHorse.cooldown(h) > 0;
    }

    /** A horse within 30 of the stable but out of it, with nobody riding it (the mod's findNearbyUnstabledHorses). */
    private AbstractHorse strayed() {
        BoundingBox box = area();
        for (Entity e : hut.block.getWorld().getNearbyEntities(hut.block.getLocation(), ROUNDUP_RANGE, ROUNDUP_RANGE, ROUNDUP_RANGE))
            if (e instanceof Horse h && !h.isDead() && h.getPassengers().isEmpty() && !box.contains(h.getLocation().toVector()) && !h.isLeashed()
                    && h.getOwner() == null && (!CavalryHorse.is(h) || CavalryHorse.reservedBy(h) == null)) return h; // ours: horses only, nobody's own
        return null;
    }

    private void start(Act a, AbstractHorse h) {
        act = a;
        target = h;
        partner = null;
        tries = 0;
        leading = false;
        leadTo = null;
        cooldown = 1;
    }

    private void done() {
        act = Act.NONE;
        if (target != null && target.isLeashed()) target.setLeashHolder(null);
        target = null;
        partner = null;
        cooldown = STEP_TICKS;
    }

    /** Walks to the horse, then does the chosen thing (a round-up leads it to a stall first). */
    private void carryOut(Colonist w) {
        cooldown = STEP_TICKS;
        w.setStatus(title() + ": working");
        if (target == null || !target.isValid() || target.isDead() || !target.getPassengers().isEmpty() || ++tries > CHASE_TRIES) { done(); return; }
        if (leading) { // on the lead to a stall
            Block stall = nextStallFor();
            if (!w.isBeside(stall)) { w.goBeside(stall); return; } // its tries run on: a stall it can't reach is given up
            done();
            return;
        }
        if (w.location().distance(target.getLocation()) > REACH) { w.goBeside(target.getLocation().getBlock()); return; }
        tries = 0;
        w.swing();
        switch (act) {
            case TRAIN -> {
                if (target instanceof Horse h && !CavalryHorse.is(h)) {
                    CavalryHorse.train(h, hut);
                    experience(w, 1);
                }
                done();
            }
            case READY -> { ready(w, target); done(); }
            case ROUNDUP -> { // the mod's: a lead in its off hand, the horse on it to a stall
                if (has(Material.LEAD) == 0 || !target.setLeashHolder(w.living())) { done(); return; }
                leading = true;
            }
            case BREED -> {
                for (AbstractHorse h : partner == null ? List.of(target) : List.of(target, partner)) {
                    if (use(Material.GOLDEN_APPLE, 1) == 1) h.setLoveModeTicks(600);
                }
                experience(w, 0.5);
                done();
            }
            default -> done();
        }
    }

    private Block leadTo;

    private Block nextStallFor() {
        if (leadTo == null) leadTo = nextStall();
        return leadTo;
    }

    /**
     * The mod's readying: the research's health first; else a hurt horse is fed (a fifth of its health back, more
     * trust), else an unfit one is worked with leather (its cooldown down by 4 × athletics / 20; horse armor of leather
     * clears it).
     */
    private void ready(Colonist w, AbstractHorse h) {
        if (CavalryHorse.healthResearch(h, colony)) { experience(w, 0.5); return; }
        double max = CavalryHorse.maxHealth(h);
        if (h.getHealth() < max) {
            if (use(FEED::contains, 1) == 1) {
                h.setHealth(Math.min(max, h.getHealth() + max * FEED_HEAL));
                h.setDomestication(Math.min(h.getMaxDomestication(), h.getDomestication() + FEED_TEMPER));
                experience(w, 0.5);
                return;
            }
        }
        if (CavalryHorse.cooldown(h) <= 0) return;
        if (use(i -> i.getType() == Material.LEATHER_HORSE_ARMOR, 1) == 1) CavalryHorse.cooldown(h, 0);
        else if (use(LEATHER::contains, 1) == 1) CavalryHorse.cooldown(h, CavalryHorse.cooldown(h) - recovery(skill(0)));
        else return;
        experience(w, 0.5);
    }

    /** What one leather takes off a horse's cooldown: 4 × its athletics / 20 (the mod's). */
    static double recovery(int athletics) {
        return RECOVERY * athletics / RECOVERY_SKILL_PAR;
    }

    private int use(Set<Material> kinds, int n) {
        return use(i -> kinds.contains(i.getType()) && i.getPersistentDataContainer().isEmpty(), n);
    }

    // ---- what it asks for and keeps ------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        if (has(Material.LEAD) + inChest(i -> i.getType() == Material.LEAD) == 0) wants.add(new Want("lead", i -> i.getType() == Material.LEAD, 1));
        int apples = has(Material.GOLDEN_APPLE) + inChest(i -> i.getType() == Material.GOLDEN_APPLE);
        if (hut.on(Settings.BREEDING) && apples < APPLES_MIN) wants.add(new Want("golden apple", i -> i.getType() == Material.GOLDEN_APPLE, APPLES_WANTED - apples, APPLES_MIN));
        List<AbstractHorse> mounts = mounts();
        if (mounts.stream().anyMatch(h -> h.getHealth() < CavalryHorse.maxHealth(h)) && has(FEED) + inChest(i -> FEED.contains(i.getType())) == 0)
            wants.add(new Want("horse feed", i -> FEED.contains(i.getType()), SUPPLY_WANTED, SUPPLY_MIN));
        if (mounts.stream().anyMatch(h -> CavalryHorse.cooldown(h) > 0) && has(LEATHER) + inChest(i -> LEATHER.contains(i.getType())) == 0)
            wants.add(new Want("leather", i -> LEATHER.contains(i.getType()), SUPPLY_WANTED, SUPPLY_MIN));
        return wants;
    }

    private int has(Set<Material> kinds) {
        return has(i -> kinds.contains(i.getType()));
    }

    /** A lead, golden apples, feed and leather stay; what it picks up goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        Material m = item.getType();
        if (m == Material.LEAD) return 1;
        if (m == Material.GOLDEN_APPLE) return APPLES_WANTED;
        return FEED.contains(m) || LEATHER.contains(m) ? SUPPLY_WANTED : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        Material m = i.getType();
        return m == Material.LEAD || m == Material.GOLDEN_APPLE || FEED.contains(m) || LEATHER.contains(m);
    }

    @Override
    public void save(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("train-in", trainIn);
        s.set("roundup-in", roundupIn);
    }

    @Override
    public void load(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.load(s, c);
        trainIn = s.getInt("train-in");
        roundupIn = s.getInt("roundup-in");
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The stable starts once level 1 is built.", NamedTextColor.GRAY));
        List<AbstractHorse> mounts = mounts();
        long fit = mounts.stream().filter(CavalryHorse::ready).count();
        return List.of(Component.text("Cavalry horses: " + mounts.size() + " of " + MOUNTS_PER_LEVEL * hut.level + " (" + fit + " fit to fight)."
                + " The stablemaster trains horses brought into the stable, breeds them with golden apples, feeds the hurt and readies the unfit with leather.", NamedTextColor.GRAY));
    }
}
