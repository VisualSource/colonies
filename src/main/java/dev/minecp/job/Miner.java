package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Settings;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.damage.DamageType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mine, as the mod digs it: a 7x7 shaft down beside the ladder in the Miner's Hut (cobblestone and a ladder
 * placed at each step, the floor below made safe), a mine level every 6 blocks, and once the shaft reaches the depth
 * the hut level allows, tunnel nodes 7 blocks apart on the deepest level, each a mineshaft structure (straight,
 * bend, T or crossroad, picked at random) that opens the next nodes. What it digs up goes to the hut chest.
 */
public final class Miner extends Job {
    private static final int SHAFT_RADIUS = 3;     // the mod's: 7x7 shaft, the ladder on its edge
    private static final int SAFE_RANGE = 5;       // the mod's SAFE_CHECK_RANGE: the floor under each new shaft layer is made solid 11x11
    private static final int NODE_DISTANCE = 7;    // the mod's
    private static final int MIN_NODES_FOR_RANDOM = 10, RANDOM_TYPES = 4; // the mod's MinerLevel: random picks past 10 dug nodes, 3 in 4
    private static final int RANDOM_TRIES = 16;    // ours: picks of already dug nodes before taking the oldest open one
    private static final int LEVEL_SPACING = 6;    // ours: the mod's spacing comes from its shaft and level pieces (not checked)
    private static final int[] MINING_LEVELS = {48, 16, -16, -100}; // the mod's: each hut level reaches the next (copper, iron, gold, diamond)
    private static final int REACH = 6;            // ours: the first layer under the shaft room is only 1 high: work from the room's hole
    private static final int FAR_REACH = 8;        // ours: if there is nowhere to stand nearer
    private static final int BLOCK_STOCK = 64, LADDER_STOCK = 16; // ours, of cobblestone and the fill block: the mod requests 32 cobblestone and 10 ladders at a time
    private static final int DELIVER_AT = 256;     // ours: loot carried before a trip up
    private static final int SHAFT_BASE_DEPTH = 8;  // the mod's: a shaft this close to the world's bottom counts as reaching it
    private static final double LUCKY_CHANCE = 0.01; // the mod's luckyBlockChance default: 1% of the stone it mines
    // The mod's ore chance blocks (forge:stone, base_stone_overworld and base_stone_nether), as vanilla blocks.
    private static final Set<Material> LUCKY_BLOCKS = EnumSet.of(Material.STONE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.POLISHED_GRANITE, Material.POLISHED_DIORITE, Material.POLISHED_ANDESITE, Material.DEEPSLATE,
            Material.POLISHED_DEEPSLATE, Material.TUFF, Material.INFESTED_STONE, Material.INFESTED_DEEPSLATE, Material.NETHERRACK,
            Material.BASALT, Material.BLACKSTONE);
    // Its miner/lucky_ore<hut level> loot tables: each ore and its weight, more of them at each level.
    private static final Material[] LUCKY_ORES = {Material.COAL_ORE, Material.COPPER_ORE, Material.IRON_ORE, Material.GOLD_ORE,
            Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE};
    private static final int[] LUCKY_WEIGHTS = {64, 48, 32, 16, 8, 4, 2, 1};
    private static final int[] LUCKY_KINDS = {0, 2, 4, 6, 8, 8}; // ores in the table by hut level
    private static final int HUT_RANGE = 12;       // ours: how far from the hut block the shaft's ladder may be
    private static final String SHAFTS = "/infrastructure/mineshafts/";

    /** Node shapes, with their structure and their exits relative to the way in (0 straight on, 1 and 3 the sides). */
    enum Style {
        SHAFT(null), LADDER_BACK(null),
        TUNNEL("minerx2top", 0), CROSSROAD("minerx4", 0, 1, 3), BEND_RIGHT("minerx2right", 3), BEND_LEFT("minerx2left", 1),
        CROSS_LEFT_RIGHT("minerx3leftright", 1, 3), CROSS_TOP_LEFT("minerx3topleft", 0, 3), CROSS_TOP_RIGHT("minerx3topright", 0, 1);

        final String file;
        final int[] exits;

        Style(String file, int... exits) {
            this.file = file;
            this.exits = exits;
        }
    }
    private static final Style[] SIDE_STYLES = {Style.TUNNEL, Style.CROSSROAD, Style.BEND_RIGHT, Style.BEND_LEFT,
            Style.CROSS_LEFT_RIGHT, Style.CROSS_TOP_LEFT, Style.CROSS_TOP_RIGHT};

    static final class Node {
        final int x, z, px, pz; // centre, and the centre of the node it branches from
        Style style;
        boolean done;

        Node(int x, int z, int px, int pz, Style style, boolean done) {
            this.x = x; this.z = z; this.px = px; this.pz = pz; this.style = style; this.done = done;
        }
    }

    static final class Level {
        final int depth; // y of its floor
        final Map<Long, Node> nodes = new LinkedHashMap<>();

        Level(int depth) {
            this.depth = depth;
        }

        void add(Node n) {
            nodes.put(key(n.x, n.z), n);
        }
    }

    final List<Level> levels = new ArrayList<>(); // saved
    int levelTarget = Integer.MIN_VALUE;          // the shaft builds the next level once it digs below this (saved)
    private Block ladder;        // top of the hut's ladder column
    private BlockFace facing;    // from the wall the ladder hangs on toward the shaft
    BuildOrder order;            // a level or node being dug and built
    private Node orderNode;      // null while that is a level
    private Node lastNode;       // the node dug last, for the random pick (not saved: the oldest open node comes first again)
    private int orderDepth;
    private Tool neededTool;
    private boolean delivering;

    @Override
    public double diseaseModifier() {
        return 2; // the mod's JobMiner
    }

    public Miner(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** The mod's Hot Boots: lava, magma, fire and burning (its four fire damage types, not campfires) don't hurt a miner. */
    @Override
    public double reaching(double damage, DamageType type, Entity source, Colonist w) {
        boolean fire = type == DamageType.LAVA || type == DamageType.HOT_FLOOR || type == DamageType.IN_FIRE || type == DamageType.ON_FIRE;
        return fire && colony.research.effect(Effects.MINER_FIRE_RESISTANCE) > 0 ? 0 : damage;
    }

    @Override
    public double hungerFactor() {
        return 1.2; // the mod's miner
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // ---- where the mine is -------------------------------------------------------------------------------

    /** Finds the ladder column of the hut building (the mod registers the ladder in the hut's blueprint). */
    private boolean findLadder() {
        Block best = null;
        for (int dx = -HUT_RANGE; dx <= HUT_RANGE; dx++) for (int dz = -HUT_RANGE; dz <= HUT_RANGE; dz++) for (int dy = -8; dy <= 8; dy++) {
            Block b = hut.block.getRelative(dx, dy, dz);
            if (b.getType() != Material.LADDER || b.getRelative(BlockFace.UP).getType() == Material.LADDER) continue; // column tops only
            if (best == null || b.getLocation().distanceSquared(hut.block.getLocation()) < best.getLocation().distanceSquared(hut.block.getLocation())) best = b;
        }
        if (best == null) return false;
        ladder = best;
        facing = ((Ladder) best.getBlockData()).getFacing();
        return true;
    }

    /** The lowest ladder of the column. */
    private int lastLadder() {
        Block b = ladder;
        while (b.getRelative(BlockFace.DOWN).getType() == Material.LADDER) b = b.getRelative(BlockFace.DOWN);
        return b.getY();
    }

    private Block center(int y) {
        Block c = ladder.getRelative(facing, SHAFT_RADIUS);
        return c.getWorld().getBlockAt(c.getX(), y, c.getZ());
    }

    private Block ladderAt(int y) {
        return ladder.getWorld().getBlockAt(ladder.getX(), y, ladder.getZ());
    }

    private Block cobbleAt(int y) {
        Block c = ladder.getRelative(facing.getOppositeFace());
        return c.getWorld().getBlockAt(c.getX(), y, c.getZ());
    }

    /** How deep the shaft may go: the hut level counts down the mining levels below the hut (the mod's rule). */
    int depthLimit() {
        if (ladder == null) return Integer.MIN_VALUE;
        int below = ladder.getY() - 5, levelsLeft = hut.level, y = 0;
        for (int m : MINING_LEVELS) {
            if (m < below) { y = m; levelsLeft--; }
            if (levelsLeft == 0) break;
        }
        int max = hut.number(Settings.MAX_DEPTH); // the mod's setting can only keep it shallower; its default means no limit
        if (max != Settings.NO_MAX_DEPTH) y = Math.max(y, max);
        return Math.max(ladder.getWorld().getMinHeight() + 5, y);
    }

    /** What fills holes, liquids and floors (its fill block setting); the ladder's backing is always cobblestone, as in the mod. */
    private Material fillBlock() {
        return hut.block(Settings.FILL_BLOCK);
    }

    /** The blocks it keeps a stock of: cobblestone for the ladder's backing, and its fill block. */
    private java.util.Set<Material> stockBlocks() {
        return java.util.EnumSet.of(Material.COBBLESTONE, fillBlock()); // the same block once when the fill block is cobblestone
    }

    /** For the menu: how deep the shaft goes and how deep this hut level lets it go. */
    record Depth(int shaft, int limit) {}

    /** Null before the ladder is found. */
    Depth depth() {
        return ladder == null ? null : new Depth(lastLadder(), depthLimit());
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The miner starts once level 1 is built.", NamedTextColor.GRAY));
        Depth d = depth();
        return List.of(
                Component.text(d == null ? "Digs a shaft down from the ladder in the hut, then tunnels."
                        : "Shaft down to y " + d.shaft() + "; this hut level allows y " + d.limit() + ". Mine levels: " + levels.size()
                        + (d.shaft() < d.limit() ? ". Tunnelling on the deepest level." : ". Digging the shaft.")
                        + " Upgrade the hut to go deeper.", NamedTextColor.GRAY),
                Component.text("Needs a pickaxe and a shovel, cobblestone and ladders for the shaft, and for the tunnel supports: "
                        + "oak planks, logs, wood, slabs, stairs and fences, torches, plus a fence gate and a sign for each mine level.",
                        NamedTextColor.GRAY));
    }

    @Override
    public Map<ItemStack, Integer> materials() {
        return hut.level > 0 ? missing() : null;
    }

    // ---- the work ----------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Miner: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (--cooldown > 0) return;
        if (w.isMoving()) { // already in reach of what it is working on: stop there rather than finish the walk
            if (target == null || w.location().distance(target.getLocation().toCenterLocation()) > REACH) return;
            w.stopWalking();
        }
        if (ladder == null || ladder.getType() != Material.LADDER) {
            if (!findLadder()) { idle(w, "No ladder found in the Miner's Hut building.", 100, null); return; }
        }
        if (delivering || loot() >= DELIVER_AT || bagFull() && loot() > 0) { // the mod's miner also goes up with a full inventory
            delivering = true;
            if (!atHut(w)) { w.setStatus("Miner: working"); goToHut(w); return; }
            if (!deliverLoot()) { idle(w, "The hut chest is full.", 100, null); return; }
            delivering = false;
        }
        w.setStatus("Miner: working");
        if (order != null) { workOrder(w); return; }
        int last = lastLadder();
        if (last < w.location().getWorld().getMinHeight() + SHAFT_BASE_DEPTH) colony.milestones.deepMine();
        if (levelTarget == Integer.MIN_VALUE) levelTarget = last - 1 - 4; // the mod: the first level 4 below where the shaft starts
        if (last >= depthLimit()) { shaftStep(w, last); return; }
        if (levels.isEmpty()) { startLevel(last + 2); return; } // shaft already at the limit: still needs a level to tunnel from
        nodeStep(w);
    }

    /** One step down the shaft: clear the 7x7 layer at the lowest ladder, then make the next step safe and go down. */
    private void shaftStep(Colonist w, int y) {
        Block c = center(y);
        for (int x = -SHAFT_RADIUS - 2; x <= SHAFT_RADIUS + 2; x++) for (int z = -SHAFT_RADIUS - 2; z <= SHAFT_RADIUS + 2; z++) {
            Block b = c.getRelative(x, 0, z);
            if (b.isLiquid()) { fill(w, b, fillBlock()); return; } // keep water and lava out of the shaft, as the mod does
        }
        for (int x = SHAFT_RADIUS; x >= -SHAFT_RADIUS; x--) for (int z = -SHAFT_RADIUS; z <= SHAFT_RADIUS; z++) {
            Block b = c.getRelative(x, 0, z);
            if (b.equals(ladderAt(y)) || b.isEmpty() || b.getType() == Material.LADDER || b.getType().getHardness() < 0) continue;
            mine(w, b);
            return;
        }
        if (y - 1 < levelTarget && levelAt(y + 2) == null) { startLevel(y + 2); return; } // a level every 6 blocks
        for (int x = -SAFE_RANGE; x <= SAFE_RANGE; x++) for (int z = -SAFE_RANGE; z <= SAFE_RANGE; z++) {
            Block f = c.getRelative(x, -2, z);
            if (f.getType().getHardness() < 0 || f.getY() < f.getWorld().getMinHeight()) continue;
            if (!f.getType().isSolid() || f.isLiquid() || isOre(f.getType())) { fill(w, f, fillBlock()); return; } // ores are dug out first
        }
        Block nextCobble = cobbleAt(y - 1), nextLadder = ladderAt(y - 1);
        if (nextCobble.getType() != Material.COBBLESTONE) { fill(w, nextCobble, Material.COBBLESTONE); return; }
        if (nextLadder.getType() != Material.LADDER) {
            if (!nextLadder.isEmpty()) { mine(w, nextLadder); return; }
            if (!supply(w, Material.LADDER, LADDER_STOCK)) return;
            if (!near(w, nextLadder)) return;
            Ladder data = (Ladder) Material.LADDER.createBlockData();
            data.setFacing(facing);
            place(w, nextLadder, data, Material.LADDER);
        }
    }

    /** Puts {@code m} at {@code b}, digging out what is there first (keeping its drops). */
    private void fill(Colonist w, Block b, Material m) {
        if (!b.isEmpty() && !b.isLiquid() && !b.isReplaceable()) { mine(w, b); return; }
        if (!supply(w, m, BLOCK_STOCK)) return;
        if (!near(w, b)) return;
        place(w, b, m.createBlockData(), m);
    }

    private Level levelAt(int depth) {
        for (Level l : levels) if (l.depth == depth) return l;
        return null;
    }

    /** Builds the mine level (minermainshaft) around the shaft, turned so its ladder is on the hut's ladder. */
    private void startLevel(int depth) {
        for (StructureRotation r : StructureRotation.values()) {
            BuildOrder o = centered("minermainshaft", center(depth), 4, r);
            if (o == null) return;
            boolean fits = o.steps.stream().anyMatch(s -> s.data().getMaterial() == Material.LADDER
                    && s.block().getX() == ladder.getX() && s.block().getZ() == ladder.getZ());
            if (fits) { order = o; orderNode = null; orderDepth = depth; return; }
        }
        order = centered("minermainshaft", center(depth), 4, StructureRotation.NONE); // shouldn't happen: the hut's ladder is on a side
        orderNode = null;
        orderDepth = depth;
    }

    /**
     * The mod's getRandomNode: once more than 10 nodes of the level are dug, 3 times in 4 a random neighbour of the node
     * dug last; a pick already dug is picked again (the mod's next call). @return null for the oldest open node instead.
     */
    private Node randomNode(Level level) {
        long dug = level.nodes.values().stream().filter(n -> n.done && n.style.file != null).count();
        if (lastNode == null || level.nodes.get(key(lastNode.x, lastNode.z)) != lastNode || dug <= MIN_NODES_FOR_RANDOM) return null;
        var rnd = ThreadLocalRandom.current();
        for (int tries = 0; tries < RANDOM_TRIES; tries++) {
            if (rnd.nextInt(RANDOM_TYPES) == 0) return null;
            Node n = neighbour(level, lastNode, 0);
            if (n == null) return null;
            if (!n.done && n.style.file != null) return n;
        }
        return null;
    }

    /**
     * The mod's getRandomNextNode: north, south or east of {@code n} (its roll never picks west), or, where there is none
     * or it is the shaft, the same from the node it branched from, at most 4 steps back.
     */
    private static Node neighbour(Level level, Node n, int step) {
        if (n == null || step > 3) return null;
        int roll = ThreadLocalRandom.current().nextInt(3);
        Node next = level.nodes.get(roll == 0 ? key(n.x, n.z - NODE_DISTANCE) : roll == 1 ? key(n.x, n.z + NODE_DISTANCE) : key(n.x + NODE_DISTANCE, n.z));
        if (next == null || next.style == Style.SHAFT) return neighbour(level, level.nodes.get(key(n.px, n.pz)), step + 1);
        return next;
    }

    /** Digs the next open node of the deepest level: the oldest open one, or a random one (randomNode). */
    private void nodeStep(Colonist w) {
        Level level = levels.getLast();
        Block c = center(level.depth);
        Node next = randomNode(level);
        if (next == null) for (Node n : level.nodes.values()) // the oldest open node (the mod's openNodes queue)
            if (!n.done && n.style.file != null) { next = n; break; }
        if (next == null) { idle(w, "No more tunnels to dig on this level (water or lava in the way).", 100, null); return; }
        order = centered(next.style.file, c.getWorld().getBlockAt(next.x, level.depth, next.z), 3, rotation(next));
        orderNode = next;
        orderDepth = level.depth;
        if (order == null) { next.done = true; orderNode = null; }
    }

    /** The way from a node's parent to it, as a rotation: east (+x) is the structures' way in. */
    private static StructureRotation rotation(Node n) {
        int dir = dir(Integer.signum(n.x - n.px), Integer.signum(n.z - n.pz));
        return StructureRotation.values()[dir];
    }

    /** 0 east, 1 south, 2 west, 3 north: the order the structure rotations turn in. */
    private static int dir(int dx, int dz) {
        return dx > 0 ? 0 : dz > 0 ? 1 : dx < 0 ? 2 : 3;
    }

    private BuildOrder centered(String file, Block center, int half, StructureRotation r) {
        BlockVector off = BuildOrder.rotate(new BlockVector(half, 0, half), r);
        Block origin = center.getRelative(-off.getBlockX(), 0, -off.getBlockZ());
        String style = BuildOrder.exists(colony.style + SHAFTS + file) ? colony.style : Colony.DEFAULT_STYLE; // the cavern style has no shafts
        return BuildOrder.create(style + SHAFTS + file, origin.getLocation(), r, null);
    }

    private void workOrder(Colonist w) {
        BuildOrder.Step step = order.next(b -> b.getType() == Material.LADDER && b.getX() == ladder.getX() && b.getZ() == ladder.getZ());
        if (step == null) { finishOrder(); return; }
        Block b = step.block();
        boolean right = !b.isEmpty() && b.getType() == step.data().getMaterial();
        if (!b.isEmpty() && !right) {
            if (b.isLiquid()) { // shortcut: sources in tunnels are removed, not walled off
                if (near(w, b)) { b.setType(Material.AIR); cooldown = 4; }
                return;
            }
            mine(w, b); // gets its tool first, then goes there
            return;
        }
        if (step.isClear()) return;
        Material cost = step.cost();
        if (cost != null && !supplyFor(w, cost)) return; // materials first: fetching them means a trip up to the hut
        if (!near(w, b)) return;
        place(w, b, step.data(), cost);
    }

    private void finishOrder() {
        if (orderNode == null) { // a level: its first four tunnels branch from the shaft, not behind the ladder
            Level level = new Level(orderDepth);
            Block c = center(orderDepth);
            level.add(new Node(c.getX(), c.getZ(), c.getX(), c.getZ(), Style.SHAFT, true));
            Block back = c.getRelative(facing.getOppositeFace(), NODE_DISTANCE);
            level.add(new Node(back.getX(), back.getZ(), c.getX(), c.getZ(), Style.LADDER_BACK, true));
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                if (f == facing.getOppositeFace()) continue;
                Block n = c.getRelative(f, NODE_DISTANCE);
                level.add(new Node(n.getX(), n.getZ(), c.getX(), c.getZ(), Style.TUNNEL, false));
            }
            levels.add(level);
            levelTarget = orderDepth - LEVEL_SPACING;
            updateSign(level);
        } else { // a node: open the nodes its exits lead to
            Level level = levelAt(orderDepth);
            int in = dir(Integer.signum(orderNode.x - orderNode.px), Integer.signum(orderNode.z - orderNode.pz));
            if (level != null) for (int e : orderNode.style.exits) {
                int d = (in + e) % 4;
                int nx = orderNode.x + (d == 0 ? NODE_DISTANCE : d == 2 ? -NODE_DISTANCE : 0);
                int nz = orderNode.z + (d == 1 ? NODE_DISTANCE : d == 3 ? -NODE_DISTANCE : 0);
                if (level.nodes.containsKey(key(nx, nz))) continue;
                if (ladder.getWorld().getBlockAt(nx, orderDepth + 2, nz).isLiquid()) continue; // the mod skips flooded spots
                Style s = SIDE_STYLES[ThreadLocalRandom.current().nextInt(SIDE_STYLES.length)];
                level.add(new Node(nx, nz, orderNode.x, orderNode.z, s, false));
            }
            orderNode.done = true;
            lastNode = orderNode;
            if (level != null) updateSign(level);
        }
        order = null;
        orderNode = null;
    }

    /** Writes the level's number, height and tunnels dug on the sign the level structure comes with, like the mod. */
    private void updateSign(Level level) {
        Block c = center(level.depth);
        long dug = level.nodes.values().stream().filter(n -> n.done && n.style.file != null).count();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy <= 3; dy++) {
            if (!(c.getRelative(dx, dy, dz).getState() instanceof org.bukkit.block.Sign sign)) continue;
            for (var side : org.bukkit.block.sign.Side.values()) {
                var text = sign.getSide(side);
                text.line(0, net.kyori.adventure.text.Component.text("Mine level " + (levels.indexOf(level) + 1)));
                text.line(1, net.kyori.adventure.text.Component.text("Y: " + (level.depth + 1)));
                text.line(2, net.kyori.adventure.text.Component.text("Tunnels: " + dug));
                text.line(3, net.kyori.adventure.text.Component.empty());
            }
            sign.update(true, false);
            return;
        }
    }

    // ---- digging, placing, supplies ------------------------------------------------------------------------

    Block target; // the block it is working on, for the menu and tests
    private int tries;   // walks toward {@link #target} that didn't get it within reach

    /**
     * Gets within reach of {@code b}: walks to a free spot near it (the shaft's ladder included, as the mod's miner).
     * @return true if it is within reach now.
     */
    private boolean near(Colonist w, Block b) {
        if (!b.equals(target)) tries = 0;
        target = b;
        // From the middle of the block it stands in, as the spots below are measured: from its feet, a spot just
        // within reach read as out of reach, and it walked to where it already stood, over and over.
        double from = w.location().getBlock().getLocation().toCenterLocation().distance(b.getLocation().toCenterLocation());
        if (from <= REACH) { tries = 0; return true; }
        List<Block> spots = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -3; dy <= 3; dy++) {
            Block s = b.getRelative(dx, dy, dz);
            if (Colonist.canStandIn(s) && s.getLocation().toCenterLocation().distance(b.getLocation().toCenterLocation()) <= REACH) spots.add(s);
        }
        spots.sort(java.util.Comparator.comparingDouble(s -> s.getLocation().distanceSquared(b.getLocation())));
        cooldown = 10;
        // Mine supports and fences make paths that look walkable but aren't: after a few tries, go there directly.
        if (++tries <= 3) for (int i = 0; i < Math.min(3, spots.size()); i++) if (w.walk(spots.get(i).getLocation().add(0.5, 0, 0.5))) return false;
        if (!spots.isEmpty()) { w.teleportTo(spots.getFirst()); return false; }
        if (from <= FAR_REACH) return true; // nowhere nearer to stand
        w.goBeside(b);
        return false;
    }

    /** Digs {@code b} out with the right tool, keeping the drops. */
    private void mine(Colonist w, Block b) {
        Tool tool = Tool.forBlock(b.getType(), false);
        if (tool != null && !hasTool(tool, b)) {
            if (!atHut(w)) { goToHut(w); return; }
            if (!fetchTool(tool, b)) {
                neededTool = tool;
                idle(w, "Needs " + toolLabel(tool) + " good enough for " + name(b.getType()) + " in the hut chest.", 100, null);
                return;
            }
        }
        if (!near(w, b)) return;
        neededTool = null;
        // A chest in the way spills what it holds, as when a player breaks it.
        for (ItemStack i : takeContents(b)) b.getWorld().dropItemNaturally(b.getLocation().toCenterLocation(), i);
        Material lucky = luckyOre(b.getType());
        gather(breakWith(tool, b, w), w);
        if (lucky != null) gather(lucky, 1, w);
        var data = b.getBlockData();
        b.getWorld().playSound(b.getLocation(), data.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1, 1);
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().toCenterLocation(), 10, 0.3, 0.3, 0.3, data);
        b.setType(Material.AIR, false);
        act(w);
        cooldown = faster(Math.max(4, 14 - 2 * hut.level), "blockbreakspeedmultiplier");
    }

    private void place(Colonist w, Block b, org.bukkit.block.data.BlockData data, Material cost) {
        if (data.getMaterial().isSolid() && w.overlaps(b)) w.hopAside(b);
        b.setBlockData(data, false);
        if (cost != null) use(cost, 1);
        var sounds = data.getSoundGroup();
        b.getWorld().playSound(b.getLocation(), sounds.getPlaceSound(), SoundCategory.BLOCKS, sounds.getVolume(), sounds.getPitch());
        act(w);
        cooldown = faster(8, Effects.PLACE_SPEED);
    }

    /** Makes sure it carries some {@code m}, restocking from the hut chest. @return true if it does. */
    private boolean supply(Colonist w, Material m, int stock) {
        if (has(m) > 0) return true;
        if (!atHut(w)) { goToHut(w); return false; }
        if (fetch(m, stock) > 0) return true;
        idle(w, "Needs " + name(m) + " in the hut chest.", 100, null);
        return false;
    }

    /** Like {@link #supply}, for a structure block: fetches what the rest of the order needs at once. */
    private boolean supplyFor(Colonist w, Material m) {
        if (has(m) > 0) return true;
        if (!atHut(w)) { goToHut(w); return false; }
        needs().forEach((mat, n) -> fetch(mat, n - has(mat))); // as much as its inventory holds
        if (has(m) > 0) return true;
        idle(w, "Needs " + name(m) + " in the hut chest.", 100, null);
        return false;
    }

    /** What the level or node being built still needs to place, up to a full load. */
    private Map<Material, Integer> needs() {
        return order == null ? new LinkedHashMap<>() : order.nextLoad(BuildOrder.Step::cost, carryLimit());
    }

    /** For the menu: tools, shaft stock and the current level or tunnel's materials that are neither carried nor in the hut chest. */
    Map<ItemStack, Integer> missing() {
        Map<ItemStack, Integer> out = new LinkedHashMap<>();
        if (!hasTool(Tool.PICKAXE, null) && inChest(i -> fits(Tool.PICKAXE, i, null)) == 0) out.put(new ItemStack(Material.WOODEN_PICKAXE), 1);
        if (!hasTool(Tool.SHOVEL, null) && inChest(i -> fits(Tool.SHOVEL, i, null)) == 0) out.put(new ItemStack(Material.WOODEN_SHOVEL), 1);
        Tool t = neededTool; // another tool a block calls for, as the mod requests it (an axe for a level's wooden posts)
        if (t != null && !hasTool(t, null) && inChest(i -> fits(t, i, null)) == 0)
            out.put(new ItemStack(switch (t) { case AXE -> Material.WOODEN_AXE; case HOE -> Material.WOODEN_HOE; case SHOVEL -> Material.WOODEN_SHOVEL; default -> Material.WOODEN_PICKAXE; }), 1);
        Map<Material, Integer> need = needs();
        for (Material m : stockBlocks()) need.merge(m, BLOCK_STOCK, Math::max);
        need.merge(Material.LADDER, LADDER_STOCK, Math::max);
        need.forEach((m, n) -> {
            int more = n - has(m) - inChest(i -> i.getType() == m);
            if (more > 0) out.put(new ItemStack(m), more);
        });
        return out;
    }

    /** Drops off what it dug up; keeps its cobblestone, ladders and what the current structure needs. */
    /** What it keeps when it delivers: what the current work still needs, plus its stock of cobblestone, fill block and ladders. */
    private Map<Material, Integer> keep() {
        Map<Material, Integer> keep = needs();
        for (Material m : stockBlocks()) keep.merge(m, BLOCK_STOCK, Integer::sum);
        keep.merge(Material.LADDER, LADDER_STOCK, Integer::sum);
        return keep;
    }

    /**
     * How much of what it carries it would hand in. Only this counts toward going back to deliver: counting the
     * supplies it keeps too sent it back up after every block once a mine level needed a lot of cobblestone.
     */
    private int loot() {
        Map<Material, Integer> keep = keep();
        return holding().entrySet().stream().mapToInt(e -> Math.max(0, e.getValue() - keep.getOrDefault(e.getKey(), 0))).sum();
    }

    private boolean deliverLoot() {
        Map<Material, Integer> keep = keep();
        boolean all = true;
        for (var e : holding().entrySet()) {
            int give = e.getValue() - keep.getOrDefault(e.getKey(), 0);
            if (give > 0 && handIn(e.getKey(), give) > 0) all = false;
        }
        return all;
    }

    /** The mod's lucky ore: mining stone may also turn up an ore block, 1% of the time, more with research. @return it, or null. */
    private Material luckyOre(Material mined) {
        var rnd = ThreadLocalRandom.current();
        if (!LUCKY_BLOCKS.contains(mined) || rnd.nextDouble() >= LUCKY_CHANCE * (1 + colony.research.effect(Effects.MORE_ORES))) return null;
        int kinds = LUCKY_KINDS[Math.min(hut.level, Hut.MAX_LEVEL)], total = 0;
        for (int i = 0; i < kinds; i++) total += LUCKY_WEIGHTS[i];
        if (total == 0) return null;
        int roll = rnd.nextInt(total);
        for (int i = 0; i < kinds; i++) if ((roll -= LUCKY_WEIGHTS[i]) < 0) return LUCKY_ORES[i];
        return null;
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    // ---- couriers ------------------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        for (Material m : stockBlocks()) {
            int have = inChest(i -> i.getType() == m) + has(m);
            if (have < BLOCK_STOCK / 2) wants.add(new Want(name(m), i -> i.getType() == m, BLOCK_STOCK - have, 1)); // minimum 1, as the mod
        }
        int ladders = inChest(i -> i.getType() == Material.LADDER) + has(Material.LADDER);
        if (ladders < LADDER_STOCK / 2) wants.add(new Want("ladders", i -> i.getType() == Material.LADDER, LADDER_STOCK - ladders, 1));
        needs().forEach((m, n) -> {
            int more = n - has(m) - inChest(i -> i.getType() == m);
            if (more > 0) wants.add(new Want(name(m), i -> i.getType() == m, more, 1)); // the mod's structure requests: minimum 1
        });
        wantTool(wants, Tool.PICKAXE);
        wantTool(wants, Tool.SHOVEL);
        Tool t = neededTool;
        if (t != null && !hasTool(t, null)) wants.add(new Want(toolLabel(t), i -> fits(t, i, null), 1));
        return wants;
    }

    /** Tools, cobblestone, the fill block, ladders and the current structure's materials stay; the ore and stone go to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (isDiggingTool(item)) return Integer.MAX_VALUE;
        Material m = item.getType();
        if (stockBlocks().contains(m)) return BLOCK_STOCK;
        if (m == Material.LADDER) return LADDER_STOCK;
        return needs().containsKey(m) ? Integer.MAX_VALUE : 0;
    }

    // ---- saving --------------------------------------------------------------------------------------------

    @Override
    public void save(ConfigurationSection js, Colony c) {
        super.save(js, c);
        var s = js.createSection("mine");
        s.set("level-target", levelTarget);
        for (int i = 0; i < levels.size(); i++) {
            Level l = levels.get(i);
            s.set("levels." + i + ".depth", l.depth);
            s.set("levels." + i + ".nodes", l.nodes.values().stream()
                    .map(n -> n.x + "," + n.z + "," + n.px + "," + n.pz + "," + n.style.name() + "," + n.done).toList());
        }
    }

    @Override
    public void load(ConfigurationSection js, Colony c) {
        super.load(js, c);
        var s = js.getConfigurationSection("mine");
        if (s == null) return;
        levelTarget = s.getInt("level-target", Integer.MIN_VALUE);
        var ls = s.getConfigurationSection("levels");
        if (ls != null) for (String i : ls.getKeys(false)) {
            Level l = new Level(ls.getInt(i + ".depth"));
            for (String n : ls.getStringList(i + ".nodes")) {
                String[] p = n.split(",");
                l.add(new Node(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Style.valueOf(p[4]), Boolean.parseBoolean(p[5])));
            }
            levels.add(l);
        }
    }
}
