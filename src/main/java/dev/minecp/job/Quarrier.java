package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quarrier (the mod's JobQuarrier and EntityAIQuarrier, the builder's structure work on a quarry's shaft blueprint),
 * the mod's rules unless noted: hired at a Miner's Hut instead of its miner, it is assigned to a quarry and digs the pit
 * below it top-down a layer at a time. On each layer it first lays the layer's solid blocks (walls and supports; where
 * the blueprint keeps the ground, an ore is mined and replaced by the mine's fill block, and a gap filled), then the
 * layer above's torches, ladders and the rest, then mines out every block that should be air. Its tools and materials
 * come from its Miner's Hut (whose level limits them); what it digs goes to the quarry's chest.
 */
public final class Quarrier extends Job {
    private static final int MAX_BLOCKS_MINED = 128;    // blocks dug before it empties its inventory, times its Miner's Hut's level
    private static final double XP_EACH_BUILDING = 8;   // the quarry dug out
    private static final double REACH = 4;              // ours: works on a block this close
    private static final int FILL_KEPT = 64;            // a stack of the fill block, to seal liquids (the mod's mine keeps one)

    private Hut quarry;                                 // the quarry it is assigned to (saved), or null
    private int layer = Integer.MIN_VALUE;              // the world y it is digging (saved); MIN_VALUE: from the top (the mod resets it on a new quarry)
    private int dug;                                    // blocks dug since it last emptied its inventory
    private Map<Integer, List<BuildOrder.Step>> layers; // the shaft's steps by world y, read once found
    private int top, bottom;
    private final Map<Material, Integer> keepLeft = new EnumMap<>(Material.class); // while handing in: how many more of each it keeps

    public Quarrier(Hut hut, Colony colony) {
        super(hut, colony);
    }

    // ---- the decisions (no world needed: QuarrierTest) --------------------------------------------------------------

    /** Ground the blueprint keeps needs work if it is a gap (not solid) or an ore, which is replaced by the fill block. */
    static boolean keptGroundNeedsWork(boolean solid, boolean ore) {
        return !solid || ore;
    }

    /** Whether to keep this many of {@code m} when handing in, counting down what it keeps of it. */
    static boolean keepOne(Map<Material, Integer> left, Material m, int amount) {
        int n = left.getOrDefault(m, 0);
        if (n <= 0) return false;
        left.put(m, n - amount);
        return true;
    }

    // ---- the pit --------------------------------------------------------------------------------------------------

    @Override
    public String title() {
        return "Quarrier";
    }

    /** Its quarry, or null: gone, dug out, or never assigned. */
    public Hut quarry() {
        if (quarry != null && (!colony.huts.contains(quarry) || quarry.dugOut)) assign(null);
        return quarry;
    }

    /** Assigns it to a quarry (null: none); one quarrier a quarry, so one already there is unassigned. The work starts from the top. */
    public void assign(Hut q) {
        if (q != null) for (Job j : colony.jobs) if (j instanceof Quarrier o && o != this && o.quarry == q) o.assign(null);
        quarry = q;
        layer = Integer.MIN_VALUE;
        layers = null;
    }

    /** The mod's QuarryModule hiring: a built quarry not dug out and without a quarrier takes any quarrier without a quarry. */
    private Hut freeQuarry() {
        for (Hut h : colony.huts)
            if (h.type.isQuarry() && h.level >= 1 && !h.dugOut && colony.jobs.stream().noneMatch(j -> j instanceof Quarrier o && o.quarry == h)) return h;
        return null;
    }

    /**
     * The pit: the quarry's blueprint with "shaft" before its level (simplequarry1: simplequarryshaft1, the mod's
     * naming), turned as the quarry is, its anchor two blocks below the quarry's hut block (the mod's WorkOrderMiner).
     * Read once found; looked for again while it isn't (the data packs may come later).
     */
    private Map<Integer, List<BuildOrder.Step>> layers() {
        if (layers != null) return layers;
        Hut q = quarry;
        String name = q.structure(colony.style, 1);
        BuildOrder built = BuildOrder.create(name, q.block.getLocation(), q.rotation, q.mirror, q.block);
        BuildOrder shaft = built == null ? null : BuildOrder.create(name.replaceFirst("1$", "shaft1"), q.block.getLocation(),
                built.rotation, q.mirror, q.block.getRelative(0, -2, 0));
        if (shaft == null) return Map.of();
        Map<Integer, List<BuildOrder.Step>> out = new LinkedHashMap<>();
        top = Integer.MIN_VALUE;
        bottom = Integer.MAX_VALUE;
        for (BuildOrder.Step s : shaft.steps) {
            out.computeIfAbsent(s.block().getY(), y -> new ArrayList<>()).add(s);
            top = Math.max(top, s.block().getY());
            bottom = Math.min(bottom, s.block().getY());
        }
        return layers = out;
    }

    /** Its Miner's Hut's fill block (the mod's: the quarrier uses its mine's). */
    private Material fill() {
        return hut.block(Settings.FILL_BLOCK);
    }

    /** A torch, ladder, trapdoor or the like: placed after the layer below has its solid blocks. */
    private static boolean isDeco(BuildOrder.Step s) {
        return !s.isClear() && !s.fill() && !s.data().getMaterial().isSolid();
    }

    /** A wall, support or kept ground: placed first on its layer. */
    private static boolean isSolid(BuildOrder.Step s) {
        return !s.isClear() && (s.fill() || s.data().getMaterial().isSolid());
    }

    /** Whether a step that places something still needs it. */
    private boolean toPlace(BuildOrder.Step s) {
        if (untouchable(s.block())) return false;
        Material have = s.block().getType();
        return s.fill() ? keptGroundNeedsWork(have.isSolid(), Miner.isOre(have)) : !s.done();
    }

    /** The next step on layer {@code y}: its solid blocks, the layer above's torches and ladders, then what is mined out. */
    private BuildOrder.Step pending(int y) {
        List<BuildOrder.Step> steps = layers().getOrDefault(y, List.of());
        for (BuildOrder.Step s : steps) if (isSolid(s) && toPlace(s)) return s;
        for (BuildOrder.Step s : layers().getOrDefault(y + 1, List.of())) if (isDeco(s) && toPlace(s)) return s;
        for (BuildOrder.Step s : steps) if (s.isClear() && !s.block().isEmpty() && !untouchable(s.block())) return s; // liquids too: sealed, then dug
        return null;
    }

    /** Bedrock, unbreakable blocks and colony huts are never touched (the mod's DONT_TOUCH). */
    private boolean untouchable(Block b) {
        return b.getType().getHardness() < 0 || colony.hutAt(b) != null;
    }

    /** What a step uses: the fill block for kept ground and for a liquid where air belongs, the blueprint's block else, nothing to dig. */
    private ItemStack cost(BuildOrder.Step s) {
        if (s.fill() || s.isClear() && s.block().isLiquid()) return new ItemStack(fill());
        return s.isClear() ? null : s.item();
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Quarrier: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (quarry() == null) {
            Hut q = freeQuarry();
            if (q == null) { idle(w, "Assign it to a quarry, or place one: every quarry is dug out or has its quarrier.", 200, hut.block); return; }
            assign(q);
        }
        if (layers().isEmpty()) { idle(w, "No pit for its quarry: the data packs need rebuilding (./gradlew datapacks).", 200, hut.block); return; }
        if (layer == Integer.MIN_VALUE) layer = top;
        if (dug >= hut.level * MAX_BLOCKS_MINED || bagFull() && loaded()) { // to the quarry's chest (the mod dumps there, not at the mine)
            if (!w.isBeside(quarry.block)) { w.setStatus("Quarrier: bringing up what it dug"); w.goBeside(quarry.block); return; }
            if (!dump()) { idle(w, "The quarry's chest is full.", 100, quarry.block); return; }
            dug = 0;
        }
        BuildOrder.Step step = pending(layer);
        while (step == null && layer >= bottom) step = pending(--layer); // this layer is done: the next one down (its step 2 covers the last layer's torches)
        if (step == null) {
            quarry.dugOut = true;
            experience(w, XP_EACH_BUILDING);
            colony.tell("The quarrier finished digging the " + quarry.type.title + "."); // ours, for the mod's "finished" message to its players
            assign(null);
            return;
        }
        Block b = step.block();
        Tool tool = b.isEmpty() || b.isLiquid() ? null : Tool.forBlock(b.getType(), false);
        if (tool != null && !hasTool(tool, b)) {
            if (!atHut(w)) { w.setStatus("Quarrier: fetching a tool"); goToHut(w); return; }
            if (!fetchTool(tool, b)) { idle(w, "Needs " + toolLabel(tool) + " good enough for " + name(b.getType()) + " in the Miner's Hut chest.", 100, hut.block); return; }
        }
        ItemStack need = cost(step);
        if (need != null && has(need::isSimilar) == 0) {
            if (!atHut(w)) { w.setStatus("Quarrier: fetching materials"); goToHut(w); return; }
            if (fetch(need::isSimilar, need.getMaxStackSize()) == 0) { idle(w, "Waiting for " + name(need.getType()) + " in the Miner's Hut chest.", 100, hut.block); return; }
        }
        note = null;
        w.setStatus("Quarrier: digging");
        if (w.location().getWorld() != b.getWorld() || w.location().distance(b.getLocation().toCenterLocation()) > REACH) { w.goBeside(b); return; }
        work(w, step, tool, need);
    }

    /** Mines what is there (sealing liquids beside it first), then places what belongs there: a liquid is filled, to be dug next. */
    private void work(Colonist w, BuildOrder.Step step, Tool tool, ItemStack need) {
        Block b = step.block();
        int ticks = 0;
        if (!b.isEmpty() && !b.isLiquid()) {
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f); // the mod's: liquids next to it are sealed with the fill block it carries
                if (n.isLiquid() && !untouchable(n) && use(i -> i.getType() == fill(), 1) == 1) n.setType(fill());
            }
            ticks += breakTicks(b, tool, skill(0)); // its strength
            gather(takeContents(b), w);
            gather(breakWith(tool, b, w), w);
            b.setType(Material.AIR, false);
            dug++;
        }
        if (need != null && use(need::isSimilar, 1) == 1) {
            b.setBlockData(step.fill() || step.isClear() ? fill().createBlockData() : step.data(), false);
            var sounds = b.getBlockData().getSoundGroup();
            b.getWorld().playSound(b.getLocation(), sounds.getPlaceSound(), SoundCategory.BLOCKS, sounds.getVolume(), sounds.getPitch());
            ticks += placeTicks(skill(1)); // its stamina
        }
        w.hold(tool == null ? null : tool(tool));
        w.swing();
        act(w);
        cooldown = Math.max(1, ticks);
    }

    /** Hands what it carries (but what it keeps on hand) into the quarry's chest; the rest stays with it. @return true if all fit. */
    private boolean dump() {
        var chest = quarry.block.getState(false) instanceof org.bukkit.block.Container c ? c.getInventory() : null;
        var bag = bag();
        if (chest == null || bag == null) return false;
        var left = chest.addItem(unload().toArray(ItemStack[]::new)).values();
        left.forEach(bag::addItem);
        return left.isEmpty();
    }

    /** What the layers it works on (this one and the one above, and the one below) still need placed. */
    private Map<Material, Integer> needed() {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        if (quarry == null || colony.worker(this) == null || layers().isEmpty()) return out;
        int from = layer == Integer.MIN_VALUE ? top : layer;
        for (int y = from + 1; y >= Math.max(bottom, from - 1); y--)
            for (BuildOrder.Step s : layers().getOrDefault(y, List.of())) {
                if (s.isClear() || !toPlace(s)) continue;
                ItemStack c = cost(s);
                if (c != null) out.merge(c.getType(), 1, Integer::sum);
            }
        return out;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1 || colony.worker(this) == null) return wants; // a mine whose worker is its miner asks for nothing of the quarrier's
        for (Tool t : new Tool[]{Tool.PICKAXE, Tool.SHOVEL, Tool.AXE}) wantTool(wants, t);
        Map<Material, Integer> needed = needed();
        needed.merge(fill(), FILL_KEPT, Math::max); // always a stack of fill, to seal liquids
        needed.forEach((m, n) -> {
            int more = n - has(m) - inChest(i -> i.getType() == m);
            if (more > 0) wants.add(new Want(name(m), i -> i.getType() == m && i.getPersistentDataContainer().isEmpty(), Math.max(more, m.getMaxStackSize() / 2), 1)); // the mod asks for at least half a stack
        });
        return wants;
    }

    @Override
    public Map<ItemStack, Integer> materials() {
        Map<ItemStack, Integer> out = new LinkedHashMap<>();
        needed().forEach((m, n) -> { int more = n - has(m) - inChest(i -> i.getType() == m); if (more > 0) out.put(new ItemStack(m), more); });
        return out;
    }

    /** Its tools, a stack of fill and what the pit still needs stay in its Miner's Hut chest (what it digs goes to the quarry's). */
    @Override
    int keep(ItemStack item) {
        if (colony.worker(this) == null) return 0; // the miner's keep counts there instead
        if (isDiggingTool(item)) return 1;
        if (item.getType() == fill()) return FILL_KEPT + needed().getOrDefault(fill(), 0);
        return needed().getOrDefault(item.getType(), 0);
    }

    /** It keeps on hand what the layers still need and a stack of fill, no more: the rest of what it digs (cobblestone too) is handed in. */
    @Override
    void startHandIn() {
        keepLeft.clear();
        keepLeft.putAll(needed());
        keepLeft.merge(fill(), FILL_KEPT, Integer::sum);
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return i.getPersistentDataContainer().isEmpty() && keepOne(keepLeft, i.getType(), i.getAmount());
    }

    @Override
    public List<Component> info(Colony c) {
        if (colony.worker(this) == null) return List.of(); // the mine has its miner, or nobody yet
        if (quarry() == null) return List.of(Component.text("Its quarrier waits for a quarry: place one, or assign it from a quarry's menu.", NamedTextColor.GRAY));
        if (layers().isEmpty()) return List.of(Component.text("No pit blueprint for its quarry: rebuild the data packs.", NamedTextColor.GRAY));
        int done = layer == Integer.MIN_VALUE ? 0 : top - layer;
        return List.of(Component.text("Its quarrier digs the pit of the " + quarry.type.title + " at " + quarry.block.getX() + " " + quarry.block.getY() + " "
                + quarry.block.getZ() + ", top-down: layer " + (done + 1) + " of " + (top - bottom + 1) + ".", NamedTextColor.GRAY));
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        if (layer != Integer.MIN_VALUE) s.set("layer", layer);
        if (quarry != null) s.set("quarry", c.huts.indexOf(quarry));
        s.set("dug", dug);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        quarry = c.hutByIndex(s.getInt("quarry", -1));
        layer = s.getInt("layer", Integer.MIN_VALUE);
        dug = s.getInt("dug");
    }
}
