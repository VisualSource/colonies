package dev.minecp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** The plugin's items: the Colony Token, hut items, and their recipes (the token is kept when crafting). */
final class Items implements Listener {
    private final Plugin plugin;
    private final NamespacedKey hutKey, tokenKey;
    private final List<NamespacedKey> recipes = new ArrayList<>();

    Items(Plugin plugin) {
        this.plugin = plugin;
        hutKey = new NamespacedKey(plugin, "hut_item");
        tokenKey = new NamespacedKey(plugin, "token");
    }

    /**
     * Hut recipes in MineColonies' shapes (their gold scepter replaced by our Colony Token). Registered here, not in a
     * data pack, because only plugin recipes can demand an exact item: an ordinary stick never matches the token.
     */
    void addRecipes() {
        RecipeChoice token = new RecipeChoice.ExactChoice(token());
        for (Hut.Type type : Hut.Type.values()) {
            RecipeChoice centre = type == Hut.Type.TOWN_HALL ? token : type.centre == null ? null : type.centre.get();
            if (centre == null) continue;
            ShapedRecipe r = new ShapedRecipe(key(type.name().toLowerCase()), hutItem(type));
            r.shape("PTP", "PXP", "PPP");
            r.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
            r.setIngredient('T', token);
            r.setIngredient('X', centre);
            add(r);
        }
        // MineColonies' storage rack: planks, iron nuggets, and the build tool (our token) in the middle.
        ShapedRecipe rack = new ShapedRecipe(key("rack"), Rack.item());
        rack.shape("PPP", "ITI", "PPP");
        rack.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        rack.setIngredient('I', new RecipeChoice.MaterialChoice(Material.IRON_NUGGET));
        rack.setIngredient('T', token);
        add(rack);
        // The field is MineColonies' scarecrow, whose recipe takes no build tool: hay bale or pumpkin on a stick body.
        ShapedRecipe field = new ShapedRecipe(key("field"), hutItem(Hut.Type.FIELD));
        field.shape(" H ", "SLS", " S ");
        field.setIngredient('H', new RecipeChoice.MaterialChoice(Material.HAY_BLOCK, Material.PUMPKIN));
        field.setIngredient('S', new RecipeChoice.ExactChoice(new ItemStack(Material.STICK))); // plain sticks, never the token
        field.setIngredient('L', new RecipeChoice.MaterialChoice(Material.LEATHER));
        add(field);
    }

    private NamespacedKey key(String name) {
        NamespacedKey key = new NamespacedKey(plugin, name);
        plugin.getServer().removeRecipe(key); // a reload would otherwise register it twice
        return key;
    }

    private void add(ShapedRecipe r) {
        r.setCategory(CraftingBookCategory.BUILDING);
        plugin.getServer().addRecipe(r);
        recipes.add(r.getKey());
    }

    /** The token every hut recipe needs; made from a stick with /colony token. Like MineColonies' build tool, it is kept. */
    ItemStack token() {
        ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(m -> {
            m.itemName(Component.text("Colony Token", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Crafts colony huts; you keep it", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(tokenKey, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    boolean isToken(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(tokenKey);
    }

    ItemStack hutItem(Hut.Type type) {
        ItemStack item = new ItemStack(type.block);
        item.editMeta(m -> {
            m.itemName(Component.text(type.title, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(type.lore, NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(hutKey, PersistentDataType.STRING, type.name());
        });
        return item;
    }

    /** The hut an item places, or null. */
    Hut.Type hutType(ItemStack item) {
        String type = item.getPersistentDataContainer().get(hutKey, PersistentDataType.STRING);
        return type == null ? null : Hut.Type.valueOf(type);
    }

    /** Hut recipes keep their tokens: they go back into the grid (or to the player) after each craft. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe r) || !recipes.contains(r.getKey())) return;
        Player p = (Player) e.getWhoClicked();
        CraftingInventory grid = e.getInventory();
        List<Integer> tokenSlots = new ArrayList<>();
        ItemStack[] matrix = grid.getMatrix();
        for (int i = 0; i < matrix.length; i++) if (isToken(matrix[i])) tokenSlots.add(i);
        if (tokenSlots.isEmpty()) return; // the field: no token to keep
        if (e.isShiftClick()) { // crafts many at once, and the tokens can't be put back in between
            e.setCancelled(true);
            p.sendMessage(Component.text("Take colony huts out one at a time.", NamedTextColor.RED));
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> { // after vanilla has used up the ingredients
            boolean open = p.getOpenInventory().getTopInventory().equals(grid);
            ItemStack[] now = grid.getMatrix();
            for (int i : tokenSlots) {
                if (open && (now[i] == null || now[i].isEmpty())) now[i] = token();
                else if (open && isToken(now[i])) now[i].add();
                else p.getInventory().addItem(token()).values().forEach(o -> p.getWorld().dropItem(p.getLocation(), o));
            }
            if (open) grid.setMatrix(now);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(recipes); // show them in the recipe book
    }
}
