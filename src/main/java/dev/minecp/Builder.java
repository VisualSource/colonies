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
import java.util.Comparator;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** The builder's hut (a chest that holds materials) and its builder's job loop. */
final class Builder extends Job {
    private static final double REACH = 4.5;      // blocks the builder can place from
    private static final double LONG_REACH = 12;  // fallback when no walkable spot is near the block
    private static final int PLACE_TICKS = 8;
    private static final int CARRY_LIMIT = 27 * 64; // items fetched per trip: a citizen's 27-slot inventory, as in the mod

    BuildOrder order;
    Hut.WorkOrder work; // the hut upgrade the current order builds, null for any other structure
    private int cooldown = 0;
    private Block workFrom; // ground spot it is working from
    private BiConsumer<Hut.WorkOrder, BuildOrder> onFinish = (w, o) -> {};
    Predicate<Block> protect = b -> false; // blocks a build must never replace; the colony sets its hut blocks

    Builder(Hut hut) {
        super(hut);
    }

    /** Called when an order is finished, with its work order (null for a plain structure) and the order. */
    void onFinish(BiConsumer<Hut.WorkOrder, BuildOrder> r) {
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


    @Override
    void tick(Colonist w) {
        if (order == null) {
            note = null;
            w.setStatus("Builder");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return; // hut chest gone; the plugin removes the hut on break

        BuildOrder.Step step = order.next(protect);
        if (step == null) {
            deliver(); // leftovers back in the chest; what doesn't fit stays carried
            Hut.WorkOrder done = work;
            BuildOrder finished = order;
            cancel();
            onFinish.accept(done, finished);
            return;
        }

        // Digging out what is in the way needs the right tool (pickaxe, axe, shovel), like a player;
        // so does turning dirt into a path (shovel) or farmland (hoe).
        Tool convert = step.convertWith();
        Tool tool = convert != null ? convert : step.block().isEmpty() ? null : Tool.forBlock(step.block().getType(), false);
        Block toolFor = convert != null ? null : step.block(); // any shovel or hoe converts
        if (tool != null && !hasTool(tool, toolFor)) {
            if (!atHut(w)) {
                w.setStatus("Builder: fetching tool");
                goToHut(w);
                return;
            }
            if (!fetchTool(tool, toolFor)) {
                w.setStatus("Builder: needs tool");
                note = "Needs " + tool.label + " good enough for "
                        + step.block().getType().name().toLowerCase().replace('_', ' ') + " in the hut chest.";
                cooldown = 100;
                return;
            }
        }

        Material need = step.cost();
        if (need != null && carried.getOrDefault(need, 0) == 0) {
            if (!atHut(w)) {
                w.setStatus("Builder: fetching materials");
                goToHut(w);
                return;
            }
            restock(chest);
            if (carried.getOrDefault(need, 0) == 0) {
                w.setStatus("Builder: needs materials");
                note = "Waiting for " + need.name().toLowerCase().replace('_', ' ') + " in the hut chest.";
                cooldown = 100; // check the chest again in 5 seconds
                return;
            }
        }

        note = null; // has what it needs
        double d = distance(w, step.block());
        if (d > REACH && !standsAt(w, workFrom, step.block())) {
            w.setStatus("Builder: building");
            // Like the mod's builders: work from the ground just outside the build, placing everything within
            // long reach from there (walls and roof included) instead of climbing onto it.
            if (walkToWorkSpot(w, step.block())) return;
            // No such spot in reach (the middle of a big build): get as close as a path leads; teleport as a last resort.
            if (w.approach(step.block().getLocation().toCenterLocation(), LONG_REACH)) return;
            if (d > LONG_REACH) { w.goBeside(step.block()); return; } // far off: walk closer, teleport only if stuck
        }
        if (!work(w, step, chest)) {
            w.setStatus("Builder: chest full");
            note = "The hut chest is full: make room for what it digs up.";
            if (!atHut(w)) goToHut(w); // wait at the hut, like the mod
            cooldown = 100; // stop until there is room for what it digs up
        }
    }

    private static boolean standsAt(Colonist w, Block spot, Block target) {
        return spot != null && w.location().distance(spot.getLocation().add(0.5, 0, 0.5)) < 1.5
                && spot.getLocation().toCenterLocation().distance(target.getLocation().toCenterLocation()) <= LONG_REACH;
    }

    /** Walks to the nearest ground spot outside the build within long reach of {@code target}. @return false if none. */
    private boolean walkToWorkSpot(Colonist w, Block target) {
        Location at = target.getLocation().toCenterLocation();
        List<Block> spots = new ArrayList<>(order.groundAround());
        spots.removeIf(s -> s.getLocation().toCenterLocation().distance(at) > LONG_REACH);
        spots.sort(Comparator.comparingDouble(s -> s.getLocation().toCenterLocation().distanceSquared(at)));
        for (int i = 0; i < Math.min(6, spots.size()); i++) { // each try is a path search
            if (w.walk(spots.get(i).getLocation().add(0.5, 0, 0.5))) {
                workFrom = spots.get(i);
                return true;
            }
        }
        return false;
    }

    /** @return false if nothing was done because the hut chest has no room for what it would dig up. */
    private boolean work(Colonist w, BuildOrder.Step step, Inventory chest) {
        BuildOrder.Step partner = order.partner(step); // top of a door, head of a bed: never leave half of one
        if (partner != null && (partner.done() || protect.test(partner.block()))) partner = null;
        List<ItemStack> drops = new ArrayList<>(step.convertWith() != null ? List.of() : dropsOf(step.block()));
        if (partner != null) drops.addAll(dropsOf(partner.block()));
        if (!fits(chest, drops)) return false;
        place(w, step, chest);
        if (partner != null) place(w, partner, chest);
        w.swing();
        cooldown = PLACE_TICKS;
        return true;
    }

    /** What breaking {@code b} with the tool it would be broken with drops. */
    private Collection<ItemStack> dropsOf(Block b) {
        Tool kind = Tool.forBlock(b.getType(), false);
        ItemStack tool = kind == null ? null : tools.get(kind);
        return tool == null ? b.getDrops() : b.getDrops(tool);
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
        Material cost = step.cost(); // before the block changes: converting dirt is free, laying it is not
        Tool convert = step.convertWith();
        if (convert != null) wear(convert, w); // flattened or tilled where it lies
        else if (!b.isEmpty()) { // clear whatever is in the way, drops go to the hut
            store(chest, breakWith(Tool.forBlock(b.getType(), false), b, w));
            b.setType(Material.AIR, false);
        }
        if (!step.isClear()) {
            if (convert == null && step.data().getMaterial().isSolid() && w.overlaps(b)) w.hopAside(b);
            b.setBlockData(step.data(), false); // no physics: blocks are placed out of support order
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


    /**
     * shortcut: drops teleport straight into the hut chest; make the builder carry them back if it matters.
     * {@link #work} checks they fit first, so the overflow drop is only a safety net.
     */
    private void store(Inventory chest, Collection<ItemStack> items) {
        Location at = hut.block.getLocation().toCenterLocation();
        for (ItemStack overflow : chest.addItem(items.toArray(ItemStack[]::new)).values())
            at.getWorld().dropItemNaturally(at, overflow);
    }


    private static double distance(Colonist w, Block b) {
        return w.location().distance(b.getLocation().toCenterLocation());
    }
}
