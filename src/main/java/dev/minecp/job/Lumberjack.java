package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Settings;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cuts natural trees near the Lumberjack's Hut (logs, then leaves for their drops), replants them with
 * the saplings that dropped, and brings the rest to the hut chest.
 * A tree is logs standing on soil with at least {@link #MIN_LEAVES} naturally grown leaves, so the logs
 * in buildings are left alone.
 */
public final class Lumberjack extends Job {
    // Ours, not the mod's (its tree detection works differently):
    private static final int MIN_LEAVES = 4;
    private static final int MAX_TREE_LOGS = 200; // more connected logs than this is a build, not a tree
    private static final int MAX_LEAVES = 600;
    private static final int LEAF_REACH = 4;      // how far past the trunk a leaf still counts as this tree's
    private static final int LEAVES_PER_SWING = 4;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private enum State { FIND, TO_TREE, CHOP, TO_HUT, REFETCH, SAPLINGS, REPLANT }

    private State state = State.FIND;
    private Block base;           // lowest log of the tree being cut
    private Material logType;     // its wood, for the sapling
    private List<Block> logs = List.of(); // the tree, bottom up
    private List<Block> leaves = List.of(); // its natural leaves, cleared after the logs for their saplings
    private int next;             // index into logs, then into leaves
    private boolean onLeaves;
    private final Set<Block> unreachable = new HashSet<>();

    public Lumberjack(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    public double hungerFactor() {
        return 1.2;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        wantTool(wants, Tool.AXE);
        wantTool(wants, Tool.HOE);
        return wants;
    }

    /** Its tools and a stack of each sapling stay (the mod's keepX); the wood and apples go to the warehouse. */
    @Override
    int keep(org.bukkit.inventory.ItemStack item) {
        if (fits(Tool.AXE, item, null) || fits(Tool.HOE, item, null)) return Integer.MAX_VALUE;
        return isSapling(item.getType()) ? 64 : 0;
    }

    private static boolean isSapling(Material m) {
        return Tag.SAPLINGS.isTagged(m) || m == Material.MANGROVE_PROPAGULE;
    }

    @Override
    public List<Component> info(Colony c) {
        return List.of(Component.text(hut.level == 0 ? "The lumberjack starts once level 1 is built."
                : "Cuts natural trees within " + radius() + " blocks and replants them.", NamedTextColor.GRAY));
    }

    int radius() {
        return 16 + 8 * Math.max(0, hut.level - 1);
    }

    private int chopTicks() {
        return faster(Math.max(6, 26 - 4 * hut.level), "blockbreakspeedmultiplier");
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Lumberjack: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        switch (state) {
            case FIND -> {
                if (loaded()) { state = State.TO_HUT; return; } // e.g. after a restart mid-trip
                if (!w.onGround()) return; // vanilla can't plan a walk mid-air; that is not an unreachable tree
                if (!getTools(w)) return;
                if (!findTree()) {
                    unreachable.clear(); // nothing left: give skipped trees another go after the pause
                    note = "No natural trees within " + radius() + " blocks.";
                    w.setStatus("Lumberjack: idle");
                    w.wander(hut.block.getLocation().toCenterLocation());
                    cooldown = 200;
                    return;
                }
                if (w.walkBeside(base) == null) {
                    unreachable.add(base);
                    cooldown = 20;
                    return;
                }
                note = null;
                w.setStatus("Lumberjack: working");
                state = State.TO_TREE;
            }
            case TO_TREE -> {
                if (w.location().distance(base.getLocation().toCenterLocation()) > 4) { // the walk didn't get there
                    unreachable.add(base);
                    state = State.FIND;
                    return;
                }
                w.setStatus("Lumberjack: working");
                state = State.CHOP;
            }
            case CHOP -> {
                if (!hasTool(onLeaves ? Tool.HOE : Tool.AXE, null)) { state = State.REFETCH; return; } // it broke
                if (w.location().distance(base.getLocation().toCenterLocation()) > 6) { w.goBeside(base); return; } // back from a meal
                if (!onLeaves) {
                    while (next < logs.size() && !Tag.LOGS.isTagged(logs.get(next).getType())) next++; // gone already
                    if (next < logs.size()) {
                        chop(logs.get(next++), Tool.AXE, w);
                        act(w);
                        cooldown = chopTicks();
                        return;
                    }
                    onLeaves = true;
                    next = 0;
                }
                int cleared = 0;
                while (next < leaves.size() && cleared < LEAVES_PER_SWING) {
                    Block leaf = leaves.get(next++);
                    if (!Tag.LEAVES.isTagged(leaf.getType())) continue; // decayed or gone already
                    chop(leaf, Tool.HOE, w);
                    cleared++;
                }
                if (cleared > 0) {
                    act(w);
                    cooldown = chopTicks() / 2;
                    return;
                }
                w.setStatus("Lumberjack: working");
                // none dropped: from the hut chest (the mod's checkAndTransferFromHut); not at all with replanting off
                state = !hut.on(Settings.REPLANT) || replant() ? State.TO_HUT : State.SAPLINGS;
            }
            case SAPLINGS -> {
                if (!atHut(w)) { goToHut(w); return; }
                deliver(); // the wood first
                Material sapling = saplingFor(logType);
                int got = sapling == null ? 0 : fetch(sapling, bareSpotBlocks().size());
                if (got == 0) { // note says which sapling; the tree's spot stays bare
                    note = "No " + (sapling == null ? "sapling" : name(sapling)) + " in the hut chest to replant the last tree.";
                    state = State.FIND;
                    return;
                }
                state = State.REPLANT;
            }
            case REPLANT -> {
                if (w.location().distance(base.getLocation().toCenterLocation()) > 4) { w.goBeside(base); return; }
                replant();
                act(w);
                state = State.TO_HUT; // what it didn't plant goes back
            }
            case TO_HUT -> {
                if (!atHut(w)) { goToHut(w); return; }
                if (!deliver()) {
                    note = "The hut chest is full.";
                    w.setStatus("Lumberjack: idle");
                    cooldown = 100;
                    return;
                }
                w.setStatus("Lumberjack: working");
                state = State.FIND;
            }
            case REFETCH -> { // a tool broke mid-tree: get another, then finish the same tree
                if (atHut(w)) deliver();
                if (!getTools(w)) return;
                state = w.walkBeside(base) == null ? State.FIND : State.TO_TREE;
            }
        }
    }

    /** Makes sure it holds an axe (logs) and a hoe (leaves), taking them from the hut chest. @return true if so. */
    private boolean getTools(Colonist w) {
        if (hasTool(Tool.AXE, null) && hasTool(Tool.HOE, null)) return true;
        if (!atHut(w)) { goToHut(w); return false; }
        for (Tool t : new Tool[]{Tool.AXE, Tool.HOE}) if (!hasTool(t, null)) fetchTool(t, null);
        List<String> missing = new ArrayList<>();
        for (Tool t : new Tool[]{Tool.AXE, Tool.HOE}) if (!hasTool(t, null)) missing.add(toolLabel(t));
        if (missing.isEmpty()) return true;
        note = "Needs " + String.join(" and ", missing) + " in the hut chest.";
        w.setStatus("Lumberjack: idle");
        cooldown = 100;
        return false;
    }

    /** Breaks a log or leaf block with the tool, keeping its drops (leaves roll their usual saplings, sticks, apples). */
    private void chop(Block block, Tool tool, Colonist w) {
        gather(breakWith(tool, block, w), w);
        var data = block.getBlockData();
        var sounds = data.getSoundGroup();
        block.getWorld().playSound(block.getLocation(), sounds.getBreakSound(), SoundCategory.BLOCKS, sounds.getVolume(), sounds.getPitch());
        block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().toCenterLocation(), 10, 0.3, 0.3, 0.3, data);
        block.setType(Material.AIR, false);
    }

    /**
     * Plants the saplings it carries on every spot the trunk stood on soil (2x2 trees need 4). @return false if a spot
     * stayed bare for want of a sapling.
     */
    private boolean replant() {
        Material sapling = saplingFor(logType);
        if (sapling == null) return true;
        boolean all = true;
        for (Block log : bareSpotBlocks()) {
            if (use(sapling, 1) == 0) { all = false; break; }
            log.setType(sapling);
        }
        return all;
    }

    /** Where the trunk stood on soil and nothing grows yet. */
    private List<Block> bareSpotBlocks() {
        return logs.stream().filter(b -> b.isEmpty() && Tag.DIRT.isTagged(b.getRelative(0, -1, 0).getType())).toList();
    }

    private static Material saplingFor(Material log) {
        if (log == Material.MANGROVE_LOG) return Material.MANGROVE_PROPAGULE;
        return Material.matchMaterial(log.name().replace("_LOG", "_SAPLING"));
    }

    /** Picks the nearest natural tree in range that is not known to be unreachable. */
    private boolean findTree() {
        Block hutBlock = hut.block;
        var world = hutBlock.getWorld();
        int r = radius();
        Set<Block> bases = new LinkedHashSet<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            // The top block ignoring leaves is a log for every column with a tree trunk in the open.
            Block top = world.getHighestBlockAt(hutBlock.getX() + dx, hutBlock.getZ() + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!Tag.LOGS.isTagged(top.getType())) continue;
            Block b = top;
            while (Tag.LOGS.isTagged(b.getRelative(0, -1, 0).getType())) b = b.getRelative(0, -1, 0);
            if (Tag.DIRT.isTagged(b.getRelative(0, -1, 0).getType()) && !unreachable.contains(b)) bases.add(b);
        }
        List<Block> sorted = new ArrayList<>(bases);
        sorted.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(hutBlock.getLocation())));
        for (Block b : sorted) {
            List<Block> tree = tree(b);
            if (tree == null) continue;
            base = b;
            logs = tree;
            leaves = leavesOf(tree);
            next = 0;
            onLeaves = false;
            logType = b.getType();
            return true;
        }
        return false;
    }

    /** Natural leaves connected to the tree's logs, within a few blocks of them (not the neighbours' canopy). */
    private static List<Block> leavesOf(List<Block> logs) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Block l : logs) {
            minX = Math.min(minX, l.getX()); maxX = Math.max(maxX, l.getX());
            minZ = Math.min(minZ, l.getZ()); maxZ = Math.max(maxZ, l.getZ());
            maxY = Math.max(maxY, l.getY());
        }
        int lowY = logs.getFirst().getY();
        Set<Block> seen = new LinkedHashSet<>();
        ArrayDeque<Block> todo = new ArrayDeque<>();
        for (Block l : logs) for (BlockFace f : FACES) todo.add(l.getRelative(f));
        while (!todo.isEmpty() && seen.size() < MAX_LEAVES) {
            Block b = todo.poll();
            if (seen.contains(b) || !(b.getBlockData() instanceof Leaves leaf) || leaf.isPersistent()) continue;
            if (b.getX() < minX - LEAF_REACH || b.getX() > maxX + LEAF_REACH || b.getZ() < minZ - LEAF_REACH
                    || b.getZ() > maxZ + LEAF_REACH || b.getY() < lowY || b.getY() > maxY + LEAF_REACH) continue;
            seen.add(b);
            for (BlockFace f : FACES) todo.add(b.getRelative(f));
        }
        List<Block> list = new ArrayList<>(seen);
        list.sort(Comparator.comparingInt(Block::getY).reversed()); // top down, like a player clearing a canopy
        return list;
    }

    /** The connected logs from {@code base}, bottom up, or null if it isn't a natural tree. */
    private static List<Block> tree(Block base) {
        Set<Block> seen = new LinkedHashSet<>();
        ArrayDeque<Block> todo = new ArrayDeque<>(List.of(base));
        int leaves = 0;
        Set<Block> leafSeen = new HashSet<>();
        while (!todo.isEmpty()) {
            Block b = todo.poll();
            if (!seen.add(b)) continue;
            if (seen.size() > MAX_TREE_LOGS) return null;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Block n = b.getRelative(dx, dy, dz);
                if (Tag.LOGS.isTagged(n.getType())) { if (!seen.contains(n)) todo.add(n); }
                else if (n.getBlockData() instanceof Leaves l && !l.isPersistent() && leafSeen.add(n)) leaves++;
            }
        }
        if (leaves < MIN_LEAVES) return null;
        List<Block> list = new ArrayList<>(seen);
        list.sort(Comparator.comparingInt(Block::getY));
        return list;
    }
}
