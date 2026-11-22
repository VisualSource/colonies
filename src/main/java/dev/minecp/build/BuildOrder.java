package dev.minecp.build;

import dev.minecp.citizen.Colonist;
import dev.minecp.job.Tool;
import dev.minecp.logistics.Rack;

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
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.BlockVector;

import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import org.bukkit.persistence.PersistentDataType;
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
public final class BuildOrder {
    /**
     * {@code fill}: a filler spot (barrier in the structure) that is only filled with dirt when not already solid.
     * {@code rack}: a chest standing in for the mod's storage rack, tagged with {@link #RACK_MARKER} when placed.
     */
    public record Step(Block block, BlockData data, boolean fill, boolean rack) {
        public boolean isClear() { return data.getMaterial().isAir(); }
        public boolean done() {
            if (fill) return block.getType().isSolid();
            // Compare type only: neighbour updates change states like fence connections after placing.
            return isClear() ? block.isEmpty() : block.getType() == data.getMaterial();
        }
        /** The tool that turns the block already there into this one in place (shovel: path, hoe: farmland), or null. */
        public Tool convertWith() {
            Material want = data.getMaterial(), have = block.getType();
            if (want == Material.DIRT_PATH && FLATTENS.contains(have)) return Tool.SHOVEL;
            if (want == Material.FARMLAND && TILLS.contains(have)) return Tool.HOE;
            return null;
        }
        /** The item this step uses up as an item (a Storage Rack for rack spots), or null if it is free. */
        public ItemStack item() {
            Material m = cost();
            return m == null ? null : rack ? Rack.item() : new ItemStack(m);
        }
        /** The item this step uses up, or null if it is free. */
        public Material cost() {
            if (isClear()) return null;
            // A fluid costs its bucket and the empty bucket comes back (Structurize's item for a fluid block, used "bucket-aware" by the mod's builder).
            if (data.getMaterial() == Material.WATER) return Material.WATER_BUCKET;
            if (data.getMaterial() == Material.LAVA) return Material.LAVA_BUCKET;
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

    public final String structure;
    public final Location origin;
    public final StructureRotation rotation;
    public final boolean mirror; // mirrored front to back (x flipped) before turning, as the mod's build tool mirrors
    public final List<Step> steps;
    private int cursor = 0;

    /** Tag on the chest that marks a structure's hut block, written by tools/convert_blueprints.py. */
    static final NamespacedKey HUT_MARKER = NamespacedKey.fromString("minecp:hut");
    private final Map<Block, Step> byBlock = new HashMap<>();
    /** Tag on other buildings' hut blocks inside a structure (a barracks' towers), with the mod's id: "barrackstower". */
    static final NamespacedKey SUBHUT_MARKER = NamespacedKey.fromString("minecp:subhut");
    /** Where the structure has other buildings' hut blocks, and which (their mod ids). */
    public final Map<Block, String> subHuts = new java.util.LinkedHashMap<>();
    /** Tag on the chests that stand in for the mod's storage racks (written by the converter), for a custom model later. */
    public static final NamespacedKey RACK_MARKER = NamespacedKey.fromString("minecp:rack");


    private BuildOrder(String structure, Location origin, StructureRotation rotation, boolean mirror, List<Step> steps) {
        this.structure = structure;
        this.origin = origin;
        this.rotation = rotation;
        this.mirror = mirror;
        this.steps = steps;
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (Step s : steps) {
            byBlock.put(s.block, s);
            x0 = Math.min(x0, s.block.getX()); x1 = Math.max(x1, s.block.getX());
            z0 = Math.min(z0, s.block.getZ()); z1 = Math.max(z1, s.block.getZ());
        }
        minX = x0; maxX = x1; minZ = z0; maxZ = z1;
    }

    public final int minX, maxX, minZ, maxZ; // the structure's footprint

    /** Ground spots in a ring 1-2 blocks outside the footprint, where a builder stands to work (like the mod's builders). */
    public List<Block> groundAround() {
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
    public Step partner(Step step) {
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
     *            moved so the marker lands on this block instead of using {@code origin}; null to always use origin
     * @param rotation null (with a hut): turned so the marker faces the way the hut block faces (a hut placed by hand)
     * @return null if no structure has that name.
     */
    public static BuildOrder create(String structure, Location origin, StructureRotation rotation, Block hut) {
        return create(structure, origin, rotation, false, hut);
    }

    public static BuildOrder create(String structure, Location origin, StructureRotation rotation, boolean mirror, Block hut) {
        var s = load(structure);
        if (s == null || s.getPalettes().isEmpty()) return null;
        var blocks = s.getPalettes().getFirst().getBlocks();
        Block base = origin.getBlock();
        if (hut != null) for (var state : blocks) {
            if (state instanceof TileState ts && ts.getPersistentDataContainer().has(HUT_MARKER)) {
                if (rotation == null && state.getBlockData() instanceof Directional marker && hut.getBlockData() instanceof Directional placed)
                    for (StructureRotation r : StructureRotation.values()) {
                        var turned = (Directional) marker.clone();
                        turned.rotate(r);
                        if (turned.getFacing() == placed.getFacing()) { rotation = r; break; }
                    }
                if (rotation == null) rotation = StructureRotation.NONE;
                BlockVector at = transform(state.getX(), state.getY(), state.getZ(), rotation, mirror);
                base = hut.getRelative(-at.getBlockX(), -at.getBlockY(), -at.getBlockZ());
                break;
            }
        }
        if (rotation == null) rotation = StructureRotation.NONE;
        List<Step> clears = new ArrayList<>(), places = new ArrayList<>();
        Map<Block, String> subHuts = new java.util.LinkedHashMap<>();
        for (var state : blocks) {
            BlockVector at = transform(state.getX(), state.getY(), state.getZ(), rotation, mirror);
            BlockData data = state.getBlockData().clone();
            if (mirror) data.mirror(Mirror.FRONT_BACK);
            data.rotate(rotation);
            Block block = base.getRelative(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            var world = block.getWorld();
            if (block.getY() < world.getMinHeight() || block.getY() >= world.getMaxHeight()) continue; // can't build there
            boolean fill = data.getMaterial() == Material.BARRIER;
            boolean rack = state instanceof TileState ts && ts.getPersistentDataContainer().has(RACK_MARKER);
            if (state instanceof TileState ts && ts.getPersistentDataContainer().has(SUBHUT_MARKER, PersistentDataType.STRING))
                subHuts.put(block, ts.getPersistentDataContainer().get(SUBHUT_MARKER, PersistentDataType.STRING));
            Step step = new Step(block, fill ? Material.DIRT.createBlockData() : data, fill, rack);
            (step.isClear() ? clears : places).add(step);
        }
        clears.sort(Comparator.comparingInt((Step st) -> -st.block.getY())); // top down
        // Bottom up, and blocks that hang on others (torches, doors, carpets) after the solid ones.
        places.sort(Comparator.comparing((Step st) -> !st.data.getMaterial().isSolid()).thenComparingInt(st -> st.block.getY()));
        clears.addAll(places);
        BuildOrder order = new BuildOrder(structure, base.getLocation(), rotation, mirror, clears);
        order.subHuts.putAll(subHuts);
        for (var e : s.getEntities()) { // marker entities the converter put on MineColonies' scarecrows and named graves
            var tags = e.getScoreboardTags();
            if (!tags.contains(FIELD_TAG) && !tags.contains(GRAVE_TAG) && !tags.contains(SIT_TAG)) continue;
            var l = e.getLocation(); // relative to the structure's corner
            BlockVector at = transform(l.getBlockX(), l.getBlockY(), l.getBlockZ(), rotation, mirror);
            Block block = base.getRelative(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            if (tags.contains(FIELD_TAG)) { order.fields.add(block); continue; }
            if (tags.contains(SIT_TAG)) { order.seats.add(block); continue; }
            var sign = (Directional) Material.OAK_WALL_SIGN.createBlockData(); // turns the grave's facing with the building
            for (String t : tags) if (t.startsWith("minecp_facing_"))
                try { sign.setFacing(org.bukkit.block.BlockFace.valueOf(t.substring(14).toUpperCase())); } catch (IllegalArgumentException ignored) {}
            if (mirror) sign.mirror(Mirror.FRONT_BACK);
            sign.rotate(rotation);
            order.graves.add(new GraveSpot(block, sign.getFacing()));
        }
        return order;
    }

    /**
     * Taking the building down (the mod's removal work order): clearing every block that is still the structure's own,
     * top down. Foundation filler and anything else that stands there now stays.
     */
    public BuildOrder removal() {
        List<Step> clears = new ArrayList<>();
        for (Step s : steps)
            if (!s.isClear() && !s.fill && s.block.getType() == s.data.getMaterial())
                clears.add(new Step(s.block, Material.AIR.createBlockData(), false, false));
        clears.sort(Comparator.comparingInt((Step st) -> -st.block.getY()));
        return new BuildOrder(structure, origin, rotation, mirror, clears);
    }

    /** Tag of the marker entities on a structure's field blocks, written by tools/convert_blueprints.py. */
    static final String FIELD_TAG = "minecp_field";
    /** Where the structure has a field block (a MineColonies scarecrow), to register as a field once built. */
    public final List<Block> fields = new ArrayList<>();
    /** Tag of the marker entities on a graveyard's burial spots (MineColonies' named graves), with minecp_facing_<facing>. */
    static final String GRAVE_TAG = "minecp_grave";
    /** A graveyard's burial spot and the way its grave faces. */
    public record GraveSpot(Block block, org.bukkit.block.BlockFace facing) {}
    /** Where the structure has a burial spot. */
    public final List<GraveSpot> graves = new ArrayList<>();

    /**
     * Places it at once with vanilla's structure placement, block entities and their contents included (the mod's
     * instant placement of its supplies); foundation filler becomes dirt where the ground wasn't solid, and keeps the
     * ground where it was.
     */
    void paste() {
        java.util.Map<Block, BlockData> ground = new java.util.HashMap<>();
        for (Step s : steps) if (s.fill && s.block.getType().isSolid()) ground.put(s.block, s.block.getBlockData());
        var template = load(structure);
        if (template == null) return;
        template.place(origin, false, rotation, mirror ? Mirror.FRONT_BACK : Mirror.NONE,
                0, 1f, new java.util.Random());
        for (Step s : steps) {
            if (s.fill) s.block.setBlockData(ground.getOrDefault(s.block, Material.DIRT.createBlockData()), false);
            if (s.rack) Rack.tag(s.block); // named for its inventory title and the optional pack's model, as the builder's are
        }
    }

    /** Lays {@code m} as the foundation filler instead of dirt (the builder's fill block setting). */
    public void fillWith(Material m) {
        steps.replaceAll(s -> s.fill ? new Step(s.block, m.createBlockData(), true, false) : s);
    }

    /** Tag of the marker entities on a blueprint's seats (its "sit" tag positions: the tavern's chairs). */
    static final String SIT_TAG = "minecp_sit";
    /** Where the structure has a seat. */
    public final List<Block> seats = new ArrayList<>();

    /** The box (minX, maxX+1, minZ, maxZ+1) a hut's building covers, placed on its hut block; null if it has none. */
    public static int[] footprint(String structure, Block hut, StructureRotation rotation, boolean mirror) {
        BuildOrder o = create(structure, hut.getLocation(), rotation, mirror, hut);
        return o == null ? null : o.footprint();
    }

    /** The box this order covers: minX, maxX+1, minZ, maxZ+1. */
    public int[] footprint() {
        return new int[]{minX, maxX + 1, minZ, maxZ + 1};
    }

    public static boolean exists(String structure) {
        return load(structure) != null;
    }

    private static org.bukkit.structure.Structure load(String structure) {
        // Names without a namespace are ours: "house" means minecp:house (structure block name "minecp:house").
        NamespacedKey key = NamespacedKey.fromString(structure.contains(":") ? structure : "minecp:" + structure);
        return key == null ? null : Bukkit.getStructureManager().loadStructure(key);
    }

    /** A structure position turned (and first mirrored, x flipped, like vanilla's FRONT_BACK) around the corner. */
    static BlockVector transform(int x, int y, int z, StructureRotation r, boolean mirror) {
        return rotate(new BlockVector(mirror ? -x : x, y, z), r);
    }

    /** Same rotation around the origin as vanilla structure placement. */
    public static BlockVector rotate(BlockVector v, StructureRotation r) {
        return switch (r) {
            case NONE -> v;
            case CLOCKWISE_90 -> new BlockVector(-v.getBlockZ(), v.getBlockY(), v.getBlockX());
            case CLOCKWISE_180 -> new BlockVector(-v.getBlockX(), v.getBlockY(), -v.getBlockZ());
            case COUNTERCLOCKWISE_90 -> new BlockVector(v.getBlockZ(), v.getBlockY(), -v.getBlockX());
        };
    }

    /** Rotation that makes the structure extend away from a player facing {@code facing}. */
    public static StructureRotation rotationFor(BlockFace facing) {
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
    public Step next(Predicate<Block> keep) {
        while (cursor < steps.size() && (steps.get(cursor).done() || keep.test(steps.get(cursor).block)
                || steps.get(cursor).block.getType().getHardness() < 0)) cursor++;
        if (cursor >= steps.size()) return null;
        // A bed head or door top can come first in the order; hand out the half that costs the item, so both go down together.
        Step s = steps.get(cursor), other = partner(s);
        return other != null && s.cost() == null && other.cost() != null && !other.done() && !keep.test(other.block) ? other : s;
    }

    /** What the next steps use up, by {@code item} (null: free), counted up to {@code limit} items: one trip's load. */
    public <K> Map<K, Integer> nextLoad(java.util.function.Function<Step, K> item, int limit) {
        Map<K, Integer> want = new java.util.LinkedHashMap<>();
        int total = 0;
        for (Step s : upcoming()) {
            K k = item.apply(s);
            if (k == null || s.done()) continue;
            want.merge(k, 1, Integer::sum);
            if (++total >= limit) break;
        }
        return want;
    }

    /** Steps from the current one on, for planning what materials to carry. */
    public List<Step> upcoming() {
        return steps.subList(cursor, steps.size());
    }
}
