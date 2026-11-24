package dev.minecp.colony;

import dev.minecp.citizen.Colonist;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The taverns' visitors (the mod's TavernBuildingModule, EntityAIVisitor and RecruitmentInteraction): travellers who
 * stay at a tavern for a while and join the colony if a player pays what they ask. The mod's numbers throughout.
 */
public final class Visitors {
    /** The mod's stay: saturation 60 dropping 0.02 every 50 ticks. */
    static final int STAY_TICKS = 60 * 50 * 50;
    private static final int FIRST_VISITOR = 10_000; // ticks before a new tavern's first visitor
    private static final int RUN_OFF_PERCENT = 3;    // the mod's nextInt(100) <= 2: they take the payment and leave
    // The mod's EntityAIVisitor combat: it hits what hurt it for 10 every second from 2 blocks, for up to 30 seconds.
    private static final double MAX_HEALTH = 20, HIT = 10, REACH = 2;
    private static final int ATTACK_DELAY = 20, FIGHT_TICKS = 30 * 20;
    // The mod's EntityAIVisitor: every 50 ticks an idle visitor picks from nextInt(5): 0 (or 1 at night) sits on a free
    // seat of its tavern for 3000 + rand(2500) ticks, 2 wanders within 10 blocks (a new spot every 200 ticks) for
    // 1000 + rand(1000) ticks by day or 300 at night, else it stays idle.
    private static final int DECIDE_TICKS = 50, WANDER_STEP = 200, WANDER_RANGE = 10, SIT_REACH = 3;
    private static final int NO_VISITOR_TICKS = 30_000, NO_VISITOR_RANDOM = 5000; // a tavern whose visitor died waits this long
    /**
     * What visitors ask, by rarity 1 to 9 (the mod's recruitment_items). Its own foods are left out: rarity 7 had
     * only those, so ours there are rabbit stew, pumpkin pie and golden carrots.
     */
    private static final Material[][] COSTS = {
            {Material.DRIED_KELP, Material.BREAD, Material.PAPER, Material.APPLE, Material.BAKED_POTATO, Material.SUGAR, Material.EGG},
            {Material.MUSHROOM_STEW, Material.FEATHER, Material.FLINT, Material.COPPER_INGOT, Material.LEATHER, Material.COOKED_COD, Material.COOKED_SALMON},
            {Material.COOKED_CHICKEN, Material.COOKED_PORKCHOP, Material.COOKED_MUTTON, Material.COOKED_BEEF, Material.BOOK},
            {Material.IRON_INGOT, Material.GOLD_INGOT, Material.COOKIE, Material.REDSTONE, Material.LAPIS_LAZULI, Material.QUARTZ, Material.AMETHYST_CLUSTER},
            {Material.HONEYCOMB, Material.HONEY_BOTTLE, Material.NETHER_WART},
            {Material.INK_SAC, Material.BLAZE_POWDER, Material.SPIDER_EYE, Material.SLIME_BALL},
            {Material.RABBIT_STEW, Material.PUMPKIN_PIE, Material.GOLDEN_CARROT},
            {Material.DIAMOND, Material.EMERALD, Material.ENDER_PEARL, Material.CAKE},
            {Material.GHAST_TEAR, Material.EXPERIENCE_BOTTLE, Material.ENCHANTED_BOOK, Material.GOLDEN_APPLE},
    };

    /** A visitor: its body, the tavern it stays at, what it asks to join, and ticks left before it leaves. */
    public static final class Visitor {
        public final Colonist colonist;
        public final Hut tavern;
        public final Material item;
        public final int count;
        int stay;
        double health = MAX_HEALTH; // shortcut: not saved and never heals; save and heal it if visitors die of old wounds
        LivingEntity target;        // what hurt it, which it fights
        int fightTicks, attackIn;
        Activity activity = Activity.IDLE;
        int activityTicks, nextCheck;
        Block seat;                 // its seat while it sits (or heads there); null with SIT: beside the hut block

        Visitor(Colonist colonist, Hut tavern, Material item, int count, int stay) {
            this.colonist = colonist;
            this.tavern = tavern;
            this.item = item;
            this.count = count;
            this.stay = stay;
        }
    }

    enum Activity { IDLE, SIT, WANDER }

    private final Colony colony;
    private final Map<Hut, Map.Entry<Integer, List<Block>>> seats = new HashMap<>(); // per tavern: its level, its seats
    public final List<Visitor> list = new ArrayList<>();
    // shortcut: not saved, so a restart waits FIRST_VISITOR again; save it if restarts turn out to starve taverns
    private final Map<Hut, Integer> timers = new HashMap<>();

    Visitors(Colony colony) {
        this.colony = colony;
    }

    /**
     * Every colony tick (500 ticks): each built tavern's countdown drops 500; at 0 a visitor arrives if it has room
     * (3 per level), and the next one is rand(3000) + (6000 / level) × citizens / room away: a fuller colony gets fewer.
     */
    void colonyTick(int ticks) {
        var rnd = ThreadLocalRandom.current();
        for (Hut h : colony.huts) {
            if (h.type != Hut.Type.TAVERN || h.level < 1) continue;
            int t = timers.getOrDefault(h, FIRST_VISITOR) - ticks;
            if (t <= 0) {
                if (colony.setting(Settings.MOVE_IN) && list.stream().filter(v -> v.tavern == h).count() < 3L * h.level) arrive(h);
                t = rnd.nextInt(3000) + (6000 / h.level) * colony.colonists.size() / Math.max(1, colony.capacity());
            }
            timers.put(h, t);
        }
    }

    /** The mod's spawnVisitor: a rarity around the middle of what the tavern's level allows, and an item of it. */
    private void arrive(Hut tavern) {
        var rnd = ThreadLocalRandom.current();
        int limit = 3 * tavern.level + 1;
        int rarity = Math.clamp((int) rnd.nextGaussian(limit / 2, 2), 1, limit - 1);
        Material[] items = COSTS[rarity - 1];
        Material item = items[rnd.nextInt(items.length)];
        int count = Math.min(5 * (10 - rarity) + rnd.nextInt(3), item.getMaxStackSize());
        boolean female = rnd.nextBoolean();
        var at = tavern.block.getLocation().toCenterLocation();
        Colonist c = new Colonist(UUID.randomUUID(), colony.uniqueName(female), Colonist.randomSkin(female), at);
        c.skills.roll(15 + rarity * rarity / 2); // the mod's recruit level: BASE_RECRUIT_LEVEL + rarity² / 2
        list.add(new Visitor(c, tavern, item, count, STAY_TICKS));
    }

    /** Each tick: visitors sit, wander and idle around their tavern (or fight) until their stay runs out. */
    void tick() {
        for (Visitor v : List.copyOf(list)) {
            if (--v.stay <= 0) { leave(v); continue; }
            if (!v.colonist.tick()) continue;
            if (v.target != null) {
                v.colonist.standUp();
                v.activity = Activity.IDLE;
                v.seat = null; // free for the others
                fight(v);
                continue;
            }
            if (v.colonist.talkTicks > 0) { v.colonist.talkTicks--; continue; } // a player clicked it
            v.colonist.setStatus("Visitor");
            if (--v.nextCheck > 0) continue;
            switch (v.activity) {
                case IDLE -> { decide(v); v.nextCheck = DECIDE_TICKS; }
                case SIT -> { sit(v); v.nextCheck = DECIDE_TICKS; }
                case WANDER -> {
                    if ((v.activityTicks -= WANDER_STEP) <= 0) v.activity = Activity.IDLE;
                    else v.colonist.walkRandom(WANDER_RANGE);
                    v.nextCheck = WANDER_STEP;
                }
            }
        }
    }

    /** The mod's decide: what an idle visitor does next. */
    private void decide(Visitor v) {
        var rnd = ThreadLocalRandom.current();
        int r = rnd.nextInt(5);
        boolean day = colony.routine.isDay();
        if (r == 0 || r == 1 && !day) {
            List<Block> all = seatsOf(v.tavern);
            if (all.isEmpty()) v.seat = null; // ours: no seats at all, so it stands beside the hut block, indoors
            else {
                List<Block> free = new ArrayList<>(all);
                for (Visitor o : list) free.remove(o.seat);
                if (free.isEmpty()) return; // every seat taken: it stays idle, as in the mod
                v.seat = free.get(rnd.nextInt(free.size()));
            }
            v.activity = Activity.SIT;
            v.activityTicks = 3000 + rnd.nextInt(2500);
            if (v.seat != null) v.colonist.walkBeside(v.seat);
            else v.colonist.walkBeside(v.tavern.block);
        } else if (r == 2) {
            v.activity = Activity.WANDER;
            v.activityTicks = day ? 1000 + rnd.nextInt(1000) : 300;
            v.colonist.walkRandom(WANDER_RANGE);
        }
    }

    /** The mod's sit: once there (within 3 blocks) it sits down, until its time is up or the seat is gone. */
    private void sit(Visitor v) {
        boolean gone = v.seat != null && v.seat.getType().isOccluding(); // walled up: nothing to sit on
        if ((v.activityTicks -= DECIDE_TICKS) <= 0 || gone) {
            v.colonist.standUp();
            v.seat = null;
            v.activity = Activity.IDLE;
            return;
        }
        if (v.colonist.seat() != null || v.colonist.isMoving()) return;
        if (v.seat == null) { if (!v.colonist.isBeside(v.tavern.block)) v.colonist.walkBeside(v.tavern.block); return; }
        if (v.colonist.isBeside(v.seat) || v.colonist.location().distance(v.seat.getLocation().toCenterLocation()) <= SIT_REACH) v.colonist.sitOn(v.seat);
        else if (v.colonist.walkBeside(v.seat) == null) { v.seat = null; v.activity = Activity.IDLE; } // can't get there
    }

    /** The tavern's seats as built at its level (its blueprint's sit tags). */
    private List<Block> seatsOf(Hut tavern) {
        var known = seats.get(tavern);
        if (known == null || known.getKey() != tavern.level) {
            var o = dev.minecp.build.BuildOrder.create(tavern.structure(colony.style, tavern.level), tavern.block.getLocation(),
                    tavern.rotation, tavern.mirror, tavern.block);
            known = Map.entry(tavern.level, o == null ? List.of() : List.copyOf(o.seats));
            seats.put(tavern, known);
        }
        return known.getValue();
    }

    /**
     * Hits on a visitor: the hit itself is cancelled and its own health takes it (the mod's VisitorCitizen.hurt). Not
     * from citizens; a player's hit of more than 1 needs HURT_VISITOR. A mob's or player's hit of more than 1 makes it
     * and the tavern's other visitors who aren't fighting yet fight back.
     */
    public void hurt(Visitor v, EntityDamageEvent e) {
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) { v.colonist.hopAside(null); return; }
        Entity direct = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null, source = direct;
        if (source instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) source = shooter;
        if (source != null && (colony.colonistOf(source) != null || of(source) != null)) return;
        if (source instanceof Player p && e.getDamage() > 1 && !colony.permissions.has(p, Action.HURT_VISITOR)) return;
        if (e.getDamage() <= 0) return;
        v.health -= e.getDamage();
        v.colonist.showHurt();
        if (direct != null) v.colonist.hitBy(direct);
        if (v.health <= 0) { die(v, e.getCause()); return; }
        if (source instanceof LivingEntity attacker && e.getDamage() > 1.01)
            for (Visitor o : list) if (o == v || o.tavern == v.tavern && o.target == null) { o.target = attacker; o.fightTicks = FIGHT_TICKS; }
    }

    /** Walks up to its target and hits it every second, until it is dead, gone or 30 seconds have passed. */
    private void fight(Visitor v) {
        LivingEntity t = v.target;
        if (!t.isValid() || t.isDead() || !t.getWorld().equals(v.colonist.location().getWorld()) || --v.fightTicks <= 0) {
            v.target = null;
            return;
        }
        v.colonist.setStatus("Visitor: fighting");
        if (v.attackIn > 0) v.attackIn--;
        Location at = v.colonist.location();
        if (at.distance(t.getLocation()) > REACH) {
            if (!v.colonist.isMoving() || v.attackIn == 0) { v.colonist.walk(t.getLocation()); v.attackIn = ATTACK_DELAY; } // a new route each second
            return;
        }
        v.colonist.stopWalking();
        if (v.attackIn > 0 || !v.colonist.living().hasLineOfSight(t)) return;
        v.attackIn = ATTACK_DELAY;
        v.colonist.swing();
        t.damage(HIT, v.colonist.living());
    }

    /** The mod's visitor death: gone, its managers told, and its tavern gets no visitors for a while. */
    private void die(Visitor v, EntityDamageEvent.DamageCause cause) {
        Location at = v.colonist.location();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_DEATH, SoundCategory.NEUTRAL, 1, 1);
        at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        leave(v);
        timers.put(v.tavern, NO_VISITOR_TICKS + ThreadLocalRandom.current().nextInt(NO_VISITOR_RANDOM));
        Colony.log.info(colony.where() + ": visitor " + v.colonist.name + " died (" + cause + ").");
        colony.tell("Visitor " + v.colonist.name + " died (" + cause.name().toLowerCase().replace('_', ' ') + ") at "
                + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + ".");
    }

    private void leave(Visitor v) {
        v.colonist.despawn();
        list.remove(v);
    }

    /** The tavern is gone: its visitors leave (as in the mod). */
    void tavernRemoved(Hut tavern) {
        for (Visitor v : List.copyOf(list)) if (v.tavern == tavern) leave(v);
        timers.remove(tavern);
        seats.remove(tavern);
    }

    void despawn() {
        list.forEach(v -> v.colonist.despawn());
    }

    public Visitor of(Entity e) {
        for (Visitor v : list) if (v.colonist.owns(e)) return v;
        return null;
    }

    /**
     * {@code p} pays what the visitor asks (nothing in creative) and it becomes a citizen where it stands, homeless
     * until a house has room. Needs room in the colony's housing. 3% of the time it runs off with the payment, as in
     * the mod. @return what happened, for the player.
     */
    public String recruit(Player p, Visitor v) {
        if (!list.contains(v)) return v.colonist.name + " has left.";
        if (!colony.hasRoom()) return "No room: build or upgrade a house or tavern first.";
        if (p.getGameMode() != GameMode.CREATIVE && !pay(p, v.item, v.count))
            return "You need " + v.count + " × " + dev.minecp.job.Job.name(v.item) + ".";
        list.remove(v);
        if (ThreadLocalRandom.current().nextInt(100) < RUN_OFF_PERCENT) {
            v.colonist.despawn();
            return v.colonist.name + " took the payment and ran off!";
        }
        v.colonist.standUp();
        colony.join(v.colonist);
        return v.colonist.name + " joined " + colony.name + ".";
    }

    /**
     * Takes {@code count} of {@code item} from the player's main inventory, if it has that many. Matched by item alone, as
     * the mod: any enchanted book will do, whatever its enchantments. Colonies' own items made from that item (an ancient
     * tome is an enchanted book, a hut a chest) don't count: in the mod they are other items.
     */
    private static boolean pay(Player p, Material item, int count) {
        var inv = p.getInventory();
        java.util.function.Predicate<ItemStack> fits = i -> i != null && i.getType() == item
                && i.getPersistentDataContainer().getKeys().stream().noneMatch(k -> k.getNamespace().equals(dev.minecp.MineCP.NAMESPACE));
        int have = 0;
        for (int s = 0; s < 36; s++) { // the main inventory and hotbar, not armour or off hand
            ItemStack i = inv.getItem(s);
            if (fits.test(i)) have += i.getAmount();
        }
        if (have < count) return false;
        for (int s = 0; s < 36 && count > 0; s++) {
            ItemStack i = inv.getItem(s);
            if (!fits.test(i)) continue;
            int take = Math.min(count, i.getAmount());
            count -= take;
            inv.setItem(s, i.getAmount() == take ? null : i.subtract(take));
        }
        return true;
    }

    void save(ConfigurationSection s) {
        for (Visitor v : list) {
            var vs = s.createSection(v.colonist.id.toString());
            vs.set("name", v.colonist.name);
            vs.set("skin", v.colonist.skin);
            vs.set("skills", v.colonist.skills.save());
            ColonyStore.putLocation(vs, "location", v.colonist.location());
            vs.set("tavern", colony.huts.indexOf(v.tavern));
            vs.set("item", v.item.name());
            vs.set("count", v.count);
            vs.set("stay", v.stay);
        }
    }

    void load(ConfigurationSection s) {
        for (String id : s.getKeys(false)) {
            var vs = s.getConfigurationSection(id);
            Hut tavern = colony.hutByIndex(vs.getInt("tavern", -1));
            Material item = Material.matchMaterial(vs.getString("item", ""));
            var at = ColonyStore.getLocation(vs, "location");
            if (tavern == null || tavern.type != Hut.Type.TAVERN || item == null || at == null) continue;
            Colonist c = new Colonist(UUID.fromString(id), vs.getString("name"), vs.getInt("skin"), at);
            c.skills.load(vs.getStringList("skills"));
            list.add(new Visitor(c, tavern, item, vs.getInt("count"), vs.getInt("stay", STAY_TICKS)));
        }
    }
}
