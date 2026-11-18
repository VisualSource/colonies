package dev.minecp;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.RecipeChoice;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/** A colony building anchored on its hut block. Level 0 is just the block; level N means level N is built. */
final class Hut {
    static final int MAX_LEVEL = 5;

    /**
     * Everything about one kind of hut. Adding a hut is a line here plus, for a worker hut, its {@link Job} class
     * (and its name in plugin.yml's usage line).
     */
    enum Type {
        TOWN_HALL("Town Hall", Material.BELL, "fundamentals/townhall", "townhall", "Place to found a colony",
                null, null, null, true), // its recipe has the Colony Token in the middle (Items)
        BUILDER("Builder's Hut", Material.CHEST, "fundamentals/builder", "builderhut", "Its chest holds building materials",
                () -> new RecipeChoice.MaterialChoice(Tag.WOODEN_DOORS), "Builder", (h, c) -> new Builder(h), true),
        HOUSE("House", Material.CHEST, "fundamentals/residence", "house", "Room for more citizens",
                () -> new RecipeChoice.MaterialChoice(Material.TORCH), null, null, false),
        LUMBERJACK("Lumberjack's Hut", Material.CHEST, "fundamentals/lumberjack", "lumberjack", "A citizen cuts and replants trees nearby",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), "Lumberjack", (h, c) -> new Lumberjack(h), false),
        FARMER("Farmer's Hut", Material.CHEST, "agriculture/horticulture/farmer", "farmer", "A citizen works the colony's fields",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_HOE), "Farmer", Farmer::new, false),
        COOK("Cook's Hut", Material.CHEST, "fundamentals/cook", "cook", "A restaurant: its cook cooks and serves food",
                () -> new RecipeChoice.MaterialChoice(Material.APPLE), "Cook", (h, c) -> new Cook(h), false),
        WAREHOUSE("Warehouse", Material.CHEST, "craftsmanship/storage/warehouse", "warehouse", "The colony's store, which couriers work from",
                () -> new RecipeChoice.MaterialChoice(Material.CHEST), null, null, true),
        COURIER("Courier's Hut", Material.CHEST, "craftsmanship/storage/deliveryman", "courier", "A courier carries goods between the warehouse and huts",
                () -> new RecipeChoice.MaterialChoice(Material.LEATHER_BOOTS), "Courier", Courier::new, false),
        MINER("Miner's Hut", Material.CHEST, "fundamentals/miner", "miner", "A citizen digs a mine below the hut",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_PICKAXE), "Miner", Miner::new, false),
        // MineColonies' scarecrow: marks a farm plot, never built or upgraded; its own recipe (Items)
        FIELD("Field", Material.HAY_BLOCK, null, "field", "Marks a farm plot; right-click it to pick the crop and size",
                null, null, null, false);

        final String title;
        final Material block;
        final String path;     // structure path inside a style pack, + level; null for blocks that aren't buildings
        final String command;  // /colony <command> gives an admin its item
        final String lore;     // on its item
        final Supplier<RecipeChoice> centre; // the middle of its recipe (planks around, token on top); null: none or special
        final String worker;   // the job's name ("Cook"), null for huts without a worker
        final BiFunction<Hut, Colony, Job> job; // makes its job; null for huts without a worker
        final boolean unique;  // one per colony

        Type(String title, Material block, String path, String command, String lore, Supplier<RecipeChoice> centre,
             String worker, BiFunction<Hut, Colony, Job> job, boolean unique) {
            this.title = title;
            this.block = block;
            this.path = path;
            this.command = command;
            this.lore = lore;
            this.centre = centre;
            this.worker = worker;
            this.job = job;
            this.unique = unique;
        }

        boolean isBuilding() {
            return path != null;
        }

        static Type byCommand(String command) {
            for (Type t : values()) if (t.command.equals(command)) return t;
            return null;
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
    java.util.List<Block> beds; // houses only: bed heads nearest first, from the last scan (Colony.bedOf)
    int bedsAt;
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
