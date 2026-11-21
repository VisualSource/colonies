package dev.minecp;

import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The mod's advancement tabs (its {@code data/minecolonies/advancements}): the same tree, triggers and vanilla icons,
 * in our words. Each has one impossible criterion that MineCP awards when the thing happens, to every online player
 * the colony sends its messages to (RECEIVE_MESSAGES), as the mod does. Loaded at startup, so no data pack is needed.
 */
public final class Advancements {
    /** {@code criteria}: awarded separately, all needed (most have one, "done"). */
    private record Def(String parent, String icon, String title, String description, String frame, boolean hidden, List<String> criteria) {}

    private static final String ROOT = "minecolonies/root", PRODUCTION = "production/root";
    /** The mod's post_and_stash: placing a postbox and placing a stash, its criteria "postbox" and "stash". */
    static final String POST_AND_STASH = "production/post_and_stash";
    private static final Map<String, Def> ALL = new LinkedHashMap<>();

    private static void add(String id, String parent, String icon, String title, String description, String frame) {
        ALL.put(id, new Def(parent, icon, title, description, frame, false, List.of("done")));
    }

    static {
        // The MineColonies tab. Its root is placing a supply camp or ship, as the mod's (its title is the mod's name).
        add(ROOT, null, "paper", "Colonies", "Craft a Supply Camp or Ship and place it by right-clicking the ground", "task");
        // The mod's copy of it in vanilla's Story tab: no toast or chat message.
        add("story/craft_supply", "minecraft:story/root", "paper", "Colonies", "Craft a Supply Camp or Ship and place it by right-clicking the ground", "task");
        add("minecolonies/place_townhall", ROOT, "bell", "A Place to Call Home", "Place a Town Hall to found a colony", "task");
        add("minecolonies/start_builder", "minecolonies/place_townhall", "yellow_wool", "Hard Hat Needed", "Ask for the Builder's Hut to be built", "task");
        add("minecolonies/build_builder", "minecolonies/start_builder", "crafting_table", "The Builder's Yard", "Finish the Builder's Hut", "goal");
        add("minecolonies/build_builder_2", "minecolonies/build_builder", "crafting_table", "Bigger Projects", "Upgrade the Builder's Hut to level 2", "goal");
        add("minecolonies/build_town_hall", "minecolonies/build_builder", "bell", "Seat of Power", "Finish the Town Hall", "task");
        add("minecolonies/build_town_hall_5", "minecolonies/build_town_hall", "bell", "Capital City", "Upgrade the Town Hall to level 5", "challenge");
        add("minecolonies/build_tavern", "minecolonies/build_builder", "barrel", "A Warm Welcome", "Finish a Tavern", "task");
        add("minecolonies/build_citizen", "minecolonies/build_tavern", "red_bed", "Room to Grow", "Finish a House", "task");
        add("minecolonies/build_guard_tower", "minecolonies/build_builder", "shield", "On Watch", "Finish a Guard Tower", "task");
        add("minecolonies/colony_population_5", "minecolonies/build_citizen", "red_bed", "A Hamlet", "Have 5 citizens", "goal");
        add("minecolonies/colony_population_10", "minecolonies/colony_population_5", "brick", "A Village", "Have 10 citizens", "task");
        add("minecolonies/colony_population_25", "minecolonies/colony_population_10", "gold_ingot", "A Town", "Have 25 citizens", "goal");
        add("minecolonies/colony_population_50", "minecolonies/colony_population_25", "diamond", "A City", "Have 50 citizens", "goal");
        add("minecolonies/colony_population_75", "minecolonies/colony_population_50", "emerald", "A Metropolis", "Have 75 citizens", "challenge");
        add("minecolonies/citizen_eat_food_first", "minecolonies/place_townhall", "cooked_beef", "First Meal", "A citizen eats for the first time", "task");
        add("minecolonies/citizen_bury", "minecolonies/build_tavern", "bone", "Rest in Peace", "Your undertaker buries a citizen", "task");
        add("minecolonies/citizen_resurrect", "minecolonies/citizen_bury", "totem_of_undying", "Back From the Grave", "Your undertaker brings a citizen back to life", "task");
        add("minecolonies/building_add_recipe_torch", "minecolonies/start_builder", "torch", "Let There Be Light", "Teach a crafter to make torches", "task");
        // The production tab.
        add(PRODUCTION, null, "crafting_table", "Colony Production", "Finish the Builder's Hut", "task");
        add("production/build_lumberjack", PRODUCTION, "iron_axe", "Timber!", "Finish a Lumberjack's Hut", "task");
        add("production/build_farmer", PRODUCTION, "iron_hoe", "Green Fields", "Finish a Farmer's Hut", "task");
        add("production/build_miner", PRODUCTION, "iron_pickaxe", "Into the Deep", "Finish a Miner's Hut", "task");
        add("production/build_fisherman", PRODUCTION, "fishing_rod", "Gone Fishing", "Finish a Fisherman's Hut", "task");
        add("production/build_all_herders", "production/build_farmer", "shears", "The Whole Herd",
                "Finish a chicken farmer's, cowhand's, shepherd's and swineherd's hut and a rabbit hutch", "goal");
        add("production/build_cook", PRODUCTION, "cooked_beef", "Soup's On", "Finish a Cook's Hut", "task");
        add("production/build_baker", "production/build_cook", "bread", "Fresh From the Oven", "Finish a Bakery", "task");
        add("production/build_warehouse", PRODUCTION, "chest", "A Place for Everything", "Finish the Warehouse", "task");
        add("production/build_delivery_person", "production/build_warehouse", "leather_boots", "Special Delivery", "Finish a Courier's Hut", "task");
        ALL.put(POST_AND_STASH, new Def("production/build_delivery_person", "barrel", "Same-Day Delivery",
                "Place a Postbox and a Stash", "task", false, List.of("postbox", "stash")));
        add("production/build_sawmill", "production/build_delivery_person", "oak_planks", "Sawdust", "Finish a Sawmill", "task");
        add("production/build_stonemason", "production/build_sawmill", "stone_bricks", "Set in Stone", "Finish a Stonemason's Hut", "task");
        add("production/build_stone_smeltery", "production/build_sawmill", "furnace", "Fired Up", "Finish a Stone Smeltery", "task");
        add("production/build_blacksmith", "production/build_sawmill", "anvil", "Hammer and Tongs", "Finish a Blacksmith's Hut", "task");
        add("production/build_smeltery", "production/build_sawmill", "iron_ingot", "Molten Metal", "Finish a Smeltery", "task");
        add("production/build_smeltery_3", "production/build_smeltery", "iron_ingot", "Hotter Furnaces", "Upgrade a Smeltery to level 3", "task");
        add("production/build_smeltery_5", "production/build_smeltery_3", "gold_ingot", "Master Smelter", "Upgrade a Smeltery to level 5", "task");
        // Filed with the MineColonies ones in the mod, but its parent (loaded first) puts it in this tab.
        add("minecolonies/building_add_recipe_door", "production/build_sawmill", "oak_door", "Knock Knock", "Teach a crafter to make a wooden door", "task");
    }

    /** The advancement for a hut finished at a level, as the mod's complete_build_request triggers; null if none. */
    static String forBuild(Hut.Type type, int level) {
        String id = switch (type) {
            case TOWN_HALL -> level == 1 ? "minecolonies/build_town_hall" : level == 5 ? "minecolonies/build_town_hall_5" : null;
            case BUILDER -> level == 1 ? "minecolonies/build_builder" : level == 2 ? "minecolonies/build_builder_2" : null;
            case HOUSE -> level == 1 ? "minecolonies/build_citizen" : null;
            case GUARD_TOWER -> level == 1 ? "minecolonies/build_guard_tower" : null;
            case LUMBERJACK -> level == 1 ? "production/build_lumberjack" : null;
            case FARMER -> level == 1 ? "production/build_farmer" : null;
            case MINER -> level == 1 ? "production/build_miner" : null;
            case COOK -> level == 1 ? "production/build_cook" : null;
            case BAKER -> level == 1 ? "production/build_baker" : null;
            case FISHERMAN -> level == 1 ? "production/build_fisherman" : null;
            case TAVERN -> level == 1 ? "minecolonies/build_tavern" : null;
            case WAREHOUSE -> level == 1 ? "production/build_warehouse" : null;
            case COURIER -> level == 1 ? "production/build_delivery_person" : null;
            case SAWMILL -> level == 1 ? "production/build_sawmill" : null;
            case STONEMASON -> level == 1 ? "production/build_stonemason" : null;
            case STONE_SMELTERY -> level == 1 ? "production/build_stone_smeltery" : null;
            case BLACKSMITH -> level == 1 ? "production/build_blacksmith" : null;
            case SMELTERY -> level == 1 ? "production/build_smeltery" : level == 3 ? "production/build_smeltery_3" : level == 5 ? "production/build_smeltery_5" : null;
            default -> null;
        };
        return type == Hut.Type.BUILDER && level == 1 ? id + "|" + PRODUCTION : id; // the production tab's root too
    }

    /**
     * Registers them with the server. Bukkit saves each one in the world's "bukkit" data pack and loads it from there
     * on the next start. If any saved by an older MineCP differs from ours, all of them are deleted and loaded again
     * (one data reload; all, as children can't load without their parent).
     */
    static void load(Logger log) {
        var saved = Bukkit.getWorldContainer().toPath().resolve(Bukkit.getWorlds().getFirst().getName()).resolve("datapacks/bukkit/data/minecp/advancement");
        boolean outdated = ALL.entrySet().stream().anyMatch(e -> Bukkit.getAdvancement(key(e.getKey())) != null && !same(saved.resolve(e.getKey() + ".json"), e.getValue()));
        if (outdated) {
            try (var files = java.nio.file.Files.walk(saved)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (java.io.IOException ex) {
                log.warning("Couldn't replace the advancements: " + ex.getMessage());
            }
            Bukkit.reloadData();
        }
        int n = 0;
        for (var e : ALL.entrySet()) {
            NamespacedKey key = key(e.getKey());
            if (Bukkit.getAdvancement(key) != null) continue;
            try {
                if (Bukkit.getUnsafe().loadAdvancement(key, json(e.getValue())) != null) n++;
            } catch (RuntimeException ex) {
                log.warning("Couldn't load advancement " + key + ": " + ex.getMessage());
            }
        }
        if (n > 0) log.info("Loaded " + n + " advancements.");
    }

    /** Whether the saved copy is this definition: compared as parsed JSON, as Bukkit saves it reformatted. */
    private static boolean same(java.nio.file.Path file, Def d) {
        try {
            return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).equals(com.google.gson.JsonParser.parseString(json(d)));
        } catch (java.io.IOException | RuntimeException e) {
            return false; // missing or unreadable: replaced
        }
    }

    private static NamespacedKey key(String id) {
        return new NamespacedKey(MineCP.NAMESPACE, id);
    }

    private static String json(Def d) {
        String bg = d.parent == null ? ",\"background\":\"minecraft:block/" + (d.title.equals("Colonies") ? "light_gray_wool" : "oak_planks") + "\"" : "";
        boolean vanillaTab = d.parent != null && d.parent.startsWith("minecraft:"); // quiet there, as the mod's
        return "{" + (d.parent == null ? "" : "\"parent\":\"" + (vanillaTab ? "" : "minecp:") + d.parent + "\",")
                + "\"display\":{\"icon\":{\"id\":\"minecraft:" + d.icon + "\"},\"title\":{\"text\":\"" + d.title + "\"},"
                + "\"description\":{\"text\":\"" + d.description + "\"},\"frame\":\"" + d.frame + "\",\"hidden\":" + d.hidden
                + ",\"show_toast\":" + !vanillaTab + ",\"announce_to_chat\":" + !vanillaTab + bg + "},"  // roots too, as the mod's
                + "\"criteria\":{" + String.join(",", d.criteria.stream().map(k -> "\"" + k + "\":{\"trigger\":\"minecraft:impossible\"}").toList()) + "}}";
    }

    /** Awards these ("a|b" for several) to one player. */
    public static void award(Player p, String ids) {
        for (String id : ids.split("\\|")) {
            Advancement a = Bukkit.getAdvancement(key(id));
            if (a != null && !p.getAdvancementProgress(a).isDone()) p.getAdvancementProgress(a).awardCriteria("done");
        }
    }

    /** Awards one criterion of an advancement to one player. */
    static void award(Player p, String id, String criterion) {
        Advancement a = Bukkit.getAdvancement(key(id));
        if (a != null) p.getAdvancementProgress(a).awardCriteria(criterion);
    }

    /** A colony's milestones, turned into advancements for its members. */
    static Colony.Milestones forColony(Colony c) {
        return new Colony.Milestones() {
            @Override
            public void queued(Hut.Type type, int level) {
                if (type == Hut.Type.BUILDER && level == 1) award(c, "minecolonies/start_builder");
            }

            @Override
            public void built(Hut.Type type, int level) {
                award(c, forBuild(type, level));
                var herders = java.util.EnumSet.of(Hut.Type.CHICKEN_HERDER, Hut.Type.COWBOY, Hut.Type.SHEPHERD, Hut.Type.SWINEHERD, Hut.Type.RABBIT_HUTCH);
                if (herders.contains(type) && herders.stream().allMatch(t -> c.huts.stream().anyMatch(h -> h.type == t && h.level >= 1)))
                    award(c, "production/build_all_herders");
            }

            @Override
            public void population(int citizens) {
                for (int t : new int[]{5, 10, 25, 50, 75}) if (citizens >= t) award(c, "minecolonies/colony_population_" + t);
            }

            @Override
            public void ate() {
                award(c, "minecolonies/citizen_eat_food_first");
            }

            @Override
            public void buried() {
                award(c, "minecolonies/citizen_bury");
            }

            @Override
            public void resurrected() {
                award(c, "minecolonies/citizen_resurrect");
            }

            @Override
            public void taught(Material result) {
                if (result == Material.TORCH) award(c, "minecolonies/building_add_recipe_torch");
                else if (Tag.WOODEN_DOORS.isTagged(result)) award(c, "minecolonies/building_add_recipe_door");
            }
        };
    }

    /** Awards them to every online player the colony sends its messages to. */
    static void award(Colony c, String ids) {
        if (ids == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (c.permissions.has(p, Action.RECEIVE_MESSAGES)) award(p, ids);
    }
}
