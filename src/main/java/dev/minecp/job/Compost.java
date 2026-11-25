package dev.minecp.job;

import dev.minecp.MineCP;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The mod's compost (ItemCompost) and its compost recipes (recipes/compostables*.json and their item tags): how much an
 * item fills a barrel. Bone meal with a tag stands in for the compost item, so it works as bone meal does, as the mod's.
 */
public final class Compost {
    private Compost() {}

    private static final NamespacedKey KEY = new NamespacedKey(MineCP.NAMESPACE, "compost");
    static final int BARREL_ITEMS = 64; // AbstractTileEntityBarrel.MAX_ITEMS: a full barrel
    private static final int MISTLETOE = 4;
    static final int PER_BARREL = 6;           // CompostRecipe.COMPOST_RESULT

    // The mod's tags (minecolonies:compostables, _poor, _rich), as vanilla items: "grass" is short grass since 1.20.3;
    // forge:crops, forge:eggs, forge:seeds and minecolonies:fungi listed out. Its "plant" ingredient (crop and stem
    // seeds, carrot, potato: 4) is left out: each is also a seed (2, the weakest wins) or already in the list.
    private static final Set<Material> NORMAL = EnumSet.of(Material.ROTTEN_FLESH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.FEATHER, Material.PUMPKIN, Material.CARVED_PUMPKIN, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.HAY_BLOCK, Material.BIG_DRIPLEAF, Material.SPORE_BLOSSOM, Material.GLOW_LICHEN, Material.KELP,
            Material.DRIED_KELP_BLOCK, Material.SEAGRASS, Material.CACTUS, Material.SUGAR_CANE, Material.VINE, Material.TWISTING_VINES,
            Material.WEEPING_VINES, Material.COCOA_BEANS, Material.LILY_PAD, Material.SEA_PICKLE, Material.BROWN_MUSHROOM_BLOCK,
            Material.RED_MUSHROOM_BLOCK, Material.MUSHROOM_STEM, Material.CAKE, Material.MELON, Material.RABBIT_FOOT,
            Material.FERMENTED_SPIDER_EYE, Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.SHROOMLIGHT, Material.NETHER_WART_BLOCK,
            Material.WARPED_WART_BLOCK, Material.NETHER_SPROUTS, Material.MANGROVE_ROOTS, Material.HANGING_ROOTS, Material.CRIMSON_ROOTS,
            Material.WARPED_ROOTS, Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.NETHER_WART,
            Material.EGG, Material.BLUE_EGG, Material.BROWN_EGG, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS);
    private static final Set<Material> SEEDS = EnumSet.of(Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS, Material.MELON_SEEDS,
            Material.PUMPKIN_SEEDS, Material.TORCHFLOWER_SEEDS);

    /**
     * How many of a barrel's 64 one of this item fills: 8 (rich), 4 or 2 (poor), or 0 if it doesn't compost. Where
     * the mod's recipes overlap the weakest wins, as its CompatibilityManager. Foods by their saturation modifier: 1 or
     * more rich, 0.5 or more normal, less poor. Ours: composted dirt, the mod's other rich input, isn't in Colonies.
     */
    static int strength(Material m) {
        int s = 0;
        if (m == Material.PODZOL) s = weakest(s, 8);
        if (NORMAL.contains(m) || Tag.FLOWERS.isTagged(m) || Tag.ITEMS_FISHES.isTagged(m)
                || Tag.LEAVES.isTagged(m) || Tag.WOOL.isTagged(m)) s = weakest(s, 4);
        if (SEEDS.contains(m) || Tag.SAPLINGS.isTagged(m) || m == Material.PITCHER_POD || m == Material.SMALL_DRIPLEAF) s = weakest(s, 2);
        var food = m.isItem() ? new ItemStack(m).getData(DataComponentTypes.FOOD) : null;
        if (food != null && food.nutrition() > 0) {
            double modifier = food.saturation() / (2.0 * food.nutrition()); // vanilla: saturation = nutrition x modifier x 2
            s = weakest(s, modifier >= 1 ? 8 : modifier >= 0.5 ? 4 : 2);
        }
        return s;
    }

    /** The same for an item: MineCP's own don't compost, but mistletoe does (4, the mod's compostables tag). */
    public static int strength(ItemStack i) {
        return Alchemist.isMistletoe(i) ? MISTLETOE : i.getPersistentDataContainer().isEmpty() ? strength(i.getType()) : 0;
    }

    /** What a compost list's entry is called: a trial key on it stands for mistletoe. */
    static String name(Material m) {
        return m == Material.TRIAL_KEY ? "mistletoe" : Job.name(m);
    }

    /** A compost list entry with how much one fills: "wheat (4)". */
    public static String label(Material m) {
        return name(m) + " (" + (m == Material.TRIAL_KEY ? MISTLETOE : strength(m)) + ")";
    }

    private static int weakest(int had, int now) {
        return had == 0 ? now : Math.min(had, now);
    }

    static ItemStack item(int amount) {
        ItemStack item = new ItemStack(Material.BONE_MEAL, amount);
        item.editMeta(m -> {
            m.itemName(Component.text("Compost", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Makes crops grow, as bone meal does; farmers use it", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.BOOLEAN, true);
        });
        dev.minecp.Items.model(item, "compost");
        return item;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.BONE_MEAL && item.getPersistentDataContainer().has(KEY);
    }

    /** What a farmer fertilizes with: compost or plain bone meal (the mod's isCompost). */
    static boolean fertilizer(ItemStack item) {
        return is(item) || item != null && item.getType() == Material.BONE_MEAL && item.getPersistentDataContainer().isEmpty();
    }
}
