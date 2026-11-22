package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

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
    private static final int HEIGHT = 4;  // blocks above and below the hut block
    private static final int LOAD = 16;   // input or fuel put in a furnace at a time

    private Furnaces() {}

    /** Burns in a furnace and isn't food (lava buckets would leave an empty bucket in the furnace). */
    static boolean isFuel(ItemStack i) {
        Material m = i.getType();
        return m.isFuel() && !m.isEdible() && m != Material.LAVA_BUCKET;
    }

    /**
     * The blocks of these types inside the hut's own building (its furnaces and smokers): only its outline, so huts next
     * to each other don't share furnaces, and players' furnaces outside it are left alone. None before level 1.
     */
    static List<Block> in(Colony colony, Hut hut, Material... types) {
        var wanted = EnumSet.noneOf(Material.class);
        wanted.addAll(List.of(types));
        return in(colony, hut, HEIGHT, HEIGHT, b -> wanted.contains(b.getType()));
    }

    /** The blocks inside the hut's own building, from {@code below} to {@code above} the hut block, that pass {@code test}. */
    static List<Block> in(Colony colony, Hut hut, int below, int above, Predicate<Block> test) {
        List<Block> found = new ArrayList<>();
        int[] box = colony.footprint(hut); // minX, maxX+1, minZ, maxZ+1
        if (box == null) return found;
        var w = hut.block.getWorld();
        int y0 = hut.block.getY();
        for (int x = box[0]; x < box[1]; x++) for (int z = box[2]; z < box[3]; z++)
            for (int y = y0 - below; y <= y0 + above; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (test.test(b)) found.add(b);
            }
        return found;
    }

    /**
     * One step at the first furnace that needs it: what it made goes into the chest (what doesn't fit stays), an empty
     * furnace gets {@code input} (null: nothing to put in, at most {@link #LOAD} of it), and one with something to
     * burn and no fuel gets fuel from the chest; but no more than {@code maxBusy} furnaces smelt at once (the mod's
     * getMaxUsableFurnaces). Walks there first. @return false if no furnace needed anything.
     */
    static boolean tend(Job job, Colonist w, List<Block> furnaces, Inventory chest, Supplier<ItemStack> input, Predicate<ItemStack> fuel, int maxBusy) {
        int busy = 0;
        for (Block b : furnaces) if (b.getState(false) instanceof Furnace f && !empty(f.getInventory().getSmelting())) busy++;
        for (Block b : furnaces) {
            if (!(b.getState(false) instanceof Furnace f)) continue;
            FurnaceInventory inv = f.getInventory();
            boolean collect = !empty(inv.getResult());
            ItemStack in = empty(inv.getSmelting()) && busy < maxBusy ? input.get() : null;
            boolean burning = !empty(inv.getSmelting()) || in != null;
            ItemStack fuelItem = burning && empty(inv.getFuel()) && f.getBurnTime() <= 0 ? first(chest, fuel) : null;
            if (!collect && in == null && fuelItem == null) continue;
            w.setStatus(job.hut.type.worker + ": working");
            if (w.location().distance(b.getLocation().toCenterLocation()) > 4) { w.goBeside(b); return true; }
            if (collect) {
                var left = chest.addItem(inv.getResult()).values();
                inv.setResult(left.isEmpty() ? null : left.iterator().next());
                job.experience(w, job instanceof Crafter ? CRAFTER_XP : COOK_XP);
            }
            if (in != null) inv.setSmelting(take(chest, in));
            if (fuelItem != null) inv.setFuel(take(chest, fuelItem));
            job.note = null;
            job.act(w);
            job.cooldown = 20;
            return true;
        }
        return false;
    }

    /**
     * Its worker's skill speeds up the lit furnaces: {@code ticks} more of cooking and burning each second (the mod's
     * accelerateFurnaces). Ours: a furnace stops one tick short of done, so vanilla finishes the item.
     */
    static void speedUp(List<Block> furnaces, int ticks) {
        if (ticks <= 0) return;
        for (Block b : furnaces) {
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) || !(b.getState(false) instanceof Furnace f)) continue;
            if (f.getBurnTime() <= 0 || empty(f.getInventory().getSmelting())) continue;
            f.setCookTime((short) Math.min(f.getCookTime() + ticks, Math.max(f.getCookTime(), f.getCookTimeTotal() - 1)));
            f.setBurnTime((short) Math.max(1, f.getBurnTime() - ticks));
        }
    }

    // the mod's BASE_XP_GAIN for taking out what a furnace made: its smelters' 5 (EntityAIWorkSmelter,
    // AbstractEntityAIRequestSmelter), the cook's 2 (AbstractEntityAIUsesFurnace)
    private static final double CRAFTER_XP = 5, COOK_XP = 2;
    static final int SPEED_UP_PER_10 = 2; // accelerateFurnaces: 2 extra ticks a second per 10 skill levels

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
