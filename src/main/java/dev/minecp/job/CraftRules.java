package dev.minecp.job;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which recipes each crafter may be taught: the mod's crafter item tags ({@code data/minecolonies/tags/items/<job>_product,
 * _product_excluded, _ingredient, _ingredient_excluded}) and its rule ({@code CraftingUtils.isRecipeCompatibleBasedOnTags}):
 * an excluded product refuses the recipe, a listed product allows it; otherwise any excluded ingredient (any of a slot's
 * choices) refuses it and a listed one allows it; else the tags don't decide. The tags are written out here as vanilla
 * items; the Forge and Domum Ornamentum tags they include are replaced by the vanilla items in them.
 */
public final class CraftRules {
    private CraftRules() {}

    /** One crafter's four tags. */
    record Rule(Predicate<Material> product, Predicate<Material> productNo, Predicate<Material> ingredient, Predicate<Material> ingredientNo) {
        /** TRUE or FALSE when the tags decide, null when they don't. */
        Boolean decide(Material output, List<Material> inputs) {
            if (productNo.test(output)) return false;
            if (product.test(output)) return true;
            Boolean result = null;
            for (Material m : inputs) {
                if (ingredientNo.test(m)) return false;
                if (ingredient.test(m)) result = true;
            }
            return result;
        }

        Boolean decide(Recipe r) {
            return decide(r.getResult().getType(), inputs(r));
        }
    }

    // ---- the Forge and MineColonies tags the crafters' tags include, as vanilla items ------------------------------

    private static Predicate<Material> of(Material... ms) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        set.addAll(List.of(ms));
        return set::contains;
    }

    @SafeVarargs
    private static Predicate<Material> tagged(Tag<Material>... tags) {
        return m -> { for (Tag<Material> t : tags) if (t.isTagged(m)) return true; return false; };
    }

    private static Predicate<Material> named(String suffix) {
        return m -> m.name().endsWith(suffix);
    }

    @SafeVarargs
    private static Predicate<Material> any(Predicate<Material>... ps) {
        return m -> { for (var p : ps) if (p.test(m)) return true; return false; };
    }

    private static final Predicate<Material> NONE = m -> false;

    static final Predicate<Material> STONE = of(Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.POLISHED_GRANITE, Material.POLISHED_DIORITE, Material.POLISHED_ANDESITE, Material.DEEPSLATE,
            Material.POLISHED_DEEPSLATE, Material.TUFF, Material.INFESTED_STONE, Material.INFESTED_DEEPSLATE); // forge:stone
    static final Predicate<Material> COBBLESTONE = of(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.COBBLED_DEEPSLATE,
            Material.INFESTED_COBBLESTONE); // forge:cobblestone
    static final Predicate<Material> SANDSTONE = of(Material.SANDSTONE, Material.CUT_SANDSTONE, Material.CHISELED_SANDSTONE,
            Material.SMOOTH_SANDSTONE, Material.RED_SANDSTONE, Material.CUT_RED_SANDSTONE, Material.CHISELED_RED_SANDSTONE,
            Material.SMOOTH_RED_SANDSTONE); // forge:sandstone
    static final Predicate<Material> INGOTS = of(Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT,
            Material.NETHERITE_INGOT, Material.BRICK, Material.NETHER_BRICK); // forge:ingots
    static final Predicate<Material> NUGGETS = named("_NUGGET"); // forge:nuggets
    static final Predicate<Material> DYES = named("_DYE"); // forge:dyes
    static final Predicate<Material> CROPS = of(Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.NETHER_WART);
    static final Predicate<Material> REDSTONE = of(Material.REDSTONE, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.REDSTONE_BLOCK); // forge:dusts/ores/storage_blocks redstone
    /** minecolonies:storage_blocks: forge:storage_blocks and a few more. */
    static final Predicate<Material> STORAGE_BLOCKS = of(Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.LAPIS_BLOCK, Material.REDSTONE_BLOCK, Material.COAL_BLOCK, Material.NETHERITE_BLOCK,
            Material.COPPER_BLOCK, Material.RAW_COPPER_BLOCK, Material.RAW_GOLD_BLOCK, Material.RAW_IRON_BLOCK, Material.AMETHYST_BLOCK,
            Material.QUARTZ_BLOCK, Material.BONE_BLOCK, Material.HAY_BLOCK, Material.DRIED_KELP_BLOCK, Material.HONEY_BLOCK,
            Material.HONEYCOMB_BLOCK, Material.SNOW_BLOCK);
    static final Predicate<Material> COPPER_BLOCKS = m -> {
        String n = m.name();
        return (n.contains("COPPER") && (n.endsWith("COPPER") || n.endsWith("CUT_COPPER") || n.endsWith("COPPER_BLOCK")))
                && !n.startsWith("RAW_") && m.isBlock();
    };
    static final Predicate<Material> GLAZED_TERRACOTTA = named("_GLAZED_TERRACOTTA");
    static final Predicate<Material> CONCRETE = named("_CONCRETE");
    static final Predicate<Material> CONCRETE_POWDER = named("_CONCRETE_POWDER"); // minecolonies:concrete_powder
    static final Predicate<Material> GLASS = any(of(Material.GLASS, Material.TINTED_GLASS), named("_STAINED_GLASS")); // forge:glass
    static final Predicate<Material> GLASS_PANES = any(of(Material.GLASS_PANE), named("_STAINED_GLASS_PANE")); // forge:glass_panes

    static final Predicate<Material> MECHANIC_PRODUCT = any(STORAGE_BLOCKS, tagged(Tag.RAILS, Tag.BUTTONS, Tag.WOODEN_PRESSURE_PLATES),
            of(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.POLISHED_BLACKSTONE_PRESSURE_PLATE,
                    Material.STONE_PRESSURE_PLATE, Material.BLUE_ICE, Material.PACKED_ICE, Material.NETHER_WART_BLOCK,
                    Material.DAYLIGHT_DETECTOR, Material.COMPARATOR, Material.LEVER, Material.PISTON, Material.STICKY_PISTON,
                    Material.TRIPWIRE_HOOK, Material.ENCHANTING_TABLE, Material.JACK_O_LANTERN, Material.LANTERN, Material.SEA_LANTERN,
                    Material.SOUL_LANTERN, Material.SOUL_TORCH, Material.END_ROD, Material.TORCH, Material.ENDER_CHEST,
                    Material.TRAPPED_CHEST, Material.FIRE_CHARGE, Material.CONDUIT, Material.RESPAWN_ANCHOR, Material.SHULKER_BOX,
                    Material.SLIME_BALL, Material.GLOW_ITEM_FRAME, Material.SPYGLASS));
    static final Predicate<Material> MECHANIC_INGREDIENT = any(REDSTONE, STORAGE_BLOCKS, of(Material.BLAZE_ROD, Material.SLIME_BALL,
            Material.GUNPOWDER, Material.ENDER_PEARL, Material.ENDER_EYE, Material.REDSTONE_TORCH, Material.GLOWSTONE_DUST,
            Material.DRIED_KELP_BLOCK, Material.AMETHYST_SHARD));
    static final Predicate<Material> DYER_PRODUCT = any(DYES, of(Material.FIREWORK_STAR, Material.RED_NETHER_BRICKS));
    static final Predicate<Material> SAWMILL_PRODUCT = any(tagged(Tag.PLANKS, Tag.WOODEN_SLABS, Tag.WOODEN_STAIRS, Tag.ITEMS_BOATS,
            Tag.ITEMS_CHEST_BOATS), of(Material.BAMBOO_MOSAIC, Material.BAMBOO_MOSAIC_SLAB, Material.BAMBOO_MOSAIC_STAIRS,
            Material.BAMBOO_HANGING_SIGN));
    static final Predicate<Material> STONEMASON_PRODUCT = any(of(Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_BRICKS,
            Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_TILES, Material.BRICKS,
            Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS, Material.CHISELED_NETHER_BRICKS, Material.DRIPSTONE_BLOCK,
            Material.CHISELED_QUARTZ_BLOCK, Material.QUARTZ_PILLAR, Material.QUARTZ_BRICKS, Material.MAGMA_BLOCK, Material.SNOW),
            COPPER_BLOCKS, STONE, COBBLESTONE, SANDSTONE, tagged(Tag.STONE_BRICKS, Tag.SLABS, Tag.STAIRS, Tag.WALLS));

    // ---- the crafters ---------------------------------------------------------------------------------------------

    static final Rule SAWMILL_TAGS = new Rule(SAWMILL_PRODUCT, any(MECHANIC_PRODUCT, of(Material.MAGMA_CREAM)),
            any(tagged(Tag.LOGS), of(Material.CACTUS)), any(INGOTS, STONE, of(Material.REDSTONE, Material.STRING)));

    static final Rule STONEMASON_TAGS = new Rule(STONEMASON_PRODUCT,
            any(MECHANIC_PRODUCT, DYER_PRODUCT, SAWMILL_PRODUCT, named("_ARMOR_TRIM_SMITHING_TEMPLATE"), of(Material.LECTERN, Material.PISTON,
                    Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Material.PRISMARINE, Material.PRISMARINE_BRICKS)),
            any(of(Material.BRICK, Material.BRICKS, Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                    Material.MOSSY_STONE_BRICKS, Material.NETHER_BRICK, Material.NETHERRACK, Material.NETHER_BRICKS,
                    Material.CHISELED_NETHER_BRICKS, Material.RED_NETHER_BRICKS, Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES,
                    Material.CRACKED_DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_TILES, Material.POPPED_CHORUS_FRUIT,
                    Material.PURPUR_BLOCK, Material.PURPUR_SLAB, Material.PURPUR_PILLAR, Material.PRISMARINE_SHARD,
                    Material.PRISMARINE_CRYSTALS, Material.SMOOTH_STONE, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.DEEPSLATE,
                    Material.CHISELED_DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.POLISHED_DEEPSLATE, Material.BLACKSTONE,
                    Material.GILDED_BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS, Material.BASALT,
                    Material.POLISHED_BASALT, Material.SMOOTH_BASALT, Material.TUFF, Material.END_STONE),
                    COPPER_BLOCKS, tagged(Tag.TERRACOTTA, Tag.STAIRS, Tag.SLABS, Tag.WALLS), GLAZED_TERRACOTTA, STONE, COBBLESTONE,
                    SANDSTONE, CONCRETE),
            any(of(Material.STICK), tagged(Tag.LOGS, Tag.PLANKS), MECHANIC_INGREDIENT, DYES));

    static final Rule STONE_SMELTERY_TAGS = new Rule(any(of(Material.BRICK, Material.SMOOTH_BASALT, Material.SMOOTH_QUARTZ,
            Material.POPPED_CHORUS_FRUIT, Material.SPONGE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_RED_SANDSTONE, Material.COAL,
            Material.CHARCOAL, Material.NETHER_BRICK, Material.SMOOTH_STONE, Material.DEEPSLATE), STONE,
            tagged(Tag.TERRACOTTA, Tag.STONE_BRICKS), GLAZED_TERRACOTTA), NONE, STONEMASON_PRODUCT, NONE);

    static final Rule BLACKSMITH_TAGS = new Rule(any(of(Material.SHEARS, Material.LIGHTNING_ROD), NUGGETS, INGOTS),
            any(DYER_PRODUCT, MECHANIC_PRODUCT, SAWMILL_PRODUCT, STONEMASON_PRODUCT,
                    of(Material.FIREWORK_STAR, Material.GLISTERING_MELON_SLICE, Material.BOW, Material.CROSSBOW)),
            any(of(Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK), NUGGETS, INGOTS),
            any(CROPS, DYES, MECHANIC_INGREDIENT, of(Material.BRICK, Material.NETHER_BRICK)));

    static final Rule MECHANIC_TAGS = new Rule(MECHANIC_PRODUCT, of(Material.SPECTRAL_ARROW, Material.HAY_BLOCK, Material.LEAD),
            MECHANIC_INGREDIENT, NONE);

    // Its product tag lists only the mod's own foods; its own recipes make bread, cake, cookies and pumpkin pie (Hut.Type.BAKER).
    static final Rule BAKER_TAGS = new Rule(NONE, of(Material.BREAD, Material.CAKE, Material.COOKIE, Material.PUMPKIN_PIE,
            Material.PACKED_MUD), of(Material.WHEAT), NONE);

    static final Rule FLETCHER_TAGS = new Rule(of(Material.STRING, Material.MOSS_CARPET), of(Material.BOOK, Material.ITEM_FRAME),
            any(tagged(Tag.LEAVES, Tag.WOOL), of(Material.STRING, Material.RABBIT_HIDE, Material.LEATHER, Material.FISHING_ROD)), DYES);

    static final Rule DYER_TAGS = new Rule(DYER_PRODUCT, CONCRETE_POWDER, DYES, NONE);
    static final Rule DYER_SMELTING_TAGS = new Rule(DYES, NONE, NONE, NONE);

    static final Rule GLASSBLOWER_TAGS = new Rule(NONE, NONE, any(GLASS, GLASS_PANES), DYES);
    static final Rule GLASSBLOWER_SMELTING_TAGS = new Rule(GLASS, NONE, NONE, NONE);

    /** Tools, weapons and armor (the mod's equipment types, without the fishing rod and flint and steel). */
    static boolean equipment(Material m) {
        String n = m.name();
        return Tag.ITEMS_AXES.isTagged(m) || Tag.ITEMS_PICKAXES.isTagged(m) || Tag.ITEMS_SHOVELS.isTagged(m) || Tag.ITEMS_HOES.isTagged(m)
                || Tag.ITEMS_SWORDS.isTagged(m) || m == Material.SHEARS || m == Material.SHIELD
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private static boolean crafting(Recipe r) {
        return r instanceof ShapedRecipe || r instanceof ShapelessRecipe;
    }

    /** The Sawmill (BuildingSawmill): its tags, else wood over everything else above 0.75. */
    public static final Predicate<Recipe> SAWMILL = r -> {
        if (!crafting(r)) return false;
        Boolean tags = SAWMILL_TAGS.decide(r);
        return tags != null ? tags : woodRatio(r) > Crafter.MIN_WOOD_RATIO;
    };

    /** The Stonemason (BuildingStonemason): its tags only. */
    public static final Predicate<Recipe> STONEMASON = r -> crafting(r) && Boolean.TRUE.equals(STONEMASON_TAGS.decide(r));

    /** The Stone Smeltery (BuildingStoneSmeltery): furnace recipes, its tags only. */
    public static final Predicate<Recipe> STONE_SMELTERY = r -> r instanceof FurnaceRecipe && Boolean.TRUE.equals(STONE_SMELTERY_TAGS.decide(r));

    /** The Blacksmith (BuildingBlacksmith): nothing with leather; any tool, weapon or armor; else its tags. */
    public static final Predicate<Recipe> BLACKSMITH = r -> {
        if (!crafting(r) || inputs(r).contains(Material.LEATHER)) return false;
        return equipment(r.getResult().getType()) || Boolean.TRUE.equals(BLACKSMITH_TAGS.decide(r));
    };

    /** The Mechanic (BuildingMechanic): its tags, else minecarts and hoppers. */
    public static final Predicate<Recipe> MECHANIC = r -> {
        if (!crafting(r)) return false;
        Boolean tags = MECHANIC_TAGS.decide(r);
        Material out = r.getResult().getType();
        return tags != null ? tags : out.name().endsWith("MINECART") || out == Material.HOPPER;
    };

    /** The Bakery (BuildingBaker): crafting recipes its tags allow (anything else made from wheat). */
    public static final Predicate<Recipe> BAKER = r -> crafting(r) && Boolean.TRUE.equals(BAKER_TAGS.decide(r));

    /** The Fletcher (BuildingFletcher): its tags, else arrows and leather armor. */
    public static final Predicate<Recipe> FLETCHER = r -> {
        if (!crafting(r)) return false;
        Boolean tags = FLETCHER_TAGS.decide(r);
        Material out = r.getResult().getType();
        return tags != null ? tags : Tag.ITEMS_ARROWS.isTagged(out) || out.name().startsWith("LEATHER_") && equipment(out);
    };

    /** Crafting recipes by one set of tags and furnace recipes by another, the tags alone deciding (the mod's crafting and smelting modules). */
    private static Predicate<Recipe> craftOrSmelt(Rule crafting, Rule smelting) {
        return r -> crafting(r) ? Boolean.TRUE.equals(crafting.decide(r)) : r instanceof FurnaceRecipe && Boolean.TRUE.equals(smelting.decide(r));
    }

    /** The Dyer (BuildingDyer). */
    public static final Predicate<Recipe> DYER = craftOrSmelt(DYER_TAGS, DYER_SMELTING_TAGS);

    /** The Glassblower (BuildingGlassblower). */
    public static final Predicate<Recipe> GLASSBLOWER = craftOrSmelt(GLASSBLOWER_TAGS, GLASSBLOWER_SMELTING_TAGS);

    /** The Smeltery (BuildingSmeltery): every furnace recipe that smelts an ore or raw metal. */
    public static final Predicate<Recipe> SMELTERY = r -> r instanceof FurnaceRecipe f && ore(f);

    static boolean ore(CookingRecipe<?> r) {
        for (Material m : inputs(r)) if (isOre(m)) return true;
        return false;
    }

    /** Ores and raw metals (forge:ores and forge:raw_materials). */
    static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS || m == Material.RAW_IRON || m == Material.RAW_GOLD || m == Material.RAW_COPPER;
    }

    /** Every item any ingredient slot accepts. */
    static List<Material> inputs(Recipe r) {
        List<Material> out = new ArrayList<>();
        for (RecipeChoice c : Crafter.ingredients(r).keySet()) {
            if (c instanceof RecipeChoice.MaterialChoice mc) out.addAll(mc.getChoices());
            else out.add(c.getItemStack().getType());
        }
        return out;
    }

    /**
     * The mod's sawmill ratio, checked on each ingredient's first item: wood ingredients (planks, logs, and items
     * with "wood" in a tag) over every ingredient that isn't planks or logs. Planks and logs count only on top, so
     * 2 planks + 2 iron is 1.0; a recipe of only planks and logs divides by zero and passes, as in the mod.
     */
    private static double woodRatio(Recipe r) {
        int planksAndLogs = 0, otherWood = 0, rest = 0;
        for (var e : Crafter.ingredients(r).entrySet()) {
            Material m = e.getKey().getItemStack().getType();
            if (Tag.PLANKS.isTagged(m) || Tag.LOGS.isTagged(m)) planksAndLogs += e.getValue();
            else if (woodTagged(m)) otherWood += e.getValue();
            else rest += e.getValue();
        }
        return Crafter.woodRatio(planksAndLogs, otherWood, rest);
    }

    /** Items with "wood" in one of their tags, as the mod checks: sticks (c:rods/wooden), wooden slabs, stairs and so on. */
    private static boolean woodTagged(Material m) {
        return Tag.BAMBOO_BLOCKS.isTagged(m) || m == Material.STICK || Tag.WOODEN_SLABS.isTagged(m) || Tag.WOODEN_STAIRS.isTagged(m)
                || Tag.WOODEN_FENCES.isTagged(m) || Tag.WOODEN_DOORS.isTagged(m) || Tag.WOODEN_TRAPDOORS.isTagged(m)
                || Tag.WOODEN_BUTTONS.isTagged(m) || Tag.WOODEN_PRESSURE_PLATES.isTagged(m);
    }
}
