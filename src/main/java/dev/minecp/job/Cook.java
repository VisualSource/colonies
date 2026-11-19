package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.citizen.Meal;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
public final class Cook extends Job {
    // Ours, not the mod's (its cook uses the building's furnaces and the request system):
    private static final int FURNACE_RANGE = 12;  // how far from the hut chest its furnaces may be (the buildings are ~25 wide)
    private static final int FURNACE_HEIGHT = 4;
    private static final int LOAD = 16;           // raw food or fuel put in a furnace at a time

    public final Deque<Meal> waiting = new ArrayDeque<>(); // citizens at the restaurant, first come first served
    private final List<ItemStack> serving = new ArrayList<>(); // food it carries to the next customer
    private List<Block> furnaces = List.of();
    private int scan = 0;

    public Cook(Hut hut) {
        super(hut);
    }

    private static final int STOCK = 32; // ours: food, raw food and fuel it keeps on hand

    private static boolean isFuel(ItemStack i) {
        Material m = i.getType();
        return m.isFuel() && !m.isEdible() && m != Material.LAVA_BUCKET;
    }

    @Override
    public boolean worksInRain() {
        return true; // the mod's cook works indoors
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        // Ask once below half, for a full stock, so couriers come with a load rather than a few at a time.
        int meals = inChest(Food::isMeal), raw = inChest(i -> Food.cooked().containsKey(i.getType()));
        if (meals < STOCK / 2) wants.add(new Want("food", Food::isMeal, STOCK - meals, 1)); // the mod's Food request: minimum 1
        if (raw < STOCK / 2) wants.add(new Want("raw food to cook", i -> Food.cooked().containsKey(i.getType()), STOCK - raw, 1)); // ours
        if (!furnaces.isEmpty() && inChest(Cook::isFuel) < STOCK / 2) wants.add(new Want("fuel", Cook::isFuel, STOCK / 2, 1)); // the mod's fuel request: minimum 1
        return wants;
    }

    /** Food, raw food and fuel stay; anything else goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        return Food.isMeal(item) || Food.cooked().containsKey(item.getType()) || isFuel(item) ? Integer.MAX_VALUE : 0;
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text(hut.level == 0 ? "The restaurant opens once level 1 is built."
                : "Hungry citizens without food in their hut or house come here to be served. Put food in this chest, "
                + "or raw food and fuel: the cook cooks it in the furnaces and smokers nearby.", NamedTextColor.GRAY));
        if (!waiting.isEmpty()) lines.add(Component.text("Waiting to be served: " + waiting.size()));
        return lines;
    }

    @Override
    public void tick(Colonist w) {
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
        if (furnaces.isEmpty()) { idle(w, "No furnace or smoker near the hut to cook with.", 40, hut.block); return; }
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
        idle(w, waiting.isEmpty() ? null : note, 40, hut.block);
    }

    /** Raw food a furnace cooks ({@code raw}), or fuel that isn't food, in the chest; null if none. */
    private static ItemStack find(Inventory chest, boolean raw) {
        for (ItemStack i : chest.getContents()) {
            if (i == null || i.isEmpty()) continue;
            Material m = i.getType();
            if (raw ? Food.cooked().containsKey(m) : isFuel(i)) return i;
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

    /** Food it was carrying goes back to the chest when the job ends. */
    @Override
    public void dropCarried() {
        serving.forEach(i -> carried.merge(i.getType(), i.getAmount(), Integer::sum));
        serving.clear();
        super.dropCarried();
    }

    @Override
    public List<ItemStack> takeCarried() {
        List<ItemStack> all = super.takeCarried();
        all.addAll(serving);
        serving.clear();
        return all;
    }

    /** Food on its way to a customer goes back in the chest (it isn't saved); what doesn't fit drops at the hut. */
    @Override
    public void stow() {
        Inventory chest = chest();
        var at = hut.block.getLocation().toCenterLocation();
        for (ItemStack i : serving)
            for (ItemStack over : chest == null ? List.of(i) : chest.addItem(i).values()) at.getWorld().dropItemNaturally(at, over);
        serving.clear();
    }
}
