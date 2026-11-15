package dev.minecp;

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
final class Lumberjack extends Job {
    private static final int MIN_LEAVES = 4;
    private static final int MAX_TREE_LOGS = 200; // more connected logs than this is a build, not a tree
    private static final int MAX_LEAVES = 600;
    private static final int LEAF_REACH = 4;      // how far past the trunk a leaf still counts as this tree's
    private static final int LEAVES_PER_SWING = 4;
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private enum State { FIND, TO_TREE, CHOP, TO_HUT, REFETCH }

    private State state = State.FIND;
    private Block base;           // lowest log of the tree being cut
    private Material logType;     // its wood, for the sapling
    private List<Block> logs = List.of(); // the tree, bottom up
    private List<Block> leaves = List.of(); // its natural leaves, cleared after the logs for their saplings
    private int next;             // index into logs, then into leaves
    private boolean onLeaves;
    private final Set<Block> unreachable = new HashSet<>();
    private int cooldown = 0;

    Lumberjack(Hut hut) {
        super(hut);
    }

    int radius() {
        return 16 + 8 * Math.max(0, hut.level - 1);
    }

    private int chopTicks() {
        return Math.max(6, 26 - 4 * hut.level);
    }

    @Override
    void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Lumberjack: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        switch (state) {
            case FIND -> {
                if (!carried.isEmpty()) { state = State.TO_HUT; return; } // e.g. after a restart mid-trip
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
                if (!onLeaves) {
                    while (next < logs.size() && !Tag.LOGS.isTagged(logs.get(next).getType())) next++; // gone already
                    if (next < logs.size()) {
                        chop(logs.get(next++), Tool.AXE, w);
                        w.swing();
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
                    w.swing();
                    cooldown = chopTicks() / 2;
                    return;
                }
                replant();
                w.setStatus("Lumberjack: working");
                state = State.TO_HUT;
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
        for (Tool t : new Tool[]{Tool.AXE, Tool.HOE}) if (!hasTool(t, null)) missing.add(t.label);
        if (missing.isEmpty()) return true;
        note = "Needs " + String.join(" and ", missing) + " in the hut chest.";
        w.setStatus("Lumberjack: idle");
        cooldown = 100;
        return false;
    }

    /** Breaks a log or leaf block with the tool, keeping its drops (leaves roll their usual saplings, sticks, apples). */
    private void chop(Block block, Tool tool, Colonist w) {
        for (var drop : breakWith(tool, block, w)) carried.merge(drop.getType(), drop.getAmount(), Integer::sum);
        var data = block.getBlockData();
        var sounds = data.getSoundGroup();
        block.getWorld().playSound(block.getLocation(), sounds.getBreakSound(), SoundCategory.BLOCKS, sounds.getVolume(), sounds.getPitch());
        block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().toCenterLocation(), 10, 0.3, 0.3, 0.3, data);
        block.setType(Material.AIR, false);
    }

    /** Plants saplings gathered from the leaves on every spot the trunk stood on soil (2x2 trees need 4). */
    private void replant() {
        Material sapling = saplingFor(logType);
        if (sapling == null) return;
        for (Block log : logs) {
            if (!log.isEmpty() || !Tag.DIRT.isTagged(log.getRelative(0, -1, 0).getType())) continue;
            if (carried.getOrDefault(sapling, 0) <= 0) {
                note = "No " + sapling.name().toLowerCase().replace('_', ' ') + " dropped to replant the last tree.";
                return;
            }
            carried.merge(sapling, -1, Integer::sum);
            log.setType(sapling);
        }
        carried.values().removeIf(n -> n <= 0);
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
