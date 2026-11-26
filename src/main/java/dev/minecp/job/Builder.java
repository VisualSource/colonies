package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Flag;
import dev.minecp.colony.ColonyStore;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Rack;
import dev.minecp.research.Effects;

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
    // The mod's (EntityAIStructureBuilder.walkToConstructionSite):
    private static final double REACH = 5;        // places anything within 5 blocks across, at any height
    private static final double CLOSE = 4;        // PathJobMoveCloseToXNearY: walks to within 4 of the block (the mod: dx+dy+dz)
    // Ours, not the mod's:
    private static final double LONG_REACH = 12;  // fallback when no walkable spot is near the block
    private int breaking; // ticks of mining what this step cleared
    private boolean waiting; // for a tool or materials it lacks (the mod's NEEDS_ITEM): free time, a meal or the hospital may take it

    public BuildOrder order;
    public Hut.WorkOrder work; // the hut upgrade the current order builds, null for any other structure
    public Colony.Decoration decoration; // the build without a hut the current order builds, or null; saved
    private Block workFrom; // ground spot it is working from
    private Tool neededTool; // a tool it is waiting for, for couriers
    private Block neededFor;
    private BiConsumer<Hut.WorkOrder, BuildOrder> onFinish = (w, o) -> {};
    public Predicate<Block> protect = b -> false; // blocks a build must never replace; the colony sets its hut blocks

    public Builder(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Called when an order is finished, with its work order (null for a plain structure) and the order. */
    public void onFinish(BiConsumer<Hut.WorkOrder, BuildOrder> r) {
        onFinish = r;
    }

    public void start(BuildOrder o, Hut.WorkOrder w) {
        o.fillWith(hut.block(dev.minecp.colony.Settings.FILL_BLOCK));
        // the mod's builder mode: the chosen order with Builder Modes researched, else Structurize's default
        o.arrange(colony.research.effect(dev.minecp.research.Effects.BUILDER_MODE) > 0 ? hut.setting(dev.minecp.colony.Settings.BUILD_MODE) : "default");
        order = o;
        work = w;
        needed = null;
    }

    public void cancel() {
        waiting = false;
        order = null;
        work = null;
        decoration = null;
        needed = null;
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

    /** For the resource scroll: each item the rest of the build uses, with {needed, carried or in the hut chest}. */
    public Map<ItemStack, int[]> resources() {
        Map<ItemStack, int[]> list = new java.util.LinkedHashMap<>();
        if (order == null) return list;
        Inventory chest = chest();
        needed().forEach((item, n) -> list.put(item, new int[]{n, carrying(item) + (chest == null ? 0 : count(chest, item))}));
        return list;
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

    /** How many of this build item it carries: matched whole, so a Storage Rack is never a plain chest or the other way round. */
    private int carrying(ItemStack item) {
        return has(i -> i.isSimilar(item));
    }

    private static String name(ItemStack item) {
        return Rack.is(item) ? "storage racks" : Flag.is(item) ? "colony banners" : Job.name(item.getType());
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
        if (decoration != null) ColonyStore.putDecoration(s.createSection("order.decoration"), decoration);
        if (work != null) {
            s.set("order.hut", c.huts.indexOf(work.hut()));
            s.set("order.level", work.level());
            if (work.remove()) s.set("order.remove", true);
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
        boolean remove = s.getBoolean("order.remove");
        if (o != null && remove) o = o.removal(); // taking it down
        if (o != null) start(o, h != null ? new Hut.WorkOrder(h, s.getInt("order.level"), remove) : null);
        else dev.minecp.colony.Colony.log.warning("Structure " + structure + " not found; build order of " + c.name + " dropped.");
        if (o != null && h == null && s.isConfigurationSection("order.decoration")) decoration = ColonyStore.getDecoration(s.getConfigurationSection("order.decoration"));
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        missing().forEach((item, n) -> wants.add(new Want(name(item), item::isSimilar, n, 1))); // the mod's builder asks with a minimum of 1
        Tool t = neededTool;
        Block b = neededFor;
        if (t != null) wants.add(new Want(toolLabel(t), i -> fits(t, i, b), 1));
        return wants;
    }

    /** Tools and what the build still needs stay; leftovers and what it dug up go to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (isDiggingTool(item) || useShears() && fits(Tool.SHEARS, item, null)) return Integer.MAX_VALUE;
        return needed().containsKey(item.asOne()) ? Integer.MAX_VALUE : 0;
    }

    /** How many of {@code item} (plain blocks, or racks: they don't count for each other) the inventory holds. */
    public static int count(Inventory inv, ItemStack item) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && item.isSimilar(i)) n += i.getAmount();
        return n;
    }

    /**
     * The mod's canAIBeInterrupted for its builder: with a build, only while it waits for what it lacks (its NEEDS_ITEM);
     * placing, digging, loading and finishing (START_BUILDING, BUILDING_STEP, MINE_BLOCK, ...) can't be interrupted.
     */
    @Override
    public boolean interruptible() {
        return order == null || waiting;
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
            experience(w, XP_PER_BUILDING);
            cancel();
            onFinish.accept(done, finished);
            return;
        }

        // Digging out what is in the way needs the right tool (pickaxe, axe, shovel), like a player;
        // so does turning dirt into a path (shovel) or farmland (hoe).
        Tool convert = step.convertWith();
        Tool tool = convert != null ? convert : step.block().isEmpty() ? null : toolFor(step.block().getType());
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
                waiting = true;
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
            if (freeSlots() == 0) { emptyInventory(w); return; } // full of what it dug up: no room even for the next block
            fetch(i -> i.isSimilar(need), need.getMaxStackSize()); // the next block first, whatever room the rest leaves
            restock(chest);
            if (carrying(need) == 0) {
                waiting = true;
                w.setStatus("Builder: needs materials");
                note = "Waiting for " + name(need) + " in the hut chest.";
                cooldown = 100; // check the chest again in 5 seconds
                return;
            }
            waiting = true; // the mod's pickUpMaterial passes through GATHERING_REQUIRED_MATERIALS: a meal or care may take it here
            return;
        }

        note = null; // has what it needs
        neededTool = null;
        waiting = false;
        double d = distance(w, step.block());
        // As the mod's builder (walkToConstructionSite): work in place when the block is within reach across; else walk
        // to the nearest spot a path reaches within 4 of it, inside the build or out; where none gets that close, work
        // from as close as it got, if that is within long reach. Far off: a spot on the ground outside the build first.
        if (across(w, step.block()) > REACH && !standsAt(w, workFrom, step.block())) {
            w.setStatus("Builder: building");
            workFrom = null;
            if (w.approach(step.block().getLocation().toCenterLocation(), CLOSE)) return;
            // None gets within 4: as close as a path gets (false once it stands there, then it works from here).
            if (w.approach(step.block().getLocation().toCenterLocation(), LONG_REACH)) return;
            if (d > LONG_REACH) {
                if (walkToWorkSpot(w, step.block())) return;
                w.goBeside(step.block()); // walk closer, teleport only if stuck
                return;
            }
        }
        if (!work(w, step, chest)) emptyInventory(w);
    }

    /**
     * Its inventory has no room for what it would dig up: to the hut to hand it in, keeping what the build still needs
     * (the mod's INVENTORY_FULL dump); if those alone fill it, they go in too and come back with the next restock. With
     * the hut chest full as well it waits there for couriers to clear it (the mod asks for a pickup and says so).
     */
    private void emptyInventory(Colonist w) {
        if (!atHut(w)) { w.setStatus("Builder: emptying its inventory"); goToHut(w); return; }
        boolean all = deliver();
        if (all && bagFull()) { emptying = true; all = deliver(); emptying = false; }
        if (!all) {
            w.setStatus("Builder: chest full");
            note = "Its inventory and the hut chest are full: make room in the hut chest for what it digs up.";
            cooldown = 100;
        }
    }

    private boolean emptying; // handing in the build's materials too, to make room

    /** When it hands things in, it keeps the materials the build still needs (the mod keeps what its building needs). */
    @Override
    boolean keepsOnHand(ItemStack i) {
        if (emptying || order == null) return false;
        for (ItemStack need : needed().keySet()) if (need.isSimilar(i.asOne())) return true;
        return false;
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

    /** @return false if nothing was done because its inventory has no room for what it would dig up (the mod's builder digs into its inventory). */
    private boolean work(Colonist w, BuildOrder.Step step, Inventory chest) {
        BuildOrder.Step partner = order.partner(step); // top of a door, head of a bed: never leave half of one
        if (partner != null && (partner.done() || protect.test(partner.block()))) partner = null;
        List<ItemStack> drops = new ArrayList<>(step.convertWith() != null ? List.of() : dropsOf(step.block()));
        if (partner != null) drops.addAll(dropsOf(partner.block()));
        Inventory bag = bag();
        if (bag == null || !fits(bag, drops)) return false;
        breaking = 0;
        place(w, step, chest);
        if (partner != null) place(w, partner, chest);
        act(w);
        // the mod's place time with its adaptability, and the mining time of what it cleared (ours: one step does both)
        cooldown = Math.max(1, (step.isClear() ? 0 : placeTicks(skill(0))) + breaking);
        return true;
    }

    /** What breaking {@code b} with the tool it would be broken with drops. */
    private Collection<ItemStack> dropsOf(Block b) {
        Tool kind = toolFor(b.getType());
        ItemStack tool = kind == null ? null : tool(kind);
        List<ItemStack> drops = new ArrayList<>(drops(b, tool));
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
        ItemStack cost = step.item(); // before the block changes: converting dirt is free, laying it is not
        Tool convert = step.convertWith();
        if (convert != null) wear(convert, w); // flattened or tilled where it lies
        else if (!b.isEmpty()) { // clear whatever is in the way, into its inventory
            breaking += breakTicks(b, toolFor(b.getType()), skill(1)); // the mod's mining time, with its athletics
            gather(takeContents(b), w); // emptied first, or its contents would be lost
            gather(breakWith(toolFor(b.getType()), b, w), w);
            b.setType(Material.AIR, false);
        }
        if (!step.isClear()) {
            if (convert == null && step.data().getMaterial().isSolid() && w.overlaps(b)) w.hopAside(b);
            b.setBlockData(step.data(), false); // no physics: blocks are placed out of support order
            if (step.flag()) Flag.tag(b, colony);
            if (step.rack()) Rack.tag(b); // the builder places the block, not the structure's chest data
            if (cost != null) { // used up
                use(cost::isSimilar, 1);
                if (cost.getType() == Material.WATER_BUCKET || cost.getType() == Material.LAVA_BUCKET) gather(Material.BUCKET, 1, w); // emptied
            }
            experience(w, XP_PER_BLOCK); // BuildingStructureHandler: each block placed (what it clears counts in breakWith)
            var sounds = step.data().getSoundGroup();
            b.getWorld().playSound(b.getLocation(), sounds.getPlaceSound(), SoundCategory.BLOCKS,
                    sounds.getVolume(), sounds.getPitch());
        }
    }

    private static final double XP_PER_BUILDING = 8; // AbstractEntityAIStructure.XP_EACH_BUILDING: each build finished
    private static final int MIN_OPEN_SLOTS = 5; // the mod's: it stops loading materials with this few slots free, for what it digs up

    /** Takes what the next steps need from the chest, leaving 5 slots free (the mod's MIN_OPEN_SLOTS). */
    private void restock(Inventory chest) {
        Map<ItemStack, Integer> want = order.nextLoad(BuildOrder.Step::item, carryLimit());
        for (var e : want.entrySet()) {
            int room = (freeSlots() - MIN_OPEN_SLOTS) * e.getKey().getMaxStackSize();
            if (room <= 0) break;
            ItemStack item = e.getKey();
            fetch(i -> i.isSimilar(item), Math.min(room, e.getValue() - carrying(item))); // whole items: racks never taken for chests
        }
    }

    /** Distance across the ground (the mod's getDistance2D): height doesn't count. */
    private static double across(Colonist w, Block b) {
        return Math.hypot(w.location().getX() - (b.getX() + 0.5), w.location().getZ() - (b.getZ() + 0.5));
    }

    private static double distance(Colonist w, Block b) {
        return w.location().distance(b.getLocation().toCenterLocation());
    }
}
