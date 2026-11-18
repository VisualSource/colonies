package dev.minecp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** A job at a worker hut: one colonist works it, using the hut's chest. */
abstract class Job {
    final Hut hut;
    UUID worker; // colonist id, or null while nobody has the job
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // what the worker holds; saved with the colony
    final Map<Tool, ItemStack> tools = new EnumMap<>(Tool.class);          // tools taken from the hut chest; saved too
    String note; // why the worker is stuck, in full for the hut menu (the name tag stays short)
    final List<ItemStack> load = new ArrayList<>(); // whole items in hand (a courier's delivery); saved with the colony

    /** Something the hut wants a courier to bring to its chest: items matching {@code test}, {@code amount} more. */
    record Want(String label, Predicate<ItemStack> test, int amount) {}

    /** What this hut wants delivered from the warehouse. */
    List<Want> wants() {
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
    double hungerFactor() {
        return 1.0;
    }

    /** One tick of work for the worker, whose entities are spawned. */
    abstract void tick(Colonist w);

    /** Called each tick while work stops (night, rain). @return false while it is still getting out of its work place. */
    boolean leaveWork(Colonist w) {
        return true;
    }

    /** The mod lets only the cook and fully upgraded huts' workers work in the rain. */
    boolean worksInRain() {
        return hut.level >= Hut.MAX_LEVEL;
    }

    Inventory chest() {
        return hut.block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    /** Puts what the worker carries into the chest; what doesn't fit stays carried. @return true if all fit. */
    boolean deliver() {
        Inventory chest = chest();
        if (chest == null) return false;
        carried.replaceAll((m, n) -> n <= 0 ? 0 : chest.addItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum());
        carried.values().removeIf(n -> n <= 0);
        return carried.isEmpty();
    }

    /** The hut is gone or the job ends: drop what the worker carries, tools included, at the hut. */
    void dropCarried() {
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
        return kind.label + (max == Integer.MAX_VALUE ? "" : " (" + Tool.tierName(max) + " or lower until the hut is upgraded)");
    }

    /** Everything the worker carries, tools included, taken off it (it died: it all goes into its grave). */
    List<ItemStack> takeCarried() {
        List<ItemStack> all = new ArrayList<>();
        carried.forEach((m, n) -> { for (int left = n; left > 0; left -= m.getMaxStackSize()) all.add(new ItemStack(m, Math.min(left, m.getMaxStackSize()))); });
        carried.clear();
        all.addAll(tools.values());
        tools.clear();
        all.addAll(load);
        load.clear();
        return all;
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
        Collection<ItemStack> drops = tool == null ? block.getDrops() : block.getDrops(tool);
        if (tool != null) wear(kind, w);
        return drops;
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
