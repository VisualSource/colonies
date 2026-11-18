package dev.minecp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MineCP extends JavaPlugin implements Listener {
    private static final long AUTOSAVE_TICKS = 20 * 60 * 5;
    private final Map<UUID, Colony> colonies = new HashMap<>(); // by owner
    private static final int HUT_RANGE = 64;
    private NamespacedKey hutKey, tokenKey;
    private final List<NamespacedKey> recipes = new java.util.ArrayList<>();
    private File dataFile;
    private YamlConfiguration data; // kept in memory so colonies in unloaded worlds survive a save
    private final Menus menus = new Menus(this);
    private final Placement placement = new Placement(this);

    @Override
    public void onEnable() {
        hutKey = new NamespacedKey(this, "hut_item");
        tokenKey = new NamespacedKey(this, "token");
        addRecipes();
        dataFile = new File(getDataFolder(), "colonies.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        var section = data.getConfigurationSection("colonies");
        if (section != null) for (String owner : section.getKeys(false)) {
            Colony colony = Colony.load(UUID.fromString(owner), section.getConfigurationSection(owner));
            if (colony == null) getLogger().warning("Colony of " + owner + " is in a world that is not loaded; skipped.");
            else colonies.put(colony.owner, colony);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(placement, this);
        getServer().getScheduler().runTaskTimer(this, () -> colonies.values().forEach(Colony::tick), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> colonies.values().forEach(Colony::tape), 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::save, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
    }

    /**
     * Hut recipes in MineColonies' shapes (their gold scepter replaced by our Colony Token). Registered here, not in a
     * data pack, because only plugin recipes can demand an exact item: an ordinary stick never matches the token.
     */
    private void addRecipes() {
        RecipeChoice token = new RecipeChoice.ExactChoice(tokenItem());
        Map<Hut.Type, RecipeChoice> middle = Map.of(
                Hut.Type.TOWN_HALL, token,
                Hut.Type.BUILDER, new RecipeChoice.MaterialChoice(Tag.WOODEN_DOORS),
                Hut.Type.HOUSE, new RecipeChoice.MaterialChoice(Material.TORCH),
                Hut.Type.LUMBERJACK, new RecipeChoice.MaterialChoice(Material.WOODEN_AXE),
                Hut.Type.FARMER, new RecipeChoice.MaterialChoice(Material.WOODEN_HOE),
                Hut.Type.COOK, new RecipeChoice.MaterialChoice(Material.APPLE),
                Hut.Type.WAREHOUSE, new RecipeChoice.MaterialChoice(Material.CHEST),
                Hut.Type.COURIER, new RecipeChoice.MaterialChoice(Material.LEATHER_BOOTS),
                Hut.Type.MINER, new RecipeChoice.MaterialChoice(Material.WOODEN_PICKAXE));
        middle.forEach((type, centre) -> {
            NamespacedKey key = new NamespacedKey(this, type.name().toLowerCase());
            getServer().removeRecipe(key); // a reload would otherwise register it twice
            ShapedRecipe r = new ShapedRecipe(key, hutItem(type));
            r.shape("PTP", "PXP", "PPP");
            r.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
            r.setIngredient('T', token);
            r.setIngredient('X', centre);
            r.setCategory(CraftingBookCategory.BUILDING);
            getServer().addRecipe(r);
            recipes.add(key);
        });
        // MineColonies' storage rack: planks, iron nuggets, and the build tool (our token) in the middle.
        NamespacedKey rackKey = new NamespacedKey(this, "rack");
        getServer().removeRecipe(rackKey);
        ShapedRecipe rack = new ShapedRecipe(rackKey, Rack.item());
        rack.shape("PPP", "ITI", "PPP");
        rack.setIngredient('P', new RecipeChoice.MaterialChoice(Tag.PLANKS));
        rack.setIngredient('I', new RecipeChoice.MaterialChoice(Material.IRON_NUGGET));
        rack.setIngredient('T', token);
        rack.setCategory(CraftingBookCategory.BUILDING);
        getServer().addRecipe(rack);
        recipes.add(rackKey);
        // The field is MineColonies' scarecrow, whose recipe takes no build tool: hay bale or pumpkin on a stick body.
        NamespacedKey key = new NamespacedKey(this, "field");
        getServer().removeRecipe(key);
        ShapedRecipe field = new ShapedRecipe(key, hutItem(Hut.Type.FIELD));
        field.shape(" H ", "SLS", " S ");
        field.setIngredient('H', new RecipeChoice.MaterialChoice(Material.HAY_BLOCK, Material.PUMPKIN));
        field.setIngredient('S', new RecipeChoice.ExactChoice(new ItemStack(Material.STICK))); // plain sticks, never the token
        field.setIngredient('L', new RecipeChoice.MaterialChoice(Material.LEATHER));
        field.setCategory(CraftingBookCategory.BUILDING);
        getServer().addRecipe(field);
        recipes.add(key);
    }

    /** The token every hut recipe needs; made from a stick with /colony token. Like MineColonies' build tool, it is kept. */
    private ItemStack tokenItem() {
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

    /** Hut recipes keep their tokens: they go back into the grid (or to the player) after each craft. */
    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe r) || !recipes.contains(r.getKey())) return;
        Player p = (Player) e.getWhoClicked();
        CraftingInventory grid = e.getInventory();
        List<Integer> tokenSlots = new java.util.ArrayList<>();
        ItemStack[] matrix = grid.getMatrix();
        for (int i = 0; i < matrix.length; i++) if (isToken(matrix[i])) tokenSlots.add(i);
        if (tokenSlots.isEmpty()) return; // the field: no token to keep
        if (e.isShiftClick()) { // crafts many at once, and the tokens can't be put back in between
            e.setCancelled(true);
            p.sendMessage(Component.text("Take colony huts out one at a time.", NamedTextColor.RED));
            return;
        }
        getServer().getScheduler().runTask(this, () -> { // after vanilla has used up the ingredients
            boolean open = p.getOpenInventory().getTopInventory().equals(grid);
            ItemStack[] now = grid.getMatrix();
            for (int i : tokenSlots) {
                if (open && (now[i] == null || now[i].isEmpty())) now[i] = tokenItem();
                else if (open && isToken(now[i])) now[i].add();
                else p.getInventory().addItem(tokenItem()).values().forEach(o -> p.getWorld().dropItem(p.getLocation(), o));
            }
            if (open) grid.setMatrix(now);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(recipes); // show them in the recipe book
    }

    @Override
    public void onDisable() {
        placement.endAll();
        save();
        colonies.values().forEach(Colony::despawn);
    }

    void save() {
        for (Colony c : colonies.values()) {
            data.set("colonies." + c.owner, null);
            c.save(data.createSection("colonies." + c.owner));
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save colonies: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) return false;
        switch (args[0]) {
            case "townhall", "builderhut", "house", "lumberjack", "farmer", "field", "cook", "warehouse", "courier", "miner" -> {
                if (!player.hasPermission("minecp.admin")) {
                    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                player.getInventory().addItem(hutItem(switch (args[0]) {
                    case "townhall" -> Hut.Type.TOWN_HALL;
                    case "builderhut" -> Hut.Type.BUILDER;
                    case "lumberjack" -> Hut.Type.LUMBERJACK;
                    case "farmer" -> Hut.Type.FARMER;
                    case "field" -> Hut.Type.FIELD;
                    case "cook" -> Hut.Type.COOK;
                    case "warehouse" -> Hut.Type.WAREHOUSE;
                    case "courier" -> Hut.Type.COURIER;
                    case "miner" -> Hut.Type.MINER;
                    default -> Hut.Type.HOUSE;
                }));
            }
            case "rack" -> {
                if (!player.hasPermission("minecp.admin")) {
                    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                player.getInventory().addItem(Rack.item().asQuantity(16));
            }
            case "token" -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!hand.isSimilar(new ItemStack(Material.STICK))) { // plain sticks only, not renamed or tokens already
                    player.sendMessage(Component.text("Hold a stick to turn it into a Colony Token.", NamedTextColor.RED));
                    return true;
                }
                hand.subtract(); // one is enough: the token is kept when crafting
                player.getInventory().setItemInMainHand(hand.isEmpty() ? null : hand);
                player.getInventory().addItem(tokenItem()).values().forEach(o -> player.getWorld().dropItem(player.getLocation(), o));
                player.sendMessage(Component.text("Colony Token made. It's kept when you craft huts.", NamedTextColor.GREEN));
            }
            case "build" -> {
                Colony c = colonies.get(player.getUniqueId());
                if (c == null || c.builder == null) {
                    player.sendMessage(Component.text("You need a colony with a Builder's Hut.", NamedTextColor.RED));
                    return true;
                }
                if (args.length != 2) return false;
                if (args[1].equals("cancel")) {
                    c.builder.cancel();
                    player.sendMessage("Build cancelled.");
                    return true;
                }
                if (c.builder.order != null) {
                    player.sendMessage(Component.text("The builder is busy. Use /colony build cancel first.", NamedTextColor.RED));
                    return true;
                }
                Block target = player.getTargetBlockExact(10);
                if (target == null) {
                    player.sendMessage(Component.text("Look at the block to build on.", NamedTextColor.RED));
                    return true;
                }
                BuildOrder order = BuildOrder.create(args[1], target.getRelative(0, 1, 0).getLocation(),
                        BuildOrder.rotationFor(player.getFacing()), null);
                if (order == null) {
                    player.sendMessage(Component.text("No structure named " + args[1]
                            + ". Save one with a structure block first.", NamedTextColor.RED));
                    return true;
                }
                c.builder.start(order, null);
                save();
                player.sendMessage("Build of " + args[1] + " queued (" + order.steps.size() + " blocks). "
                        + "Put materials in the Builder's Hut. Huts are built from their own menus.");
            }
            case "info" -> {
                Colony c = colonies.get(player.getUniqueId());
                if (c == null) {
                    player.sendMessage("You don't have a colony. Place a Town Hall to found one.");
                    return true;
                }
                var th = c.townHall().block;
                player.sendMessage(Component.text(c.name, NamedTextColor.GOLD));
                player.sendMessage("Town hall: " + th.getX() + " " + th.getY() + " " + th.getZ()
                        + " (right-click it for the colony menu)");
                player.sendMessage("Citizens: " + c.colonists.size() + " of " + c.capacity());
            }
            case "movement" -> { // how often each citizen's walking went wrong
                Colony c = colonies.get(player.getUniqueId());
                if (c == null) {
                    player.sendMessage("You don't have a colony.");
                    return true;
                }
                for (Colonist w : c.colonists) {
                    var s = w.stats;
                    player.sendMessage(w.name + ": " + s.teleports + " teleports, "
                            + s.stuck + " stuck, " + s.searches + " path searches"
                            + (s.searches == 0 ? "" : String.format(" (%.2f ms avg)", s.searchNanos / 1e6 / s.searches)));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    ItemStack hutItem(Hut.Type type) {
        ItemStack item = new ItemStack(type.block);
        item.editMeta(m -> {
            m.itemName(Component.text(type.title, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(switch (type) {
                case TOWN_HALL -> "Place to found a colony";
                case BUILDER -> "Its chest holds building materials";
                case HOUSE -> "Room for more citizens";
                case LUMBERJACK -> "A citizen cuts and replants trees nearby";
                case FARMER -> "A citizen works the colony's fields";
                case FIELD -> "Marks a farm plot; right-click it to pick the crop and size";
                case COOK -> "A restaurant: its cook cooks and serves food";
                case WAREHOUSE -> "The colony's store, which couriers work from";
                case COURIER -> "A courier carries goods between the warehouse and huts";
                case MINER -> "A citizen digs a mine below the hut";
            }, NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(hutKey, PersistentDataType.STRING, type.name());
        });
        return item;
    }

    Hut.Type hutType(ItemStack item) {
        String type = item.getPersistentDataContainer().get(hutKey, PersistentDataType.STRING);
        return type == null ? null : Hut.Type.valueOf(type);
    }

    /** The colony {@code p} owns, or null. */
    Colony colonyOf(Player p) {
        return colonies.get(p.getUniqueId());
    }

    /** The colony one of whose huts (town hall included) is this block. */
    Colony colonyAt(Block block) {
        for (Colony c : colonies.values()) if (c.hutAt(block) != null) return c;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (Rack.is(e.getItemInHand())) { Rack.tag(e.getBlockPlaced()); return; } // item data doesn't carry over to the block
        Hut.Type type = hutType(e.getItemInHand());
        if (type == null) return;
        Player p = e.getPlayer();
        String problem = placeProblem(p, type, e.getBlockPlaced());
        if (problem != null) {
            e.setCancelled(true);
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return;
        }
        placed(p, type, e.getBlockPlaced(), null, false);
    }

    /** Why {@code p} can't put a {@code type} hut at {@code at}, or null if it can. */
    String placeProblem(Player p, Hut.Type type, Block at) {
        Colony c = colonies.get(p.getUniqueId());
        if (type == Hut.Type.TOWN_HALL) return c != null ? "You already have a colony." : null;
        Block th = c == null ? null : c.townHall().block;
        return c == null ? "You need a colony first."
                : type == Hut.Type.BUILDER && c.builder != null ? "Your colony already has a Builder's Hut."
                : type == Hut.Type.WAREHOUSE && c.hut(Hut.Type.WAREHOUSE) != null ? "Your colony already has a Warehouse."
                : !th.getWorld().equals(at.getWorld()) || th.getLocation().distance(at.getLocation()) > HUT_RANGE
                        ? "Place it within " + HUT_RANGE + " blocks of your town hall." : null;
    }

    /**
     * Registers a hut block just put at {@code at} (by hand, or by the build tool with the building's turn and mirror;
     * rotation null: from the block's facing). Check {@link #placeProblem} first.
     */
    void placed(Player p, Hut.Type type, Block at, StructureRotation rotation, boolean mirror) {
        Colony c = colonies.get(p.getUniqueId());
        Hut hut;
        if (type == Hut.Type.TOWN_HALL) {
            c = Colony.found(p.getUniqueId(), p.getName() + "'s Colony", at);
            colonies.put(c.owner, c);
            hut = c.townHall();
            p.sendMessage(Component.text(c.name + " founded with " + c.colonists.size() + " citizens!", NamedTextColor.GREEN));
        } else {
            hut = c.addHut(type, at);
            p.sendMessage(Component.text(type == Hut.Type.FIELD
                    ? "Field placed. Right-click it to choose the crop and the size of the field."
                    : type.title + " placed. Right-click it to build it.", NamedTextColor.GREEN));
        }
        hut.rotation = rotation;
        hut.mirror = mirror;
        save();
    }

    /** Colonists walk their fields; vanilla would let their jumps trample the farmland. */
    @EventHandler(ignoreCancelled = true)
    public void onTrample(org.bukkit.event.entity.EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && e.getEntity().getScoreboardTags().contains(Colonist.LEGS_TAG)) e.setCancelled(true);
    }

    /** A rack drops as a Storage Rack, not a plain chest (its contents spill as usual). */
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGH)
    public void onBreakRack(BlockBreakEvent e) {
        if (!Rack.is(e.getBlock()) || colonyAt(e.getBlock()) != null || !e.isDropItems()) return;
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE)
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().toCenterLocation(), Rack.item());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Colony c = colonyAt(e.getBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        e.setDropItems(false);
        Hut hut = c.hutAt(e.getBlock());
        var at = e.getBlock().getLocation();
        if (hut.type != Hut.Type.TOWN_HALL) {
            if (hut.block.getState(false) instanceof Container ct) { // drop contents ourselves, then empty it, so they can never drop twice
                for (ItemStack item : ct.getInventory().getContents()) if (item != null) at.getWorld().dropItemNaturally(at, item);
                ct.getInventory().clear();
            }
            c.removeHut(hut);
            save();
            at.getWorld().dropItemNaturally(at, hutItem(hut.type));
            p.sendMessage(Component.text(hut.type.title + " removed.", NamedTextColor.YELLOW));
            return;
        }
        if (c.builder != null) c.removeHut(c.builder.hut); // gives back what the builder carries
        c.despawn();
        colonies.remove(c.owner);
        data.set("colonies." + c.owner, null);
        save();
        at.getWorld().dropItemNaturally(at, hutItem(Hut.Type.TOWN_HALL));
        p.sendMessage(Component.text(c.name + " was disbanded.", NamedTextColor.YELLOW));
    }

    /** Right-click opens the colony menus; sneak + right-click keeps the normal action (open chest, ring bell). */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (isToken(e.getItem())) return; // the build tool (Placement)
        Colony c = colonyAt(e.getClickedBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        if (p.isSneaking()) return;
        e.setCancelled(true);
        menus.hut(p, c, c.hutAt(e.getClickedBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    /** Hits on a citizen's body or legs hurt the citizen instead (Colony.hurt). */
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.LOW)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        for (Colony c : colonies.values()) {
            Colonist w = c.colonistOf(e.getEntity());
            if (w != null) { c.hurt(w, e); return; }
        }
    }

    private Colony graveColony(Block b) {
        for (Colony c : colonies.values()) if (c.graveAt(b) != null) return c;
        return null;
    }

    /** Right-click a grave to take what is in it; breaking it drops everything. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onGraveClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Colony c = graveColony(e.getClickedBlock());
        if (c == null) return;
        e.setCancelled(true);
        Grave g = c.graveAt(e.getClickedBlock());
        if (!e.getPlayer().getUniqueId().equals(c.owner)) {
            e.getPlayer().sendMessage(Component.text("This grave belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        e.getPlayer().openInventory(g.items);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGraveBreak(BlockBreakEvent e) {
        Colony c = graveColony(e.getBlock());
        if (c == null) return;
        if (!e.getPlayer().getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("This grave belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        Grave g = c.graveAt(e.getBlock());
        e.setDropItems(false);
        g.dropAll();
        c.graves.remove(g);
    }
}
