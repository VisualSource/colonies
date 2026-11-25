package dev.minecp.colony;

import dev.minecp.research.Effects;

import java.util.List;
import java.util.stream.Stream;

/**
 * The mod's building settings (its SettingsModule, BuildingModules' *_SETTINGS) that MineCP has the behaviour for, with
 * the mod's defaults, and its hiring modes. Each hut keeps its own values (Hut.setting); the Town Hall's are the
 * colony's. Left out because their features don't exist here: the lumberjack's restricted area, defoliating and shears,
 * the builder's work order mode and building patterns, and the tavern's music.
 */
public final class Settings {
    /** The mod's HiringMode: DEFAULT follows the Town Hall's auto hiring (or auto housing for homes). */
    public enum Hiring {
        DEFAULT("Default"), AUTO("Automatic"), MANUAL("Manual"), LOCKED("Locked");

        public final String label;

        Hiring(String label) {
            this.label = label;
        }
    }

    public enum Kind { BOOL, INT, BLOCK, CHOICE }

    /** A setting: its save key, its label in the menu, its kind, the mod's default, for numbers their range (ours), and for a choice its options. */
    public record Setting(String key, String label, Kind kind, String initial, int min, int max, List<String> options) {
        Setting(String key, String label, Kind kind, String initial, int min, int max) {
            this(key, label, kind, initial, min, max, List.of());
        }
    }

    private static Setting bool(String key, String label) {
        return bool(key, label, true);
    }

    private static Setting bool(String key, String label, boolean initial) {
        return new Setting(key, label, Kind.BOOL, String.valueOf(initial), 0, 0);
    }

    private static Setting number(String key, String label, int initial, int min, int max) {
        return new Setting(key, label, Kind.INT, String.valueOf(initial), min, max);
    }

    private static Setting block(String key, String label, org.bukkit.Material initial) {
        return new Setting(key, label, Kind.BLOCK, initial.name(), 0, 0);
    }

    /** One of {@code options} (item names), the first by default. */
    private static Setting choice(String key, String label, org.bukkit.Material... options) {
        return new Setting(key, label, Kind.CHOICE, options[0].name(), 0, 0, java.util.Arrays.stream(options).map(Enum::name).toList());
    }

    // The Town Hall's (the colony's), all on by default as in the mod.
    // Keys are also the menu's input names, which allow only letters, digits and underscores.
    public static final String MOVE_IN = "move_in", AUTO_HIRING = "auto_hiring", AUTO_HOUSING = "auto_housing",
            TAPE = "construction_tape", ENTER_LEAVE = "enter_leave_messages";
    public static final String FILL_BLOCK = "fill_block", MAX_DEPTH = "max_depth", REPLANT = "replant", MIN_ORE = "min_ore",
            RESTRICT = "restrict", BREEDING = "breeding", SHEARING = "shearing", DYEING = "dyeing", PATROL_INTERVAL = "patrol_interval",
            MILKING = "milking_amount", MILKING_DAYS = "milking_days", STEWING = "stewing_amount",
            CRUSHER_MODE = "crusher_mode", DAILY_LIMIT = "daily_limit", GUARD_TYPE = "guard_type",
            MAKE_DIRT = "make_dirt", CLOSE_PORTAL = "close_portal", FERTILIZE = "fertilize",
            BEE_MODE = "beekeeper_mode", RECIPE_MODE = "recipe_mode", GROW_SUGAR = "grow_sugar_cane", GROW_CACTUS = "grow_cactus", GROW_BAMBOO = "grow_bamboo";
    static final String HIRE_TRAINEE = "hire_trainee";
    public static final String GUARD_TASK = "guard_task", PATROL_MODE = "patrol_mode", FOLLOW_MODE = "follow_mode", RETREAT = "retreat";
    /** The miner's max depth default, which means "no limit but the hut level's" (the mod's). */
    public static final int NO_MAX_DEPTH = -100;

    private static final Setting BREED = bool(BREEDING, "Breed animals"),
            HIRE_TRAINEES = bool(HIRE_TRAINEE, "Hire the best trainee of the Archery or Combat Academy first");
    /** A guard hut's, besides its guards' types. */
    private static final List<Setting> GUARDS = List.of(words(GUARD_TASK, "Task", "PATROL", "GUARD", "FOLLOW", "PATROL_MINE"),
            words(PATROL_MODE, "Patrol points (manual: set with the Guard Scepter)", "AUTO", "MANUAL"),
            words(FOLLOW_MODE, "Follow", "TIGHT", "LOOSE"), bool(RETREAT, "Retreat when badly hurt (with research)"), HIRE_TRAINEES);

    private static final List<Setting> TOWER = Stream.concat(Stream.of(guardType(0, "Guard type", "KNIGHT", "RANGER", "MARKSMAN", "HUSCARL")), GUARDS.stream()).toList(); // the mod's: druids only in barracks towers
    private static final List<Setting> BARRACKS_TOWER = Stream.concat(java.util.stream.IntStream.range(0, Hut.MAX_LEVEL)
            .mapToObj(i -> guardType(i, "Guard " + (i + 1) + " type (from level " + (i + 1) + ")", "KNIGHT", "RANGER", "DRUID", "HUSCARL", "MARKSMAN")), GUARDS.stream()).toList();

    private Settings() {}

    /** The guard type of a hut's {@code slot}th guard (from 0): knight by default. */
    public static String guardTypeKey(int slot) {
        return slot == 0 ? GUARD_TYPE : GUARD_TYPE + "_" + (slot + 1);
    }

    /** Whether players may pick this option: the guard types research unlocks, once it has (the mod's hiring modules). */
    public static boolean unlocked(String option, Colony c) {
        return switch (option) {
            case "MARKSMAN" -> c.research.effect(Effects.MARKSMAN) > 0;
            case "HUSCARL" -> c.research.effect(Effects.HUSCARL) > 0;
            case "BOTH" -> c.research.effect(Effects.HONEY_POT) > 0; // the mod's BeekeeperCollectionSetting
            case "MAX_STOCK" -> c.research.effect(Effects.RECIPE_MODE) > 0; // the mod's CrafterRecipeSetting
            default -> true;
        };
    }

    private static Setting guardType(int slot, String label, String... types) {
        return words(guardTypeKey(slot), label, types);
    }

    /** One of these words, the first by default. */
    private static Setting words(String key, String label, String... options) {
        return new Setting(key, label, Kind.CHOICE, options[0], 0, 0, List.of(options));
    }

    /** The mod's crafters' recipe mode (its CrafterRecipeSetting): the first recipe found, or the one whose main ingredient the warehouse has most of (research). */
    private static final Setting RECIPE = words(RECIPE_MODE, "Recipe to use when several make it (most in warehouse: research)", "PRIORITY", "MAX_STOCK");
    /** The huts with the mod's recipe mode (BuildingModules' crafter settings): crafters that can be taught, and the crusher's and nether mine's. */
    private static final java.util.Set<Hut.Type> RECIPE_HUTS = java.util.EnumSet.of(Hut.Type.SAWMILL, Hut.Type.STONEMASON, Hut.Type.STONE_SMELTERY,
            Hut.Type.BLACKSMITH, Hut.Type.MECHANIC, Hut.Type.GLASSBLOWER, Hut.Type.BAKER, Hut.Type.FLETCHER, Hut.Type.DYER, Hut.Type.CONCRETE_MIXER, Hut.Type.CRUSHER, Hut.Type.NETHER_WORKER);

    /** The settings a hut of this type has, in menu order. */
    public static List<Setting> of(Hut.Type t) {
        List<Setting> own = own(t);
        return RECIPE_HUTS.contains(t) ? Stream.concat(own.stream(), Stream.of(RECIPE)).toList() : own;
    }

    private static List<Setting> own(Hut.Type t) {
        return switch (t) {
            case TOWN_HALL -> List.of(
                    bool(MOVE_IN, "Citizens move in (new citizens, children, visitors)"),
                    bool(AUTO_HIRING, "Huts hire on their own"),
                    bool(AUTO_HOUSING, "Homes take in the homeless on their own"),
                    bool(TAPE, "Construction tape around build sites"),
                    bool(ENTER_LEAVE, "Title for visitors entering or leaving the colony"));
            case BUILDER -> List.of(block(FILL_BLOCK, "Foundation fill block", org.bukkit.Material.DIRT));
            case MINER -> List.of(block(FILL_BLOCK, "Fill block (holes, liquids, floors)", org.bukkit.Material.COBBLESTONE),
                    number(MAX_DEPTH, "Deepest y to dig to (" + NO_MAX_DEPTH + ": as deep as the hut level allows)", NO_MAX_DEPTH, NO_MAX_DEPTH, 320));
            case NETHER_WORKER -> List.of(bool(CLOSE_PORTAL, "Close the portal when it comes back"));
            case LUMBERJACK -> List.of(bool(REPLANT, "Replant saplings"), bool(RESTRICT, "Only cut trees in the zone set with its Forest Tool", false));
            // ours: the mod's players assign its fields in a list; here each of its own kinds is a switch
            case PLANTATION -> List.of(bool(GROW_SUGAR, "Grow sugar cane"), bool(GROW_CACTUS, "Grow cactus"), bool(GROW_BAMBOO, "Grow bamboo (needs research)"));
            case FARMER -> List.of(bool(FERTILIZE, "Ask for compost or bone meal to fertilize crops"));
            // not its "min warehouse stock" (16): MineCP's warehouse keeps nothing back from requests
            case COMPOSTER -> List.of(bool(MAKE_DIRT, "Make dirt instead of compost", false));
            // at most what it asks for at a time (Crafter.ORES)
            case SMELTERY -> List.of(number(MIN_ORE, "Fewest ores a delivery may bring", 0, 0, 32));
            // the mod's crusher mode (ours: the first is gravel) and daily limit (the mod's 0: off; ours: its top)
            case CRUSHER -> List.of(choice(CRUSHER_MODE, "Crush each day", org.bukkit.Material.GRAVEL, org.bukkit.Material.SAND,
                            org.bukkit.Material.CLAY, org.bukkit.Material.CLAY_BALL, org.bukkit.Material.BONE_MEAL,
                            org.bukkit.Material.COBBLESTONE, org.bukkit.Material.TUFF),
                    number(DAILY_LIMIT, "How many a day (0: only what huts ask for)", 0, 0, 4096));
            // ours: the mod picks the guard type with its hiring modules (knight, ranger, druid), here a choice
            case GUARD_TOWER -> TOWER;
            case BARRACKS_TOWER -> BARRACKS_TOWER;
            // the mod's STABLE_SETTINGS: no mine to patrol; cavalry ride out every so many minutes
            case STABLE -> List.of(words(GUARD_TASK, "Task", "PATROL", "GUARD", "FOLLOW"),
                    words(PATROL_MODE, "Patrol points (manual: set with the Guard Scepter)", "AUTO", "MANUAL"), words(FOLLOW_MODE, "Follow", "TIGHT", "LOOSE"),
                    bool(RETREAT, "Retreat when badly hurt (with research)"), BREED,
                    number(PATROL_INTERVAL, "Minutes between patrols", 6, 1, 60));
            // the mod's: guard only, retreat, hire trainees; ours: a knight and a ranger by default (it has spots for 2 of each)
            case GATE_HOUSE -> List.of(guardType(0, "Guard 1 type", "KNIGHT", "RANGER"), guardType(1, "Guard 2 type", "RANGER", "KNIGHT"),
                    words(GUARD_TASK, "Task", "GUARD"), bool(RETREAT, "Retreat when badly hurt (with research)"), HIRE_TRAINEES);
            case CHICKEN_HERDER, SWINEHERD, RABBIT_HUTCH -> List.of(BREED);
            case BEEKEEPER -> List.of(BREED, words(BEE_MODE, "Take from full hives", "HONEYCOMB", "HONEY", "BOTH"));
            case SHEPHERD -> List.of(BREED, bool(SHEARING, "Shear sheep"), bool(DYEING, "Dye sheep"));
            case COWBOY -> List.of(BREED, number(MILKING, "Buckets of milk each time", 1, 0, 64),
                    number(STEWING, "Bowls of mushroom stew each time", 1, 0, 64),
                    number(MILKING_DAYS, "Days between milkings", 1, 1, 100));
            default -> List.of();
        };
    }

    /** The hiring modes a hut of this type offers: Locked only for homes, as in the mod. */
    public static List<Hiring> modes(Hut.Type t) {
        return t.isHome() ? List.of(Hiring.values()) : List.of(Hiring.DEFAULT, Hiring.AUTO, Hiring.MANUAL);
    }

    /** Whether a hut of this type has a hiring mode: worker huts, and homes for their residents. */
    public static boolean hires(Hut.Type t) {
        return t.worker != null || t.isHome();
    }

    /** Whether a hut of this type has this setting. */
    public static boolean has(Hut.Type t, String key) {
        return of(t).stream().anyMatch(s -> s.key().equals(key));
    }

    static String initial(Hut.Type t, String key) {
        for (Setting s : of(t)) if (s.key().equals(key)) return s.initial();
        throw new IllegalArgumentException(t + " has no setting " + key);
    }
}
