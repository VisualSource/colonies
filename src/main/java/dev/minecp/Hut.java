package dev.minecp;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;

/** A colony building anchored on its hut block. Level 0 is just the block; level N means level N is built. */
final class Hut {
    static final int MAX_LEVEL = 5;

    enum Type {
        TOWN_HALL("Town Hall", Material.BELL, "fundamentals/townhall"),
        BUILDER("Builder's Hut", Material.CHEST, "fundamentals/builder"),
        HOUSE("House", Material.CHEST, "fundamentals/residence"),
        LUMBERJACK("Lumberjack's Hut", Material.CHEST, "fundamentals/lumberjack"),
        FARMER("Farmer's Hut", Material.CHEST, "agriculture/horticulture/farmer"),
        COOK("Cook's Hut", Material.CHEST, "fundamentals/cook"),
        WAREHOUSE("Warehouse", Material.CHEST, "craftsmanship/storage/warehouse"),
        COURIER("Courier's Hut", Material.CHEST, "craftsmanship/storage/deliveryman"),
        MINER("Miner's Hut", Material.CHEST, "fundamentals/miner"),
        FIELD("Field", Material.HAY_BLOCK, null); // MineColonies' scarecrow: marks a farm plot, never built or upgraded

        final String title;
        final Material block;
        final String path; // structure path inside a style pack, + level; null for blocks that aren't buildings

        boolean isBuilding() {
            return path != null;
        }

        Type(String title, Material block, String path) {
            this.title = title;
            this.block = block;
            this.path = path;
        }
    }

    /** An upgrade of a hut to a level, waiting for or being built by the builder. */
    record WorkOrder(Hut hut, int level) {}

    final Type type;
    final Block block;
    int level;
    // How the building is turned and mirrored, chosen with the build tool; rotation null: from the hut block's facing.
    StructureRotation rotation;
    boolean mirror;
    int[] footprint;          // minX, maxX+1, minZ, maxZ+1 of the building at footprintLevel, for the particle tape
    int footprintLevel = -1;
    final java.util.List<org.bukkit.entity.BlockDisplay> tape = new java.util.ArrayList<>(); // shown while waiting for the builder
    // Fields only, set in the field's menu: the seed to plant (null: none chosen) and how far the soil reaches.
    Material seed;
    final int[] extent = {4, 4, 4, 4}; // north, east, south, west; 0..Farmer.MAX_EXTENT
    int stage;            // what the farmer does here next: till, plant, harvest (Farmer.Kind order)
    int workedDay = -1;   // colony day the farmer last worked it (one action a day, as in the mod)

    Hut(Type type, Block block, int level) {
        this.type = type;
        this.block = block;
        this.level = level;
    }

    String structure(String style, int level) {
        return style + "/" + type.path + level;
    }
}
