package dev.minecp.colony;

import java.util.List;

/**
 * The mod's building settings (its SettingsModule, BuildingModules' *_SETTINGS) that MineCP has the behaviour for, with
 * the mod's defaults, and its hiring modes. Each hut keeps its own values (Hut.setting); the Town Hall's are the
 * colony's. Left out because their features don't exist here: the lumberjack's restricted area, defoliating and shears,
 * the builder's work order mode and building patterns, crafters' recipe mode, the guard
 * tower's tasks, and the tavern's music.
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
            BREEDING = "breeding", SHEARING = "shearing", DYEING = "dyeing",
            MILKING = "milking_amount", MILKING_DAYS = "milking_days", STEWING = "stewing_amount",
            CRUSHER_MODE = "crusher_mode", DAILY_LIMIT = "daily_limit", GUARD_TYPE = "guard_type",
            MAKE_DIRT = "make_dirt", FERTILIZE = "fertilize",
            BEE_MODE = "beekeeper_mode", GROW_SUGAR = "grow_sugar_cane", GROW_CACTUS = "grow_cactus", GROW_BAMBOO = "grow_bamboo";
    /** The miner's max depth default, which means "no limit but the hut level's" (the mod's). */
    public static final int NO_MAX_DEPTH = -100;

    private static final Setting BREED = bool(BREEDING, "Breed animals");

    private Settings() {}

    /** The guard type of a hut's {@code slot}th guard (from 0): knight by default. */
    public static String guardTypeKey(int slot) {
        return slot == 0 ? GUARD_TYPE : GUARD_TYPE + "_" + (slot + 1);
    }

    private static Setting guardType(int slot, String label, String... types) {
        return new Setting(guardTypeKey(slot), label, Kind.CHOICE, types[0], 0, 0, List.of(types));
    }

    /** The settings a hut of this type has, in menu order. */
    public static List<Setting> of(Hut.Type t) {
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
            case LUMBERJACK -> List.of(bool(REPLANT, "Replant saplings"));
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
            case GUARD_TOWER -> List.of(guardType(0, "Guard type", "KNIGHT", "RANGER")); // the mod's: druids only in barracks towers
            case BARRACKS_TOWER -> java.util.stream.IntStream.range(0, Hut.MAX_LEVEL)
                    .mapToObj(i -> guardType(i, "Guard " + (i + 1) + " type (from level " + (i + 1) + ")", "KNIGHT", "RANGER", "DRUID")).toList();
            case CHICKEN_HERDER, SWINEHERD, RABBIT_HUTCH -> List.of(BREED);
            case BEEKEEPER -> List.of(BREED, new Setting(BEE_MODE, "Take from full hives", Kind.CHOICE, "HONEYCOMB", 0, 0, List.of("HONEYCOMB", "HONEY", "BOTH")));
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

    static String initial(Hut.Type t, String key) {
        for (Setting s : of(t)) if (s.key().equals(key)) return s.initial();
        throw new IllegalArgumentException(t + " has no setting " + key);
    }
}
