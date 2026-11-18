package dev.minecp;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures.SkinModel;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A colony citizen: a visible Mannequin body that copies the position of a hidden "legs" mob every tick.
 * The legs mob is moved along our own routes ({@link Route}) by steering its MoveControl ({@link Nms}).
 * Entities are not saved by the world; they are spawned from colony data whenever the chunk is loaded.
 */
final class Colonist {
    private static final double WALK_SPEED = 0.45; // multiplier on the legs mob's movement speed
    private static final double STEP_DISTANCE = 1.6; // blocks walked per footstep sound
    private static final int WANDER_RADIUS = 12;
    private static final int CLIMB_TICKS = 100;     // a ladder climb taking longer than this, plus 10 a block, has failed
    private static final double CLIMB_SPEED = 0.4;  // MoveControl speed on and off ladders
    private static final int STUCK_TICKS = 60; // 3 seconds without moving half a block while walking
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    /** Vanilla default skins, shipped with every client, so no resource pack or skin lookups are needed. */
    private static final String[] SKINS = {"alex", "ari", "efe", "makena", "noor", "kai", "steve", "sunny", "zuri"};
    private static final int FEMALE_SKINS = 5; // the first five above
    static final String LEGS_TAG = "minecp_legs";

    /** A skin index: which default character, and wide or slim arms (index >= SKINS.length means slim). */
    static int randomSkin(boolean female) {
        var rnd = ThreadLocalRandom.current();
        int character = female ? rnd.nextInt(FEMALE_SKINS) : rnd.nextInt(FEMALE_SKINS, SKINS.length);
        return character + (rnd.nextBoolean() ? SKINS.length : 0);
    }

    final UUID id;
    final String name;
    final int skin;
    Hut home; // the house the colonist lives in, or null
    double saturation = Food.MAX_SATURATION; // hunger: 0 starving .. 20 full; saved with the colony
    static final double MAX_HEALTH = 20;      // the mod's BASE_MAX_HEALTH
    double health = MAX_HEALTH;               // saved with the colony
    int hurtTicks;   // ticks since a mob or player last hurt it are still counted down: no healing meanwhile
    int fleeTicks;   // running away: no work until it runs out
    int healTicks;
    Meal meal;              // while it is getting something to eat
    String hungerNote;      // why its last meal failed, for the menus
    int foodRetry, hungerTicks;
    private Location last; // where the colonist is, kept while despawned
    private Mannequin body;
    private WanderingTrader legs;
    private Location target; // wander destination
    private Block bed;       // head of the bed it is asleep in
    boolean bedtime;         // on its way to bed or asleep (the mod keeps a citizen in its sleep state once there)
    private String status = "Unemployed";
    private int wait = 0;
    private Climb climb;            // on a ladder
    private Location approachTo;   // approach(): the target it is closing in on, its best distance so far, and
    private double approachBest;   // how many partial paths since then got it no closer
    private int approachFails;
    // the route being followed, where it is on it, and what it was searched for (to search again).
    private List<Route.Step> route;
    private int routeAt, climbResume = -1;
    private int[] routeTo;
    private Route.Goal routeGoal;
    private Location routeFrom;
    private int routeIdle, replans;
    final Stats stats = new Stats(); // movement counters for /colony movement; not saved

    /** How often moving went wrong, to compare movement systems. */
    static final class Stats {
        int teleports, stuck, searches;
        long searchNanos;
    }
    private double walked = 0;
    private final List<Block> opened = new ArrayList<>(); // doors it opened, to close behind it

    Colonist(UUID id, String name, int skin, Location at) {
        this.id = id;
        this.name = name;
        this.skin = skin;
        this.last = at.clone();
    }

    Location location() {
        return last.clone();
    }

    /** One server tick: keeps the entities spawned and the body on the legs. @return false while despawned. */
    boolean tick() {
        if (legs == null || !body.isValid() || !legs.isValid()) {
            despawn();
            if (!entitiesTicking(last)) return false;
            spawn();
        }
        if (bed != null) { // lying still: keep the body on the bed (respawning resets its pose)
            if (body.getPose() != Pose.SLEEPING) lieDown();
            return true;
        }
        Location to = legs.getLocation();
        Location from = body.getLocation();
        body.teleport(to);
        last = to;
        footsteps(from, to);
        doors(to);
        if (climb != null) climbTick(to);
        else if (route != null) followTick(to);
        return true;
    }

    /**
     * Each tick: steer toward the next step of the route (vanilla's MoveControl jumps up steps by
     * itself), hand ladders to the climb, and when it stops getting anywhere search again, then teleport ahead.
     */
    private void followTick(Location at) {
        if (climbResume >= 0) { routeAt = climbResume; climbResume = -1; }
        // Past the steps it has reached (the mod's rule: within half a block across and a block up or down).
        while (routeAt < route.size()) {
            Route.Step s = route.get(routeAt);
            if (s.ladder() || Math.abs(at.getX() - s.x() - 0.5) >= 0.5 || Math.abs(at.getZ() - s.z() - 0.5) >= 0.5
                    || Math.abs(at.getY() - s.h()) >= 1) break;
            routeAt++;
            replans = 0; // getting somewhere
        }
        if (routeAt >= route.size()) { route = null; Nms.halt(legs); return; }
        Route.Step s = route.get(routeAt);
        if (s.ladder()) { // the whole run up or down this ladder, then the step off it
            int j = routeAt;
            while (j + 1 < route.size() && route.get(j + 1).ladder() && route.get(j + 1).x() == s.x() && route.get(j + 1).z() == s.z()) j++;
            int exit = Math.min(j + 1, route.size() - 1);
            Route.Step off = route.get(exit);
            World w = at.getWorld();
            climbResume = exit + 1;
            startClimb(w.getBlockAt(s.x(), s.y(), s.z()), w.getBlockAt(off.x(), off.y(), off.z()));
            return;
        }
        if (routeIdle % 10 == 0 && !Route.standable(at.getWorld(), s)) { // the world changed (a miner digs its own floor)
            stats.stuck++;
            if (++replans > 2) { route = null; replans = 0; return; } // still blocked: let the job decide again
            replan();
            return;
        }
        Nms.drive(legs, s.x() + 0.5, s.h(), s.z() + 0.5, WALK_SPEED);
        if (routeFrom == null || routeFrom.distanceSquared(at) > 0.5 * 0.5) { routeFrom = at; routeIdle = 0; return; }
        if (++routeIdle <= STUCK_TICKS) return;
        // Stuck, as the mod's stuck handler goes: search again first, then teleport a few steps ahead on the route.
        stats.stuck++;
        routeIdle = 0;
        routeFrom = null;
        if (++replans <= 1) { replan(); return; }
        replans = 0;
        int ahead = Math.min(routeAt + 5, route.size() - 1);
        Route.Step t = route.get(ahead);
        warp(new Location(at.getWorld(), t.x() + 0.5, t.h(), t.z() + 0.5));
        routeAt = ahead;
    }

    /** searches from here. Null if it stands nowhere a route can start. */
    private Route.Result plan(int gx, int gy, int gz, Route.Goal goal) {
        Location at = legs.getLocation();
        long t = System.nanoTime();
        Route.Result r = Route.find(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), gx, gy, gz, goal);
        stats.searches++;
        stats.searchNanos += System.nanoTime() - t;
        return r;
    }

    private void follow(Route.Result r, int gx, int gy, int gz, Route.Goal goal) {
        climb = null;
        climbResume = -1;
        route = r.steps().isEmpty() ? null : new ArrayList<>(r.steps());
        routeAt = 0;
        routeTo = new int[]{gx, gy, gz};
        routeGoal = goal;
        routeFrom = null;
        routeIdle = 0;
    }

    private void replan() {
        Route.Result r = plan(routeTo[0], routeTo[1], routeTo[2], routeGoal);
        int keep = replans;
        if (r == null || r.steps().isEmpty()) { route = null; return; }
        follow(r, routeTo[0], routeTo[1], routeTo[2], routeGoal);
        replans = keep;
    }

    /** Where walkBeside may stop for {@code b}: up to 2 out, 2 below to 1 above, not in its own column. */
    private static Route.Goal besideGoal(Block b) {
        int bx = b.getX(), by = b.getY(), bz = b.getZ();
        return (x, y, z) -> Math.abs(x - bx) <= 2 && Math.abs(z - bz) <= 2 && y - by >= -2 && y - by <= 1 && !(x == bx && z == bz);
    }

    /** A ladder climb in progress : up or down the column at {@code col} to stand on {@code to}. */
    private record Climb(Block col, Block to, boolean up, int[] ticks) {}

    /** Climbs or slides along a ladder with vanilla physics, then steps off onto {@code to}. */
    private void startClimb(Block col, Block to) {
        climb = new Climb(col, to, to.getY() > legs.getLocation().getBlockY(), new int[1]);
    }

    private void climbTick(Location at) {
        Climb c = climb;
        double cx = c.col().getX() + 0.5, cz = c.col().getZ() + 0.5, tx = c.to().getX() + 0.5, tz = c.to().getZ() + 0.5;
        // Centred: off-centre, the 0.6-wide body overlaps the floor beside the hole and bumps its head on it.
        boolean inShaft = Math.abs(at.getX() - cx) < 0.15 && Math.abs(at.getZ() - cz) < 0.15;
        boolean level = c.up() ? at.getY() >= c.to().getY() : at.getY() <= c.to().getY() + 0.05;
        if (level && Math.abs(at.getX() - tx) < 0.4 && Math.abs(at.getZ() - tz) < 0.4) {
            climb = null; // off the ladder on the other floor
            return;
        }
        if (++c.ticks()[0] > CLIMB_TICKS + 10 * Math.abs(c.to().getY() - c.col().getY())) { // vanilla climbs 4 a second // physics let it down (open top, no wall): finish as before
            climb = null;
            teleportTo(c.to());
            return;
        }
        if (level && c.up()) Nms.drive(legs, tx, c.to().getY(), tz, CLIMB_SPEED); // step off onto the floor
        else if (level) { // step off the bottom without MoveControl, which would hop on the ladder
            Nms.halt(legs);
            legs.setVelocity(new org.bukkit.util.Vector(Math.clamp((tx - at.getX()) * 0.3, -0.15, 0.15),
                    legs.getVelocity().getY(), Math.clamp((tz - at.getZ()) * 0.3, -0.15, 0.15)));
        }
        else if (!c.up() && at.getBlockX() == c.col().getX() && at.getBlockZ() == c.col().getZ()) {
            // Sliding down: no steering (MoveControl jumps a mob standing in a ladder block), just keep it centred.
            Nms.halt(legs);
            legs.setVelocity(new org.bukkit.util.Vector((cx - at.getX()) * 0.3, -0.15, (cz - at.getZ()) * 0.3));
        }
        else if (c.up() && at.getY() >= c.to().getY() - 1) { // at the top: over the floor edge, even if it bobs
            Nms.drive(legs, tx, c.to().getY(), tz, CLIMB_SPEED);
            Nms.jump(legs);
        }
        else if (!inShaft) Nms.drive(legs, cx, c.up() ? at.getY() : c.to().getY(), cz, CLIMB_SPEED); // into the shaft
        else if (c.up()) { // pushing into the ladder's wall and jumping both make vanilla lift it 0.2 a tick (vines: jump)
            BlockFace wall = at.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Ladder l ? l.getFacing().getOppositeFace() : null;
            Nms.drive(legs, cx + (wall == null ? 0 : wall.getModX() * 0.8), at.getY() + 1,
                    cz + (wall == null ? 0 : wall.getModZ() * 0.8), CLIMB_SPEED);
            Nms.jump(legs);
        }
    }

    String status() {
        return status;
    }

    void setStatus(String status) {
        if (status.equals(this.status)) return;
        this.status = status;
        if (body != null) body.setDescription(Component.text(status));
    }

    /** Shows {@code item} (null: nothing) in the colonist's main hand. */
    void hold(ItemStack item) {
        if (body != null) body.getEquipment().setItemInMainHand(item);
    }

    LivingEntity living() {
        return body;
    }

    boolean onGround() {
        return legs.isOnGround();
    }

    void stopWalking() {
        climb = null;
        route = null;
    }

    boolean isMoving() {
        return climb != null || route != null;
    }

    /**
     * Walks as close to {@code to} as any path leads, if that ends within {@code within} blocks of it.
     * @return false if no path gets that close, or it already stands at the closest point.
     */
    boolean approach(Location to, double within) {
        // Partial paths can take turns ending in two dead ends near the target (a ledge and the pocket beside it),
        // each one far enough from the other to pass the check below: give up once they stop getting it closer.
        double now = legs.getLocation().distance(to);
        if (!to.equals(approachTo)) { approachTo = to.clone(); approachBest = now; approachFails = 0; }
        else if (now < approachBest - 0.5) { approachBest = now; approachFails = 0; }
        else if (++approachFails >= 3) return false;
        Block b = to.getBlock();
        Route.Goal goal = besideGoal(b);
        Route.Result r = plan(b.getX(), b.getY(), b.getZ(), goal);
        if (r == null || r.steps().isEmpty()) return false; // nowhere to start, or already as close as it gets
        Route.Step end = r.steps().getLast();
        Location e = new Location(to.getWorld(), end.x() + 0.5, end.h(), end.z() + 0.5);
        if (e.distance(to) > within || e.distanceSquared(legs.getLocation()) < 1.5 * 1.5) return false;
        follow(r, b.getX(), b.getY(), b.getZ(), goal);
        return true;
    }

    /** Opens wooden doors and fence gates on the way, and closes them once through. */
    private void doors(Location at) {
        Block step1 = null, step2 = null; // the next two steps (the mod checks 2 ahead)
        if (route != null && routeAt < route.size()) {
            Route.Step s = route.get(routeAt);
            step1 = at.getWorld().getBlockAt(s.x(), s.y(), s.z());
            if (routeAt + 1 < route.size()) { s = route.get(routeAt + 1); step2 = at.getWorld().getBlockAt(s.x(), s.y(), s.z()); }
        }
        for (Block b : new Block[]{at.getBlock(), step1, step2}) {
            if (b == null || !(b.getBlockData() instanceof Openable o) || o.isOpen() || !toggles(b)) continue;
            int bottomY = o instanceof Door d && d.getHalf() == Door.Half.TOP ? b.getY() - 1 : b.getY();
            if (Math.abs(at.getY() - bottomY) > 0.5) continue; // walking past above or below it: opening would drop it
            if (b.getLocation().toCenterLocation().distanceSquared(at) > 2.5 * 2.5) continue; // the mod opens within 2

            o.setOpen(true);
            b.setBlockData(o); // with physics, so a door's other half follows
            b.getWorld().playSound(b.getLocation(), o instanceof Door ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_FENCE_GATE_OPEN, SoundCategory.BLOCKS, 1, 1);
            opened.add(b);
        }
        opened.removeIf(b -> {
            if (b.getLocation().toCenterLocation().distanceSquared(at) < 2 * 2 || overlaps(b)) return false;
            if (toggles(b) && b.getBlockData() instanceof Openable o && o.isOpen()) {
                o.setOpen(false);
                b.setBlockData(o);
                b.getWorld().playSound(b.getLocation(), o instanceof Door ? Sound.BLOCK_WOODEN_DOOR_CLOSE : Sound.BLOCK_FENCE_GATE_CLOSE, SoundCategory.BLOCKS, 1, 1);
            }
            return true;
        });
    }

    /** What citizens open and close on their way, as the mod's citizens do: wooden doors and fence gates. */
    private static boolean toggles(Block b) {
        return Tag.MOB_INTERACTABLE_DOORS.isTagged(b.getType()) || Tag.FENCE_GATES.isTagged(b.getType());
    }

    /** Whether {@code e} is this citizen's body or legs. */
    boolean owns(org.bukkit.entity.Entity e) {
        return e != null && (e.equals(body) || e.equals(legs));
    }

    /** The hurt flash and sound a player would make. */
    void showHurt() {
        if (body == null) return;
        body.playHurtAnimation(0);
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.NEUTRAL, 1, 1);
    }

    /**
     * Runs {@code distance} blocks away from {@code from} (the mod: 15 from an attacker, 5 from a hurting block),
     * trying straight away first, then a little to either side.
     */
    void flee(Location from, int distance) {
        Location at = legs.getLocation();
        org.bukkit.util.Vector away = at.toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new org.bukkit.util.Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
        away.normalize().multiply(distance);
        for (double turn : new double[]{0, 0.5, -0.5, 1, -1}) {
            Block b = at.clone().add(away.clone().rotateAroundY(turn)).getBlock();
            for (int dy = 4; dy >= -4; dy--) {
                Block s = b.getRelative(0, dy, 0);
                if (canStandIn(s) && walk(s.getLocation().add(0.5, 0, 0.5))) {
                    fleeTicks = distance * 10; // about the time it takes to get there
                    return;
                }
            }
        }
    }

    /** Whether the colonist's body is (partly) inside {@code b}; a mob is 0.6 wide, so it can overlap neighbours. */
    boolean overlaps(Block b) {
        return legs.getBoundingBox().overlaps(BoundingBox.of(b));
    }

    /**
     * Teleports to the nearest free spot at least 1.5 blocks from {@code avoid} (e.g. a block about to be placed),
     * or with {@code avoid} null, to the nearest free spot other than where it stands. Climbs up if there is none.
     */
    void hopAside(Block avoid) {
        Block here = legs.getLocation().getBlock(), best = null;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 2; dy++) {
            Block b = here.getRelative(dx, dy, dz);
            if (!canStandIn(b) || (avoid == null ? b.equals(here)
                    : b.getLocation().distanceSquared(avoid.getLocation()) < 1.5 * 1.5)) continue;
            if (best == null || b.getLocation().distanceSquared(here.getLocation())
                    < best.getLocation().distanceSquared(here.getLocation())) best = b;
        }
        if (best == null) { climbOut(); return; }
        stopWalking();
        warp(best.getLocation().add(0.5, 0, 0.5));
    }

    /** Moves straight up onto the first free spot in this column. */
    void climbOut() {
        Block b = legs.getLocation().getBlock();
        for (int dy = 1; dy <= 16; dy++) if (canStandIn(b.getRelative(0, dy, 0))) {
            stopWalking();
            warp(b.getRelative(0, dy, 0).getLocation().add(0.5, 0, 0.5));
            return;
        }
    }

    /** The head half of the bed it sleeps in, or null while awake. */
    Block bed() {
        return bed;
    }

    /** Lies down in the bed whose head half is {@code head}. */
    void sleepIn(Block head) {
        bed = head;
        stopWalking();
        hold(null);
        lieDown();
    }

    private void lieDown() {
        Location at = bed.getLocation().add(0.5, 0.6875, 0.5); // where vanilla puts a sleeper: on the pillow block
        legs.teleport(at);
        body.teleport(at);
        body.setPose(Pose.SLEEPING, true);
        Nms.sleep(body, bed); // the client lays the body along the bed only if it knows it (yaw alone drifts)
        last = at;
    }

    /** Gets up and steps out beside the bed. */
    void wake() {
        if (bed == null) return;
        Block head = bed;
        bed = null;
        if (body == null) return;
        body.setPose(Pose.STANDING, false);
        Nms.wake(body);
        teleportNear(head);
    }

    /** Teleports onto {@code spot} (a block it can stand in). */
    void teleportTo(Block spot) {
        stopWalking();
        warp(spot.getLocation().add(0.5, 0, 0.5));
    }

    /** Teleports onto a free spot near {@code block}, for when nothing walkable leads there. */
    void teleportNear(Block block) {
        List<Block> spots = spotsBeside(block);
        for (int dy = 2; spots.isEmpty() && dy <= 16; dy++) // nothing at reach height: look further up
            for (BlockFace f : SIDES) if (canStandIn(block.getRelative(f).getRelative(0, dy, 0))) spots.add(block.getRelative(f).getRelative(0, dy, 0));
        if (spots.isEmpty()) return;
        stopWalking();
        warp(spots.getFirst().getLocation().add(0.5, 0, 0.5));
    }

    /** Whether it stands on a spot {@link #walkBeside} would walk to for {@code block} (up to 2 out, 2 below to 1 above). */
    boolean isBeside(Block block) {
        Block at = last.getBlock();
        int dy = at.getY() - block.getY();
        return Math.abs(at.getX() - block.getX()) <= 2 && Math.abs(at.getZ() - block.getZ()) <= 2 && dy >= -2 && dy <= 1;
    }

    /**
     * Walks beside {@code block}. Paths only reach ~48 blocks, so farther away it walks as far toward it as a path
     * leads and goes on from there; it teleports only when no path gets it any closer.
     */
    void goBeside(Block block) {
        if (walkBeside(block) != null) return;
        if (approach(block.getLocation().toCenterLocation(), Double.MAX_VALUE)) return;
        teleportNear(block);
    }

    /** Starving citizens walk slowly, as in the mod. */
    void setStarving(boolean starving) {
        if (legs == null || starving == legs.hasPotionEffect(PotionEffectType.SLOWNESS)) return;
        if (starving) legs.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, PotionEffect.INFINITE_DURATION, 1, false, false, false));
        else legs.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /** Free spots next to {@code block} to reach it from: level with it, or up to 2 below (it sits on a pillar) or 1 above. */
    private static List<Block> spotsBeside(Block block) {
        List<Block> spots = new ArrayList<>();
        // Up to 2 blocks out (a small oak's leaves hang at head height right beside the trunk), nearest first.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy : new int[]{0, -1, 1, -2}) {
            if (dx == 0 && dz == 0) continue;
            Block b = block.getRelative(dx, dy, dz);
            if (canStandIn(b)) spots.add(b);
        }
        spots.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(block.getLocation())));
        return spots;
    }

    void swing() {
        body.swingMainHand();
    }

    void wander(Location center) {
        if (target != null) {
            if (near(target) || !isMoving()) {
                target = null;
                wait = ThreadLocalRandom.current().nextInt(60, 200);
            }
            return;
        }
        if (--wait > 0) return;
        Location spot = randomStandSpot(center);
        if (spot != null && walk(spot)) target = spot;
        else wait = 20;
    }

    private static Location randomStandSpot(Location center) {
        var rnd = ThreadLocalRandom.current();
        Block base = center.getBlock().getRelative(rnd.nextInt(-WANDER_RADIUS, WANDER_RADIUS + 1), 4,
                rnd.nextInt(-WANDER_RADIUS, WANDER_RADIUS + 1));
        for (int dy = 0; dy <= 8; dy++) {
            Block b = base.getRelative(0, -dy, 0);
            if (canStandIn(b)) return b.getLocation().toCenterLocation();
        }
        return null;
    }

    /** Room to stand in {@code b}, on solid ground that isn't a fence, wall or gate (1.5 tall: no mob walks there). */
    static boolean canStandIn(Block b) {
        Material below = b.getRelative(BlockFace.DOWN).getType();
        return b.isPassable() && b.getRelative(BlockFace.UP).isPassable() && below.isSolid()
                && !Tag.FENCES.isTagged(below) && !Tag.WALLS.isTagged(below) && !Tag.FENCE_GATES.isTagged(below);
    }

    /** Walks to exactly {@code to}. @return false if no full path leads there. */
    boolean walk(Location to) {
        Block b = to.getBlock();
        int bx = b.getX(), by = b.getY(), bz = b.getZ();
        Route.Goal goal = (x, y, z) -> x == bx && y == by && z == bz;
        Route.Result r = plan(bx, by, bz, goal);
        if (r == null || !r.reached() || r.steps().isEmpty()) return false; // no way there, or already there
        follow(r, bx, by, bz, goal);
        return true;
    }

    /**
     * Walks to the nearest reachable free spot within 2 blocks of {@code block} (e.g. a chest or a tree).
     * @return the spot it is walking to, or null if none can be reached.
     */
    Location walkBeside(Block block) {
        Route.Goal goal = besideGoal(block);
        Route.Result r = plan(block.getX(), block.getY(), block.getZ(), goal);
        if (r == null || !r.reached()) return null;
        follow(r, block.getX(), block.getY(), block.getZ(), goal);
        Route.Step end = r.steps().isEmpty() ? null : r.steps().getLast();
        return end == null ? legs.getLocation() : new Location(block.getWorld(), end.x() + 0.5, end.h(), end.z() + 0.5);
    }

    /** Teleports the legs while walking (not into bed): counted, it's what better movement should make rare. */
    private void warp(Location to) {
        stats.teleports++;
        legs.teleport(to);
    }

    private boolean near(Location to) {
        return legs.getLocation().distanceSquared(to) < 1.0; // within 1 block of the target spot
    }

    // Legs are silenced (no trader chatter) and teleported bodies make no steps, so play them ourselves.
    private void footsteps(Location from, Location to) {
        if (!legs.isOnGround()) return;
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        walked += Math.sqrt(dx * dx + dz * dz);
        if (walked < STEP_DISTANCE) return;
        walked = 0;
        var ground = to.clone().subtract(0, 0.2, 0).getBlock();
        if (ground.isEmpty()) return;
        var sounds = ground.getBlockData().getSoundGroup();
        to.getWorld().playSound(to, sounds.getStepSound(), SoundCategory.NEUTRAL,
                sounds.getVolume() * 0.15f, sounds.getPitch());
    }

    private static boolean entitiesTicking(Location at) {
        var world = at.getWorld();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        return world.isChunkLoaded(cx, cz) && world.getChunkAt(cx, cz).getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING;
    }

    private void spawn() {
        var world = last.getWorld();
        // Wandering trader: player-sized, goal-based AI (removable), no burning, not removed on peaceful.
        legs = world.spawn(last, WanderingTrader.class, l -> {
            l.setVisibleByDefault(false);
            l.setSilent(true);
            l.setInvulnerable(false); // hits on it hurt the citizen (Colony.hurt); the hits themselves are cancelled
            l.setCollidable(false);
            l.setPersistent(false); // the colony file is the source of truth, not the world save
            l.setDespawnDelay(0);
            l.addScoreboardTag(LEGS_TAG);
        });
        Bukkit.getMobGoals().removeAllGoals(legs);
        // Paper only fully ticks mobs within ~32 blocks of a player ("entity activation range"); farther
        // away the legs would freeze mid-walk. Any potion effect makes Paper keep an entity active.
        legs.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, PotionEffect.INFINITE_DURATION, 0,
                false, false, false));
        String skinName = SKINS[skin % SKINS.length];
        boolean slim = skin >= SKINS.length;
        body = world.spawn(last, Mannequin.class, m -> {
            m.customName(Component.text(name));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text(status));
            m.setProfile(ResolvableProfile.resolvableProfile().skinPatch(p -> p
                    .body(Key.key("entity/player/" + (slim ? "slim/" : "wide/") + skinName))
                    .model(slim ? SkinModel.SLIM : SkinModel.CLASSIC)).build());
            m.setInvulnerable(false);
            m.setCollidable(false);
            m.setGravity(false); // position comes from the legs every tick
            m.setPersistent(false);
        });
        target = null;
        wait = 20;
    }

    void despawn() {
        for (Block b : opened) if (b.getBlockData() instanceof Door d && d.isOpen()) { d.setOpen(false); b.setBlockData(d); }
        opened.clear();
        if (body != null && bed != null) Nms.wake(body); // else the bed stays marked occupied
        if (body != null) body.remove();
        if (legs != null) legs.remove();
        body = null;
        legs = null;
    }
}
