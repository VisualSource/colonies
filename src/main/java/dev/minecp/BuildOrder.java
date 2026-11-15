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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        /** The tool that turns the block already there into this one in place (shovel: path, hoe: farmland), or null. */
        Tool convertWith() {
            Material want = data.getMaterial(), have = block.getType();
            if (want == Material.DIRT_PATH && FLATTENS.contains(have)) return Tool.SHOVEL;
            if (want == Material.FARMLAND && TILLS.contains(have)) return Tool.HOE;
            return null;
        }
        /** The item this step uses up, or null if it is free. */
        Material cost() {
            if (isClear() || data.getMaterial() == Material.WATER || data.getMaterial() == Material.LAVA) return null;
            if (convertWith() != null) return null;
            // Players can't get path or farmland items without Silk Touch: lay dirt, flattened or tilled as it goes down.
            if (data.getMaterial() == Material.DIRT_PATH || data.getMaterial() == Material.FARMLAND) return Material.DIRT;
            if (twoTall(data) && ((Bisected) data).getHalf() == Bisected.Half.TOP) return null; // paid by the bottom half
            if (data instanceof Bed b && b.getPart() == Bed.Part.HEAD) return null;
            Material m = data.getPlacementMaterial();
            return m.isItem() && !m.isAir() ? m : null;
        }
    }

    /** What a shovel flattens into a path and a hoe tills into farmland, as in vanilla. */
    private static final Set<Material> FLATTENS = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.PODZOL,
            Material.COARSE_DIRT, Material.MYCELIUM, Material.ROOTED_DIRT);
    private static final Set<Material> TILLS = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH);

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
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (Step s : steps) {
            byBlock.put(s.block, s);
            x0 = Math.min(x0, s.block.getX()); x1 = Math.max(x1, s.block.getX());
            z0 = Math.min(z0, s.block.getZ()); z1 = Math.max(z1, s.block.getZ());
        }
        minX = x0; maxX = x1; minZ = z0; maxZ = z1;
    }

    final int minX, maxX, minZ, maxZ; // the structure's footprint

    /** Ground spots in a ring 1-2 blocks outside the footprint, where a builder stands to work (like the mod's builders). */
    List<Block> groundAround() {
        var world = origin.getWorld();
        List<Block> spots = new ArrayList<>();
        for (int x = minX - 2; x <= maxX + 2; x++) for (int z = minZ - 2; z <= maxZ + 2; z++) {
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
            Block b = world.getHighestBlockAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES).getRelative(BlockFace.UP);
            if (Colonist.canStandIn(b)) spots.add(b);
        }
        return spots;
    }

    /** The other half of a door, tall plant or bed, which should be placed together with this one. */
    Step partner(Step step) {
        if (step.data instanceof Bed bed)
            return byBlock.get(step.block.getRelative(bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace()));
        if (twoTall(step.data))
            return byBlock.get(step.block.getRelative(((Bisected) step.data).getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN));
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
        var s = load(structure);
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
        BuildOrder order = new BuildOrder(structure, base.getLocation(), rotation, clears);
        for (var e : s.getEntities()) { // marker entities the converter put on MineColonies' scarecrows
            if (!e.getScoreboardTags().contains(FIELD_TAG)) continue;
            var l = e.getLocation(); // relative to the structure's corner
            BlockVector at = rotate(new BlockVector(l.getBlockX(), l.getBlockY(), l.getBlockZ()), rotation);
            order.fields.add(base.getRelative(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        }
        return order;
    }

    /** Tag of the marker entities on a structure's field blocks, written by tools/convert_blueprints.py. */
    static final String FIELD_TAG = "minecp_field";
    /** Where the structure has a field block (a MineColonies scarecrow), to register as a field once built. */
    final List<Block> fields = new ArrayList<>();

    static boolean exists(String structure) {
        return load(structure) != null;
    }

    private static org.bukkit.structure.Structure load(String structure) {
        // Names without a namespace are ours: "house" means minecp:house (structure block name "minecp:house").
        NamespacedKey key = NamespacedKey.fromString(structure.contains(":") ? structure : "minecp:" + structure);
        return key == null ? null : Bukkit.getStructureManager().loadStructure(key);
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
        if (cursor >= steps.size()) return null;
        // A bed head or door top can come first in the order; hand out the half that costs the item, so both go down together.
        Step s = steps.get(cursor), other = partner(s);
        return other != null && s.cost() == null && other.cost() != null && !other.done() && !keep.test(other.block) ? other : s;
    }

    /** Steps from the current one on, for planning what materials to carry. */
    List<Step> upcoming() {
        return steps.subList(cursor, steps.size());
    }
}
