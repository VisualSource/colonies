package dev.minecp.logistics;

import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The colony's store: the warehouse hut chest and the storage racks around it, as in the mod (plain chests and
 * barrels don't count).
 */
public final class Warehouse {
    // Ours, not the mod's (it registers the building's racks from the blueprint):
    private static final int RANGE = 12;              // the buildings are ~25 wide, the hut chest near the middle
    private static final int BELOW = 2, ABOVE = 12;
    private static final int RESCAN_TICKS = 200;

    public final Hut hut;
    private final Colony colony;
    private List<Block> containers = List.of();
    private long scannedAt = -RESCAN_TICKS; // game time starts at 0, so the first call scans

    public Warehouse(Hut hut, Colony colony) {
        this.hut = hut;
        this.colony = colony;
    }

    /** The storage blocks, the hut chest first; rescanned now and then as the building grows. */
    public List<Block> containers() {
        long now = hut.block.getWorld().getGameTime();
        if (now - scannedAt < RESCAN_TICKS) return containers;
        scannedAt = now;
        List<Block> found = new ArrayList<>();
        found.add(hut.block);
        for (int dx = -RANGE; dx <= RANGE; dx++) for (int dz = -RANGE; dz <= RANGE; dz++) for (int dy = -BELOW; dy <= ABOVE; dy++) {
            Block b = hut.block.getRelative(dx, dy, dz);
            if (Rack.is(b) && !b.equals(hut.block) && colony.hutAt(b) == null) found.add(b); // other huts' chests aren't storage
        }
        return containers = found;
    }


    private List<Inventory> inventories() {
        List<Inventory> invs = new ArrayList<>();
        for (Block b : containers()) {
            // Each half of a double chest on its own: Paper's double inventory is a new object every time, so it can't be matched.
            if (b.getState(false) instanceof org.bukkit.block.Chest c) invs.add(c.getBlockInventory());
            else if (b.getState(false) instanceof Container c) invs.add(c.getInventory());
        }
        return invs;
    }

    private List<ItemStack> held; // while held: its contents, read once for many counts

    /** Reads its contents once, for the counts that follow until {@link #release} (nothing may be taken or stored meanwhile). */
    void hold() {
        held = null;
        held = items();
    }

    void release() {
        held = null;
    }

    private List<ItemStack> items() {
        if (held != null) return held;
        List<ItemStack> out = new ArrayList<>();
        for (Inventory inv : inventories()) for (ItemStack i : inv.getContents()) if (i != null && !i.isEmpty()) out.add(i);
        return out;
    }

    public int count(Predicate<ItemStack> test) {
        int n = 0;
        for (ItemStack i : items()) if (test.test(i)) n += i.getAmount();
        return n;
    }

    /** The same, with {@code leave} of each kind of item kept back (the mod's leftOver). */
    public int count(Predicate<ItemStack> test, int leave) {
        if (leave <= 0) return count(test);
        int n = 0;
        for (int of : kinds(test).values()) n += free(of, leave);
        return n;
    }

    /** How many of an item it has {@code of} may go with {@code leave} kept back. */
    static int free(int of, int leave) {
        return Math.max(0, of - leave);
    }

    /** How many of each kind of item matching {@code test} it holds. */
    private java.util.Map<org.bukkit.Material, Integer> kinds(Predicate<ItemStack> test) {
        java.util.Map<org.bukkit.Material, Integer> found = new java.util.EnumMap<>(org.bukkit.Material.class);
        for (ItemStack i : items()) if (test.test(i)) found.merge(i.getType(), i.getAmount(), Integer::sum);
        return found;
    }

    /** Takes as {@link #take(Predicate, int, int)}, leaving {@code leave} of each kind of item. */
    public List<ItemStack> take(Predicate<ItemStack> test, int amount, int maxStacks, int leave) {
        if (leave <= 0) return take(test, amount, maxStacks);
        List<ItemStack> taken = new ArrayList<>();
        for (var e : kinds(test).entrySet()) {
            int left = Math.min(amount, free(e.getValue(), leave));
            for (Inventory inv : inventories()) {
                if (left <= 0) break;
                int n = take(inv, i -> i.getType() == e.getKey() && test.test(i), left, maxStacks, taken);
                left -= n;
                amount -= n;
            }
        }
        return taken;
    }

    /** Takes up to {@code amount} items matching {@code test}, as many as fit in {@code maxStacks} stacks. */
    public List<ItemStack> take(Predicate<ItemStack> test, int amount, int maxStacks) {
        List<ItemStack> taken = new ArrayList<>();
        for (Inventory inv : inventories()) amount -= take(inv, test, amount, maxStacks, taken);
        return taken;
    }

    /**
     * Moves up to {@code amount} items matching {@code test} out of {@code inv} into {@code taken}, without the load
     * going over {@code maxStacks} stacks. @return how many it took.
     */
    public static int take(Inventory inv, Predicate<ItemStack> test, int amount, int maxStacks, List<ItemStack> taken) {
        int took = 0;
        for (int slot = 0; slot < inv.getSize() && took < amount; slot++) {
            ItemStack i = inv.getItem(slot);
            if (i == null || i.isEmpty() || !test.test(i)) continue;
            int n = Math.min(Math.min(amount - took, i.getAmount()), room(taken, i, maxStacks));
            if (n <= 0) continue;
            taken.add(i.asQuantity(n));
            took += n;
            i.subtract(n);
            inv.setItem(slot, i.isEmpty() ? null : i);
        }
        return took;
    }

    /**
     * How many more of {@code item} fit in {@code load} without it going over {@code maxStacks} stacks
     * (each kind of item fills stacks of its own size: 64 planks or one hoe is a stack).
     */
    public static int room(List<ItemStack> load, ItemStack item, int maxStacks) {
        if (maxStacks == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        int same = 0;
        java.util.Map<ItemStack, Integer> kinds = new java.util.HashMap<>();
        for (ItemStack l : load)
            if (l.isSimilar(item)) same += l.getAmount();
            else kinds.merge(l.asOne(), l.getAmount(), Integer::sum);
        List<Kind> others = kinds.entrySet().stream().map(e -> new Kind(e.getValue(), e.getKey().getMaxStackSize())).toList();
        return room(others, same, item.getMaxStackSize(), maxStacks);
    }

    /** How many of one kind of item a load holds, and that item's stack size. */
    record Kind(int amount, int stackSize) {}

    /** The arithmetic of {@link #room}, without items (unit tested). */
    static int room(List<Kind> others, int same, int stackSize, int maxStacks) {
        int used = 0;
        for (Kind o : others) used += (o.amount() + o.stackSize() - 1) / o.stackSize(); // each kind fills whole stacks of its own size
        return Math.max(0, (maxStacks - used) * stackSize - same);
    }

    /** Stores items. @return what didn't fit. */
    public List<ItemStack> store(List<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>(items);
        for (Inventory inv : inventories()) {
            if (left.isEmpty()) break;
            left = new ArrayList<>(inv.addItem(left.toArray(ItemStack[]::new)).values());
        }
        return left;
    }

    /** Slots used and in all, for the menu. */
    public record Slots(int used, int total) {}

    public Slots slots() {
        int used = 0, total = 0;
        for (Inventory inv : inventories()) for (ItemStack i : inv.getContents()) { total++; if (i != null && !i.isEmpty()) used++; }
        return new Slots(used, total);
    }
}
