package dev.minecp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** The builder's hut (a chest that holds materials) and its builder's job loop. */
final class Builder {
    private static final double REACH = 4.5;      // blocks the builder can place from
    private static final double LONG_REACH = 12;  // fallback when no walkable spot is near the block
    private static final int PLACE_TICKS = 8;
    private static final int CARRY_LIMIT = 256;   // items fetched per trip

    final Block hut;
    UUID worker; // colonist id, or null while the hut has no builder
    BuildOrder order;
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // saved with the colony
    private int cooldown = 0;
    private Runnable onFinish = () -> {};
    Predicate<Block> protect = b -> false; // blocks a build must never replace; the colony sets hut + town hall

    Builder(Block hut) {
        this.hut = hut;
    }

    void onFinish(Runnable r) {
        onFinish = r;
    }

    private Inventory chest() {
        return hut.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    void tick(Colonist w) {
        if (order == null) {
            w.setStatus("Builder");
            w.wander(hut.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return; // hut chest gone; the plugin removes the hut on break

        BuildOrder.Step step = order.next(protect);
        if (step == null) {
            returnCarried(chest);
            order = null;
            onFinish.run();
            return;
        }

        Material need = step.cost();
        if (need != null && carried.getOrDefault(need, 0) == 0) {
            if (distance(w, hut) > 3) {
                w.setStatus("Builder: fetching materials");
                if (w.walkBeside(hut) == null) w.teleportNear(hut);
                return;
            }
            restock(chest);
            if (carried.getOrDefault(need, 0) == 0) {
                w.setStatus("Builder: needs " + need.name().toLowerCase().replace('_', ' '));
                cooldown = 100; // check the chest again in 5 seconds
                return;
            }
        }

        double d = distance(w, step.block());
        if (d > REACH) {
            w.setStatus("Builder: building");
            if (w.walkTo(step.block().getLocation().toCenterLocation(), 3)) return;
            if (d > LONG_REACH) { w.teleportNear(step.block()); return; }
        }
        work(w, step, chest);
    }

    private void work(Colonist w, BuildOrder.Step step, Inventory chest) {
        place(w, step, chest);
        BuildOrder.Step partner = order.partner(step); // top of a door, head of a bed: never leave half of one
        if (partner != null && !partner.done() && !protect.test(partner.block())) place(w, partner, chest);
        w.swing();
        cooldown = PLACE_TICKS;
    }

    private void place(Colonist w, BuildOrder.Step step, Inventory chest) {
        Block b = step.block();
        if (!b.isEmpty()) { // clear whatever is in the way, drops go to the hut
            store(chest, b.getDrops());
            b.setType(Material.AIR, false);
        }
        if (!step.isClear()) {
            if (step.data().getMaterial().isSolid() && w.overlaps(b)) w.hopAside(b);
            b.setBlockData(step.data(), false); // no physics: blocks are placed out of support order
            Material cost = step.cost();
            if (cost != null) carried.merge(cost, -1, Integer::sum);
            var sounds = step.data().getSoundGroup();
            b.getWorld().playSound(b.getLocation(), sounds.getPlaceSound(), SoundCategory.BLOCKS,
                    sounds.getVolume(), sounds.getPitch());
        }
    }

    /** Takes what the next steps need from the chest, up to {@link #CARRY_LIMIT} items. */
    private void restock(Inventory chest) {
        Map<Material, Integer> want = new EnumMap<>(Material.class);
        int total = 0;
        for (BuildOrder.Step s : order.upcoming()) {
            Material m = s.cost();
            if (m == null || s.done()) continue;
            want.merge(m, 1, Integer::sum);
            if (++total >= CARRY_LIMIT) break;
        }
        want.forEach((m, n) -> {
            int missing = n - carried.getOrDefault(m, 0);
            if (missing <= 0) return;
            int left = chest.removeItem(new ItemStack(m, missing)).values().stream().mapToInt(ItemStack::getAmount).sum();
            carried.merge(m, missing - left, Integer::sum);
        });
    }

    private void returnCarried(Inventory chest) {
        carried.forEach((m, n) -> { if (n > 0) store(chest, java.util.List.of(new ItemStack(m, n))); });
        carried.clear();
    }

    /** shortcut: drops teleport straight into the hut chest; make the builder carry them back if it matters. */
    private void store(Inventory chest, Collection<ItemStack> items) {
        Location at = hut.getLocation().toCenterLocation();
        for (ItemStack overflow : chest.addItem(items.toArray(ItemStack[]::new)).values())
            at.getWorld().dropItemNaturally(at, overflow);
    }

    /** Called when the hut is removed or the builder loses the job: give back what it carries. */
    void dropCarried() {
        Location at = hut.getLocation().toCenterLocation();
        carried.forEach((m, n) -> { if (n > 0) at.getWorld().dropItemNaturally(at, new ItemStack(m, n)); });
        carried.clear();
    }

    private static double distance(Colonist w, Block b) {
        return w.location().distance(b.getLocation().toCenterLocation());
    }
}
