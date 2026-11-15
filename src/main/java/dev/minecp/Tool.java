package dev.minecp;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/** Kinds of tool a worker needs, matched to blocks by the vanilla "mineable" tags. */
enum Tool {
    AXE("an axe", Tag.ITEMS_AXES, Tag.MINEABLE_AXE),
    PICKAXE("a pickaxe", Tag.ITEMS_PICKAXES, Tag.MINEABLE_PICKAXE),
    SHOVEL("a shovel", Tag.ITEMS_SHOVELS, Tag.MINEABLE_SHOVEL),
    HOE("a hoe", Tag.ITEMS_HOES, Tag.MINEABLE_HOE);

    final String label;
    private final Tag<Material> items, mines;

    Tool(String label, Tag<Material> items, Tag<Material> mines) {
        this.label = label;
        this.items = items;
        this.mines = mines;
    }

    /** The tool kind a block is mined with (hoe blocks only if {@code hoe}), or null if hands will do. */
    static Tool forBlock(Material m, boolean hoe) {
        for (Tool t : values()) if ((hoe || t != HOE) && t.mines.isTagged(m)) return t;
        return null;
    }

    /** Whether {@code tool} is this kind and, for {@code block} (null: any), good enough to get its drops. */
    boolean fits(ItemStack tool, Block block) {
        return tool != null && items.isTagged(tool.getType())
                && (block == null || !block.getBlockData().requiresCorrectToolForDrops() || block.isPreferredTool(tool));
    }
}
