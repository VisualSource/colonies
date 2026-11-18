package dev.minecp;

import com.destroystokyo.paper.entity.Pathfinder.PathResult;
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
import org.bukkit.block.data.type.Door;
import org.bukkit.attribute.Attribute;
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
 * The legs mob does the real pathfinding through Paper's Pathfinder API.
 * Entities are not saved by the world; they are spawned from colony data whenever the chunk is loaded.
 */
final class Colonist {
    private static final double WALK_SPEED = 0.45; // multiplier on the legs mob's movement speed
    private static final double STEP_DISTANCE = 1.6; // blocks walked per footstep sound
    private static final int WANDER_RADIUS = 12;
    private static final int LADDER_RANGE = 12; // how far around it looks for a ladder to another floor
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
    private Location stuckFrom;
    private int stuckTicks = 0;
    private int unstuck = 0; // times the watchdog moved it on during the current walk
    private int wait = 0;
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
        ladders(to);
        unstick(to);
        return true;
    }

    /**
     * Vanilla paths drop straight down a ladder shaft, but a mob on a ladder brushing the wall climbs back up and
     * spins at the top. Once it reaches the shaft, put it at the bottom, as the miner does on its ladder.
     */
    private void ladders(Location at) {
        PathResult path = legs.getPathfinder().hasPath() ? legs.getPathfinder().getCurrentPath() : null;
        Location next = path == null ? null : path.getNextPoint();
        if (next == null || next.getBlockY() >= at.getBlockY()) return;
        double dx = next.getBlockX() + 0.5 - at.getX(), dz = next.getBlockZ() + 0.5 - at.getZ();
        if (dx * dx + dz * dz > 1.2 * 1.2) return; // not at the shaft yet
        Block bottom = next.getBlock();
        boolean shaft = false;
        for (int y = bottom.getY(); y <= at.getBlockY(); y++)
            shaft |= Tag.CLIMBABLE.isTagged(bottom.getWorld().getBlockAt(bottom.getX(), y, bottom.getZ()).getType());
        if (shaft) legs.teleport(bottom.getLocation().add(0.5, 0, 0.5)); // keeps its path; it stands on this point now
    }

    /** Watchdog: walking but not getting anywhere (trapped by a fence, wedged on a corner) for a while -> hop aside. */
    private void unstick(Location at) {
        if (!legs.getPathfinder().hasPath()) { stuckTicks = 0; unstuck = 0; return; }
        if (stuckFrom == null || stuckFrom.distanceSquared(at) > 0.5 * 0.5) {
            stuckFrom = at;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            // Usually a jump it can't make (e.g. up off a path block): put it where it was going; else just step aside.
            var path = legs.getPathfinder().getCurrentPath();
            Location next = path == null ? null : path.getNextPoint();
            // (Not if that is where it stands already: blocked there, it would be put back on the same spot for ever.)
            // A second time on the same walk, the path itself is no good: drop it and let the job plan a new one.
            if (++unstuck < 2 && next != null && canStandIn(next.getBlock()) && !next.getBlock().equals(at.getBlock()))
                legs.teleport(next.getBlock().getLocation().add(0.5, 0, 0.5));
            else { legs.getPathfinder().stopPathfinding(); hopAside(null); unstuck = 0; }
            stuckFrom = null;
            stuckTicks = 0;
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
        legs.getPathfinder().stopPathfinding();
    }

    boolean isMoving() {
        return legs.getPathfinder().hasPath();
    }

    /**
     * Walks as close to {@code to} as any path leads, if that ends within {@code within} blocks of it.
     * @return false if no path gets that close, or it already stands at the closest point.
     */
    boolean approach(Location to, double within) {
        PathResult path = legs.getPathfinder().findPath(to);
        Location end = path == null ? null : path.getFinalPoint();
        if (end == null || end.toCenterLocation().distance(to) > within) return false;
        if (end.toCenterLocation().distance(to) < 2) { // the path ends in the block's own spot: stop short, beside it
            Location stop = null;
            for (Location p : path.getPoints()) if (p.toCenterLocation().distance(to) >= 2) stop = p;
            if (stop == null) return false; // already beside it
            path = legs.getPathfinder().findPath(stop.toCenterLocation());
            if (path == null || !path.canReachFinalPoint()) return false;
            end = stop;
        }
        if (end.toCenterLocation().distanceSquared(legs.getLocation()) < 1.5 * 1.5) return false; // nowhere closer to go
        return legs.getPathfinder().moveTo(path, WALK_SPEED);
    }

    /** Opens wooden doors on the way like a villager, and closes them once through. */
    private void doors(Location at) {
        PathResult path = legs.getPathfinder().hasPath() ? legs.getPathfinder().getCurrentPath() : null;
        Location next = path == null ? null : path.getNextPoint();
        for (Block b : new Block[]{at.getBlock(), next == null ? null : next.getBlock()}) {
            if (b == null || !Tag.MOB_INTERACTABLE_DOORS.isTagged(b.getType()) || !(b.getBlockData() instanceof Door d) || d.isOpen()) continue;
            int bottomY = d.getHalf() == Door.Half.TOP ? b.getY() - 1 : b.getY();
            if (Math.abs(at.getY() - bottomY) > 0.5) continue; // walking past above or below it: opening would drop it

            d.setOpen(true);
            b.setBlockData(d); // with physics, so the other half follows
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, 1, 1);
            opened.add(b);
        }
        opened.removeIf(b -> {
            if (b.getLocation().toCenterLocation().distanceSquared(at) < 2 * 2 || overlaps(b)) return false;
            if (b.getBlockData() instanceof Door d && d.isOpen()) {
                d.setOpen(false);
                b.setBlockData(d);
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 1, 1);
            }
            return true;
        });
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
        legs.getPathfinder().stopPathfinding();
        legs.teleport(best.getLocation().add(0.5, 0, 0.5));
    }

    /** Moves straight up onto the first free spot in this column. */
    void climbOut() {
        Block b = legs.getLocation().getBlock();
        for (int dy = 1; dy <= 16; dy++) if (canStandIn(b.getRelative(0, dy, 0))) {
            legs.getPathfinder().stopPathfinding();
            legs.teleport(b.getRelative(0, dy, 0).getLocation().add(0.5, 0, 0.5));
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
        legs.getPathfinder().stopPathfinding();
        hold(null);
        lieDown();
    }

    private void lieDown() {
        Location at = bed.getLocation().add(0.5, 0.6875, 0.5); // where vanilla puts a sleeper: on the pillow block
        legs.teleport(at);
        body.teleport(at);
        body.setPose(Pose.SLEEPING, true);
        // The client lays a sleeper out along its bed only if it knows the bed (yaw alone drifts with the body's
        // last movement); the API can't set that, so start vanilla sleep on the server entity.
        sleepHandle("startSleeping", bed);
        last = at;
    }

    /**
     * Calls a vanilla LivingEntity sleep method on the body; it also marks the bed occupied or free.
     * shortcut: reflection on Mojang-mapped names (Paper's runtime), replace if Paper adds a sleeping-position API.
     */
    private void sleepHandle(String method, Block at) {
        try {
            Object handle = body.getClass().getMethod("getHandle").invoke(body);
            if (at == null) { handle.getClass().getMethod(method).invoke(handle); return; }
            Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
            handle.getClass().getMethod(method, pos)
                    .invoke(handle, pos.getConstructor(int.class, int.class, int.class).newInstance(at.getX(), at.getY(), at.getZ()));
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning("[MineCP] can't call " + method + " on a colonist: " + e);
        }
    }

    /** Gets up and steps out beside the bed. */
    void wake() {
        if (bed == null) return;
        Block head = bed;
        bed = null;
        if (body == null) return;
        body.setPose(Pose.STANDING, false);
        sleepHandle("stopSleeping", null);
        teleportNear(head);
    }

    /** Teleports onto {@code spot} (a block it can stand in). */
    void teleportTo(Block spot) {
        legs.getPathfinder().stopPathfinding();
        legs.teleport(spot.getLocation().add(0.5, 0, 0.5));
    }

    /** Teleports onto a free spot near {@code block}, for when nothing walkable leads there. */
    void teleportNear(Block block) {
        List<Block> spots = spotsBeside(block);
        for (int dy = 2; spots.isEmpty() && dy <= 16; dy++) // nothing at reach height: look further up
            for (BlockFace f : SIDES) if (canStandIn(block.getRelative(f).getRelative(0, dy, 0))) spots.add(block.getRelative(f).getRelative(0, dy, 0));
        if (spots.isEmpty()) return;
        legs.getPathfinder().stopPathfinding();
        legs.teleport(spots.getFirst().getLocation().add(0.5, 0, 0.5));
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
        if (viaLadder(block)) return;
        if (approach(block.getLocation().toCenterLocation(), Double.MAX_VALUE)) return;
        teleportNear(block);
    }

    /**
     * No vanilla path climbs a ladder, so when {@code target} is on another floor, walks to a ladder on its own
     * floor and, once there, goes to where that ladder meets the target's floor (as the miner does on its ladder).
     * @return false if no reachable ladder brings it closer to the target's floor.
     */
    private boolean viaLadder(Block target) {
        Block here = legs.getLocation().getBlock();
        if (Math.abs(target.getY() - here.getY()) < 2) return false;
        record Ladder(Block near, Block far) {}
        List<Ladder> ladders = new ArrayList<>();
        for (int dx = -LADDER_RANGE; dx <= LADDER_RANGE; dx++) for (int dz = -LADDER_RANGE; dz <= LADDER_RANGE; dz++) {
            Block col = here.getRelative(dx, 0, dz);
            int dy = -1; // the shaft passes this floor: a ladder here, or just below (standing at its top)
            while (dy <= 1 && !Tag.CLIMBABLE.isTagged(col.getRelative(0, dy, 0).getType())) dy++;
            if (dy > 1) continue;
            int bottom = col.getY() + dy, top = bottom;
            while (Tag.CLIMBABLE.isTagged(col.getWorld().getBlockAt(col.getX(), bottom - 1, col.getZ()).getType())) bottom--;
            while (Tag.CLIMBABLE.isTagged(col.getWorld().getBlockAt(col.getX(), top + 1, col.getZ()).getType())) top++;
            int farY = Math.clamp(target.getY(), bottom, top + 1);
            if (Math.abs(farY - target.getY()) >= Math.abs(here.getY() - target.getY())) continue; // gets it no closer
            Block near = shaftSpot(col.getWorld().getBlockAt(col.getX(), here.getY(), col.getZ()));
            Block far = shaftSpot(col.getWorld().getBlockAt(col.getX(), farY, col.getZ()));
            if (near != null && far != null) ladders.add(new Ladder(near, far));
        }
        ladders.sort(Comparator.comparingDouble(l -> l.near().getLocation().distanceSquared(here.getLocation())));
        int tries = 0;
        for (Ladder l : ladders) {
            Block n = l.near();
            if (n.getY() == here.getY() && Math.abs(n.getX() - here.getX()) <= 1 && Math.abs(n.getZ() - here.getZ()) <= 1) {
                teleportTo(l.far());
                return true;
            }
            if (++tries > 4) break; // each try is a path search
            if (walk(n.getLocation().add(0.5, 0, 0.5))) return true;
        }
        return false;
    }

    /** Where to stand to use the shaft at {@code at}: on the ladder itself if it stands on something, else beside it. */
    private static Block shaftSpot(Block at) {
        if (canStandIn(at)) return at;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST,
                BlockFace.NORTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_WEST, BlockFace.NORTH_WEST})
            if (canStandIn(at.getRelative(f))) return at.getRelative(f);
        return null;
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
            if (near(target) || !legs.getPathfinder().hasPath()) {
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
        PathResult path = reachablePath(to);
        return path != null && legs.getPathfinder().moveTo(path, WALK_SPEED);
    }

    /**
     * Walks to the nearest reachable free spot within 2 blocks of {@code block} (e.g. a chest or a tree).
     * @return the spot it is walking to, or null if none can be reached.
     */
    Location walkBeside(Block block) {
        int tries = 0;
        for (Block b : spotsBeside(block)) {
            if (++tries > 12) break; // each try is a path search; enough to get around one obstacle
            PathResult path = reachablePath(b.getLocation().toCenterLocation());
            if (path != null && legs.getPathfinder().moveTo(path, WALK_SPEED)) return b.getLocation().toCenterLocation();
        }
        return null;
    }

    /** Vanilla returns partial paths that stop short of unreachable targets; treat those as no path. */
    private PathResult reachablePath(Location to) {
        PathResult path = legs.getPathfinder().findPath(to);
        return path != null && path.canReachFinalPoint() ? path : null;
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
            l.setInvulnerable(true);
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
        // Vanilla caps path search by follow range; the trader's default cut paths off ~10 blocks out.
        legs.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(48);
        legs.getPathfinder().setCanOpenDoors(true); // paths may go through closed wooden doors; doors() opens them
        String skinName = SKINS[skin % SKINS.length];
        boolean slim = skin >= SKINS.length;
        body = world.spawn(last, Mannequin.class, m -> {
            m.customName(Component.text(name));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text(status));
            m.setProfile(ResolvableProfile.resolvableProfile().skinPatch(p -> p
                    .body(Key.key("entity/player/" + (slim ? "slim/" : "wide/") + skinName))
                    .model(slim ? SkinModel.SLIM : SkinModel.CLASSIC)).build());
            m.setInvulnerable(true);
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
        if (body != null && bed != null) sleepHandle("stopSleeping", null); // else the bed stays marked occupied
        if (body != null) body.remove();
        if (legs != null) legs.remove();
        body = null;
        legs = null;
    }
}
