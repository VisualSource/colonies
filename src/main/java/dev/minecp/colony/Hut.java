package dev.minecp.colony;

import dev.minecp.job.Builder;
import dev.minecp.job.Cook;
import dev.minecp.job.Courier;
import dev.minecp.job.CraftRules;
import dev.minecp.job.Crafter;
import dev.minecp.job.Farmer;
import dev.minecp.job.Fisherman;
import dev.minecp.job.Herder;
import dev.minecp.job.Guard;
import dev.minecp.job.Job;
import dev.minecp.job.Lumberjack;
import dev.minecp.job.Miner;
import dev.minecp.job.PostBox;
import dev.minecp.job.Researcher;
import dev.minecp.job.Stash;
import dev.minecp.job.Undertaker;

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
                () -> new RecipeChoice.MaterialChoice(Tag.WOODEN_DOORS), "Builder", Builder::new, true),
        HOUSE("House", Material.CHEST, "fundamentals/residence", "house", "Room for more citizens",
                () -> new RecipeChoice.MaterialChoice(Material.TORCH), false),
        LUMBERJACK("Lumberjack's Hut", Material.CHEST, "fundamentals/lumberjack", "lumberjack", "A citizen cuts and replants trees nearby",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), "Lumberjack", Lumberjack::new, false),
        FARMER("Farmer's Hut", Material.CHEST, "agriculture/horticulture/farmer", "farmer", "A citizen works the colony's fields",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_HOE), "Farmer", Farmer::new, false),
        COOK("Cook's Hut", Material.CHEST, "fundamentals/cook", "cook", "A restaurant: its cook cooks and serves food",
                () -> new RecipeChoice.MaterialChoice(Material.APPLE), "Cook", Cook::new, false),
        WAREHOUSE("Warehouse", Material.CHEST, "craftsmanship/storage/warehouse", "warehouse", "The colony's store, which couriers work from",
                () -> new RecipeChoice.MaterialChoice(Material.CHEST), true),
        COURIER("Courier's Hut", Material.CHEST, "craftsmanship/storage/deliveryman", "courier", "A courier carries goods between the warehouse and huts",
                () -> new RecipeChoice.MaterialChoice(Material.LEATHER_BOOTS), "Courier", Courier::new, false),
        MINER("Miner's Hut", Material.CHEST, "fundamentals/miner", "miner", "A citizen digs a mine below the hut",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_PICKAXE), "Miner", Miner::new, false),
        // The crafters: middle rows from the mod's recipes.
        SAWMILL("Sawmill", Material.CHEST, "craftsmanship/carpentry/sawmill", "sawmill", "A carpenter crafts the wooden things the colony needs",
                () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), () -> new RecipeChoice.MaterialChoice(Material.WOODEN_AXE), "Carpenter",
                (h, c) -> new Crafter(h, c, CraftRules.SAWMILL, "things made mostly of wood"), false),
        STONEMASON("Stonemason's Hut", Material.CHEST, "craftsmanship/masonry/stonemason", "stonemason", "A stonemason crafts stone blocks, stairs, slabs and walls",
                () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), "Stonemason",
                (h, c) -> new Crafter(h, c, CraftRules.STONEMASON, "stone and brick blocks, stairs, slabs and walls"), false),
        STONE_SMELTERY("Stone Smeltery", Material.CHEST, "craftsmanship/masonry/stonesmeltery", "stonesmeltery", "A stone smelter fires stone, bricks and terracotta",
                () -> new RecipeChoice.MaterialChoice(Material.FURNACE), () -> new RecipeChoice.MaterialChoice(Material.STONE_BRICKS), "Stone Smelter",
                (h, c) -> new Crafter(h, c, CraftRules.STONE_SMELTERY, "stone, smooth stone, bricks, terracotta and charcoal in a furnace", Crafter.Kind.FURNACE), false),
        BLACKSMITH("Blacksmith's Hut", Material.CHEST, "craftsmanship/metallurgy/blacksmith", "blacksmith", "A blacksmith makes tools, weapons and armor",
                () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), "Blacksmith",
                (h, c) -> new Crafter(h, c, CraftRules.BLACKSMITH, "tools, weapons, armor and things of metal (nothing with leather)"), false),
        SMELTERY("Smeltery", Material.CHEST, "craftsmanship/metallurgy/smeltery", "smeltery", "A smelter turns the miner's ores into ingots",
                () -> new RecipeChoice.MaterialChoice(Material.IRON_INGOT), null, "Smelter",
                (h, c) -> new Crafter(h, c, CraftRules.SMELTERY, "ingots from ores", Crafter.Kind.ORES), false),
        MECHANIC("Mechanic's Hut", Material.CHEST, "craftsmanship/metallurgy/mechanic", "mechanic", "A mechanic makes torches, lanterns, rails and redstone things",
                () -> new RecipeChoice.MaterialChoice(Material.REDSTONE_BLOCK), null, "Mechanic",
                (h, c) -> new Crafter(h, c, CraftRules.MECHANIC, "torches, lanterns, rails, redstone parts and storage blocks"), false),
        // Unlocked by research; the mod's recipes: planks, the Build Tool on top, and string, a red dye or glass in the middle
        FLETCHER("Fletcher's Hut", Material.CHEST, "craftsmanship/carpentry/fletcher", "fletcher", "A fletcher makes arrows, string, leather armor and wool things",
                () -> new RecipeChoice.MaterialChoice(Material.STRING), "Fletcher", Type::fletcher, false),
        DYER("Dyer's Hut", Material.CHEST, "craftsmanship/luxury/dyer", "dyer", "A dyer makes dyes and dyes things",
                () -> new RecipeChoice.MaterialChoice(Material.RED_DYE), "Dyer", Type::dyer, false),
        GLASSBLOWER("Glassblower's Hut", Material.CHEST, "craftsmanship/luxury/glassblower", "glassblower", "A glassblower smelts glass and makes glass things",
                () -> new RecipeChoice.MaterialChoice(Material.GLASS), "Glassblower",
                (h, c) -> new Crafter(h, c, CraftRules.GLASSBLOWER, "glass from sand, and things made of glass (no dyes)", Crafter.Kind.BOTH), false),
        // Houses 4 citizens from level 1 (the mod's TavernLivingBuildingModule) and hosts visitors (Visitors); the mod's recipe: a barrel
        TAVERN("Tavern", Material.CHEST, "fundamentals/tavern", "tavern", "Houses 4 citizens; visitors stay here and can be recruited",
                () -> new RecipeChoice.MaterialChoice(Material.BARREL), false),
        BAKER("Bakery", Material.CHEST, "craftsmanship/luxury/baker", "baker", "A baker bakes bread, cookies, pumpkin pie and cake",
                () -> new RecipeChoice.MaterialChoice(Material.WHEAT), "Baker", Type::baker, false),
        // Fisherman and herders: the mod's recipe middles (a rod, an egg, beef, shears, a porkchop, a rabbit)
        FISHERMAN("Fisherman's Hut", Material.CHEST, "agriculture/husbandry/fisherman", "fisherman", "A fisherman fishes in ponds nearby",
                () -> new RecipeChoice.MaterialChoice(Material.FISHING_ROD), "Fisherman", Fisherman::new, false),
        CHICKEN_HERDER("Chicken Farmer's Hut", Material.CHEST, "agriculture/husbandry/chickenherder", "chickenherder",
                "A chicken herder breeds and butchers the chickens in its building",
                () -> new RecipeChoice.MaterialChoice(Material.EGG), "Chicken Herder", (h, c) -> new Herder(h, c, Herder.Kind.CHICKEN), false),
        COWBOY("Cowhand's Hut", Material.CHEST, "agriculture/husbandry/cowboy", "cowboy", "A cowhand breeds, milks and butchers the cows in its building",
                () -> new RecipeChoice.MaterialChoice(Material.BEEF), "Cowhand", (h, c) -> new Herder(h, c, Herder.Kind.COW), false),
        SHEPHERD("Shepherd's Hut", Material.CHEST, "agriculture/husbandry/shepherd", "shepherd", "A shepherd breeds, shears and butchers the sheep in its building",
                () -> new RecipeChoice.MaterialChoice(Material.SHEARS), "Shepherd", (h, c) -> new Herder(h, c, Herder.Kind.SHEEP), false),
        SWINEHERD("Swineherd's Hut", Material.CHEST, "agriculture/husbandry/swineherder", "swineherder", "A swineherd breeds and butchers the pigs in its building",
                () -> new RecipeChoice.MaterialChoice(Material.PORKCHOP), "Swineherd", (h, c) -> new Herder(h, c, Herder.Kind.PIG), false),
        RABBIT_HUTCH("Rabbit Hutch", Material.CHEST, "agriculture/husbandry/rabbithutch", "rabbithutch", "A rabbit herder breeds and butchers the rabbits in its building",
                () -> new RecipeChoice.MaterialChoice(Material.RABBIT), "Rabbit Herder", (h, c) -> new Herder(h, c, Herder.Kind.RABBIT), false),
        // Unlocked by research in the mod (ours: no research); its recipe is stone around a bone
        GRAVEYARD("Graveyard", Material.CHEST, "mystic/graveyard", "graveyard", "An undertaker buries the colony's dead, and may bring one back",
                () -> new RecipeChoice.MaterialChoice(Material.BONE), () -> new RecipeChoice.MaterialChoice(Material.STONE), "Undertaker",
                Undertaker::new, false),
        GUARD_TOWER("Guard Tower", Material.CHEST, "military/guardtower", "guardtower", "A knight patrols the colony and fights monsters",
                () -> new RecipeChoice.MaterialChoice(Material.BOW), "Knight", Guard::new, false),
        // The mod's research building; its recipe is its own (books under the Build Tool: Items)
        UNIVERSITY("University", Material.CHEST, "education/university", "university", "A researcher studies here: research unlocks huts and improvements",
                null, "Researcher", Researcher::new, false),
        // MineColonies' scarecrow: marks a farm plot, never built or upgraded; its own recipe (Items)
        FIELD("Field", Material.HAY_BLOCK, null, "field", "Marks a farm plot; right-click it to pick the crop and size",
                null, false),
        // MineColonies' postbox and stash: blocks, never built; nobody works there. Recipes: a row of three chests, or
        // two with planks between; plain chests (the mod's chests tag), never huts or racks, which are chests too
        POST_BOX("Postbox", Material.CHEST, null, "postbox", "Order items from the warehouse; couriers bring them here",
                Type::plainChests, Type::plainChests, null, PostBox::new, false),
        STASH("Stash", Material.CHEST, null, "stash", "Couriers take what you put in here to the warehouse",
                () -> new RecipeChoice.MaterialChoice(Tag.PLANKS), Type::plainChests, null, Stash::new, false);

        private static RecipeChoice plainChests() {
            return RecipeChoice.exactChoice(new org.bukkit.inventory.ItemStack(Material.CHEST), new org.bukkit.inventory.ItemStack(Material.TRAPPED_CHEST));
        }

        public final String title;
        public final Material block;
        public final String path;     // structure path inside a style pack, + level; null for blocks that aren't buildings
        final String command;  // /colony <command> gives an admin its item
        public final String lore;     // on its item
        public final Supplier<RecipeChoice> centre; // the middle of its recipe (planks around, Build Tool on top); null: none or special
        public final String worker;   // the job's name ("Cook"), null for huts without a worker
        final BiFunction<Hut, Colony, Job> job; // makes its job; null for huts without one (the postbox and stash have one but no worker)
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
        private static Job baker(Hut h, Colony c) {
            Crafter b = new Crafter(h, c, CraftRules.BAKER, "bread, cookies, pumpkin pie, cake, and other things made from wheat");
            vanilla(b, "bread", 1);
            vanilla(b, "cookie", 2);
            vanilla(b, "pumpkin_pie", 3);
            vanilla(b, "cake", 4);
            return b;
        }

        /** A vanilla recipe a crafter knows from a hut level, unless a data pack removed it. */
        private static void vanilla(Crafter c, String recipe, int level) {
            var r = org.bukkit.Bukkit.getRecipe(org.bukkit.NamespacedKey.minecraft(recipe));
            if (r != null) c.builtIn.put(r, level);
        }

        /** The fletcher's built-in recipes (the mod's crafterrecipes/fletcher): flint from gravel, string from wool. */
        private static Job fletcher(Hut h, Colony c) {
            Crafter f = new Crafter(h, c, CraftRules.FLETCHER, "arrows, string, leather armor, and things made with string, wool, leather or leaves (no dyes)");
            f.builtIn.put(own("fletcher_flint", Material.FLINT, 1, java.util.Map.of(Material.GRAVEL, 3)), 1);
            f.builtIn.put(own("fletcher_string", Material.STRING, 4, java.util.Map.of(Material.WHITE_WOOL, 1)), 1);
            return f;
        }

        /**
         * The dyer's built-in recipes (the mod's crafterrecipes/dyer): red sand, black dye from charcoal (level 2), dark
         * prismarine (3); and white wool from dyed wool, which the mod's dyer knows too (as vanilla's recipe: any wool).
         */
        private static Job dyer(Hut h, Colony c) {
            Crafter d = new Crafter(h, c, CraftRules.DYER, "dyes, and dyed things (no concrete powder)", Crafter.Kind.BOTH);
            vanilla(d, "dye_white_wool", 1);
            d.builtIn.put(own("dyer_red_sand", Material.RED_SAND, 4, java.util.Map.of(Material.SAND, 4, Material.RED_DYE, 1)), 1);
            d.builtIn.put(own("dyer_black_dye", Material.BLACK_DYE, 1, java.util.Map.of(Material.CHARCOAL, 1)), 2);
            d.builtIn.put(own("dyer_dark_prismarine", Material.DARK_PRISMARINE, 4, java.util.Map.of(Material.PRISMARINE, 4, Material.BLACK_DYE, 1)), 3);
            return d;
        }

        /** One of the mod's own crafter recipes, never registered with the server (players can't use it): {@code count} x {@code result}. */
        private static org.bukkit.inventory.Recipe own(String id, Material result, int count, java.util.Map<Material, Integer> ingredients) {
            var r = new org.bukkit.inventory.ShapelessRecipe(new org.bukkit.NamespacedKey("minecp", id), new org.bukkit.inventory.ItemStack(result, count));
            ingredients.forEach((m, n) -> r.addIngredient(n, m));
            return r;
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

        /** A house or the tavern: citizens live there. */
        public boolean isHome() {
            return this == HOUSE || this == TAVERN;
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

        /** The mod's building id ("residence"), the last part of its structure path; null for blocks that aren't buildings. */
        public String modId() {
            return path == null ? null : path.substring(path.lastIndexOf('/') + 1);
        }

        /** The /colony subcommands that give a hut's item (admins). */
        public static java.util.List<String> commands() {
            return java.util.Arrays.stream(values()).map(t -> t.command).toList();
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

    /**
     * The version placed with the build tool (the mod's blueprint path), its path without the level, like
     * "fundamentals/altbuilder"; null: the usual one. Saved.
     */
    public String blueprint;

    /** Its hiring mode (Settings.hires): who works or lives here, chosen by the colony or by players. Saved. */
    public Settings.Hiring hiring = Settings.Hiring.DEFAULT;
    final java.util.Map<String, String> settings = new java.util.HashMap<>(); // changed settings only (Settings.of); saved

    /** A setting's value, its default if unchanged. */
    public String setting(String key) {
        String v = settings.get(key);
        return v != null ? v : Settings.initial(type, key);
    }

    public boolean on(String key) {
        return Boolean.parseBoolean(setting(key));
    }

    public int number(String key) {
        return Integer.parseInt(setting(key));
    }

    public Material block(String key) {
        return Material.valueOf(setting(key));
    }

    /** Sets a setting (checked against its kind by the menu). */
    public void set(String key, String value) {
        if (Settings.initial(type, key).equals(value)) settings.remove(key);
        else settings.put(key, value);
    }

    /** Its building at {@code level}: the version it was placed as, else (or where a style lacks that version) the usual one. */
    public String structure(String style, int level) {
        if (blueprint != null && dev.minecp.build.BuildOrder.exists(style + "/" + blueprint + level)) return style + "/" + blueprint + level;
        return type.structure(style, level);
    }
}
