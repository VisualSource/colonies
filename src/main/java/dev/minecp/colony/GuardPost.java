package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
 * the spot they guard, the player they follow, the mine they patrol and the player they rally to.
 */
public final class GuardPost {
    /** The guard task setting's values (the mod's GuardTaskSetting). */
    public enum Task { PATROL, GUARD, FOLLOW, PATROL_MINE }

    private static final int PATROL_BASE = 50, PATROL_PER_LEVEL = 30; // PATROL_BASE_DIST, PATROL_DISTANCE
    private static final int WAIT = 1, TIMEOUT = 5;      // colony ticks: for the others once the first is there, and to get there at all
    private static final int FAR = 20;                   // PathJobRandomPos: a random patrol point is at least this far on
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

    GuardPost(Hut hut) {
        this.hut = hut;
        point = hut.block;
    }

    public Task task() {
        return Task.valueOf(hut.setting(Settings.GUARD_TASK));
    }

    /** How far from the hut its patrol points and the spot it guards may be. */
    public int range() {
        return PATROL_BASE + PATROL_PER_LEVEL * hut.level;
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
        if (colony.workersAt(hut).size() <= arrived.size() || timer <= 0) goOn(w, colony);
    }

    /** Each colony tick: they go on without those who are late. */
    void colonyTick(Colony colony) {
        if (timer <= 0 || task() != Task.PATROL || --timer > 0) return;
        List<Colonist> guards = colony.workersAt(hut);
        if (!guards.isEmpty()) goOn(guards.getFirst(), colony);
    }

    private void goOn(Colonist w, Colony colony) {
        timer = TIMEOUT;
        arrived.clear();
        if (next != null) { point = next; next = null; return; }
        if (hut.setting(Settings.PATROL_MODE).equals("MANUAL") && !patrol.isEmpty()) {
            point = patrol.get((patrol.indexOf(point) + 1) % patrol.size()); // the first, if it is none of them
            return;
        }
        Block to = ThreadLocalRandom.current().nextBoolean() ? w.farSpot(FAR) : randomHut(colony);
        if (to != null) point = to.getLocation().distance(hut.block.getLocation()) > range() ? hut.block : to;
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
        followUntil = hut.block.getWorld().getFullTime() + ticks;
    }

    /** Once a second: a scroll's time to follow is up. */
    void checkFollow() {
        if (followUntil <= 0 || hut.block.getWorld().getFullTime() < followUntil) return;
        followUntil = 0;
        if (task() == Task.FOLLOW) hut.set(Settings.GUARD_TASK, "PATROL");
    }

    public UUID followed() {
        return follow;
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
    }

    /** The rally ends; those already hungry are left starving (the mod's setRallyLocation). */
    public void stopRally(Colony colony) {
        if (rally == null) return;
        rally = null;
        for (Colonist w : colony.workersAt(hut))
            if (w.saturation < Food.LOW_SATURATION) w.saturation = Math.max(0, w.saturation - Food.LOW_SATURATION);
    }

    /**
     * The player they rally to now, or null: not while the player lacks the active banner, and no more once the player
     * left the server or the colony's land (with the telescope research: 500 blocks from its town hall).
     */
    public Player rallied(Colony colony) {
        if (rally == null) return null;
        Player p = Bukkit.getPlayer(rally);
        if (p == null) { stopRally(colony); return null; }
        Location at = p.getLocation(), centre = colony.townHall().block.getLocation();
        World world = hut.block.getWorld();
        boolean inside = at.getWorld().equals(world) && colony.claims().contains(Claims.key(at.getBlockX() >> 4, at.getBlockZ() >> 4));
        if (!inside && (!at.getWorld().equals(world) || colony.research.effect(Effects.TELESCOPE) <= 0
                || Colonist.flat(at, centre) > TELESCOPE_RANGE)) {
            p.sendMessage(Component.text("Too far from the colony: the guards of its " + hut.type.title + " stopped following your banner.", NamedTextColor.RED));
            stopRally(colony);
            return null;
        }
        for (ItemStack i : p.getInventory().getContents()) if (i != null && banner.test(i, hut)) return p;
        return null;
    }

    // ---- saving ----------------------------------------------------------------------------------------------------

    void save(ConfigurationSection s) {
        if (guard != null) s.set("guard", saved(guard));
        if (!patrol.isEmpty()) s.set("patrol", patrol.stream().map(GuardPost::saved).toList());
        if (follow != null) s.set("follow", follow.toString());
        if (followUntil > 0) s.set("follow-until", followUntil);
        if (mine != null) s.set("mine", saved(mine));
    }

    void load(ConfigurationSection s) {
        guard = blockAt(s.getString("guard"));
        for (String p : s.getStringList("patrol")) if (blockAt(p) instanceof Block b) patrol.add(b);
        if (s.getString("follow") instanceof String id) follow = UUID.fromString(id);
        followUntil = s.getLong("follow-until");
        mine = blockAt(s.getString("mine"));
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
