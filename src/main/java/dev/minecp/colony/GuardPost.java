package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;

/**
 * What the guards of one guard hut do together (the mod's AbstractBuildingGuards), the mod's numbers: where they patrol,
 * the spot they guard, the player they follow, the mine they patrol and the player or colony banner they rally to.
 */
public final class GuardPost {
    /** The guard task setting's values (the mod's GuardTaskSetting). */
    public enum Task { PATROL, GUARD, FOLLOW, PATROL_MINE }

    private static final int PATROL_BASE = 50, PATROL_PER_LEVEL = 30; // PATROL_BASE_DIST, PATROL_DISTANCE
    private static final int WAIT = 1, TIMEOUT = 5;      // colony ticks: for the others once the first is there, and to get there at all
    private static final int FAR = 20;                   // PathJobRandomPos: a random patrol point is at least this far on
    private static final double CAVALRY_RANGE = 1.5;     // CAVALRY_PATROL_RANGE_BOOST
    private static final int TELESCOPE_RANGE = 500;      // how far from the town hall that research lets them rally

    /** Whether an item is an active rally banner for this guard hut (the plugin's Items). */
    public static BiPredicate<ItemStack, Hut> banner = (item, hut) -> false;

    private final Hut hut;
    private Block guard;                                 // the spot to guard; null: the hut block. Saved, as are the next three
    private final List<Block> patrol = new ArrayList<>();
    private UUID follow;
    private Block mine;                                  // the Miner's Hut block
    private Block point;                                 // where they patrol now: first their hut, as the mod's
    private Block next;                                  // where they go after that (an alert)
    private final Set<UUID> arrived = new HashSet<>();
    private int timer;
    private UUID rally;
    private Block rallyAt; // a colony banner, in place of a player
    private long lastPatrol; // a Stable's: world time its cavalry last set off (saved; the mod's lastPatrolTime)

    GuardPost(Hut hut) {
        this.hut = hut;
        point = hut.block;
    }

    public Task task() {
        if (hut.type == Hut.Type.GATE_HOUSE) return Task.GUARD; // the mod's: always, each guard at its own spot
        return Task.valueOf(hut.setting(Settings.GUARD_TASK));
    }

    /** How far from the hut its patrol points and the spot it guards may be. */
    public int range() {
        int r = PATROL_BASE + PATROL_PER_LEVEL * hut.level;
        return hut.type == Hut.Type.STABLE ? (int) (r * CAVALRY_RANGE) : r;
    }

    /** A Stable's cavalry set off again once its patrol interval (minutes) has passed; other guards always patrol. */
    public boolean patrolDue() {
        return hut.type != Hut.Type.STABLE || hut.block.getWorld().getGameTime() - lastPatrol >= hut.number(Settings.PATROL_INTERVAL) * 1200L;
    }

    /** Its guards: who works there but a Stable's stablemaster. */
    private List<Colonist> guards(Colony colony) {
        return colony.jobsAt(hut).stream().filter(j -> !(j instanceof dev.minecp.job.Stablemaster)).map(colony::worker).filter(java.util.Objects::nonNull).toList();
    }

    // ---- patrolling -----------------------------------------------------------------------------------------------

    /** Where they patrol to now. */
    public Block point() {
        return point;
    }

    /** They go there after the point they are on their way to (a raid; back to their hut). */
    public void alert(Block to) {
        next = to;
    }

    /** {@code w} reached the point: they go on once all of them are there, or the wait for them is over. */
    public void arrived(Colonist w, Colony colony) {
        if (arrived.isEmpty()) timer = WAIT;
        arrived.add(w.id);
        if (guards(colony).size() <= arrived.size() || timer <= 0) goOn(w, colony);
    }

    /** Each colony tick: they go on without those who are late. */
    void colonyTick(Colony colony) {
        if (timer <= 0 || task() != Task.PATROL || --timer > 0) return;
        List<Colonist> guards = guards(colony);
        if (!guards.isEmpty()) goOn(guards.getFirst(), colony);
    }

    private void goOn(Colonist w, Colony colony) {
        timer = TIMEOUT;
        arrived.clear();
        if (hut.type == Hut.Type.STABLE) { // the mod's startPatrolNext: one patrol each interval
            if (!patrolDue()) return;
            lastPatrol = hut.block.getWorld().getGameTime();
        }
        if (next != null) { point = next; next = null; return; }
        if (hut.setting(Settings.PATROL_MODE).equals("MANUAL") && !patrol.isEmpty()) {
            point = patrol.get((patrol.indexOf(point) + 1) % patrol.size()); // the first, if it is none of them
            return;
        }
        Block to = ThreadLocalRandom.current().nextBoolean() ? w.farSpot(FAR) : hut.type == Hut.Type.STABLE ? cavalryPoint(colony) : randomHut(colony);
        if (to != null) point = to.getLocation().distance(hut.block.getLocation()) > range() ? hut.block : to;
    }

    /**
     * A Stable's patrol target (the mod's cavalryPatrolFilter): a random stable or gatehouse, at one of its "patrol_point"
     * tags, else one of its building's corners at its hut block's height.
     */
    private Block cavalryPoint(Colony colony) {
        List<Hut> to = colony.huts.stream().filter(h -> h.level >= 1 && (h.type == Hut.Type.STABLE || h.type == Hut.Type.GATE_HOUSE)).toList();
        if (to.isEmpty()) return null;
        var rnd = ThreadLocalRandom.current();
        Hut h = to.get(rnd.nextInt(to.size()));
        List<Block> points = h.workSpots(colony.style, "patrol_point");
        if (!points.isEmpty()) return points.get(rnd.nextInt(points.size()));
        int[] f = colony.footprint(h);
        if (f == null) return h.block;
        return h.block.getWorld().getBlockAt(rnd.nextBoolean() ? f[0] : f[1] - 1, h.block.getY(), rnd.nextBoolean() ? f[2] : f[3] - 1);
    }

    private static Block randomHut(Colony colony) {
        List<Hut> built = colony.huts.stream().filter(h -> h.level >= 1).toList();
        return built.isEmpty() ? null : built.get(ThreadLocalRandom.current().nextInt(built.size())).block;
    }

    /** Sets a patrol point with the scepter, the {@code first} of a new round or one more. */
    public void addPatrol(Block at, boolean first) {
        if (first) patrol.clear();
        patrol.add(at);
    }

    public List<Block> patrolPoints() {
        return List.copyOf(patrol);
    }

    // ---- guarding, following, the mine -----------------------------------------------------------------------------

    /** The spot they guard. */
    public Block guardSpot() {
        return guard == null ? hut.block : guard;
    }

    private long followUntil; // world time a scroll's following ends; 0: none

    public void guard(Block at) {
        guard = at;
    }

    public void follow(Player p) {
        follow = p.getUniqueId();
        followUntil = 0; // told to by a player: for as long as they like
    }

    /** Follow {@code p} for {@code ticks} of world time, then back to patrolling (the guard help scroll); saved, so a restart keeps it. */
    public void followFor(Player p, long ticks) {
        follow = p.getUniqueId();
        followUntil = hut.block.getWorld().getGameTime() + ticks;
    }

    /** Once a second: a scroll's time to follow is up. */
    void checkFollow() {
        if (followUntil <= 0 || hut.block.getWorld().getGameTime() < followUntil) return;
        followUntil = 0;
        if (task() == Task.FOLLOW) hut.set(Settings.GUARD_TASK, "PATROL");
    }

    public UUID followed() {
        return follow;
    }

    /** The player they rally to or follow (the mod's getPlayerToFollowOrRally), or null. */
    public Player leader() {
        UUID id = rally != null ? rally : rallyAt == null && task() == Task.FOLLOW ? follow : null;
        return id == null ? null : Bukkit.getPlayer(id);
    }

    /** Where the player they follow is; the hut if it isn't in their world. */
    public Location followTo() {
        Player p = follow == null ? null : Bukkit.getPlayer(follow);
        return p != null && p.getWorld().equals(hut.block.getWorld()) ? p.getLocation() : hut.block.getLocation().toCenterLocation();
    }

    /** The Miner's Hut whose mine they patrol, or null. */
    public Hut mine(Colony colony) {
        Hut h = mine == null ? null : colony.hutAt(mine);
        return h != null && h.type == Hut.Type.MINER ? h : null;
    }

    /** The mine they are to patrol is gone: back to patrolling the colony. @return false if they never had one. */
    public boolean mineGone() {
        if (mine == null) return false;
        mine = null;
        hut.set(Settings.GUARD_TASK, Task.PATROL.name());
        return true;
    }

    /** They patrol this mine, if it has room for one more guard hut. */
    public void assignMine(Hut miner, Colony colony) {
        if (mine(colony) == null && mineGuards(miner, colony).size() < mostMineGuards(miner.level)) mine = miner.block;
    }

    public void leaveMine() {
        mine = null;
    }

    /** The guard huts that patrol this mine. */
    public static List<Hut> mineGuards(Hut miner, Colony colony) {
        return colony.huts.stream().filter(h -> h.post != null && h.post.task() == Task.PATROL_MINE && h.post.mine(colony) == miner).toList();
    }

    /** How many guard huts a mine of this level takes (the mod's window's getMaxGuards). */
    public static int mostMineGuards(int level) {
        return (level + 1) / 2;
    }

    // ---- rallying --------------------------------------------------------------------------------------------------

    /** They rally to {@code p}. */
    public void rally(Player p) {
        rally = p.getUniqueId();
        rallyAt = null;
    }

    /** They rally to a colony banner (the standard research). */
    public void rally(Block flag) {
        rally = null;
        rallyAt = flag;
    }

    /** The rally ends; those already hungry are left starving (the mod's setRallyLocation). */
    public void stopRally(Colony colony) {
        if (rally == null && rallyAt == null) return;
        rally = null;
        rallyAt = null;
        for (Colonist w : guards(colony))
            if (w.saturation < Food.LOW_SATURATION) w.saturation = Math.max(0, w.saturation - Food.LOW_SATURATION);
    }

    /**
     * Where they rally to now, or null: not while the player lacks the active banner, and no more once the player left
     * the server or either left the colony's land (with the telescope research: 500 blocks from its town hall). To a
     * colony banner they rally until told to stop, whoever is there and whether or not it still stands, as the mod's.
     */
    public Location rallied(Colony colony) {
        if (rallyAt != null) {
            Location at = rallyAt.getLocation().toCenterLocation();
            if (inRange(at, colony)) return at;
            colony.tell("The colony banner is too far from the colony: the guards of its " + hut.type.title + " stopped rallying to it.");
            stopRally(colony);
            return null;
        }
        if (rally == null) return null;
        Player p = Bukkit.getPlayer(rally);
        if (p == null) { stopRally(colony); return null; }
        if (!inRange(p.getLocation(), colony)) {
            p.sendMessage(Component.text("Too far from the colony: the guards of its " + hut.type.title + " stopped following your banner.", NamedTextColor.RED));
            stopRally(colony);
            return null;
        }
        for (ItemStack i : p.getInventory().getContents()) if (i != null && banner.test(i, hut)) return p.getLocation();
        return null;
    }

    private boolean inRange(Location at, Colony colony) {
        if (!at.getWorld().equals(hut.block.getWorld())) return false;
        return colony.claims().contains(Claims.key(at.getBlockX() >> 4, at.getBlockZ() >> 4))
                || colony.research.effect(Effects.TELESCOPE) > 0 && Colonist.flat(at, colony.townHall().block.getLocation()) <= TELESCOPE_RANGE;
    }

    // ---- saving ----------------------------------------------------------------------------------------------------

    void save(ConfigurationSection s) {
        if (guard != null) s.set("guard", saved(guard));
        if (!patrol.isEmpty()) s.set("patrol", patrol.stream().map(GuardPost::saved).toList());
        if (follow != null) s.set("follow", follow.toString());
        if (followUntil > 0) s.set("follow-until", followUntil);
        if (mine != null) s.set("mine", saved(mine));
        if (lastPatrol > 0) s.set("last-patrol", lastPatrol);
    }

    void load(ConfigurationSection s) {
        guard = blockAt(s.getString("guard"));
        for (String p : s.getStringList("patrol")) if (blockAt(p) instanceof Block b) patrol.add(b);
        if (s.getString("follow") instanceof String id) follow = UUID.fromString(id);
        followUntil = s.getLong("follow-until");
        mine = blockAt(s.getString("mine"));
        lastPatrol = s.getLong("last-patrol");
    }

    private static String saved(Block b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    /** A block of the hut's world. */
    private Block blockAt(String saved) {
        if (saved == null) return null;
        String[] p = saved.split(" ");
        return hut.block.getWorld().getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
