package dev.minecp;

import dev.minecp.colony.Hut;
import dev.minecp.logistics.Rack;

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

/** The plugin's items: the Build Tool, hut items, and their recipes (the Build Tool is kept when crafting). */
public final class Items implements Listener {
    private final Plugin plugin;
    private final NamespacedKey hutKey, buildToolKey, gogglesKey;
    private final List<NamespacedKey> recipes = new ArrayList<>();

    Items(Plugin plugin) {
        this.plugin = plugin;
        hutKey = new NamespacedKey(plugin, "hut_item");
        buildToolKey = new NamespacedKey(plugin, "build_tool");
        gogglesKey = new NamespacedKey(plugin, "build_goggles");
    }

    /**
     * Hut recipes in MineColonies' shapes (their gold scepter replaced by our Build Tool). Registered here, not in a
     * data pack, because only plugin recipes can demand an exact item: an ordinary stick never matches the Build Tool.
     */
    void addRecipes() {
        RecipeChoice tool = RecipeChoice.exactChoice(buildTool());
        for (Hut.Type type : Hut.Type.values()) {
            RecipeChoice centre = type == Hut.Type.TOWN_HALL ? tool : type.centre == null ? null : type.centre.get();
            if (centre == null) continue;
            ShapedRecipe r = new ShapedRecipe(key(type.name().toLowerCase()), hutItem(type));
            r.shape("PTP", type.sides != null ? "SXS" : "PXP", "PPP");
            r.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
            if (type.sides != null) r.setIngredient('S', type.sides.get());
            r.setIngredient('T', tool);
            r.setIngredient('X', centre);
            add(r);
        }
        // MineColonies' storage rack: planks, iron nuggets, and the build tool in the middle.
        ShapedRecipe rack = new ShapedRecipe(key("rack"), Rack.item());
        rack.shape("PPP", "ITI", "PPP");
        rack.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        rack.setIngredient('I', new RecipeChoice.MaterialChoice(Material.IRON_NUGGET));
        rack.setIngredient('T', tool);
        add(rack);
        // MineColonies' build goggles: gold nuggets, an iron ingot, glass panes and leather around the build tool.
        ShapedRecipe goggles = new ShapedRecipe(key("build_goggles"), goggles());
        goggles.shape("NIN", "GTG", "L L");
        goggles.setIngredient('N', new RecipeChoice.MaterialChoice(Material.GOLD_NUGGET));
        goggles.setIngredient('I', new RecipeChoice.MaterialChoice(Material.IRON_INGOT));
        goggles.setIngredient('G', new RecipeChoice.MaterialChoice(Material.GLASS_PANE));
        goggles.setIngredient('L', new RecipeChoice.MaterialChoice(Material.LEATHER));
        goggles.setIngredient('T', tool);
        add(goggles);
        // The field is MineColonies' scarecrow, whose recipe takes no build tool: hay bale or pumpkin on a stick body.
        ShapedRecipe field = new ShapedRecipe(key("field"), hutItem(Hut.Type.FIELD));
        field.shape(" H ", "SLS", " S ");
        field.setIngredient('H', new RecipeChoice.MaterialChoice(Material.HAY_BLOCK, Material.PUMPKIN));
        field.setIngredient('S', RecipeChoice.exactChoice(new ItemStack(Material.STICK))); // plain sticks, never the Build Tool
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

    /**
     * Tags the item for the optional resource pack (tools/build_resourcepack.py): a custom_model_data string
     * "minecp:<id>". A player with the pack sees the mod's model; everyone else sees the plain vanilla item, as the
     * tag changes nothing without the pack. The server neither knows nor cares who has it.
     */
    public static void model(ItemStack item, String id) {
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString("minecp:" + id).build());
    }

    /** The Build Tool: places huts (Placement) and every hut recipe needs it; made from a stick with /colony buildtool. Kept when crafting, as in the mod. */
    ItemStack buildTool() {
        ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(m -> {
            m.itemName(Component.text("Build Tool", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Crafts colony huts; you keep it", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(buildToolKey, PersistentDataType.BOOLEAN, true);
        });
        model(item, "build_tool");
        return item;
    }

    /** Worn, they show colony borders (Border). A leather helmet stands in for the mod's goggles model. */
    private ItemStack goggles() {
        ItemStack item = new ItemStack(Material.LEATHER_HELMET);
        item.editMeta(org.bukkit.inventory.meta.LeatherArmorMeta.class, m -> {
            m.itemName(Component.text("Build Goggles", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Wear them to see colony borders", NamedTextColor.GRAY)));
            m.setColor(org.bukkit.Color.fromRGB(0x8B5A2B));
            m.getPersistentDataContainer().set(gogglesKey, PersistentDataType.BOOLEAN, true);
        });
        model(item, "build_goggles");
        return item;
    }

    public boolean isGoggles(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(gogglesKey);
    }

    public boolean isBuildTool(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(buildToolKey);
    }

    ItemStack hutItem(Hut.Type type) {
        ItemStack item = new ItemStack(type.block);
        item.editMeta(m -> {
            m.itemName(Component.text(type.title, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(type.lore, NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(hutKey, PersistentDataType.STRING, type.name());
        });
        model(item, "hut/" + type.name().toLowerCase());
        return item;
    }

    /** The hut an item places, or null. */
    public Hut.Type hutType(ItemStack item) {
        String type = item.getPersistentDataContainer().get(hutKey, PersistentDataType.STRING);
        return type == null ? null : Hut.Type.valueOf(type);
    }

    /** Recipes keep their Build Tool: it goes back into the grid (or to the player) after each craft. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe r) || !recipes.contains(r.getKey())) return;
        Player p = (Player) e.getWhoClicked();
        CraftingInventory grid = e.getInventory();
        List<Integer> toolSlots = new ArrayList<>();
        ItemStack[] matrix = grid.getMatrix();
        for (int i = 0; i < matrix.length; i++) if (isBuildTool(matrix[i])) toolSlots.add(i);
        if (toolSlots.isEmpty()) return; // the field: no Build Tool to keep
        if (e.isShiftClick()) { // crafts many at once, and the Build Tool can't be put back in between
            e.setCancelled(true);
            p.sendMessage(Component.text("Take colony huts out one at a time.", NamedTextColor.RED));
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> { // after vanilla has used up the ingredients
            boolean open = p.getOpenInventory().getTopInventory().equals(grid);
            ItemStack[] now = grid.getMatrix();
            for (int i : toolSlots) {
                if (open && (now[i] == null || now[i].isEmpty())) now[i] = buildTool();
                else if (open && isBuildTool(now[i])) now[i].add();
                else p.getInventory().addItem(buildTool()).values().forEach(o -> p.getWorld().dropItem(p.getLocation(), o));
            }
            if (open) grid.setMatrix(now);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(recipes); // show them in the recipe book
    }
}
