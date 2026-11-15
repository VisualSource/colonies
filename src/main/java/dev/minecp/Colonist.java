package dev.minecp;

import com.destroystokyo.paper.entity.Pathfinder.PathResult;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
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
    private static final int STUCK_TICKS = 60; // 3 seconds without moving half a block while walking
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    /** Vanilla default skins, shipped with every client, so no resource pack or skin lookups are needed. */
    private static final String[] SKINS = {"alex", "ari", "efe", "makena", "noor", "kai", "steve", "sunny", "zuri"};
    private static final int FEMALE_SKINS = 5; // the first five above

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
    private Location last; // where the colonist is, kept while despawned
    private Mannequin body;
    private WanderingTrader legs;
    private Location target; // wander destination
    private String status = "Unemployed";
    private Location stuckFrom;
    private int stuckTicks = 0;
    private int wait = 0;
    private double walked = 0;

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
        Location to = legs.getLocation();
        Location from = body.getLocation();
        body.teleport(to);
        last = to;
        footsteps(from, to);
        unstick(to);
        return true;
    }

    /** Watchdog: walking but not getting anywhere (trapped by a fence, wedged on a corner) for a while -> hop aside. */
    private void unstick(Location at) {
        if (!legs.getPathfinder().hasPath()) { stuckTicks = 0; return; }
        if (stuckFrom == null || stuckFrom.distanceSquared(at) > 0.5 * 0.5) {
            stuckFrom = at;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            hopAside(null);
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

    boolean isMoving() {
        return legs.getPathfinder().hasPath();
    }

    /** Walks to within {@code reach} blocks of {@code to}. @return false if it cannot get there. */
    boolean walkTo(Location to, int reach) {
        PathResult path = legs.getPathfinder().findPath(to, reach);
        return path != null && path.canReachFinalPoint() && legs.getPathfinder().moveTo(path, WALK_SPEED);
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

    /** Teleports onto a free spot near {@code block}, for when nothing walkable leads there. */
    void teleportNear(Block block) {
        List<Block> spots = spotsBeside(block);
        for (int dy = 2; spots.isEmpty() && dy <= 16; dy++) // nothing at reach height: look further up
            for (BlockFace f : SIDES) if (canStandIn(block.getRelative(f).getRelative(0, dy, 0))) spots.add(block.getRelative(f).getRelative(0, dy, 0));
        if (spots.isEmpty()) return;
        legs.getPathfinder().stopPathfinding();
        legs.teleport(spots.getFirst().getLocation().add(0.5, 0, 0.5));
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

    private static boolean canStandIn(Block b) {
        return b.isPassable() && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private boolean walk(Location to) {
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
        });
        Bukkit.getMobGoals().removeAllGoals(legs);
        // Paper only fully ticks mobs within ~32 blocks of a player ("entity activation range"); farther
        // away the legs would freeze mid-walk. Any potion effect makes Paper keep an entity active.
        legs.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, PotionEffect.INFINITE_DURATION, 0,
                false, false, false));
        // Vanilla caps path search by follow range; the trader's default cut paths off ~10 blocks out.
        legs.getAttribute(Attribute.FOLLOW_RANGE).setBaseValue(48);
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
        if (body != null) body.remove();
        if (legs != null) legs.remove();
        body = null;
        legs = null;
    }
}
