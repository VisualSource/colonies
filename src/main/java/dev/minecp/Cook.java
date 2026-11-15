package dev.minecp;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The restaurant: the cook serves citizens waiting at the Cook's Hut with food from its chest, and keeps the
 * furnaces and smokers near the hut cooking the raw food in the chest, with fuel from the chest.
 */
final class Cook extends Job {
    private static final int FURNACE_RANGE = 12;  // how far from the hut chest its furnaces may be (the buildings are ~25 wide)
    private static final int FURNACE_HEIGHT = 4;
    private static final int LOAD = 16;           // raw food or fuel put in a furnace at a time

    final Deque<Meal> waiting = new ArrayDeque<>(); // citizens at the restaurant, first come first served
    private final List<ItemStack> serving = new ArrayList<>(); // food it carries to the next customer
    private List<Block> furnaces = List.of();
    private int cooldown = 0, scan = 0;

    Cook(Hut hut) {
        super(hut);
    }

    @Override
    void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Cook: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return;
        waiting.removeIf(m -> !m.isWaiting());

        if (!waiting.isEmpty()) { // serving comes first
            Meal next = waiting.peek();
            if (serving.isEmpty()) {
                if (!atHut(w)) { w.setStatus("Cook: working"); goToHut(w); return; }
                serving.addAll(Food.take(chest, next.hunger()));
                if (serving.isEmpty()) note = "No food to serve: put food in the chest, or raw food and fuel to cook.";
            }
            if (!serving.isEmpty()) {
                w.setStatus("Cook: working");
                Block at = next.colonist.location().getBlock();
                if (w.location().distance(next.colonist.location()) > 3) { w.goBeside(at); return; }
                waiting.poll();
                next.serve(new ArrayList<>(serving));
                serving.clear();
                w.swing();
                cooldown = 10;
                return;
            }
        }

        if (scan-- <= 0) { furnaces = findFurnaces(); scan = 200; }
        if (furnaces.isEmpty()) { idle(w, "No furnace or smoker near the hut to cook with."); return; }
        for (Block f : furnaces) {
            if (!(f.getState(false) instanceof Furnace furnace)) continue;
            FurnaceInventory inv = furnace.getInventory();
            boolean collect = inv.getResult() != null && !inv.getResult().isEmpty();
            ItemStack raw = inv.getSmelting() == null || inv.getSmelting().isEmpty() ? find(chest, true) : null;
            boolean needsFuel = (inv.getFuel() == null || inv.getFuel().isEmpty()) && furnace.getBurnTime() <= 0
                    && ((inv.getSmelting() != null && !inv.getSmelting().isEmpty()) || raw != null);
            ItemStack fuel = needsFuel ? find(chest, false) : null;
            if (!collect && raw == null && fuel == null) continue;
            w.setStatus("Cook: working");
            if (w.location().distance(f.getLocation().toCenterLocation()) > 4) { w.goBeside(f); return; }
            if (collect) { // cooked food to the chest; what doesn't fit stays in the furnace
                var left = chest.addItem(inv.getResult()).values();
                inv.setResult(left.isEmpty() ? null : left.iterator().next());
            }
            if (raw != null) inv.setSmelting(takeFrom(chest, raw));
            if (fuel != null) inv.setFuel(takeFrom(chest, fuel));
            note = null;
            w.swing();
            cooldown = 20;
            return;
        }
        idle(w, waiting.isEmpty() ? null : note);
    }

    /** Raw food a furnace cooks ({@code raw}), or fuel that isn't food, in the chest; null if none. */
    private static ItemStack find(Inventory chest, boolean raw) {
        for (ItemStack i : chest.getContents()) {
            if (i == null || i.isEmpty()) continue;
            Material m = i.getType();
            if (raw ? Food.cooked().containsKey(m) : m.isFuel() && !m.isEdible() && m != Material.LAVA_BUCKET) return i;
        }
        return null;
    }

    /** Takes up to {@link #LOAD} of {@code like} out of the chest. */
    private static ItemStack takeFrom(Inventory chest, ItemStack like) {
        ItemStack want = like.asQuantity(Math.min(LOAD, like.getAmount()));
        int left = chest.removeItem(want.clone()).values().stream().mapToInt(ItemStack::getAmount).sum();
        return want.asQuantity(want.getAmount() - left);
    }

    private List<Block> findFurnaces() {
        List<Block> found = new ArrayList<>();
        for (int dx = -FURNACE_RANGE; dx <= FURNACE_RANGE; dx++) for (int dz = -FURNACE_RANGE; dz <= FURNACE_RANGE; dz++)
            for (int dy = -FURNACE_HEIGHT; dy <= FURNACE_HEIGHT; dy++) {
                Block b = hut.block.getRelative(dx, dy, dz);
                if (b.getType() == Material.FURNACE || b.getType() == Material.SMOKER) found.add(b);
            }
        return found;
    }

    private void idle(Colonist w, String why) {
        note = why;
        w.setStatus("Cook: idle");
        if (!atHut(w)) goToHut(w);
        cooldown = 40;
    }

    /** Food it was carrying goes back to the chest when the job ends. */
    @Override
    void dropCarried() {
        serving.forEach(i -> carried.merge(i.getType(), i.getAmount(), Integer::sum));
        serving.clear();
        super.dropCarried();
    }
}
