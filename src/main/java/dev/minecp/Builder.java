package dev.minecp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** The builder's hut (a chest that holds materials) and its builder's job loop. */
final class Builder {
    private static final double REACH = 4.5;      // blocks the builder can place from
    private static final double LONG_REACH = 12;  // fallback when no walkable spot is near the block
    private static final int PLACE_TICKS = 8;
    private static final int CARRY_LIMIT = 256;   // items fetched per trip

    final Hut hut; // the Builder's Hut; its chest holds the materials
    UUID worker;   // colonist id, or null while the hut has no builder
    BuildOrder order;
    Hut.WorkOrder work; // the hut upgrade the current order builds, null for any other structure
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // saved with the colony
    private int cooldown = 0;
    private Consumer<Hut.WorkOrder> onFinish = w -> {};
    Predicate<Block> protect = b -> false; // blocks a build must never replace; the colony sets its hut blocks

    Builder(Hut hut) {
        this.hut = hut;
    }

    /** Called when an order is finished, with its work order (null for a plain structure). */
    void onFinish(Consumer<Hut.WorkOrder> r) {
        onFinish = r;
    }

    void start(BuildOrder o, Hut.WorkOrder w) {
        order = o;
        work = w;
    }

    void cancel() {
        order = null;
        work = null;
    }

    /** Items the rest of the build needs that are neither carried nor in the hut chest. */
    Map<Material, Integer> missing() {
        Map<Material, Integer> need = new EnumMap<>(Material.class);
        if (order == null) return need;
        for (BuildOrder.Step s : order.upcoming()) {
            Material m = s.cost();
            if (m != null && !s.done() && !protect.test(s.block())) need.merge(m, 1, Integer::sum);
        }
        Inventory chest = chest();
        need.replaceAll((m, n) -> n - carried.getOrDefault(m, 0) - (chest == null ? 0 : count(chest, m)));
        need.values().removeIf(n -> n <= 0);
        return need;
    }

    private static int count(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && i.getType() == m) n += i.getAmount();
        return n;
    }

    Inventory chest() {
        return hut.block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    void tick(Colonist w) {
        if (order == null) {
            w.setStatus("Builder");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return; // hut chest gone; the plugin removes the hut on break

        BuildOrder.Step step = order.next(protect);
        if (step == null) {
            returnCarried(chest);
            Hut.WorkOrder done = work;
            cancel();
            onFinish.accept(done);
            return;
        }

        Material need = step.cost();
        if (need != null && carried.getOrDefault(need, 0) == 0) {
            if (distance(w, hut.block) > 3) {
                w.setStatus("Builder: fetching materials");
                if (w.walkBeside(hut.block) == null) w.teleportNear(hut.block);
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
        if (!work(w, step, chest)) {
            w.setStatus("Builder: hut chest is full");
            cooldown = 100; // like the mod: stop until there is room for what it digs up
        }
    }

    /** @return false if nothing was done because the hut chest has no room for what it would dig up. */
    private boolean work(Colonist w, BuildOrder.Step step, Inventory chest) {
        BuildOrder.Step partner = order.partner(step); // top of a door, head of a bed: never leave half of one
        if (partner != null && (partner.done() || protect.test(partner.block()))) partner = null;
        List<ItemStack> drops = new ArrayList<>(step.block().getDrops());
        if (partner != null) drops.addAll(partner.block().getDrops());
        if (!fits(chest, drops)) return false;
        place(w, step, chest);
        if (partner != null) place(w, partner, chest);
        w.swing();
        cooldown = PLACE_TICKS;
        return true;
    }

    /** Whether {@code items} would all fit in {@code chest}, tried on a copy. */
    private static boolean fits(Inventory chest, List<ItemStack> items) {
        if (items.isEmpty()) return true;
        Inventory copy = Bukkit.createInventory(null, chest.getSize());
        for (int i = 0; i < chest.getSize(); i++) {
            ItemStack it = chest.getItem(i);
            if (it != null) copy.setItem(i, it.clone());
        }
        return copy.addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).isEmpty();
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

    /** Puts leftovers back in the chest; what doesn't fit stays carried (and saved) instead of spilling. */
    private void returnCarried(Inventory chest) {
        carried.replaceAll((m, n) -> n <= 0 ? 0 : chest.addItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum());
        carried.values().removeIf(n -> n <= 0);
    }

    /**
     * shortcut: drops teleport straight into the hut chest; make the builder carry them back if it matters.
     * {@link #work} checks they fit first, so the overflow drop is only a safety net.
     */
    private void store(Inventory chest, Collection<ItemStack> items) {
        Location at = hut.block.getLocation().toCenterLocation();
        for (ItemStack overflow : chest.addItem(items.toArray(ItemStack[]::new)).values())
            at.getWorld().dropItemNaturally(at, overflow);
    }

    /** Called when the hut is removed or the builder loses the job: give back what it carries. */
    void dropCarried() {
        Location at = hut.block.getLocation().toCenterLocation();
        carried.forEach((m, n) -> { if (n > 0) at.getWorld().dropItemNaturally(at, new ItemStack(m, n)); });
        carried.clear();
    }

    private static double distance(Colonist w, Block b) {
        return w.location().distance(b.getLocation().toCenterLocation());
    }
}
