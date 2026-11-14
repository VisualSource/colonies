package dev.minecp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.BlockVector;

import org.bukkit.block.TileState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A structure (saved with a vanilla structure block) to be built block by block at a position.
 * Structure voids are left alone; barriers mean "fill with dirt unless already solid" (foundations).
 * Progress is not stored: steps whose block is already right are skipped, so a reloaded order resumes by itself.
 */
final class BuildOrder {
    /** {@code fill}: a filler spot (barrier in the structure) that is only filled with dirt when not already solid. */
    record Step(Block block, BlockData data, boolean fill) {
        boolean isClear() { return data.getMaterial().isAir(); }
        boolean done() {
            if (fill) return block.getType().isSolid();
            // Compare type only: neighbour updates change states like fence connections after placing.
            return isClear() ? block.isEmpty() : block.getType() == data.getMaterial();
        }
        /** The item this step uses up, or null if it is free. */
        Material cost() {
            if (isClear() || data.getMaterial() == Material.WATER || data.getMaterial() == Material.LAVA) return null;
            if (twoTall(data) && ((Bisected) data).getHalf() == Bisected.Half.TOP) return null; // paid by the bottom half
            if (data instanceof Bed b && b.getPart() == Bed.Part.HEAD) return null;
            Material m = data.getPlacementMaterial();
            return m.isItem() && !m.isAir() ? m : null;
        }
    }

    final String structure;
    final Location origin;
    final StructureRotation rotation;
    final List<Step> steps;
    private int cursor = 0;

    /** Tag on the chest that marks a structure's hut block, written by tools/convert_blueprints.py. */
    static final NamespacedKey HUT_MARKER = NamespacedKey.fromString("minecp:hut");
    private final Map<Block, Step> byBlock = new HashMap<>();

    private BuildOrder(String structure, Location origin, StructureRotation rotation, List<Step> steps) {
        this.structure = structure;
        this.origin = origin;
        this.rotation = rotation;
        this.steps = steps;
        for (Step s : steps) byBlock.put(s.block, s);
    }

    /** The other half of a door, tall plant or bed, which should be placed together with this one. */
    Step partner(Step step) {
        if (step.data instanceof Bed bed && bed.getPart() == Bed.Part.FOOT)
            return byBlock.get(step.block.getRelative(bed.getFacing()));
        if (twoTall(step.data) && ((Bisected) step.data).getHalf() == Bisected.Half.BOTTOM)
            return byBlock.get(step.block.getRelative(BlockFace.UP));
        return null;
    }

    /** Doors and tall plants. Stairs and trapdoors are also Bisected, but each half is a whole block. */
    private static boolean twoTall(BlockData data) {
        return data instanceof Bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor);
    }

    /**
     * @param origin where the structure's corner goes
     * @param hut if the structure has a hut marker (the converter tags MineColonies hut blocks), the structure is
     *            moved so the marker lands on this block and turned so the marker faces the way this block faces,
     *            instead of using {@code origin} and {@code rotation}; null to always use those
     * @return null if no structure has that name.
     */
    static BuildOrder create(String structure, Location origin, StructureRotation rotation, Block hut) {
        // Names without a namespace are ours: "house" means minecp:house (structure block name "minecp:house").
        NamespacedKey key = NamespacedKey.fromString(structure.contains(":") ? structure : "minecp:" + structure);
        var s = key == null ? null : Bukkit.getStructureManager().loadStructure(key);
        if (s == null || s.getPalettes().isEmpty()) return null;
        var blocks = s.getPalettes().getFirst().getBlocks();
        Block base = origin.getBlock();
        if (hut != null) for (var state : blocks) {
            if (state instanceof TileState ts && ts.getPersistentDataContainer().has(HUT_MARKER)) {
                if (state.getBlockData() instanceof Directional marker && hut.getBlockData() instanceof Directional placed)
                    for (StructureRotation r : StructureRotation.values()) {
                        var turned = (Directional) marker.clone();
                        turned.rotate(r);
                        if (turned.getFacing() == placed.getFacing()) { rotation = r; break; }
                    }
                BlockVector at = rotate(new BlockVector(state.getX(), state.getY(), state.getZ()), rotation);
                base = hut.getRelative(-at.getBlockX(), -at.getBlockY(), -at.getBlockZ());
                break;
            }
        }
        List<Step> clears = new ArrayList<>(), places = new ArrayList<>();
        for (var state : blocks) {
            BlockVector at = rotate(new BlockVector(state.getX(), state.getY(), state.getZ()), rotation);
            BlockData data = state.getBlockData().clone();
            data.rotate(rotation);
            Block block = base.getRelative(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            var world = block.getWorld();
            if (block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight()) continue; // can't build there
            boolean fill = data.getMaterial() == Material.BARRIER;
            Step step = new Step(block, fill ? Material.DIRT.createBlockData() : data, fill);
            (step.isClear() ? clears : places).add(step);
        }
        clears.sort(Comparator.comparingInt((Step st) -> -st.block.getY())); // top down
        // Bottom up, and blocks that hang on others (torches, doors, carpets) after the solid ones.
        places.sort(Comparator.comparing((Step st) -> !st.data.getMaterial().isSolid()).thenComparingInt(st -> st.block.getY()));
        clears.addAll(places);
        return new BuildOrder(structure, base.getLocation(), rotation, clears);
    }

    /** Same rotation around the origin as vanilla structure placement. */
    private static BlockVector rotate(BlockVector v, StructureRotation r) {
        return switch (r) {
            case NONE -> v;
            case CLOCKWISE_90 -> new BlockVector(-v.getBlockZ(), v.getBlockY(), v.getBlockX());
            case CLOCKWISE_180 -> new BlockVector(-v.getBlockX(), v.getBlockY(), -v.getBlockZ());
            case COUNTERCLOCKWISE_90 -> new BlockVector(v.getBlockZ(), v.getBlockY(), -v.getBlockX());
        };
    }

    /** Rotation that makes the structure extend away from a player facing {@code facing}. */
    static StructureRotation rotationFor(BlockFace facing) {
        return switch (facing) {
            case WEST -> StructureRotation.CLOCKWISE_90;
            case NORTH -> StructureRotation.CLOCKWISE_180;
            case EAST -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /**
     * @return the first step not yet done and not on a {@code keep} block, or null when the build is finished.
     * Unbreakable blocks (bedrock, barriers, portal frames) are always left alone.
     */
    Step next(Predicate<Block> keep) {
        while (cursor < steps.size() && (steps.get(cursor).done() || keep.test(steps.get(cursor).block)
                || steps.get(cursor).block.getType().getHardness() < 0)) cursor++;
        return cursor < steps.size() ? steps.get(cursor) : null;
    }

    /** Steps from the current one on, for planning what materials to carry. */
    List<Step> upcoming() {
        return steps.subList(cursor, steps.size());
    }
}
