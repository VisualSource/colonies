package dev.minecp.colony;

import dev.minecp.citizen.Colonist;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mercenaries (the mod's EntityMercenary and EntityMercenaryAI), the mod's numbers unless noted: hired from the Town
 * Hall (MANAGE_HUTS) for nothing but what they steal, at most once every 40 minutes, a tenth of the citizens + 3 and a
 * leader line up on the town hall's north side and stay 40 minutes. 60 health, speed 0.3, a golden sword with Fire
 * Aspect, a diamond helmet and golden chestplate with Protection IV, chainmail legs and boots. They fight the monsters
 * they see (and whoever hurts them), giving up past 50 blocks; else they patrol, 1 time in 4 to one of the colony's
 * buildings, stealing up to 5 of something from its chest there, else 10 to 27 blocks off. Bumping into a player they
 * slap it (1 damage); into a citizen, they take up to 5 of something it carries; once each 5 s at most.
 * Ours: vindicators stand in for them (not saved: they leave with a restart); a hit is 15 (the mod's mob damage).
 */
public final class Mercenaries {
    private static final String TAG = "minecp_mercenary";
    private static final long STAY = 48_000;    // TICKS_FOURTY_MIN: how long they stay, and the time between hires
    private static final int SLAP_INTERVAL = 100, CHASE = 50, STEAL = 5, STUCK = 10; // the mod's; STUCK: its MAX_STUCK_TIME, in our 1 s checks
    private static final int SIGHT = 16; // ours: how far they look for monsters
    private static final double DAMAGE = 15;

    private final Colony colony;
    private final List<Vindicator> band = new ArrayList<>();
    private final Map<Vindicator, Integer> slapped = new HashMap<>(); // the tick each last slapped or stole
    private final Map<Vindicator, Block> patrol = new HashMap<>();    // where each is walking: a hut block, or a spot
    private final Map<Vindicator, Location> last = new HashMap<>();
    private final Map<Vindicator, Integer> stuck = new HashMap<>();
    long hiredAt; // game time of the last hire; saved
    private int ticks;

    Mercenaries(Colony colony) {
        this.colony = colony;
    }

    public static boolean is(Entity e) {
        return e != null && e.getScoreboardTags().contains(TAG);
    }

    /** Hires them (the mod's spawnMercenariesInColony). @return why not, or null. */
    public String hire() {
        Hut hall = colony.townHall();
        World w = hall.block.getWorld();
        long now = w.getGameTime();
        if (hiredAt != 0 && now - hiredAt < STAY) return "Mercenaries can be hired again in " + (STAY - (now - hiredAt) + 1199) / 1200 + " minutes.";
        if (hall.level < 1) return "The Town Hall must be built first.";
        hiredAt = now;
        int n = colony.colonists.size() / 10 + 3;
        int[] box = dev.minecp.build.BuildOrder.footprint(hall.structure(colony.style, hall.level), hall.block, hall.rotation, hall.mirror);
        int x = box == null ? hall.block.getX() : (box[0] + box[1]) / 2, z = box == null ? hall.block.getZ() - 3 : box[2] - 1; // the middle of its north edge
        for (int i = 0; i <= n; i++) { // the last is the leader, behind the line
            int bx = i < n ? x + i : x, bz = i < n ? z : z - 1; // the leader behind the line, away from the hall
            Block floor = w.getHighestBlockAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            band.add(spawn(floor.getRelative(0, 1, 0).getLocation().add(0.5, 0, 0.5), i == n));
        }
        colony.tell(n + " mercenaries and their leader have arrived at the Town Hall. Mind your pockets."); // ours: the mod's window says so
        return null;
    }

    private static Vindicator spawn(Location at, boolean leader) {
        return at.getWorld().spawn(at, Vindicator.class, v -> {
            v.addScoreboardTag(TAG);
            v.customName(Component.text(leader ? "Mercenary Leader" : "Mercenary"));
            v.setPersistent(false); // not saved with the world: a restart ends them
            v.setRemoveWhenFarAway(false);
            v.setCanJoinRaid(false);
            v.setPatrolLeader(false);
            v.setCanPickupItems(false);
            set(v, Attribute.MAX_HEALTH, 60);
            v.setHealth(60);
            set(v, Attribute.MOVEMENT_SPEED, 0.3);
            set(v, Attribute.ATTACK_DAMAGE, DAMAGE);
            EntityEquipment e = v.getEquipment();
            ItemStack sword = new ItemStack(Material.GOLDEN_SWORD);
            sword.addEnchantment(Enchantment.FIRE_ASPECT, 1);
            sword.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build()); // its hit is the mod's 15
            e.setItemInMainHand(sword);
            ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET), chest = new ItemStack(Material.GOLDEN_CHESTPLATE);
            helmet.addEnchantment(Enchantment.PROTECTION, 4);
            chest.addEnchantment(Enchantment.PROTECTION, 4);
            e.setHelmet(helmet);
            e.setChestplate(chest);
            e.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
            e.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
            for (EquipmentSlot slot : EquipmentSlot.values()) if (slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE) e.setDropChance(slot, 0);
        });
    }

    private static void set(LivingEntity e, Attribute a, double value) {
        var attr = e.getAttribute(a);
        if (attr != null) attr.setBaseValue(value);
    }

    /** Each tick from the colony: once a second, fighting, patrols and bumps; after 40 minutes they leave. */
    void tick() {
        if (band.isEmpty() || ++ticks % 20 != 0) return;
        band.removeIf(v -> {
            if (v.isValid() && !v.isDead()) return false;
            forget(v);
            return true;
        });
        if (band.isEmpty()) return;
        if (band.getFirst().getWorld().getGameTime() - hiredAt > STAY) { despawn(); colony.tell("The mercenaries have left."); return; }
        for (Vindicator v : band) {
            dev.minecp.citizen.Nms.keepActive(v, 40);
            bump(v);
            LivingEntity t = v.getTarget();
            if (t != null && (!t.isValid() || t.isDead() || t.getWorld() != v.getWorld() || t.getLocation().distance(v.getLocation()) > CHASE)) {
                v.setTarget(null);
                t = null;
            }
            if (t == null) t = nearestMonster(v);
            if (t != null) { v.setTarget(t); patrol.remove(v); continue; }
            patrol(v);
        }
    }

    private static LivingEntity nearestMonster(Vindicator v) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : v.getNearbyEntities(SIGHT, SIGHT, SIGHT)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity l) || is(e) || l.isDead() || !v.hasLineOfSight(e)) continue;
            double d = e.getLocation().distanceSquared(v.getLocation());
            if (d < bestD) { bestD = d; best = l; }
        }
        return best;
    }

    /** The mod's patrol: on arriving, it steals at a building it went to; then 1 in 4 another building, else a spot 10 to 27 away. */
    private void patrol(Vindicator v) {
        var rnd = ThreadLocalRandom.current();
        Block goal = patrol.get(v);
        Location here = v.getLocation();
        int still = here.getBlock().equals(last.getOrDefault(v, here).getBlock()) ? stuck.merge(v, 1, Integer::sum) : 0;
        stuck.put(v, still);
        last.put(v, here);
        boolean arrived = goal != null && goal.getWorld() == here.getWorld() && goal.getLocation().toCenterLocation().distance(here) <= 3;
        if (goal != null && !arrived && still <= STUCK) {
            if (!v.getPathfinder().hasPath()) v.getPathfinder().moveTo(goal.getLocation().toCenterLocation());
            return;
        }
        if (arrived && colony.hutAt(goal) instanceof Hut h && h.block.getState(false) instanceof Container c) steal(c.getInventory(), "the " + h.type.title);
        List<Hut> built = colony.huts.stream().filter(h -> h.level >= 1).toList();
        if (rnd.nextInt(4) == 0 && !built.isEmpty()) goal = built.get(rnd.nextInt(built.size())).block;
        else {
            double angle = rnd.nextDouble(Math.PI * 2), r = rnd.nextDouble(10, 27);
            goal = v.getWorld().getHighestBlockAt((int) (here.getX() + Math.cos(angle) * r), (int) (here.getZ() + Math.sin(angle) * r)).getRelative(0, 1, 0);
        }
        patrol.put(v, goal);
        stuck.put(v, 0);
        v.getPathfinder().moveTo(goal.getLocation().toCenterLocation());
    }

    /** The mod's doPush: a player it bumps into is slapped; a citizen loses up to 5 of something it carries. */
    private void bump(Vindicator v) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        if (now - slapped.getOrDefault(v, -SLAP_INTERVAL) < SLAP_INTERVAL) return;
        for (Entity e : v.getNearbyEntities(0.6, 1, 0.6)) {
            if (e instanceof Player p && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
                p.damage(1, v);
                v.swingOffHand();
                slapped.put(v, now);
                return;
            }
            Colonist c = colony.colonistOf(e);
            if (c != null && !(e instanceof Vindicator)) {
                slapped.put(v, now);
                if (steal(c.inventory(), c.name)) v.swingOffHand();
                return;
            }
        }
    }

    /** Takes up to 5 from a random slot (nothing if it's empty, as the mod's) and tells the colony. @return whether anything was taken. */
    private boolean steal(Inventory from, String whose) {
        int slot = ThreadLocalRandom.current().nextInt(from.getSize());
        ItemStack stack = from.getItem(slot);
        if (stack == null || stack.isEmpty()) return false;
        int n = Math.min(STEAL, stack.getAmount());
        String name = dev.minecp.job.Job.name(stack.getType());
        stack.setAmount(stack.getAmount() - n);
        from.setItem(slot, stack.getAmount() == 0 ? null : stack);
        colony.tell("A mercenary stole " + n + " " + name + " from " + whose + "!");
        return true;
    }

    private void forget(Vindicator v) {
        slapped.remove(v);
        patrol.remove(v);
        last.remove(v);
        stuck.remove(v);
    }

    /** They leave (their time is up, or the plugin stops). */
    void despawn() {
        band.forEach(v -> { forget(v); v.remove(); });
        band.clear();
    }
}
