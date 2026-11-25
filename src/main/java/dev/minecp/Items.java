package dev.minecp;

import dev.minecp.colony.Flag;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Rack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
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
    private final NamespacedKey hutKey, buildToolKey, gogglesKey, clipboardKey, scrollKey, supplyKey, starterKey, scepterKey, scepterLastKey, bannerKey, rallyingKey, mapKey;
    private final List<NamespacedKey> recipes = new ArrayList<>();

    Items(Plugin plugin) {
        this.plugin = plugin;
        hutKey = new NamespacedKey(MineCP.NAMESPACE, "hut_item");
        buildToolKey = new NamespacedKey(MineCP.NAMESPACE, "build_tool");
        gogglesKey = new NamespacedKey(MineCP.NAMESPACE, "build_goggles");
        clipboardKey = new NamespacedKey(MineCP.NAMESPACE, "clipboard");     // value: the colony owner's UUID, "" until bound
        scrollKey = new NamespacedKey(MineCP.NAMESPACE, "resource_scroll");
        supplyKey = new NamespacedKey(MineCP.NAMESPACE, "supply");     // value: "camp" or "ship"
        starterKey = new NamespacedKey(MineCP.NAMESPACE, "starter");   // the converter's stand-ins in the supplies' barrels
        scepterKey = new NamespacedKey(MineCP.NAMESPACE, "guard_scepter");       // value: its guard hut, "world,x,y,z"
        scepterLastKey = new NamespacedKey(MineCP.NAMESPACE, "guard_scepter_last"); // the block it was used on last
        bannerKey = new NamespacedKey(MineCP.NAMESPACE, "rally_banner");         // value: its guard huts, separated by ";"
        rallyingKey = new NamespacedKey(MineCP.NAMESPACE, "rallying");
        mapKey = new NamespacedKey(MineCP.NAMESPACE, "colony_map");
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
            // the School's recipe has its feather in the middle twice over (the mod's)
            r.shape("PTP", type.sides != null ? "SXS" : "PXP", type == Hut.Type.SCHOOL ? "PXP" : "PPP");
            r.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
            if (type.sides != null) r.setIngredient('S', type.sides.get());
            if (type.isQuarry()) { // the mod's: the pickaxe over a barrel
                r.shape("PTP", "PXP", "PBP");
                r.setIngredient('B', Material.BARREL);
            }
            if (type == Hut.Type.GATE_HOUSE) { // the mod's: an iron sword and a bow either side of planks
                r.shape("PTP", "SXB", "PPP");
                r.setIngredient('S', Material.IRON_SWORD);
                r.setIngredient('B', Material.BOW);
            }
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
        // MineColonies' clipboard and resource scroll: sticks around leather (two for the scroll), the build tool on top.
        RecipeChoice stick = RecipeChoice.exactChoice(new ItemStack(Material.STICK)); // plain sticks, never the Build Tool
        ShapedRecipe clipboard = new ShapedRecipe(key("clipboard"), clipboard());
        clipboard.shape("XTX", "XPX", "XXX");
        clipboard.setIngredient('X', stick);
        clipboard.setIngredient('T', tool);
        clipboard.setIngredient('P', new RecipeChoice.MaterialChoice(Material.LEATHER));
        add(clipboard);
        ShapedRecipe scroll = new ShapedRecipe(key("resource_scroll"), resourceScroll());
        scroll.shape("XTX", "XPX", "XPX");
        scroll.setIngredient('X', stick);
        scroll.setIngredient('T', tool);
        scroll.setIngredient('P', new RecipeChoice.MaterialChoice(Material.LEATHER));
        add(scroll);
        // The field is MineColonies' scarecrow, whose recipe takes no build tool: hay bale or pumpkin on a stick body.
        ShapedRecipe field = new ShapedRecipe(key("field"), hutItem(Hut.Type.FIELD));
        field.shape(" H ", "SLS", " S ");
        field.setIngredient('H', new RecipeChoice.MaterialChoice(Material.HAY_BLOCK, Material.PUMPKIN));
        field.setIngredient('S', RecipeChoice.exactChoice(new ItemStack(Material.STICK))); // plain sticks, never the Build Tool
        field.setIngredient('L', new RecipeChoice.MaterialChoice(Material.LEATHER));
        add(field);
        // MineColonies' university: planks around the build tool and two books.
        ShapedRecipe university = new ShapedRecipe(key("university"), hutItem(Hut.Type.UNIVERSITY));
        university.shape("PTP", "PBP", "PBP");
        university.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        university.setIngredient('T', tool);
        university.setIngredient('B', new RecipeChoice.MaterialChoice(Material.BOOK));
        add(university);
        // MineColonies' rally banner: iron swords, guard towers beside a yellow banner, bows.
        ShapedRecipe rally = new ShapedRecipe(key("rally_banner"), rallyBanner());
        rally.shape("AAA", "BXB", "CCC");
        rally.setIngredient('A', new RecipeChoice.MaterialChoice(Material.IRON_SWORD));
        rally.setIngredient('B', RecipeChoice.exactChoice(hutItem(Hut.Type.GUARD_TOWER)));
        rally.setIngredient('X', RecipeChoice.exactChoice(new ItemStack(Material.YELLOW_BANNER))); // plain, not another Rally Banner
        rally.setIngredient('C', new RecipeChoice.MaterialChoice(Material.BOW));
        add(rally);
        // MineColonies' colony banner: two wool over the build tool.
        ShapedRecipe flag = new ShapedRecipe(key("colony_banner"), Flag.item());
        flag.shape("W", "W", "T");
        flag.setIngredient('W', new RecipeChoice.MaterialChoice(Tag.WOOL));
        flag.setIngredient('T', tool);
        add(flag);
        // MineColonies' colony signs: two rows of planks over the build tool make 6.
        ShapedRecipe sign = new ShapedRecipe(key("colony_sign"), dev.minecp.colony.ColonySign.item(6));
        sign.shape("XXX", "XXX", " T ");
        sign.setIngredient('X', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        sign.setIngredient('T', tool);
        add(sign);
        // MineColonies' colony map: maps around the build tool.
        ShapedRecipe map = new ShapedRecipe(key("colony_map"), colonyMap());
        map.shape("XXX", "XTX", "XXX");
        map.setIngredient('X', new RecipeChoice.MaterialChoice(Material.MAP));
        map.setIngredient('T', tool);
        add(map);
        // MineColonies' supply camp (chests) and supply ship (boats): a row of three under two corners. Plain items only
        // (chests or trapped chests; any boat): hut items and racks are chests too, and a Supply Ship is a boat.
        ShapedRecipe camp = new ShapedRecipe(key("supply_camp"), supply(false));
        camp.shape("C C", "CCC");
        camp.setIngredient('C', RecipeChoice.exactChoice(new ItemStack(Material.CHEST), new ItemStack(Material.TRAPPED_CHEST)));
        add(camp);
        ShapedRecipe ship = new ShapedRecipe(key("supply_ship"), supply(true));
        ship.shape("B B", "BBB");
        ship.setIngredient('B', RecipeChoice.exactChoice(Tag.ITEMS_BOATS.getValues().stream().map(ItemStack::new).toList()));
        add(ship);
    }

    /**
     * The mod's supply camp or ship deployer: right-click to place the camp on land or the ship on water, with a
     * Town Hall, a Build Tool and supplies in it (Placement). Paper stands in for its model: it places nothing itself.
     */
    ItemStack supply(boolean ship) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(m -> {
            m.itemName(Component.text(ship ? "Supply Ship" : "Supply Camp", NamedTextColor.GOLD));
            m.lore(List.of(Component.text(ship ? "Right-click by the water to place a ship with" : "Right-click the ground to place a camp with", NamedTextColor.GRAY),
                    Component.text("a Town Hall, a Build Tool and supplies. One per player.", NamedTextColor.GRAY)));
            m.setMaxStackSize(1); // as the mod's
            m.getPersistentDataContainer().set(supplyKey, PersistentDataType.STRING, ship ? "ship" : "camp");
        });
        model(item, ship ? "supply_ship" : "supply_camp");
        return item;
    }

    /** "camp" or "ship" for a supply item, else null. */
    public String supplyKind(ItemStack item) {
        return item == null ? null : item.getPersistentDataContainer().get(supplyKey, PersistentDataType.STRING);
    }

    /**
     * The plugin's item for a converter stand-in in a supply camp or ship's barrel (its Town Hall and gold scepter),
     * else null.
     */
    public ItemStack starter(ItemStack item) {
        String kind = item == null ? null : item.getPersistentDataContainer().get(starterKey, PersistentDataType.STRING);
        if (kind == null) return null;
        ItemStack real = switch (kind) {
            case "town_hall" -> hutItem(Hut.Type.TOWN_HALL);
            case "build_tool" -> buildTool();
            default -> null;
        };
        return real == null ? null : real.asQuantity(item.getAmount());
    }

    private NamespacedKey key(String name) {
        NamespacedKey key = new NamespacedKey(MineCP.NAMESPACE, name);
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
    public ItemStack buildTool() {
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

    /** The colony's open requests waiting for a player (Menus.clipboard); bound by right-clicking any of its huts. Paper stands in for its model. */
    private ItemStack clipboard() {
        return note(Material.PAPER, "Clipboard", "Right-click a colony's hut to note its requests", clipboardKey, "clipboard");
    }

    /** A Builder's Hut's material list (Menus.resourceScroll); bound by right-clicking the Builder's Hut. */
    private ItemStack resourceScroll() {
        return note(Material.PAPER, "Resource Scroll", "Right-click a Builder's Hut to link its materials", scrollKey, "resource_scroll");
    }

    /** Becomes a colony's map (below) when used on one of its huts. Paper, as an empty map would fill in where it's used. */
    private ItemStack colonyMap() {
        return note(Material.PAPER, "Colony Map", "Right-click a colony's hut to map it", mapKey, "colony_map");
    }

    /** A colony's map: a vanilla map with the borders on it (MapBorders). */
    ItemStack colonyMap(dev.minecp.colony.Colony c, org.bukkit.map.MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        item.editMeta(org.bukkit.inventory.meta.MapMeta.class, m -> {
            m.itemName(Component.text("Colony Map", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Map of " + c.name + ", " + dev.minecp.build.MapBorders.across(view) + " blocks across", NamedTextColor.GRAY),
                    Component.text("Right-click a hut to zoom in or out", NamedTextColor.GRAY)));
            m.setMapView(view);
            m.getPersistentDataContainer().set(mapKey, PersistentDataType.STRING, "");
        });
        return item;
    }

    boolean isColonyMap(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(mapKey);
    }

    private ItemStack note(Material base, String name, String hint, NamespacedKey key, String model) {
        ItemStack item = new ItemStack(base);
        item.editMeta(m -> {
            m.itemName(Component.text(name, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(hint, NamedTextColor.GRAY)));
            m.setMaxStackSize(1); // each one remembers its own colony
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, "");
        });
        model(item, model);
        return item;
    }

    boolean isClipboard(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(clipboardKey);
    }

    boolean isResourceScroll(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(scrollKey);
    }

    /** The owner of the colony a clipboard or resource scroll is bound to, or null. */
    java.util.UUID boundTo(ItemStack item) {
        String id = item.getPersistentDataContainer().get(isClipboard(item) ? clipboardKey : scrollKey, PersistentDataType.STRING);
        try {
            return id == null || id.isEmpty() ? null : java.util.UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Binds a clipboard or resource scroll to a colony, and says which on the item. */
    void bind(ItemStack item, dev.minecp.colony.Colony c) {
        NamespacedKey key = isClipboard(item) ? clipboardKey : scrollKey;
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, c.owner.toString());
            m.lore(List.of(Component.text((isClipboard(item) ? "Requests of " : "Builder of ") + c.name, NamedTextColor.GRAY)));
        });
    }

    /** The mod's guard scepter for one guard hut (GuardTools): a blaze rod stands in for its model. */
    ItemStack guardScepter(Block hut, boolean patrol) {
        ItemStack item = note(Material.BLAZE_ROD, "Guard Scepter", patrol ? "Right-click each patrol point in turn, the last one twice"
                : "Right-click the spot to guard", scepterKey, "guard_scepter");
        item.editMeta(m -> m.getPersistentDataContainer().set(scepterKey, PersistentDataType.STRING, place(hut)));
        return item;
    }

    boolean isGuardScepter(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(scepterKey);
    }

    /** The guard hut's block of a scepter, or null. */
    Block scepterHut(ItemStack item) {
        return placeAt(item.getPersistentDataContainer().get(scepterKey, PersistentDataType.STRING));
    }

    /** The block a scepter was used on last, or null. */
    Block scepterLast(ItemStack item) {
        return placeAt(item.getPersistentDataContainer().get(scepterLastKey, PersistentDataType.STRING));
    }

    void scepterLast(ItemStack item, Block at) {
        item.editMeta(m -> m.getPersistentDataContainer().set(scepterLastKey, PersistentDataType.STRING, place(at)));
    }

    /** The mod's rally banner (GuardTools): a yellow banner with a tag. */
    private ItemStack rallyBanner() {
        ItemStack item = new ItemStack(Material.YELLOW_BANNER);
        item.editMeta(m -> {
            m.itemName(Component.text("Rally Banner", NamedTextColor.GOLD));
            m.setMaxStackSize(1);
            m.getPersistentDataContainer().set(bannerKey, PersistentDataType.STRING, "");
        });
        bannerHuts(item, List.of());
        return item;
    }

    public boolean isRallyBanner(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(bannerKey);
    }

    /** The guard hut blocks a rally banner calls. */
    public List<Block> bannerHuts(ItemStack item) {
        String s = item.getPersistentDataContainer().getOrDefault(bannerKey, PersistentDataType.STRING, "");
        List<Block> out = new ArrayList<>();
        for (String one : s.split(";")) if (placeAt(one) instanceof Block b) out.add(b);
        return out;
    }

    void bannerHuts(ItemStack item, List<Block> huts) {
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(bannerKey, PersistentDataType.STRING, String.join(";", huts.stream().map(Items::place).toList()));
            m.lore(List.of(Component.text(huts.isEmpty() ? "Right-click guard huts to add them" : "Rallies " + huts.size() + (huts.size() == 1 ? " guard hut" : " guard huts"), NamedTextColor.GRAY),
                    Component.text("Sneak and right-click to rally or stop", NamedTextColor.GRAY)));
        });
    }

    /** Whether a rally banner is active (it glints). */
    public boolean rallying(ItemStack item) {
        return isRallyBanner(item) && item.getPersistentDataContainer().has(rallyingKey);
    }

    void rallying(ItemStack item, boolean on) {
        item.editMeta(m -> {
            if (on) m.getPersistentDataContainer().set(rallyingKey, PersistentDataType.BOOLEAN, true);
            else m.getPersistentDataContainer().remove(rallyingKey);
            m.setEnchantmentGlintOverride(on ? true : null);
        });
    }

    private static String place(Block b) {
        return b.getWorld().getUID() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static Block placeAt(String s) {
        String[] p = s == null ? new String[0] : s.split(",");
        if (p.length != 4) return null;
        var world = org.bukkit.Bukkit.getWorld(java.util.UUID.fromString(p[0]));
        return world == null ? null : world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    public boolean isGoggles(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(gogglesKey);
    }

    public boolean isBuildTool(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(buildToolKey);
    }

    public ItemStack hutItem(Hut.Type type) {
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

    /**
     * MineCP's items are vanilla items with a tag (paper, chests, a stick...), so vanilla recipes would take them as
     * plain ones: a Supply Camp into a book, the Build Tool into torches. Only MineCP's own recipes may use them.
     */
    @EventHandler
    public void onPrepareCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof org.bukkit.Keyed k && recipes.contains(k.getKey())) return;
        for (ItemStack it : e.getInventory().getMatrix())
            if (ours(it)) {
                e.getInventory().setResult(null);
                return;
            }
    }

    /** The Crafter block too; nor may it make our own recipes with the Build Tool, which it wouldn't give back. */
    @EventHandler(ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (!(e.getBlock().getState(false) instanceof org.bukkit.block.Crafter crafter)) return;
        boolean ownRecipe = recipes.contains(e.getRecipe().getKey());
        for (ItemStack it : crafter.getInventory().getContents())
            if (ours(it) && (!ownRecipe || isBuildTool(it))) {
                e.setCancelled(true);
                return;
            }
    }

    /** One of MineCP's own items. */
    static boolean ours(ItemStack item) {
        return item != null && item.getPersistentDataContainer().getKeys().stream().anyMatch(key -> key.getNamespace().equals(MineCP.NAMESPACE));
    }

    /** A Cartography Table only copies a Colony Map: zoomed or locked it would be another map, without borders. Nor does it use up our paper items. */
    @EventHandler
    public void onPrepareMap(com.destroystokyo.paper.event.inventory.PrepareResultEvent e) {
        if (!(e.getInventory() instanceof org.bukkit.inventory.CartographyInventory table)) return;
        ItemStack map = table.getItem(0), added = table.getItem(1);
        if (ours(added) || isColonyMap(map) && added != null && added.getType() != Material.MAP) e.setResult(null);
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
