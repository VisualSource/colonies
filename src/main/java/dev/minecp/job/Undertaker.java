package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonyStore;
import dev.minecp.colony.Grave;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Graveyard's undertaker (the mod's EntityAIWorkUndertaker): digs up the colony's graves one at a time, nearest
 * the hut first, taking what each held to the hut; tries to bring the citizen back; else buries them in one of the
 * graveyard's burial spots under a named grave. Its timings are the mod's effort counts: each swing adds its skill
 * level (strength for emptying and burying, mana for bringing back) until 100, 400 and 400; at level 10, 2.5 s, 10 s
 * and 10 s.
 */
public final class Undertaker extends Job {
    private static final int EMPTY_EFFORT = 100, RESURRECT_EFFORT = 400, BURY_EFFORT = 400; // EFFORT_EMPTY_GRAVE, _RESURRECT, _BURY
    private static final int SWING_TICKS = 5; // ours: a swing every 5 ticks, the pace that gives the mod's timings
    // The mod's chance without totems: 0.5% a hut level, 0.125% a mana level, plus research, at most 2.5% plus 0.5% a mystical site level.
    private static final double CHANCE_PER_LEVEL = 0.005, CHANCE_PER_MANA = 0.00125, MAX_CHANCE = 0.025, MAX_PER_MYSTICAL_LEVEL = 0.005;
    private static final double SPRINT = 1.3; // vanilla's sprinting speed modifier
    // With Raising the Dead: a totem adds 5% (two or more 7.5%) to the chance and its cap; one breaks 1 time in 100 a try (the mod's).
    private static final double ONE_TOTEM = 0.05, TOTEMS = 0.075, TOTEM_BREAKS = 0.01;
    private static final int TOTEMS_KEPT = 2; // the graveyard's keepX

    private enum State { FIND, TO_GRAVE, EMPTY, RESURRECT, DIG, BURY, DUMP }

    private State state = State.FIND;
    private Grave grave;            // the grave it works on
    private String body, bodyJob;   // dug up, waiting to be buried
    private BuildOrder.GraveSpot buryAt;
    private int effort;
    private final Map<Block, String> resting = new LinkedHashMap<>(); // buried here: named grave (its burial spot), name
    private List<BuildOrder.GraveSpot> spots = List.of();
    private int spotsLevel = -1;

    public Undertaker(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** The named grave of a citizen buried here, or null. */
    public Block restingPlace(String name) {
        prune();
        for (var e : resting.entrySet()) if (e.getValue().equals(name)) return e.getKey();
        return null;
    }

    /** Graves a player broke are forgotten: the spot can take someone else. */
    private void prune() {
        resting.keySet().removeIf(b -> b.getType() != Material.STONE_BRICK_WALL);
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        wantTool(wants, Tool.SHOVEL);
        return wants;
    }

    /** Its shovel and 2 totems stay; what came out of the graves goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        return fits(Tool.SHOVEL, item, null) ? Integer.MAX_VALUE : totem(item) ? TOTEMS_KEPT : 0;
    }

    private static boolean totem(ItemStack i) {
        return i != null && i.getType() == Material.TOTEM_OF_UNDYING && i.getPersistentDataContainer().isEmpty();
    }

    /** What its totems add, with the research (the mod's getTotemResurrectChance). */
    private double totemChance() {
        if (colony.research.effect(Effects.USE_TOTEM) <= 0) return 0;
        int n = has(Undertaker::totem);
        return n == 1 ? ONE_TOTEM : n > 1 ? TOTEMS : 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The undertaker starts once level 1 is built.", NamedTextColor.GRAY));
        long waiting = c.graves.size();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Graves to dig up: " + waiting + ". Buried here: " + resting.size() + " of "
                + spots().size() + " spots.", NamedTextColor.GRAY));
        lines.add(Component.text(String.format("Chance to bring one back: %.1f%%", chance() * 100), NamedTextColor.GRAY));
        if (!resting.isEmpty()) lines.add(Component.text("Resting here: " + String.join(", ", resting.values()), NamedTextColor.GRAY));
        return lines;
    }

    /** The mod's chance, with its resurrection research added; the mod caps the total, research included, at 2.5%; totems raise both. */
    private double chance() {
        double totems = totemChance();
        return Math.min(MAX_CHANCE + MAX_PER_MYSTICAL_LEVEL * colony.mysticalLevel() + totems, CHANCE_PER_LEVEL * hut.level + CHANCE_PER_MANA * level(Skill.MANA)
                + colony.research.effect(Effects.RESURRECT_CHANCE) + totems);
    }

    /** It runs to a grave once researched (the mod's undertakerrun: vanilla sprinting, +30%). */
    @Override
    public double pace() {
        return state == State.TO_GRAVE && colony.research.effect(Effects.UNDERTAKER_RUN) > 0 ? SPRINT : 1;
    }

    /** The burial spots of the graveyard as built (the mod's named grave blocks in its blueprint). */
    private List<BuildOrder.GraveSpot> spots() {
        if (spotsLevel != hut.level) {
            BuildOrder o = hut.built(colony.style);
            spots = o == null ? List.of() : o.graves;
            spotsLevel = hut.level;
        }
        return spots;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Undertaker: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (grave != null && !colony.graves.contains(grave)) { grave = null; state = State.FIND; } // decayed or broken meanwhile
        switch (state) {
            case FIND -> {
                if (loaded()) { state = State.DUMP; return; }
                if (body != null) { state = State.BURY; return; }
                if (spots().isEmpty()) { // structures converted before burial spots were marked
                    note = "This graveyard has no burial spots: its structures need converting again (tools/convert_blueprints.py).";
                    w.setStatus("Undertaker: idle");
                    w.wander(hut.block.getLocation().toCenterLocation());
                    cooldown = 200;
                    return;
                }
                if (grave == null) grave = pick();
                if (grave == null) {
                    note = "No graves to dig up.";
                    w.setStatus("Undertaker: idle");
                    w.wander(hut.block.getLocation().toCenterLocation());
                    cooldown = 60; // the mod's REQUEST_DELAY
                    return;
                }
                grave.reserved = true;
                if (!hasTool(Tool.SHOVEL, null)) {
                    if (!atHut(w)) { goToHut(w); return; }
                    if (!fetchTool(Tool.SHOVEL, null)) {
                        note = "Needs " + toolLabel(Tool.SHOVEL) + " in the hut chest.";
                        w.setStatus("Undertaker: idle");
                        cooldown = 100;
                        return;
                    }
                }
                if (colony.research.effect(Effects.USE_TOTEM) > 0 && has(Undertaker::totem) < TOTEMS_KEPT && inChest(Undertaker::totem) > 0) {
                    if (!atHut(w)) { goToHut(w); return; }
                    fetch(Undertaker::totem, TOTEMS_KEPT - has(Undertaker::totem));
                }
                note = null;
                w.setStatus("Undertaker: working");
                state = State.TO_GRAVE;
            }
            case TO_GRAVE -> {
                if (!w.isBeside(grave.block)) { w.goBeside(grave.block); return; }
                effort = 0;
                state = State.EMPTY;
            }
            case EMPTY -> {
                if (!w.isBeside(grave.block)) { state = State.TO_GRAVE; return; } // back from bed, a meal or the rain
                if (!work(w, EMPTY_EFFORT, skill(0))) return;
                Inventory bag = bag();
                for (int i = 0; i < grave.items.getSize() && bag != null; i++) { // into its inventory, as much as fits (the mod's)
                    ItemStack item = grave.items.getItem(i);
                    if (item == null) continue;
                    var left = bag.addItem(item).values();
                    grave.items.setItem(i, left.isEmpty() ? null : left.iterator().next());
                    if (!left.isEmpty()) break;
                }
                effort = 0;
                state = grave.items.isEmpty() ? State.RESURRECT : State.DUMP; // full: to the hut, then back to this grave
            }
            case RESURRECT -> {
                if (!w.isBeside(grave.block)) { effort = 0; state = State.TO_GRAVE; return; }
                Location at = grave.block.getLocation().toCenterLocation();
                if (effort < RESURRECT_EFFORT) {
                    at.getWorld().spawnParticle(Particle.ENCHANT, at, 20, 0.5, 0.8, 0.5, 0.5);
                    effort += skill(1);
                    cooldown = SWING_TICKS;
                    return;
                }
                effort = 0;
                if (totemChance() > 0) {
                    colony.milestones.totem(); // the mod's undertaker_totem advancement
                    if (ThreadLocalRandom.current().nextDouble() <= TOTEM_BREAKS) {
                        use(Undertaker::totem, 1);
                        at.getWorld().playSound(at, org.bukkit.Sound.ITEM_TOTEM_USE, org.bukkit.SoundCategory.NEUTRAL, 1, 1);
                    }
                }
                if (ThreadLocalRandom.current().nextDouble() < chance()) {
                    Grave g = grave;
                    removeGrave(g);
                    colony.revive(g);
                    state = State.DUMP;
                    return;
                }
                state = State.DIG;
            }
            case DIG -> {
                wear(Tool.SHOVEL, w);
                act(w);
                Grave g = grave;
                removeGrave(g);
                body = g.name;
                bodyJob = g.job;
                buryAt = null;
                state = State.BURY;
                cooldown = SWING_TICKS;
            }
            case BURY -> {
                if (buryAt == null || !buryAt.block().isReplaceable()) buryAt = freeSpot();
                if (buryAt == null) { // the mod: the body is lost
                    colony.tell("There is no room in the graveyard to bury " + body + ".");
                    body = null;
                    state = State.DUMP;
                    return;
                }
                if (!w.isBeside(buryAt.block())) { w.goBeside(buryAt.block()); return; }
                if (!work(w, BURY_EFFORT, skill(0))) return;
                bury(buryAt, body, bodyJob);
                resting.put(buryAt.block(), body);
                colony.milestones.buried();
                body = null;
                effort = 0;
                state = State.DUMP;
            }
            case DUMP -> {
                if (!atHut(w)) { goToHut(w); return; }
                if (!deliver()) {
                    note = "The hut chest is full.";
                    w.setStatus("Undertaker: idle");
                    cooldown = 100;
                    return;
                }
                state = State.FIND;
            }
        }
    }

    /** Swings the shovel, each swing adding its {@code skill} level of effort; @return true once {@code needed} is reached. */
    private boolean work(Colonist w, int needed, int skill) {
        if (effort >= needed) return true;
        w.hold(tool(Tool.SHOVEL));
        act(w);
        effort += skill;
        cooldown = SWING_TICKS;
        return false;
    }

    /** The nearest loaded grave no undertaker is working on. */
    private Grave pick() {
        Grave best = null;
        double bestD = Double.MAX_VALUE;
        for (Grave g : colony.graves) {
            Block b = g.block;
            if (g.reserved || !b.getWorld().equals(hut.block.getWorld()) || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            double d = b.getLocation().distanceSquared(hut.block.getLocation());
            if (d < bestD) { bestD = d; best = g; }
        }
        return best;
    }

    /** The grave is dug up: block gone, anything left in it dropped. */
    private void removeGrave(Grave g) {
        Block b = g.block;
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 1, 1);
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().toCenterLocation(), 10, 0.3, 0.3, 0.3, b.getBlockData());
        g.dropAll();
        b.setType(Material.AIR);
        colony.graves.remove(g);
        grave = null;
    }

    /** A free burial spot, picked at random as the mod does. */
    private BuildOrder.GraveSpot freeSpot() {
        prune();
        List<BuildOrder.GraveSpot> free = spots().stream().filter(s -> s.block().isReplaceable() && !resting.containsKey(s.block())).toList();
        return free.isEmpty() ? null : free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    /**
     * The named grave: a stone brick wall with a sign in front (on top if the front is taken), its lines the citizen's
     * first name, the rest of its name and its job, as the mod's.
     */
    private static void bury(BuildOrder.GraveSpot spot, String name, String job) {
        Block b = spot.block();
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_ROOTED_DIRT_PLACE, SoundCategory.BLOCKS, 1, 1);
        b.setType(Material.STONE_BRICK_WALL);
        Block front = b.getRelative(spot.facing()), above = b.getRelative(0, 1, 0);
        Block at;
        BlockData data;
        if (front.isEmpty()) {
            WallSign s = (WallSign) Material.OAK_WALL_SIGN.createBlockData();
            s.setFacing(spot.facing());
            at = front;
            data = s;
        } else if (above.isEmpty()) {
            var s = (org.bukkit.block.data.type.Sign) Material.OAK_SIGN.createBlockData();
            s.setRotation(spot.facing());
            at = above;
            data = s;
        } else return;
        at.setBlockData(data);
        if (!(at.getState() instanceof Sign sign)) return;
        int space = name.indexOf(' ');
        var side = sign.getSide(Side.FRONT);
        side.line(0, Component.text(space < 0 ? name : name.substring(0, space)));
        side.line(1, Component.text(space < 0 ? "" : name.substring(space + 1)));
        side.line(2, Component.text(job == null ? "" : job));
        sign.setWaxed(true);
        sign.update();
    }

    @Override
    public void dropCarried() {
        super.dropCarried();
        if (grave != null) grave.reserved = false;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        if (grave != null) ColonyStore.putLocation(s, "grave", grave.block.getLocation());
        if (body != null) { s.set("body", body); s.set("body-job", bodyJob); }
        int i = 0;
        for (var e : resting.entrySet()) {
            var rs = s.createSection("resting." + i++);
            rs.set("name", e.getValue());
            ColonyStore.putLocation(rs, "block", e.getKey().getLocation());
        }
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        Location g = ColonyStore.getLocation(s, "grave");
        if (g != null) grave = c.graveAt(g.getBlock());
        if (grave != null) grave.reserved = true;
        body = s.getString("body");
        bodyJob = s.getString("body-job");
        var rs = s.getConfigurationSection("resting");
        if (rs != null) for (String k : rs.getKeys(false)) {
            Location at = ColonyStore.getLocation(rs.getConfigurationSection(k), "block");
            if (at != null) resting.put(at.getBlock(), rs.getString(k + ".name"));
        }
    }
}
