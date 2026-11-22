package dev.minecp.job;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** Kinds of tool a worker needs, matched to blocks by the vanilla "mineable" tags. */
public enum Tool {
    AXE("an axe", Tag.ITEMS_AXES, Tag.MINEABLE_AXE),
    PICKAXE("a pickaxe", Tag.ITEMS_PICKAXES, Tag.MINEABLE_PICKAXE),
    SHOVEL("a shovel", Tag.ITEMS_SHOVELS, Tag.MINEABLE_SHOVEL),
    HOE("a hoe", Tag.ITEMS_HOES, Tag.MINEABLE_HOE),
    SWORD("a sword", Tag.ITEMS_SWORDS, null), // a guard's weapon: mines nothing
    BOW("a bow", m -> m == Material.BOW), // a ranger's (the mod's bow equipment: wood level, plus enchantments)
    ROD("a fishing rod", m -> m == Material.FISHING_ROD),
    SHEARS("shears", m -> m == Material.SHEARS);

    final String label;
    private final java.util.function.Predicate<Material> items;
    private final Tag<Material> mines;

    Tool(String label, Tag<Material> items, Tag<Material> mines) {
        this.label = label;
        this.items = items::isTagged;
        this.mines = mines;
    }

    Tool(String label, java.util.function.Predicate<Material> items) { // mines nothing
        this.label = label;
        this.items = items;
        this.mines = null;
    }

    /** The kind of tool {@code item} is, or null. */
    static Tool kindOf(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        for (Tool t : values()) if (t.items.test(item.getType())) return t;
        return null;
    }

    /** Whether it mines blocks (not a sword, rod or shears). */
    boolean digs() {
        return mines != null;
    }

    /** The tool kind a block is mined with (hoe blocks only if {@code hoe}), or null if hands will do. */
    static Tool forBlock(Material m, boolean hoe) {
        for (Tool t : values()) if ((hoe || t != HOE) && t.mines != null && t.mines.isTagged(m)) return t;
        return null;
    }

    /**
     * The mod's equipment level of a tool: wood and gold 0, stone 1 (copper too: it mines what stone does; the mod
     * predates it), iron 2, diamond 3, netherite 4, plus its highest enchantment level minus 1.
     */
    static int tier(ItemStack tool) {
        String n = tool.getType().name();
        int tier = n.startsWith("NETHERITE_") ? 4 : n.startsWith("DIAMOND_") ? 3 : n.startsWith("IRON_") ? 2
                : n.startsWith("STONE_") || n.startsWith("COPPER_") ? 1 : 0;
        int enchant = tool.getEnchantments().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return tier + Math.max(enchant - 1, 0);
    }

    /**
     * How fast {@code tool} breaks blocks of {@code m} (vanilla's tool speeds, without Efficiency, as the mod's
     * Item.getDestroySpeed): its tier's speed when it is the kind that mines it, else 1 (by hand too).
     */
    static float speed(ItemStack tool, Material m) {
        Tool kind = kindOf(tool);
        if (kind == null) return 1;
        if (kind == SHEARS) return Tag.LEAVES.isTagged(m) || m == Material.COBWEB ? 15 : Tag.WOOL.isTagged(m) ? 5 : 1;
        if (kind.mines == null || !kind.mines.isTagged(m)) return 1;
        String n = tool.getType().name();
        return n.startsWith("NETHERITE_") ? 9 : n.startsWith("DIAMOND_") ? 8 : n.startsWith("IRON_") ? 6 : n.startsWith("COPPER_") ? 5
                : n.startsWith("STONE_") ? 4 : n.startsWith("GOLDEN_") ? 12 : 2;
    }

    /** Names of the tiers a hut allows at most, for the hut menus. */
    static String tierName(int tier) {
        return switch (tier) {
            case 0 -> "wood or gold";
            case 1 -> "stone";
            case 2 -> "iron";
            case 3 -> "diamond";
            default -> "netherite";
        };
    }

    /** Whether {@code tool} is this kind and, for {@code block} (null: any), good enough to get its drops. */
    boolean fits(ItemStack tool, Block block) {
        return tool != null && items.test(tool.getType())
                && (block == null || !block.getBlockData().requiresCorrectToolForDrops() || block.isPreferredTool(tool));
    }
}
