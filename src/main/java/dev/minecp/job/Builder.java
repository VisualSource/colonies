package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonyStore;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Rack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
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
public final class Builder extends Job {
    // Ours, not the mod's:
    private static final double REACH = 4.5;      // blocks the builder can place from
    private static final double LONG_REACH = 12;  // fallback when no walkable spot is near the block
    private static final int PLACE_TICKS = 8;

    public BuildOrder order;
    public Hut.WorkOrder work; // the hut upgrade the current order builds, null for any other structure
    private Block workFrom; // ground spot it is working from
    private Tool neededTool; // a tool it is waiting for, for couriers
    private Block neededFor;
    private BiConsumer<Hut.WorkOrder, BuildOrder> onFinish = (w, o) -> {};
    public Predicate<Block> protect = b -> false; // blocks a build must never replace; the colony sets its hut blocks

    public Builder(Hut hut) {
        super(hut);
    }

    /** Called when an order is finished, with its work order (null for a plain structure) and the order. */
    public void onFinish(BiConsumer<Hut.WorkOrder, BuildOrder> r) {
        onFinish = r;
    }

    public void start(BuildOrder o, Hut.WorkOrder w) {
        order = o;
        work = w;
        needed = null;
    }

    public void cancel() {
        order = null;
        work = null;
        needed = null;
    }

    /** Storage Racks carried: in {@link #load}, as whole items, so they are never mixed up with plain chests. */
    private int racks() {
        int n = 0;
        for (ItemStack i : load) if (Rack.is(i)) n += i.getAmount();
        return n;
    }

    private void addRacks(int n) {
        for (; n > 0; n -= 64) load.add(Rack.item().asQuantity(Math.min(n, 64)));
    }

    private void useRack() {
        for (ItemStack i : load) if (Rack.is(i)) { i.subtract(); break; }
        load.removeIf(ItemStack::isEmpty);
    }

    /**
     * Items the rest of the build needs that are neither carried nor in the hut chest, as one-item stacks (a plain
     * block, or a Storage Rack for rack spots).
     */
    Map<ItemStack, Integer> missing() {
        Map<ItemStack, Integer> need = new java.util.LinkedHashMap<>(needed());
        Inventory chest = chest();
        need.replaceAll((item, n) -> n - carrying(item) - (chest == null ? 0 : count(chest, item)));
        need.values().removeIf(n -> n <= 0);
        return need;
    }

    private Map<ItemStack, Integer> needed;
    private int neededAt;

    /**
     * What the rest of the build uses, as one-item stacks with counts. Each scan reads every remaining step's block
     * and couriers ask once per chest slot, so it is rescanned at most once a second.
     */
    private Map<ItemStack, Integer> needed() {
        int now = Bukkit.getCurrentTick();
        if (needed != null && now - neededAt < 20) return needed;
        needed = new java.util.LinkedHashMap<>();
        neededAt = now;
        if (order != null) for (BuildOrder.Step s : order.upcoming()) {
            ItemStack item = s.item();
            if (item != null && !s.done() && !protect.test(s.block())) needed.merge(item, 1, Integer::sum);
        }
        return needed;
    }

    /** How many of this build item it carries. */
    private int carrying(ItemStack item) {
        return Rack.is(item) ? racks() : carried.getOrDefault(item.getType(), 0);
    }

    private static String name(ItemStack item) {
        return Rack.is(item) ? "storage racks" : Job.name(item.getType());
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>();
        if (order == null) return lines;
        if (work == null) lines.add(Component.text("Building " + order.structure + "."));
        int kinds = missing().size();
        lines.add(kinds == 0 ? Component.text("All materials are in the hut.", NamedTextColor.GREEN)
                : Component.text(kinds + " kinds of materials missing from the hut chest.", NamedTextColor.RED));
        return lines;
    }

    @Override
    public Map<ItemStack, Integer> materials() {
        return order != null ? missing() : null;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        if (order == null) return;
        s.set("order.structure", order.structure);
        s.set("order.rotation", order.rotation.name());
        s.set("order.mirror", order.mirror);
        ColonyStore.putLocation(s, "order.origin", order.origin);
        if (work != null) {
            s.set("order.hut", c.huts.indexOf(work.hut()));
            s.set("order.level", work.level());
        }
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        Location origin = ColonyStore.getLocation(s, "order.origin");
        if (origin == null) return;
        String structure = s.getString("order.structure");
        BuildOrder o = BuildOrder.create(structure, origin,
                org.bukkit.block.structure.StructureRotation.valueOf(s.getString("order.rotation", "NONE")), s.getBoolean("order.mirror"), null); // origin already placed
        Hut h = c.hutByIndex(s.getInt("order.hut", -1));
        if (o != null) start(o, h != null ? new Hut.WorkOrder(h, s.getInt("order.level")) : null);
        else Bukkit.getLogger().warning("[MineCP] Structure " + structure + " not found; build order of " + c.name + " dropped.");
    }

    @Override
    List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        missing().forEach((item, n) -> wants.add(new Want(name(item), item::isSimilar, n)));
        Tool t = neededTool;
        Block b = neededFor;
        if (t != null) wants.add(new Want(toolLabel(t), i -> fits(t, i, b), 1));
        return wants;
    }

    /** Tools and what the build still needs stay; leftovers and what it dug up go to the warehouse. */
    @Override
    int keep(ItemStack item) {
        for (Tool t : Tool.values()) if (fits(t, item, null)) return Integer.MAX_VALUE;
        return needed().containsKey(item.asOne()) ? Integer.MAX_VALUE : 0;
    }

    /** How many of {@code item} (plain blocks, or racks: they don't count for each other) the inventory holds. */
    private static int count(Inventory inv, ItemStack item) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && item.isSimilar(i)) n += i.getAmount();
        return n;
    }


    @Override
    public void tick(Colonist w) {
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
                neededTool = tool;
                neededFor = toolFor;
                w.setStatus("Builder: needs tool");
                note = "Needs " + toolLabel(tool) + " good enough for "
                        + Job.name(step.block().getType()) + " in the hut chest.";
                cooldown = 100;
                return;
            }
        }

        ItemStack need = step.item();
        if (need != null && carrying(need) == 0) {
            if (!atHut(w)) {
                w.setStatus("Builder: fetching materials");
                goToHut(w);
                return;
            }
            restock(chest);
            if (carrying(need) == 0) {
                w.setStatus("Builder: needs materials");
                note = "Waiting for " + name(need) + " in the hut chest.";
                cooldown = 100; // check the chest again in 5 seconds
                return;
            }
        }

        note = null; // has what it needs
        neededTool = null;
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
            if (w.location().getBlock().equals(spots.get(i)) || w.walk(spots.get(i).getLocation().add(0.5, 0, 0.5))) { // there, or on the way
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
        List<ItemStack> drops = new ArrayList<>(tool == null ? b.getDrops() : b.getDrops(tool));
        drops.addAll(contents(b)); // what a chest in the way holds goes to the hut chest too
        return drops;
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
            store(chest, takeContents(b)); // emptied first, or its contents would be lost
            store(chest, breakWith(Tool.forBlock(b.getType(), false), b, w));
            b.setType(Material.AIR, false);
        }
        if (!step.isClear()) {
            if (convert == null && step.data().getMaterial().isSolid() && w.overlaps(b)) w.hopAside(b);
            b.setBlockData(step.data(), false); // no physics: blocks are placed out of support order
            if (step.rack()) Rack.tag(b); // the builder places the block, not the structure's chest data
            if (cost != null) { // used up
                if (step.rack()) useRack();
                else carried.merge(cost, -1, Integer::sum);
            }
            var sounds = step.data().getSoundGroup();
            b.getWorld().playSound(b.getLocation(), sounds.getPlaceSound(), SoundCategory.BLOCKS,
                    sounds.getVolume(), sounds.getPitch());
        }
    }

    /** Takes what the next steps need from the chest, up to {@link #CARRY_LIMIT} items. */
    private void restock(Inventory chest) {
        Map<ItemStack, Integer> want = order.nextLoad(BuildOrder.Step::item, CARRY_LIMIT);
        want.forEach((item, n) -> {
            int missing = n - carrying(item);
            if (missing <= 0) return;
            // removeItem matches whole items, so plain chests are never taken for racks or the other way round
            int left = chest.removeItem(item.asQuantity(missing)).values().stream().mapToInt(ItemStack::getAmount).sum();
            if (Rack.is(item)) addRacks(missing - left);
            else carried.merge(item.getType(), missing - left, Integer::sum);
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
