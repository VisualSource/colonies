package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** A job at a worker hut: one colonist works it, using the hut's chest. */
public abstract class Job {
    public final Hut hut;
    public UUID worker; // colonist id, or null while nobody has the job
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // what the worker holds; saved with the colony
    final Map<Tool, ItemStack> tools = new EnumMap<>(Tool.class);          // tools taken from the hut chest; saved too
    public String note; // why the worker is stuck, in full for the hut menu (the name tag stays short)
    // Whole items in hand, kept as items (a courier's delivery, the builder's storage racks); saved with the colony.
    public final List<ItemStack> load = new ArrayList<>();
    int cooldown; // ticks until the worker acts again

    /** Items fetched per trip: a citizen's 27-slot inventory, as in the mod. */
    static final int CARRY_LIMIT = 27 * 64;

    // ---- what a kind of job adds: the hut menu, saving, shutting down ----------------------------------------

    /** Lines for the hut menu, under the worker and its status (the note is added after them). */
    public List<Component> info(Colony c) {
        return List.of();
    }

    /** What the hut menu's "Materials needed" lists, or null for no such button. */
    public Map<ItemStack, Integer> materials() {
        return null;
    }

    /** Saves what this job holds; kinds of job add their own state. */
    public void save(ConfigurationSection s, Colony c) {
        if (worker != null) s.set("worker", worker.toString());
        carried.forEach((m, n) -> s.set("carried." + m.name(), n));
        // Tools keep durability and enchantments, so they are stored as whole items.
        tools.forEach((kind, item) -> s.set("tools." + kind.name(), Base64.getEncoder().encodeToString(item.serializeAsBytes())));
        if (!load.isEmpty()) s.set("load", load.stream().map(i -> Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList());
    }

    /** Loads what {@link #save} wrote. Called once every hut and job of the colony exists. */
    public void load(ConfigurationSection s, Colony c) {
        String w = s.getString("worker");
        if (w != null) worker = UUID.fromString(w);
        var ts = s.getConfigurationSection("tools");
        if (ts != null) for (String kind : ts.getKeys(false))
            tools.put(Tool.valueOf(kind), ItemStack.deserializeBytes(Base64.getDecoder().decode(ts.getString(kind))));
        for (String item : s.getStringList("load")) load.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(item)));
        var cs = s.getConfigurationSection("carried");
        if (cs != null) for (String m : cs.getKeys(false)) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) carried.put(mat, cs.getInt(m));
        }
    }

    /** The colony's entities are going away (shutdown, disband): put back what isn't saved. */
    public void stow() {}

    // ---- shared helpers --------------------------------------------------------------------------------------

    /** Waits {@code ticks}, showing why in the hut menu; walks beside {@code near} first (null: stays where it is). */
    void idle(Colonist w, String why, int ticks, Block near) {
        note = why;
        w.setStatus(hut.type.worker + ": idle");
        if (near != null && !w.isBeside(near)) w.goBeside(near);
        cooldown = ticks;
    }

    /** An item's name as players read it: "oak planks". */
    public static String name(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    /**
     * Something the hut wants brought to its chest: items matching {@code test}, {@code amount} more. {@code min}: the least the warehouse must have to take the request (the mod's minimum count); by default the
     * whole amount, as the mod's crafters ask for their ingredients.
     */
    public record Want(String label, Predicate<ItemStack> test, int amount, int min) {
        public Want(String label, Predicate<ItemStack> test, int amount) {
            this(label, test, amount, amount);
        }
    }

    /** What this hut wants delivered from the warehouse. */
    public List<Want> wants() {
        return List.of();
    }

    /** How many of this item the hut keeps in its chest; a courier takes the rest to the warehouse. */
    int keep(ItemStack item) {
        return Integer.MAX_VALUE;
    }

    /** How many items matching {@code test} the hut chest holds. */
    int inChest(Predicate<ItemStack> test) {
        Inventory chest = chest();
        int n = 0;
        if (chest != null) for (ItemStack i : chest.getContents()) if (i != null && test.test(i)) n += i.getAmount();
        return n;
    }

    /** A want for one tool of this kind, unless the worker holds one or the chest has one. */
    void wantTool(List<Want> wants, Tool kind) {
        if (!hasTool(kind, null) && inChest(i -> fits(kind, i, null)) == 0) wants.add(new Want(toolLabel(kind), i -> fits(kind, i, null), 1));
    }

    Job(Hut hut) {
        this.hut = hut;
    }

    /** How much faster than others this job makes a citizen hungry (the mod: farmer and lumberjack 1.2). */
    public double hungerFactor() {
        return 1.0;
    }

    /** One tick of work for the worker, whose entities are spawned. */
    public abstract void tick(Colonist w);

    /** Called each tick while work stops (night, rain). @return false while it is still getting out of its work place. */
    public boolean leaveWork(Colonist w) {
        return true;
    }

    /** Most health its worker can have (the knight has more). */
    public double maxHealth() {
        return Colonist.MAX_HEALTH;
    }

    /** What of a hit reaches its worker (the knight's armor takes some). */
    public double afterArmor(double damage) {
        return damage;
    }

    /** Its worker was hurt by {@code source} (null: no entity): true if it stands and fights instead of running away. */
    public boolean fightsBack(org.bukkit.entity.Entity source) {
        return false;
    }

    /** Whether its worker goes to bed at night (the knight stays on duty). */
    public boolean sleepsAtNight() {
        return true;
    }

    /** A tool for digging this hut allows (not a sword): workers that dig keep these in their chest. */
    boolean isDiggingTool(ItemStack item) {
        for (Tool t : Tool.values()) if (t.digs() && fits(t, item, null)) return true;
        return false;
    }

    /** The mod lets only the cook and fully upgraded huts' workers work in the rain. */
    public boolean worksInRain() {
        return hut.level >= Hut.MAX_LEVEL;
    }

    Inventory chest() {
        return hut.block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    /** Puts what the worker carries (load included) into the chest; what doesn't fit stays carried. @return true if all fit. */
    boolean deliver() {
        Inventory chest = chest();
        if (chest == null) return false;
        carried.replaceAll((m, n) -> n <= 0 ? 0 : chest.addItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum());
        carried.values().removeIf(n -> n <= 0);
        List<ItemStack> left = new ArrayList<>(chest.addItem(load.toArray(ItemStack[]::new)).values());
        load.clear();
        load.addAll(left);
        return carried.isEmpty() && load.isEmpty();
    }

    /** The hut is gone or the job ends: drop what the worker carries, tools included, at the hut. */
    public void dropCarried() {
        Location at = hut.block.getLocation().toCenterLocation();
        carried.forEach((m, n) -> { if (n > 0) at.getWorld().dropItemNaturally(at, new ItemStack(m, n)); });
        carried.clear();
        tools.values().forEach(t -> at.getWorld().dropItemNaturally(at, t));
        tools.clear();
        load.forEach(i -> at.getWorld().dropItemNaturally(at, i));
        load.clear();
    }

    /**
     * The best tool tier this hut's worker may use (the mod's getMaxEquipmentLevel): stone up to hut level 1, then the
     * hut's level, anything at the top level. Better tools stay in the chest: the hut must be upgraded first.
     */
    int maxToolTier() {
        return hut.level >= Hut.MAX_LEVEL ? Integer.MAX_VALUE : Math.max(hut.level, 1);
    }

    /** Whether {@code item} is a tool of this kind good enough for {@code block} (null: any) that this hut allows. */
    boolean fits(Tool kind, ItemStack item, Block block) {
        return kind.fits(item, block) && Tool.tier(item) <= maxToolTier();
    }

    /** What to ask for: the tool kind and, below the top level, the best tier this hut allows. */
    String toolLabel(Tool kind) {
        int max = maxToolTier();
        if (max == Integer.MAX_VALUE) return kind.label;
        if (!kind.digs() && kind != Tool.SWORD) // rods and shears have no material: only their enchantments count
            return kind.label + " (enchanted up to level " + (max + 1) + " until the hut is upgraded)";
        return kind.label + " (" + Tool.tierName(max) + " or lower until the hut is upgraded)";
    }

    /** Everything the worker carries, tools included, taken off it (it died: it all goes into its grave). */
    public List<ItemStack> takeCarried() {
        List<ItemStack> all = new ArrayList<>();
        carried.forEach((m, n) -> { for (int left = n; left > 0; left -= m.getMaxStackSize()) all.add(new ItemStack(m, Math.min(left, m.getMaxStackSize()))); });
        carried.clear();
        all.addAll(tools.values());
        tools.clear();
        all.addAll(load);
        load.clear();
        return all;
    }

    /**
     * What a block that holds items (chest, barrel, shulker box, furnace…) has inside, as copies: its own half only
     * for a double chest. getDrops leaves contents out.
     */
    public static List<ItemStack> contents(Block b) {
        Inventory inv = inventoryOf(b);
        List<ItemStack> items = new ArrayList<>();
        if (inv != null) for (ItemStack i : inv.getContents()) if (i != null && !i.isEmpty()) items.add(i.clone());
        return items;
    }

    /** Same, and empties the block, so breaking it afterwards drops just the block. */
    public static List<ItemStack> takeContents(Block b) {
        List<ItemStack> items = contents(b);
        Inventory inv = inventoryOf(b);
        if (inv != null) inv.clear();
        return items;
    }

    private static Inventory inventoryOf(Block b) {
        if (!(b.getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder holder)) return null;
        return holder instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : holder.getInventory();
    }

    /** Whether the worker holds a tool of this kind that can break {@code block} (null: any block). */
    boolean hasTool(Tool kind, Block block) {
        return fits(kind, tools.get(kind), block);
    }

    /**
     * Takes a fitting tool of this kind from the hut chest (the worker must be at the hut), putting back the one it
     * replaces. @return false if the chest has none.
     */
    boolean fetchTool(Tool kind, Block block) {
        Inventory chest = chest();
        if (chest == null) return false;
        int best = -1; // the mod takes the lowest tier that will do, keeping better tools for harder blocks
        for (int i = 0; i < chest.getSize(); i++)
            if (fits(kind, chest.getItem(i), block) && (best < 0 || Tool.tier(chest.getItem(i)) < Tool.tier(chest.getItem(best)))) best = i;
        if (best < 0) return false;
        ItemStack item = chest.getItem(best);
        ItemStack old = tools.put(kind, item.asOne());
        item.subtract();
        chest.setItem(best, item.isEmpty() ? null : item);
        if (old != null) for (ItemStack overflow : chest.addItem(old).values())
            hut.block.getWorld().dropItemNaturally(hut.block.getLocation().toCenterLocation(), overflow);
        return true;
    }

    /**
     * Breaks {@code block} with the held tool of this kind (null: by hand): drops as that tool would get them,
     * the tool shows in the worker's hand and wears down, and is gone when it breaks.
     */
    Collection<ItemStack> breakWith(Tool kind, Block block, Colonist w) {
        ItemStack tool = kind == null ? null : tools.get(kind);
        Collection<ItemStack> drops = drops(block, tool);
        if (tool != null) wear(kind, w);
        return drops;
    }

    /**
     * What a worker breaking {@code b} with {@code tool} (null: by hand) gets: a storage rack comes back as a rack (its
     * plain drop would be a chest named "Storage Rack" that works as neither); anything else drops as vanilla's.
     */
    static Collection<ItemStack> drops(Block b, ItemStack tool) {
        if (dev.minecp.logistics.Rack.is(b)) return List.of(dev.minecp.logistics.Rack.item());
        return tool == null ? b.getDrops() : b.getDrops(tool);
    }

    /** Uses the held tool of this kind once: it shows in the worker's hand, wears down, and is gone when it breaks. */
    void wear(Tool kind, Colonist w) {
        ItemStack tool = tools.get(kind);
        if (tool == null) return;
        w.hold(tool);
        ItemStack worn = tool.damage(1, w.living()); // Unbreaking and the break animation included
        if (worn.isEmpty()) {
            tools.remove(kind);
            w.hold(null);
        } else tools.put(kind, worn);
    }

    /**
     * Within reach of the hut chest to take or put things: on any spot {@link Colonist#walkBeside} walks to (up to
     * 2 blocks out, 2 below to 1 above). A plain distance check missed some of those, so workers walked back and forth.
     */
    boolean atHut(Colonist w) {
        return w.isBeside(hut.block);
    }

    /** Walks to the hut chest (teleports if no way leads there). */
    void goToHut(Colonist w) {
        w.goBeside(hut.block);
    }
}
