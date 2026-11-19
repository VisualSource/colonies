package dev.minecp.colony;

import dev.minecp.job.Builder;
import dev.minecp.job.Cook;
import dev.minecp.job.Courier;
import dev.minecp.job.Farmer;
import dev.minecp.job.Job;
import dev.minecp.job.Lumberjack;
import dev.minecp.job.Miner;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.inventory.RecipeChoice;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/** A colony building anchored on its hut block. Level 0 is just the block; level N means level N is built. */
public final class Hut {
    public static final int MAX_LEVEL = 5;

    /**
     * Everything about one kind of hut. Adding a hut is a line here plus, for a worker hut, its {@link Job} class
     * (and its name in plugin.yml's usage line).
     */
    public enum Type {
        TOWN_HALL("Town Hall", Material.BELL, "fundamentals/townhall", "townhall", "Place to found a colony",
                null, true), // its recipe has the Build Tool in the middle (Items)
        BUILDER("Builder's Hut", Material.CHEST, "fundamentals/builder", "builderhut", "Its chest holds building materials",
                () -> new RecipeChoice.MaterialChoice(Tag.WOODEN_DOORS), "Builder", (h, c) -> new Builder(h), true),
        HOUSE("House", Material.CHEST, "fundamentals/residence", "house", "Room for more citizens",
                () -> new RecipeChoice.MaterialChoice(Material.TORCH), false),
        LUMBERJACK("Lumberjack's Hut", Material.CHEST, "fundamentals/lumberjack", "lumberjack", "A citizen cuts and replants trees nearby",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), "Lumberjack", (h, c) -> new Lumberjack(h), false),
        FARMER("Farmer's Hut", Material.CHEST, "agriculture/horticulture/farmer", "farmer", "A citizen works the colony's fields",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_HOE), "Farmer", Farmer::new, false),
        COOK("Cook's Hut", Material.CHEST, "fundamentals/cook", "cook", "A restaurant: its cook cooks and serves food",
                () -> new RecipeChoice.MaterialChoice(Material.APPLE), "Cook", (h, c) -> new Cook(h), false),
        WAREHOUSE("Warehouse", Material.CHEST, "craftsmanship/storage/warehouse", "warehouse", "The colony's store, which couriers work from",
                () -> new RecipeChoice.MaterialChoice(Material.CHEST), true),
        COURIER("Courier's Hut", Material.CHEST, "craftsmanship/storage/deliveryman", "courier", "A courier carries goods between the warehouse and huts",
                () -> new RecipeChoice.MaterialChoice(Material.LEATHER_BOOTS), "Courier", Courier::new, false),
        MINER("Miner's Hut", Material.CHEST, "fundamentals/miner", "miner", "A citizen digs a mine below the hut",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_PICKAXE), "Miner", Miner::new, false),
        // MineColonies' scarecrow: marks a farm plot, never built or upgraded; its own recipe (Items)
        FIELD("Field", Material.HAY_BLOCK, null, "field", "Marks a farm plot; right-click it to pick the crop and size",
                null, false);

        public final String title;
        public final Material block;
        public final String path;     // structure path inside a style pack, + level; null for blocks that aren't buildings
        final String command;  // /colony <command> gives an admin its item
        public final String lore;     // on its item
        public final Supplier<RecipeChoice> centre; // the middle of its recipe (planks around, Build Tool on top); null: none or special
        public final String worker;   // the job's name ("Cook"), null for huts without a worker
        final BiFunction<Hut, Colony, Job> job; // makes its job; null for huts without a worker
        public final boolean unique;  // one per colony

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

        /** A hut without a worker. */
        Type(String title, Material block, String path, String command, String lore, Supplier<RecipeChoice> centre, boolean unique) {
            this(title, block, path, command, lore, centre, null, null, unique);
        }

        /** Chunks claimed around the hut's chunk at this level (the mod's getClaimRadius). */
        public int claimRadius(int level) {
            return switch (this) {
                case TOWN_HALL -> new int[]{0, 1, 1, 2, 3, 5}[level];
                case FIELD -> 0;
                default -> level == 0 ? 0 : level <= 3 ? 1 : 2;
            };
        }

        public boolean isBuilding() {
            return path != null;
        }

        public static Type byCommand(String command) {
            for (Type t : values()) if (t.command.equals(command)) return t;
            return null;
        }
    }

    /** An upgrade of a hut to a level, waiting for or being built by the builder. */
    public record WorkOrder(Hut hut, int level) {}

    public final Type type;
    public final Block block;
    public int level;
    // How the building is turned and mirrored, chosen with the build tool; rotation null: from the hut block's facing.
    public StructureRotation rotation;
    public boolean mirror;
    int[] footprint;          // minX, maxX+1, minZ, maxZ+1 of the building at footprintLevel, for the particle tape
    int footprintLevel = -1;
    final java.util.List<org.bukkit.entity.BlockDisplay> tape = new java.util.ArrayList<>(); // shown while waiting for the builder
    int[] builtBox;           // the box its building covers at builtBoxLevel, for claims (Colony.footprint)
    int builtBoxLevel = -1;
    java.util.List<Block> beds; // houses only: bed heads nearest first, from the last scan (Colony.bedOf)
    int bedsAt;
    // Fields only, set in the field's menu: the seed to plant (null: none chosen) and how far the soil reaches.
    public Material seed;
    public final int[] extent = {4, 4, 4, 4}; // north, east, south, west; 0..Farmer.MAX_EXTENT
    public int stage;            // what the farmer does here next: till, plant, harvest (Farmer.Kind order)
    public int workedDay = -1;   // colony day the farmer last worked it (one action a day, as in the mod)

    Hut(Type type, Block block, int level) {
        this.type = type;
        this.block = block;
        this.level = level;
    }

    String structure(String style, int level) {
        return style + "/" + type.path + level;
    }
}
