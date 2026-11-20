package dev.minecp.colony;

import dev.minecp.job.Builder;
import dev.minecp.job.Cook;
import dev.minecp.job.Courier;
import dev.minecp.job.CraftRules;
import dev.minecp.job.Crafter;
import dev.minecp.job.Farmer;
import dev.minecp.job.Guard;
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
                () -> new RecipeChoice.MaterialChoice(Material.APPLE), "Cook", (h, c) -> new Cook(h, c), false),
        WAREHOUSE("Warehouse", Material.CHEST, "craftsmanship/storage/warehouse", "warehouse", "The colony's store, which couriers work from",
                () -> new RecipeChoice.MaterialChoice(Material.CHEST), true),
        COURIER("Courier's Hut", Material.CHEST, "craftsmanship/storage/deliveryman", "courier", "A courier carries goods between the warehouse and huts",
                () -> new RecipeChoice.MaterialChoice(Material.LEATHER_BOOTS), "Courier", Courier::new, false),
        MINER("Miner's Hut", Material.CHEST, "fundamentals/miner", "miner", "A citizen digs a mine below the hut",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_PICKAXE), "Miner", Miner::new, false),
        // The crafters: middle rows from the mod's recipes.
        SAWMILL("Sawmill", Material.CHEST, "craftsmanship/carpentry/sawmill", "sawmill", "A carpenter crafts the wooden things the colony needs",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), "Carpenter",
                (h, c) -> new Crafter(h, CraftRules.SAWMILL, "things made mostly of wood"), false),
        STONEMASON("Stonemason's Hut", Material.CHEST, "craftsmanship/masonry/stonemason", "stonemason", "A stonemason crafts stone blocks, stairs, slabs and walls",
                () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), "Stonemason",
                (h, c) -> new Crafter(h, CraftRules.STONEMASON, "stone and brick blocks, stairs, slabs and walls"), false),
        STONE_SMELTERY("Stone Smeltery", Material.CHEST, "craftsmanship/masonry/stonesmeltery", "stonesmeltery", "A stone smelter fires stone, bricks and terracotta",
                () -> new RecipeChoice.MaterialChoice(Material.FURNACE), () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), "Stone Smelter",
                (h, c) -> new Crafter(h, c, CraftRules.STONE_SMELTERY, "stone, smooth stone, bricks, terracotta and charcoal in a furnace", true, false), false),
        BLACKSMITH("Blacksmith's Hut", Material.CHEST, "craftsmanship/metallurgy/blacksmith", "blacksmith", "A blacksmith makes tools, weapons and armor",
                () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), "Blacksmith",
                (h, c) -> new Crafter(h, CraftRules.BLACKSMITH, "tools, weapons, armor and things of metal (nothing with leather)"), false),
        SMELTERY("Smeltery", Material.CHEST, "craftsmanship/metallurgy/smeltery", "smeltery", "A smelter turns the miner's ores into ingots",
                () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), null, "Smelter",
                (h, c) -> new Crafter(h, c, CraftRules.SMELTERY, "ingots from ores", true, true), false),
        MECHANIC("Mechanic's Hut", Material.CHEST, "craftsmanship/metallurgy/mechanic", "mechanic", "A mechanic makes torches, lanterns, rails and redstone things",
                () -> new RecipeChoice.MaterialChoice(Material.REDSTONE_BLOCK), null, "Mechanic",
                (h, c) -> new Crafter(h, CraftRules.MECHANIC, "torches, lanterns, rails, redstone parts and storage blocks"), false),
        // Houses 4 citizens from level 1 (the mod's TavernLivingBuildingModule) and hosts visitors (Visitors); the mod's recipe: a barrel
        TAVERN("Tavern", Material.CHEST, "fundamentals/tavern", "tavern", "Houses 4 citizens; visitors stay here and can be recruited",
                () -> new RecipeChoice.MaterialChoice(Material.BARREL), false),
        BAKER("Bakery", Material.CHEST, "craftsmanship/luxury/baker", "baker", "A baker bakes bread, cookies, pumpkin pie and cake",
                () -> new RecipeChoice.MaterialChoice(Material.WHEAT), "Baker", (h, c) -> baker(h), false),
        GUARD_TOWER("Guard Tower", Material.CHEST, "military/guardtower", "guardtower", "A knight patrols the colony and fights monsters",
                () -> new RecipeChoice.MaterialChoice(Material.BOW), "Knight", Guard::new, false),
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
        public final Supplier<RecipeChoice> sides; // either side of the centre in its recipe; null: planks

        Type(String title, Material block, String path, String command, String lore, Supplier<RecipeChoice> centre,
             String worker, BiFunction<Hut, Colony, Job> job, boolean unique) {
            this(title, block, path, command, lore, centre, null, worker, job, unique);
        }

        Type(String title, Material block, String path, String command, String lore, Supplier<RecipeChoice> centre,
             Supplier<RecipeChoice> sides, String worker, BiFunction<Hut, Colony, Job> job, boolean unique) {
            this.sides = sides;
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

        /**
         * The baker knows the mod's own recipes for vanilla bread (level 1), cookies (2), pumpkin pie (3) and cake (4), as
         * the vanilla crafting recipes: the mod's doughs and batters, baked in a furnace, are its own items.
         */
        private static Job baker(Hut h) {
            Crafter b = new Crafter(h, CraftRules.BAKER, "bread, cookies, pumpkin pie, cake, and other things made from wheat");
            b.builtIn.put(org.bukkit.NamespacedKey.minecraft("bread"), 1);
            b.builtIn.put(org.bukkit.NamespacedKey.minecraft("cookie"), 2);
            b.builtIn.put(org.bukkit.NamespacedKey.minecraft("pumpkin_pie"), 3);
            b.builtIn.put(org.bukkit.NamespacedKey.minecraft("cake"), 4);
            return b;
        }

        /** Chunks claimed around the hut's chunk at this level (the mod's getClaimRadius). */
        int claimRadius(int level) {
            return switch (this) {
                case TOWN_HALL -> new int[]{0, 1, 1, 2, 3, 5}[level];
                case GUARD_TOWER -> new int[]{0, 2, 3, 3, 4, 5}[level]; // BuildingGuardTower.getClaimRadius
                case FIELD -> 0;
                default -> level == 0 ? 0 : level <= 3 ? 1 : 2;
            };
        }

        /** Its highest level: the mod's building max level (the tavern stops at 3). */
        public int maxLevel() {
            return this == TAVERN ? 3 : MAX_LEVEL;
        }

        /** Citizens it houses at {@code level}: a house one per level, the tavern 4 (the mod's LivingBuildingModule). */
        public int rooms(int level) {
            if (level < 1) return 0;
            return this == HOUSE ? level : this == TAVERN ? 4 : 0;
        }

        public boolean isBuilding() {
            return path != null;
        }

        /** Whether a removal work order can take it down; the Town Hall and Builder's Hut go only by breaking the hut block. */
        public boolean removable() {
            return isBuilding() && this != TOWN_HALL && this != BUILDER;
        }

        private static final java.util.Map<String, String> RESOLVED = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Its building at {@code level} in a style: the structure at its path, or else in a "default" folder beside it
         * (the cavern style keeps each hut in default, alternative and megahall folders).
         */
        public String structure(String style, int level) {
            String plain = style + "/" + path + level;
            return RESOLVED.computeIfAbsent(plain, k -> {
                if (dev.minecp.build.BuildOrder.exists(plain)) return plain;
                int slash = path.lastIndexOf('/');
                String nested = style + "/" + path.substring(0, slash + 1) + "default/" + path.substring(slash + 1) + level;
                return dev.minecp.build.BuildOrder.exists(nested) ? nested : plain;
            });
        }

        public static Type byCommand(String command) {
            for (Type t : values()) if (t.command.equals(command)) return t;
            return null;
        }
    }

    /**
     * Builder's work on a hut, waiting or under way: an upgrade to {@code level}, or ({@code remove}) taking down the
     * building at {@code level}, the one standing (the mod's removal work order).
     */
    public record WorkOrder(Hut hut, int level, boolean remove) {
        public WorkOrder(Hut hut, int level) {
            this(hut, level, false);
        }

        /** "Level 2" or "Taking it down", for the menus. */
        public String what() {
            return remove ? "Taking it down" : "Level " + level;
        }
    }

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
        return type.structure(style, level);
    }
}
