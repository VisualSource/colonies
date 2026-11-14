package dev.minecp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
    private NamespacedKey townHallKey, builderHutKey;
    private File dataFile;
    private YamlConfiguration data; // kept in memory so colonies in unloaded worlds survive a save
    private final Menus menus = new Menus(this);

    @Override
    public void onEnable() {
        townHallKey = new NamespacedKey(this, "town_hall");
        builderHutKey = new NamespacedKey(this, "builder_hut");
        dataFile = new File(getDataFolder(), "colonies.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        var section = data.getConfigurationSection("colonies");
        if (section != null) for (String owner : section.getKeys(false)) {
            Colony colony = Colony.load(UUID.fromString(owner), section.getConfigurationSection(owner));
            if (colony == null) getLogger().warning("Colony of " + owner + " is in a world that is not loaded; skipped.");
            else colonies.put(colony.owner, colony);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> colonies.values().forEach(Colony::tick), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::save, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
    }

    @Override
    public void onDisable() {
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
            case "townhall", "builderhut" -> {
                if (!player.hasPermission("minecp.admin")) {
                    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                player.getInventory().addItem(args[0].equals("townhall") ? townHallItem() : builderHutItem());
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
                        BuildOrder.rotationFor(player.getFacing()), c.builder.hut);
                if (order == null) {
                    player.sendMessage(Component.text("No structure named " + args[1]
                            + ". Save one with a structure block first.", NamedTextColor.RED));
                    return true;
                }
                c.builder.start(order, 0);
                save();
                if (!order.origin.equals(target.getRelative(0, 1, 0).getLocation()))
                    player.sendMessage("This is a hut building: it is placed around your Builder's Hut.");
                player.sendMessage("Build of " + args[1] + " queued (" + order.steps.size() + " blocks). "
                        + "Put materials in the Builder's Hut.");
            }
            case "info" -> {
                Colony c = colonies.get(player.getUniqueId());
                if (c == null) {
                    player.sendMessage("You don't have a colony. Place a Town Hall to found one.");
                    return true;
                }
                var th = c.townHall;
                player.sendMessage(Component.text(c.name, NamedTextColor.GOLD));
                player.sendMessage("Town hall: " + th.getBlockX() + " " + th.getBlockY() + " " + th.getBlockZ());
                player.sendMessage("Citizens (" + c.colonists.size() + "): "
                        + String.join(", ", c.colonists.stream().map(x -> x.name).toList()));
                if (c.builder != null) player.sendMessage("Builder: " + (c.builder.order == null ? "idle"
                        : "building " + c.builder.order.structure));
            }
            default -> { return false; }
        }
        return true;
    }

    private ItemStack townHallItem() {
        ItemStack item = new ItemStack(Material.BELL);
        item.editMeta(m -> {
            m.itemName(Component.text("Town Hall", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Place to found a colony", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(townHallKey, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    private ItemStack builderHutItem() {
        ItemStack item = new ItemStack(Material.CHEST);
        item.editMeta(m -> {
            m.itemName(Component.text("Builder's Hut", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Place near your town hall; holds building materials", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(builderHutKey, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    /** The colony whose town hall or hut this block is. */
    private Colony colonyAt(Block block) {
        for (Colony c : colonies.values()) if (c.isTownHall(block) || c.isBuilderHut(block)) return c;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        var pdc = e.getItemInHand().getPersistentDataContainer();
        if (pdc.has(builderHutKey)) {
            placeBuilderHut(e);
            return;
        }
        if (!pdc.has(townHallKey)) return;
        Player p = e.getPlayer();
        if (colonies.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(Component.text("You already have a colony.", NamedTextColor.RED));
            return;
        }
        Colony c = Colony.found(p.getUniqueId(), p.getName() + "'s Colony", e.getBlockPlaced());
        colonies.put(c.owner, c);
        save();
        p.sendMessage(Component.text(c.name + " founded with " + c.colonists.size() + " citizens!", NamedTextColor.GREEN));
    }

    private void placeBuilderHut(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Colony c = colonies.get(p.getUniqueId());
        String problem = c == null ? "You need a colony first."
                : c.builder != null ? "Your colony already has a Builder's Hut."
                : !c.townHall.getWorld().equals(e.getBlock().getWorld())
                        || c.townHall.distance(e.getBlock().getLocation()) > HUT_RANGE
                        ? "Place it within " + HUT_RANGE + " blocks of your town hall." : null;
        if (problem != null) {
            e.setCancelled(true);
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return;
        }
        c.addBuilderHut(e.getBlockPlaced());
        save();
        p.sendMessage(Component.text("Builder's Hut placed. Look at the ground and use /colony build <structure>.",
                NamedTextColor.GREEN));
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
        if (c.isBuilderHut(e.getBlock())) {
            Inventory chest = c.builder.hut.getState(false) instanceof Container ct ? ct.getInventory() : null;
            if (chest != null) { // drop contents ourselves, then empty it, so they can never drop twice
                for (ItemStack item : chest.getContents())
                    if (item != null) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), item);
                chest.clear();
            }
            c.removeBuilderHut();
            save();
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), builderHutItem());
            p.sendMessage(Component.text("Builder's Hut removed.", NamedTextColor.YELLOW));
            return;
        }
        if (c.builder != null) c.removeBuilderHut();
        c.despawn();
        colonies.remove(c.owner);
        data.set("colonies." + c.owner, null);
        save();
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), townHallItem());
        p.sendMessage(Component.text(c.name + " was disbanded.", NamedTextColor.YELLOW));
    }

    /** Right-click opens the colony menus; sneak + right-click keeps the normal action (open chest, ring bell). */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
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
        if (c.isBuilderHut(e.getClickedBlock())) menus.builderHut(p, c);
        else menus.townHall(p, c);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null);
    }
}
