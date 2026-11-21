package dev.minecp.colony;

import java.util.List;

/**
 * The mod's building settings (its SettingsModule, BuildingModules' *_SETTINGS) that MineCP has the behaviour for, with
 * the mod's defaults, and its hiring modes. Each hut keeps its own values (Hut.setting); the Town Hall's are the
 * colony's. Left out because their features don't exist here: the lumberjack's restricted area, defoliating and shears,
 * the farmer's fertilizing, the builder's work order mode and building patterns, crafters' recipe mode, the guard
 * tower's tasks, and the tavern's music.
 */
public final class Settings {
    /** The mod's HiringMode: DEFAULT follows the Town Hall's auto hiring (or auto housing for homes). */
    public enum Hiring {
        DEFAULT("Default (the Town Hall's setting)"), AUTO("Automatic"), MANUAL("Manual"), LOCKED("Locked");

        public final String label;

        Hiring(String label) {
            this.label = label;
        }
    }

    public enum Kind { BOOL, INT, BLOCK }

    /** A setting: its save key, its label in the menu, its kind, the mod's default, and for numbers their range (ours). */
    public record Setting(String key, String label, Kind kind, String initial, int min, int max) {}

    private static Setting bool(String key, String label) {
        return new Setting(key, label, Kind.BOOL, "true", 0, 0);
    }

    private static Setting number(String key, String label, int initial, int min, int max) {
        return new Setting(key, label, Kind.INT, String.valueOf(initial), min, max);
    }

    private static Setting block(String key, String label, org.bukkit.Material initial) {
        return new Setting(key, label, Kind.BLOCK, initial.name(), 0, 0);
    }

    // The Town Hall's (the colony's), all on by default as in the mod.
    public static final String MOVE_IN = "move-in", AUTO_HIRING = "auto-hiring", AUTO_HOUSING = "auto-housing",
            TAPE = "construction-tape", ENTER_LEAVE = "enter-leave-messages";
    public static final String FILL_BLOCK = "fill-block", MAX_DEPTH = "max-depth", REPLANT = "replant", MIN_ORE = "min-ore",
            BREEDING = "breeding", SHEARING = "shearing", DYEING = "dyeing",
            MILKING = "milking-amount", MILKING_DAYS = "milking-days", STEWING = "stewing-amount";
    /** The miner's max depth default, which means "no limit but the hut level's" (the mod's). */
    public static final int NO_MAX_DEPTH = -100;

    private static final Setting BREED = bool(BREEDING, "Breed animals");

    private Settings() {}

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
            // at most what it asks for at a time (Crafter.ORES)
            case SMELTERY -> List.of(number(MIN_ORE, "Fewest ores a delivery may bring", 0, 0, 32));
            case CHICKEN_HERDER, SWINEHERD, RABBIT_HUTCH -> List.of(BREED);
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
