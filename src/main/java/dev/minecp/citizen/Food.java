package dev.minecp.citizen;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** What citizens eat, and what the cook cooks. */
public final class Food {
    public static final double MAX_SATURATION = 20;
    public static final double LOW_SATURATION = 6; // below this a citizen goes to eat (the mod's "low saturation")

    /** Edible, but citizens won't eat it: harmful, or too precious to eat as a meal. */
    private static final Set<Material> NEVER = EnumSet.of(Material.ROTTEN_FLESH, Material.SPIDER_EYE, Material.POISONOUS_POTATO,
            Material.PUFFERFISH, Material.CHORUS_FRUIT, Material.SUSPICIOUS_STEW, Material.GOLDEN_APPLE,
            Material.ENCHANTED_GOLDEN_APPLE, Material.HONEY_BOTTLE);
    private static Map<Material, Material> cooked; // raw food -> what a furnace makes of it

    private Food() {}

    /** Raw food a furnace turns into food, mapped to the result (built from the server's furnace recipes). */
    public static Map<Material, Material> cooked() {
        if (cooked == null) {
            cooked = new EnumMap<>(Material.class);
            Bukkit.recipeIterator().forEachRemaining(r -> {
                if (!(r instanceof CookingRecipe<?> c) || !c.getResult().getType().isEdible()) return;
                for (Material m : Material.values())
                    if (m.isItem() && !m.isLegacy() && m.isEdible() && c.getInputChoice().test(new ItemStack(m))) cooked.put(m, c.getResult().getType());
            });
        }
        return cooked;
    }

    /** Food a citizen eats as it is: good food that isn't waiting to be cooked (raw meat, potatoes). */
    public static boolean isMeal(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Material m = item.getType();
        return m.isEdible() && !NEVER.contains(m) && !cooked().containsKey(m) && nutrition(item) > 0;
    }

    public static int nutrition(ItemStack item) {
        var food = item.getData(DataComponentTypes.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    static boolean hasMeal(Inventory inv) {
        if (inv == null) return false;
        for (ItemStack i : inv.getContents()) if (isMeal(i)) return true;
        return false;
    }

    /** Takes meals from {@code inv} worth at least {@code need} nutrition (fewer if there isn't that much). */
    public static java.util.List<ItemStack> take(Inventory inv, double need) {
        java.util.List<ItemStack> taken = new java.util.ArrayList<>();
        for (int slot = 0; slot < inv.getSize() && need > 0; slot++) {
            ItemStack i = inv.getItem(slot);
            if (!isMeal(i)) continue;
            int per = nutrition(i), n = Math.min(i.getAmount(), (int) Math.ceil(need / per));
            taken.add(i.asQuantity(n));
            need -= (double) n * per;
            i.subtract(n);
            inv.setItem(slot, i.isEmpty() ? null : i);
        }
        return taken;
    }
}
