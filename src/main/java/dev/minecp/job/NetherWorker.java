package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.citizen.Meal;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Nether Mine's worker (the mod's BuildingNetherWorker, JobNetherWorker and EntityAIWorkNether), the mod's numbers
 * unless noted: every fourth morning at most it may go through its building's portal. It is away for 20 s for each thing
 * it will find; then its finds play out at once: blocks it mines with its tools, and monsters it fights with its sword,
 * armor and food, and may die of. It also makes lava buckets.
 */
public final class NetherWorker extends Crafter {
    private static final int TICKS_PER_FIND = 400;
    private static final int TRIPS = 1;                  // a period
    private static final int FOOD = 16;                  // taken along
    private static final int FOODS_PER_LEVEL = 5;        // RestaurantMenuModule.STOCK_PER_LEVEL
    private static final int STEP_TICKS = 40;            // TICK_DELAY
    private static final int IDLE_TICKS = 60;            // its setDelay while it lacks something
    private static final double BASE_DAMAGE = 3;         // BASE_PHYSICAL_DAMAGE
    private static final double SKILL_PROTECTION = 0.005; // SECONDARY_DAMAGE_REDUCTION
    private static final double HEAL_HUNGER = 0.25;      // saturation a point of healing costs
    private static final double PECKISH = 10;           // AVERAGE_SATURATION: it eats below this
    private static final double XP_PER_STACK = 0.2;
    private static final Map<Material, Integer> SUPPLIES = new LinkedHashMap<>();
    private static final List<Tool> TOOLS = List.of(Tool.AXE, Tool.PICKAXE, Tool.SHOVEL, Tool.SWORD, Tool.FLINT_AND_STEEL);

    static {
        SUPPLIES.put(Material.COBBLESTONE, 64);
        SUPPLIES.put(Material.TORCH, 32);
        SUPPLIES.put(Material.LADDER, 16);
    }

    // ---- its state --------------------------------------------------------------------------------------------------

    /** The foods it takes along (the mod's menu); players choose them. */
    public final Set<Material> menu = new LinkedHashSet<>();
    private int periodDay, trips, seenDay;
    private List<NetherTrip.Find> finds; // while on a trip: what it will have found
    private int awayTicks;    // until it is back
    private UUID traveller;
    private boolean returning; // back, but not yet at the hut with the portal closed
    private List<Block> portals = List.of();
    private int portalsLevel = -1;

    /** The last trip (the mod's ExpeditionLog), for the hut menu with research. */
    private String logStatus;
    private String logName;
    private double logHealth, logFood;
    private List<String> logGear = new ArrayList<>();
    private final Map<String, Integer> logFoes = new LinkedHashMap<>(), logLoot = new LinkedHashMap<>();

    public NetherWorker(Hut hut, Colony colony) {
        super(hut, colony, r -> true, "lava buckets", Kind.OWN);
        builtIn.put(own("netherworker_lava", Material.LAVA_BUCKET, 1, Map.of(Material.BUCKET, 1)), 1);
        seenDay = colony.day;
    }

    /** Most foods on its menu. */
    public int mostFoods() {
        return hut.level * FOODS_PER_LEVEL;
    }

    /** On a trip. */
    private boolean onTrip() {
        return finds != null && traveller.equals(worker);
    }

    private boolean tripReady() {
        return finds == null && trips < TRIPS;
    }

    /** Not in the world: no body, no sleep, no meals (the mod removes the citizen too). */
    @Override
    public boolean away() {
        return onTrip() && awayTicks > 0;
    }

    @Override
    public double diseaseModifier() {
        return away() ? 0 : super.diseaseModifier();
    }

    /** Counts mornings (the mod's onWakeUp): after the third, the next starts a new period. */
    @Override
    public void everySecond() {
        super.everySecond();
        for (; seenDay < colony.day; seenDay++) {
            periodDay = NetherTrip.morning(periodDay);
            if (periodDay == 0) trips = 0;
        }
        if (finds != null && !traveller.equals(worker)) finds = null; // its job ended meanwhile (not by firing: the menu doesn't offer it): it just comes back
        else if (awayTicks > 0) awayTicks = Math.max(0, awayTicks - 20);
    }

    /** The air in its portal just above the spot its blueprint marks, or null. */
    private Block portal() {
        if (portalsLevel != hut.level) {
            portalsLevel = hut.level;
            portals = hut.workSpots(colony.style, "portal");
        }
        return portals.isEmpty() ? null : portals.getFirst().getRelative(BlockFace.UP);
    }

    private static boolean lit(Block portal) {
        return portal.getType() == Material.NETHER_PORTAL;
    }

    // ---- getting ready ----------------------------------------------------------------------------------------------

    private int[] tier() {
        return Guard.armorTiers(hut.level);
    }

    private boolean fitsArmor(ItemStack i, EquipmentSlot slot) {
        return Guard.armorFits(i, slot, tier());
    }

    /** The best piece of armor it carries for this slot (worn only on the trip), as the mod allows it at the hut level; its bag slot, or -1. */
    private int armorSlot(EquipmentSlot slot) {
        Inventory bag = bag();
        int best = -1;
        if (bag != null) for (int i = 0; i < bag.getSize(); i++)
            if (fitsArmor(bag.getItem(i), slot) && (best < 0 || Guard.armorTier(bag.getItem(i).getType()) > Guard.armorTier(bag.getItem(best).getType()))) best = i;
        return best;
    }

    private boolean onMenu(ItemStack i) {
        return i != null && menu.contains(i.getType()) && Food.isMeal(i);
    }

    /** A supply, still missing this many (in its bag and the chest). */
    private int missing(Material supply) {
        return SUPPLIES.get(supply) - has(supply) - inChest(i -> plain(i, supply));
    }

    private static boolean plain(ItemStack i, Material m) {
        return i.getType() == m && i.getPersistentDataContainer().isEmpty();
    }

    /** What it must have and lacks, in its bag and the chest, for the hut menu; null: nothing. */
    private String lacks() {
        List<String> out = new ArrayList<>();
        for (Tool t : TOOLS) if (!hasTool(t, null) && inChest(i -> fits(t, i, null)) == 0) out.add(toolLabel(t));
        for (Material m : SUPPLIES.keySet()) if (missing(m) > 0) out.add(missing(m) + " × " + name(m));
        return out.isEmpty() ? null : "Before its trip it needs " + String.join(", ", out) + ".";
    }

    /** Whether the chest has something it would take along and doesn't hold yet. */
    private boolean toFetch() {
        for (Tool t : TOOLS) if (!hasTool(t, null)) return true;
        for (var e : SUPPLIES.entrySet()) if (has(e.getKey()) < e.getValue()) return true;
        if (has(this::onMenu) < FOOD && inChest(this::onMenu) > 0) return true;
        for (EquipmentSlot s : Guard.SLOTS) if (betterArmor(s) != null) return true;
        return false;
    }

    /** A piece in the chest better than the one it carries for this slot, or null. */
    private ItemStack betterArmor(EquipmentSlot slot) {
        Inventory chest = chest();
        int at = armorSlot(slot), tier = at < 0 ? -1 : Guard.armorTier(bag().getItem(at).getType());
        ItemStack best = null;
        if (chest != null) for (ItemStack i : chest.getContents())
            if (fitsArmor(i, slot) && Guard.armorTier(i.getType()) > tier) { best = i; tier = Guard.armorTier(i.getType()); }
        return best;
    }

    private void fetchAll() {
        for (Tool t : TOOLS) if (!hasTool(t, null)) fetchTool(t, null);
        for (var e : SUPPLIES.entrySet()) fetch(e.getKey(), e.getValue() - has(e.getKey()));
        fetch(this::onMenu, FOOD - has(this::onMenu));
        for (EquipmentSlot s : Guard.SLOTS) {
            ItemStack better = betterArmor(s);
            if (better == null) continue;
            int at = armorSlot(s);
            ItemStack like = better.clone(), old = at < 0 ? null : bag().getItem(at);
            if (fetch(like::isSimilar, 1) == 0 || old == null) continue;
            bag().setItem(at, chest().addItem(old).values().stream().findFirst().orElse(null));
        }
    }

    /** Its supplies, the menu's food and armor stay with it. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        if (SUPPLIES.containsKey(i.getType()) || onMenu(i)) return true;
        for (EquipmentSlot s : Guard.SLOTS) if (fitsArmor(i, s)) return true;
        return false;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>(super.wants());
        if (hut.level < 1) return wants;
        for (Material m : menu) { // a stack of each food on the menu (the mod's MinimumStack, minimum 1)
            int max = m.getMaxStackSize(), more = max - inChest(i -> i.getType() == m && onMenu(i));
            if (more > 0) wants.add(new Want(name(m) + " for the trips", i -> i.getType() == m && onMenu(i), more, 1));
        }
        if (!tripReady()) return wants;
        for (Tool t : TOOLS) wantTool(wants, t);
        for (Material m : SUPPLIES.keySet())
            if (missing(m) > 0) wants.add(new Want(name(m), i -> plain(i, m), missing(m), missing(m)));
        for (EquipmentSlot s : Guard.SLOTS) // nice to have
            if (armorSlot(s) < 0 && inChest(i -> fitsArmor(i, s)) == 0)
                wants.add(new Want(Guard.armorLabel(s, tier()), i -> fitsArmor(i, s), 1));
        return wants;
    }

    /** One of each tool and kind of armor (the mod's keepX), its supplies, and a stack of each food on the menu stay. */
    @Override
    int keep(ItemStack item) {
        for (Tool t : TOOLS) if (fits(t, item, null)) return 1;
        for (EquipmentSlot s : Guard.SLOTS) if (item.getType().getEquipmentSlot() == s && Guard.armorTier(item.getType()) >= 0) return 1;
        if (SUPPLIES.containsKey(item.getType())) return Integer.MAX_VALUE;
        return onMenu(item) ? item.getMaxStackSize() : super.keep(item);
    }

    // ---- the trip ---------------------------------------------------------------------------------------------------

    /** Back from a trip, before all else (the mod's returnFromNether): closes the portal, then to the hut. */
    @Override
    boolean tend(Colonist w, Inventory chest) {
        if (!returning) return false;
        Block portal = portal();
        if (portal != null && lit(portal) && hut.on(Settings.CLOSE_PORTAL)) { closePortal(w, portal); return true; }
        w.setStatus("Nether Miner: working");
        if (!atHut(w)) { goToHut(w); return true; }
        returning = false;
        return false;
    }

    /** Not for bed or a doctor until it is back at the hut, as the mod. */
    @Override
    public boolean sleepsAtNight() {
        return !returning;
    }

    @Override
    public boolean interruptible() {
        return !returning;
    }

    @Override
    boolean otherWork(Colonist w, Inventory chest) {
        Block portal = portal();
        if (portal == null) return idleAt(w, "No portal in the building (its data pack may be too old: rebuild the data packs).");
        String lacks = tripReady() ? lacks() : null;
        boolean going = tripReady() && lacks == null;
        if (!going && lit(portal) && hut.on(Settings.CLOSE_PORTAL)) { closePortal(w, portal); return true; }
        if (loaded() || going && toFetch()) {
            if (!atHut(w)) { w.setStatus("Nether Miner: working"); goToHut(w); return true; }
            if (loaded() && !deliver()) return idleAt(w, "The hut chest is full.");
            if (going) {
                fetchAll();
                if (lacks() == null && toFetch()) return idleAt(w, "Its inventory is full: open it from its menu and take something out.");
            }
            act(w);
            cooldown = STEP_TICKS;
            return true;
        }
        if (!going) return lacks != null && idleAt(w, lacks);
        w.setStatus("Nether Miner: leaving");
        note = null;
        if (!w.isBeside(portal)) { w.goBeside(portal); return true; }
        cooldown = STEP_TICKS;
        if (lit(portal)) { leave(w, portal); return true; }
        if (!portal.isEmpty() && portal.getType() != Material.FIRE) return idleAt(w, "Something is in the way in the portal.");
        w.hold(tool(Tool.FLINT_AND_STEEL));
        wearOut(Tool.FLINT_AND_STEEL, w);
        w.swing();
        portal.getWorld().playSound(portal.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1, 1);
        portal.setType(Material.FIRE); // lights the frame as a player's does
        if (lit(portal)) return true;
        portal.setType(Material.AIR);
        return idleAt(w, "The portal's frame isn't whole: it can't be lit.");
    }

    private boolean idleAt(Colonist w, String why) {
        idle(w, why, IDLE_TICKS, hut.block);
        return true;
    }

    private void closePortal(Colonist w, Block portal) {
        w.setStatus("Nether Miner: closing the portal");
        if (!w.isBeside(portal)) { w.goBeside(portal); return; }
        w.hold(tool(Tool.FLINT_AND_STEEL));
        wearOut(Tool.FLINT_AND_STEEL, w);
        w.swing();
        portal.setType(Material.AIR); // the rest of it goes too
        cooldown = STEP_TICKS;
    }

    /** Wears its tool of this kind by a use; the tool durability research doesn't count here, as in the mod. */
    private void wearOut(Tool kind, Colonist w) {
        Inventory bag = bag();
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack tool = bag.getItem(i);
            if (!fits(kind, tool, null)) continue;
            ItemStack worn = tool.damage(1, w.living()); // Unbreaking included
            bag.setItem(i, worn.isEmpty() ? null : worn);
            return;
        }
    }

    /** Into the portal: its supplies are used up and what it will find is settled, which is how long it stays. */
    private void leave(Colonist w, Block portal) {
        SUPPLIES.forEach(this::use);
        trips++;
        finds = NetherTrip.finds(hut.level, effective(skill(0)), ThreadLocalRandom.current());
        awayTicks = finds.size() * TICKS_PER_FIND;
        traveller = w.id;
        logFoes.clear();
        logLoot.clear();
        logGear = gear();
        log(w, "under way");
        w.setStatus("Nether Miner: in the Nether");
        portal.getWorld().playSound(portal.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, SoundCategory.NEUTRAL, ThreadLocalRandom.current().nextFloat() * 0.5f + 0.25f, 0.25f);
    }

    /** Back from a trip: what it met and mined plays out now, before anything else. */
    @Override
    public boolean arrived(Colonist w) {
        if (!onTrip()) return false;
        List<NetherTrip.Find> trip = finds;
        finds = null;
        List<ItemStack> loot = new ArrayList<>();
        for (NetherTrip.Find f : trip) {
            if (f.foe() == null) found(mine(f, w), loot);
            else if (fight(f.foe(), w)) found(spoils(f.foe(), w), loot);
            else {
                logGear = gear();
                log(w, "killed");
                colony.health.lostInNether(w);
                return true;
            }
        }
        for (ItemStack i : loot) {
            w.workedLittle();
            experience(w, XP_PER_STACK);
        }
        returning = true;
        gather(loot, w); // ours: what doesn't fit drops at the portal (the mod loses it)
        logGear = gear();
        log(w, "back home");
        w.setStatus("Nether Miner: working");
        cooldown = STEP_TICKS;
        return true;
    }

    private void found(Collection<ItemStack> items, List<ItemStack> loot) {
        loot.addAll(items);
        for (ItemStack i : items) logLoot.merge(PostBox.label(i), i.getAmount(), Integer::sum);
    }

    /** Mines a find, with the tool for it, as long as that lasts; by hand what needs no tool (ours: the mod's code brings none of it). */
    private List<ItemStack> mine(NetherTrip.Find f, Colonist w) {
        List<ItemStack> out = new ArrayList<>();
        Tool kind = Tool.forBlock(f.block(), false);
        BlockState there = hut.block.getRelative(BlockFace.UP).getState(); // a stand-in: what the block would drop here
        there.setBlockData(f.block().createBlockData());
        for (int i = 0; i < f.n(); i++) {
            ItemStack tool = kind == null ? null : toolFor(kind, there);
            if (kind != null && tool == null) break;
            out.addAll(there.getDrops(tool, null));
            if (kind != null) wearOut(kind, w);
            absorb(w, orbs(f.block()));
        }
        return out;
    }

    /** Its tool of this kind, if good enough for the block, or null. */
    private ItemStack toolFor(Tool kind, BlockState block) {
        ItemStack tool = tool(kind);
        return tool != null && (!block.getBlockData().requiresCorrectToolForDrops() || block.getBlockData().isPreferredTool(tool)) ? tool : null;
    }

    /** The experience an ore gives. */
    private static int orbs(Material ore) {
        return ore == Material.NETHER_QUARTZ_ORE ? ThreadLocalRandom.current().nextInt(2, 5) : 0;
    }

    /**
     * A fight, blow by blow: each round each lands a hit half the time. Between rounds it heals by its saturation, or
     * eats. @return whether it lived.
     */
    private boolean fight(NetherTrip.Foe foe, Colonist w) {
        var rnd = ThreadLocalRandom.current();
        double hit = foe.damage * (1 - skill(1) * SKILL_PROTECTION), health = mob(foe, w).getHealth();
        for (int round = 0; health > 0; round++) {
            boolean strikes = rnd.nextBoolean(), struck = rnd.nextBoolean();
            if (strikes) {
                health -= damage(foe);
                wearOut(Tool.SWORD, w);
            }
            if (struck) {
                w.health -= Guard.afterArmor(armor(), 1, hit) * (1 - Math.min(20, protection()) / 25.0);
                wearArmor(hit, w);
                if (w.health <= 0) return false;
            }
            if (round % 2 == 0) heal(w);
            else if (w.saturation < PECKISH) eat(w);
        }
        logFoes.merge(foe.name().toLowerCase().replace('_', ' '), 1, Integer::sum);
        absorb(w, foe.xp);
        return true;
    }

    /** The monster, outside the world: its health and what it drops. */
    private static Mob mob(NetherTrip.Foe foe, Colonist w) {
        return (Mob) w.location().getWorld().createEntity(w.location(), EntityType.valueOf(foe.name()).getEntityClass());
    }

    /** 3 plus its sword's damage, and its sharpness or smite bonus over 2.5. */
    private double damage(NetherTrip.Foe foe) {
        ItemStack sword = tool(Tool.SWORD);
        if (sword == null) return BASE_DAMAGE;
        int sharp = sword.getEnchantmentLevel(Enchantment.SHARPNESS), smite = foe == NetherTrip.Foe.ZOMBIFIED_PIGLIN ? sword.getEnchantmentLevel(Enchantment.SMITE) : 0;
        double bonus = (sharp > 0 ? 0.5 * sharp + 0.5 : 0) + 2.5 * smite;
        return BASE_DAMAGE + Guard.stat(sword, Attribute.ATTACK_DAMAGE) + bonus / 2.5;
    }

    private Collection<ItemStack> armor() {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot s : Guard.SLOTS) if (armorSlot(s) >= 0) out.add(bag().getItem(armorSlot(s)));
        return out;
    }

    /** Vanilla's enchantment protection: a point a level of Protection (the others aren't against this), at most 20 of 25. */
    private int protection() {
        return armor().stream().mapToInt(i -> i.getEnchantmentLevel(Enchantment.PROTECTION)).sum();
    }

    private void wearArmor(double hit, Colonist w) {
        int wear = Guard.armorWear(hit, colony);
        if (wear > 0) for (EquipmentSlot s : Guard.SLOTS) {
            int at = armorSlot(s);
            if (at < 0) continue;
            ItemStack worn = bag().getItem(at).damage(wear, w.living());
            bag().setItem(at, worn.isEmpty() ? null : worn);
        }
    }

    /** The mod's checkHeal, whose amount it heals twice: 2 (well fed) or 1, none below 6 saturation. */
    private void heal(Colonist w) {
        double max = colony.health.max(w);
        if (w.health >= max || w.saturation < Food.LOW_SATURATION) return;
        double amount = colony.health.healing(w);
        w.health = Math.min(max, w.health + 2 * amount);
        w.saturation = Math.max(0, w.saturation - amount * HEAL_HUNGER);
    }

    /** One of its menu's foods worth at least its hut level - 1 (ours: the mod's code eats any food but those). */
    private void eat(Colonist w) {
        Inventory bag = bag();
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack food = bag.getItem(i);
            if (!onMenu(food) || Food.nutrition(food) < hut.level - 1) continue;
            w.saturation = Math.min(Food.MAX_SATURATION, w.saturation + Food.nutrition(food) * Meal.fills(colony));
            colony.milestones.ate();
            var remainder = food.getData(DataComponentTypes.USE_REMAINDER); // a bowl
            food.subtract();
            bag.setItem(i, food.isEmpty() ? null : food);
            if (remainder != null) gather(List.of(remainder.transformInto()), w);
            return;
        }
    }

    /** What the monster drops to it: no player's kill, so no blaze rods (as the mod). */
    private Collection<ItemStack> spoils(NetherTrip.Foe foe, Colonist w) {
        Mob mob = mob(foe, w);
        var table = mob.getLootTable();
        Location at = w.location();
        return table == null ? List.of() : table.populateLoot(ThreadLocalRandom.current(), new LootContext.Builder(at).lootedEntity(mob).luck(effective(skill(0))).build());
    }

    /** As the mod's miners, quarriers and nether miners: no harm from fire with Hot Boots. */
    @Override
    public double reaching(double damage, DamageType type, Entity source, Colonist w) {
        return hotBoots(type) ? 0 : damage;
    }

    // ---- menus and saving -------------------------------------------------------------------------------------------

    private List<String> gear() {
        List<String> out = new ArrayList<>();
        for (Tool t : TOOLS) if (t != Tool.FLINT_AND_STEEL && tool(t) != null) out.add(PostBox.label(tool(t)));
        for (ItemStack i : armor()) out.add(PostBox.label(i));
        if (has(this::onMenu) > 0) out.add(has(this::onMenu) + " food");
        return out;
    }

    private void log(Colonist w, String status) {
        logStatus = status;
        logName = w.name;
        logHealth = Math.max(0, w.health);
        logFood = w.saturation;
    }

    /** The last trip, for the hut menu. */
    public List<Component> expedition() {
        List<Component> out = new ArrayList<>();
        if (logStatus == null) return List.of(Component.text("No trip yet.", NamedTextColor.GRAY));
        out.add(Component.text(logName + ": " + logStatus, NamedTextColor.GOLD));
        out.add(Component.text(String.format("Health %.0f, food %.0f", Math.ceil(logHealth), Math.ceil(logFood))));
        if (!logGear.isEmpty()) out.add(Component.text("Carried: " + String.join(", ", logGear)));
        if (!logFoes.isEmpty()) out.add(Component.text("Beaten: " + listed(logFoes)));
        if (!logLoot.isEmpty()) out.add(Component.text("Brought back: " + listed(logLoot)));
        return out;
    }

    private static String listed(Map<String, Integer> counts) {
        return String.join(", ", counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getValue() + " × " + e.getKey()).toList());
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>(super.info(c));
        if (hut.level == 0) return lines;
        lines.add(Component.text(onTrip() ? "It is in the Nether: back in about " + Math.max(1, awayTicks / 1200) + " min."
                : tripReady() ? "Ready for a trip to the Nether. It needs " + String.join(", ", SUPPLIES.entrySet().stream().map(e -> e.getValue() + " × " + name(e.getKey())).toList())
                        + ", an axe, a pickaxe, a shovel, a sword and flint and steel; armor and food keep it alive."
                : "Its next trip can start in " + NetherTrip.morningsLeft(periodDay) + (NetherTrip.morningsLeft(periodDay) == 1 ? " morning." : " mornings."), NamedTextColor.GRAY));
        if (menu.isEmpty()) lines.add(Component.text("Choose foods for it to take along (Food for the trips).", NamedTextColor.YELLOW));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("menu", menu.stream().map(Material::name).toList());
        s.set("period-day", periodDay);
        s.set("trips", trips);
        s.set("seen-day", seenDay);
        if (finds != null) {
            s.set("finds", finds.stream().map(NetherTrip.Find::toString).toList());
            s.set("away", awayTicks);
            s.set("traveller", traveller.toString());
        }
        if (logStatus == null) return;
        s.set("log.status", logStatus);
        s.set("log.name", logName);
        s.set("log.health", logHealth);
        s.set("log.food", logFood);
        s.set("log.gear", logGear);
        logFoes.forEach((k, n) -> s.set("log.foes." + k, n));
        logLoot.forEach((k, n) -> s.set("log.loot." + k, n));
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String m : s.getStringList("menu")) if (Material.matchMaterial(m) != null) menu.add(Material.matchMaterial(m));
        periodDay = s.getInt("period-day");
        trips = s.getInt("trips");
        seenDay = s.getInt("seen-day", c.day);
        if (s.contains("traveller")) {
            finds = new ArrayList<>(s.getStringList("finds").stream().map(NetherTrip.Find::parse).toList());
            awayTicks = s.getInt("away");
            traveller = UUID.fromString(s.getString("traveller"));
        }
        logStatus = s.getString("log.status");
        logName = s.getString("log.name");
        logHealth = s.getDouble("log.health");
        logFood = s.getDouble("log.food");
        logGear = new ArrayList<>(s.getStringList("log.gear"));
        counts(s.getConfigurationSection("log.foes"), logFoes);
        counts(s.getConfigurationSection("log.loot"), logLoot);
    }

    private static void counts(ConfigurationSection s, Map<String, Integer> into) {
        if (s != null) for (String k : s.getKeys(false)) into.put(k, s.getInt(k));
    }
}
