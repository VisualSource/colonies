package dev.minecp.job;

import dev.minecp.citizen.Colonist;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The furnaces in a hut's building, tended by its worker: the cook's furnaces and smokers, the smelters' furnaces. */
final class Furnaces {
    // Ours, not the mod's (its workers use the furnaces their building registers):
    private static final int RANGE = 12;  // how far from the hut chest its furnaces may be (the buildings are ~25 wide)
    private static final int HEIGHT = 4;
    private static final int LOAD = 16;   // input or fuel put in a furnace at a time

    private Furnaces() {}

    /** Burns in a furnace and isn't food (lava buckets would leave an empty bucket in the furnace). */
    static boolean isFuel(ItemStack i) {
        Material m = i.getType();
        return m.isFuel() && !m.isEdible() && m != Material.LAVA_BUCKET;
    }

    /** The blocks of these types near a hut block (its building's furnaces and smokers). */
    static List<Block> near(Block hut, Material... types) {
        List<Block> found = new ArrayList<>();
        var wanted = EnumSet.noneOf(Material.class);
        wanted.addAll(List.of(types));
        for (int dx = -RANGE; dx <= RANGE; dx++) for (int dz = -RANGE; dz <= RANGE; dz++)
            for (int dy = -HEIGHT; dy <= HEIGHT; dy++) {
                Block b = hut.getRelative(dx, dy, dz);
                if (wanted.contains(b.getType())) found.add(b);
            }
        return found;
    }

    /**
     * One step at the first furnace that needs it: what it made goes into the chest (what doesn't fit stays), an empty
     * furnace gets {@code input} (null: nothing to put in, at most {@link #LOAD} of it), and one with something to
     * burn and no fuel gets fuel from the chest. Walks there first. @return false if no furnace needed anything.
     */
    static boolean tend(Job job, Colonist w, List<Block> furnaces, Inventory chest, Supplier<ItemStack> input, Predicate<ItemStack> fuel) {
        for (Block b : furnaces) {
            if (!(b.getState(false) instanceof Furnace f)) continue;
            FurnaceInventory inv = f.getInventory();
            boolean collect = !empty(inv.getResult());
            ItemStack in = empty(inv.getSmelting()) ? input.get() : null;
            boolean burning = !empty(inv.getSmelting()) || in != null;
            ItemStack fuelItem = burning && empty(inv.getFuel()) && f.getBurnTime() <= 0 ? first(chest, fuel) : null;
            if (!collect && in == null && fuelItem == null) continue;
            w.setStatus(job.hut.type.worker + ": working");
            if (w.location().distance(b.getLocation().toCenterLocation()) > 4) { w.goBeside(b); return true; }
            if (collect) {
                var left = chest.addItem(inv.getResult()).values();
                inv.setResult(left.isEmpty() ? null : left.iterator().next());
            }
            if (in != null) inv.setSmelting(take(chest, in));
            if (fuelItem != null) inv.setFuel(take(chest, fuelItem));
            job.note = null;
            w.swing();
            job.cooldown = 20;
            return true;
        }
        return false;
    }

    private static boolean empty(ItemStack i) {
        return i == null || i.isEmpty();
    }

    static ItemStack first(Inventory chest, Predicate<ItemStack> test) {
        for (ItemStack i : chest.getContents()) if (!empty(i) && test.test(i)) return i;
        return null;
    }

    /** Takes up to {@link #LOAD} of {@code like} (no more than its amount) out of the chest. */
    private static ItemStack take(Inventory chest, ItemStack like) {
        ItemStack want = like.asQuantity(Math.min(LOAD, like.getAmount()));
        int left = chest.removeItem(want.clone()).values().stream().mapToInt(ItemStack::getAmount).sum();
        return want.asQuantity(want.getAmount() - left);
    }
}
