package dev.minecp;

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
final class Warehouse {
    // Ours, not the mod's (it registers the building's racks from the blueprint):
    private static final int RANGE = 12;              // the buildings are ~25 wide, the hut chest near the middle
    private static final int BELOW = 2, ABOVE = 12;
    private static final int RESCAN_TICKS = 200;

    final Hut hut;
    private final Colony colony;
    private List<Block> containers = List.of();
    private long scannedAt = -RESCAN_TICKS; // game time starts at 0, so the first call scans

    Warehouse(Hut hut, Colony colony) {
        this.hut = hut;
        this.colony = colony;
    }

    /** The storage blocks, the hut chest first; rescanned now and then as the building grows. */
    List<Block> containers() {
        long now = hut.block.getWorld().getGameTime();
        if (now - scannedAt < RESCAN_TICKS) return containers;
        scannedAt = now;
        List<Block> found = new ArrayList<>();
        found.add(hut.block);
        for (int dx = -RANGE; dx <= RANGE; dx++) for (int dz = -RANGE; dz <= RANGE; dz++) for (int dy = -BELOW; dy <= ABOVE; dy++) {
            Block b = hut.block.getRelative(dx, dy, dz);
            if (b.equals(hut.block) || colony.hutAt(b) != null) continue; // other huts' chests aren't storage
            if (Rack.is(b)) found.add(b);
        }
        return containers = found;
    }


    private List<Inventory> inventories() {
        List<Inventory> invs = new ArrayList<>();
        for (Block b : containers()) {
            // A double chest's inventory covers both halves; count it once.
            if (b.getState(false) instanceof Container c && invs.stream().noneMatch(i -> i.equals(c.getInventory()))) invs.add(c.getInventory());
        }
        return invs;
    }

    int count(Predicate<ItemStack> test) {
        int n = 0;
        for (Inventory inv : inventories()) for (ItemStack i : inv.getContents()) if (i != null && test.test(i)) n += i.getAmount();
        return n;
    }

    /** Takes up to {@code amount} items matching {@code test}, as many as fit in {@code maxStacks} stacks. */
    List<ItemStack> take(Predicate<ItemStack> test, int amount, int maxStacks) {
        List<ItemStack> taken = new ArrayList<>();
        for (Inventory inv : inventories()) for (int slot = 0; slot < inv.getSize() && amount > 0; slot++) {
            ItemStack i = inv.getItem(slot);
            if (i == null || !test.test(i)) continue;
            int n = Math.min(Math.min(amount, i.getAmount()), room(taken, i, maxStacks));
            if (n <= 0) continue;
            taken.add(i.asQuantity(n));
            amount -= n;
            i.subtract(n);
            inv.setItem(slot, i.isEmpty() ? null : i);
        }
        return taken;
    }

    /**
     * How many more of {@code item} fit in {@code load} without it going over {@code maxStacks} stacks
     * (each kind of item fills stacks of its own size: 64 planks or one hoe is a stack).
     */
    static int room(List<ItemStack> load, ItemStack item, int maxStacks) {
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
    List<ItemStack> store(List<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>(items);
        for (Inventory inv : inventories()) {
            if (left.isEmpty()) break;
            left = new ArrayList<>(inv.addItem(left.toArray(ItemStack[]::new)).values());
        }
        return left;
    }

    /** Slots used and in all, for the menu. */
    record Slots(int used, int total) {}

    Slots slots() {
        int used = 0, total = 0;
        for (Inventory inv : inventories()) for (ItemStack i : inv.getContents()) { total++; if (i != null && !i.isEmpty()) used++; }
        return new Slots(used, total);
    }
}
