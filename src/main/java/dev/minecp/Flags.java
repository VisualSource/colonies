package dev.minecp;

import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Flag;
import dev.minecp.colony.Hut;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.LoomView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Colony banners (placing, breaking, following their colony's flag) and the loom a flag is designed in: a vanilla loom
 * screen whose banner, dye and pattern item are the plugin's and free, as the mod's picker (WindowBannerPicker).
 */
public final class Flags implements Listener {
    private static final int BANNER = 0, DYE = 1, PATTERN = 2, RESULT = 3; // a loom's slots

    private record Design(Colony colony, Hut townHall, LoomView view) {}

    private final MineCP plugin;
    private final Map<UUID, Design> designing = new HashMap<>();

    Flags(MineCP plugin) {
        this.plugin = plugin;
    }

    // ---- the loom --------------------------------------------------------------------------------------------------

    public void design(Player p, Colony c, Hut townHall) {
        LoomView view = MenuType.LOOM.builder().title(Component.text("Flag of " + c.name)).checkReachable(false).build(p);
        p.openInventory(view);
        if (!p.getOpenInventory().getTopInventory().equals(view.getTopInventory())) return; // another plugin refused it
        designing.put(p.getUniqueId(), new Design(c, townHall, view));
        Inventory loom = view.getTopInventory();
        ItemStack flag = c.flag.banner();
        loom.setItem(BANNER, shown(flag));
        loom.setItem(DYE, dye(Flag.base(flag) == DyeColor.BLACK ? DyeColor.WHITE : DyeColor.BLACK)); // the mod's first colour
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Design d = designing.get(e.getWhoClicked().getUniqueId());
        if (d == null) return;
        e.setCancelled(true); // nothing goes in or comes out
        int step = e.getClick() == ClickType.LEFT ? 1 : e.getClick() == ClickType.RIGHT ? -1 : 0;
        Inventory loom = d.view.getTopInventory();
        ItemStack banner = loom.getItem(BANNER);
        if (banner == null) return;
        List<Pattern> patterns = Flag.patterns(banner);
        switch (e.getRawSlot()) {
            case BANNER -> {
                if (e.getClick().isShiftClick()) loom.setItem(BANNER, shown(d.colony.flag.banner()));
                else if (e.getClick() == ClickType.DROP && !patterns.isEmpty())
                    loom.setItem(BANNER, shown(Flag.banner(Flag.base(banner), patterns.subList(0, patterns.size() - 1))));
                else if (step != 0) loom.setItem(BANNER, shown(Flag.banner(next(List.of(DyeColor.values()), Flag.base(banner), step), patterns)));
            }
            case DYE -> {
                ItemStack dye = loom.getItem(DYE);
                if (dye != null && step != 0)
                    loom.setItem(DYE, dye(next(List.of(DyeColor.values()), DyeColor.valueOf(dye.getType().name().replace("_DYE", "")), step)));
            }
            case PATTERN -> {
                ItemStack now = loom.getItem(PATTERN);
                List<Material> items = new ArrayList<>(patternItems(d.townHall.level));
                items.addFirst(Material.AIR); // the patterns that need no item
                if (step != 0) loom.setItem(PATTERN, pattern(next(items, now == null ? Material.AIR : now.getType(), step)));
            }
            case RESULT -> {
                ItemStack made = loom.getItem(RESULT);
                if (made == null || made.isEmpty()) return;
                loom.setItem(BANNER, shown(Flag.banner(Flag.base(made), Flag.patterns(made))));
                Player p = (Player) e.getWhoClicked();
                p.playSound(p.getLocation(), Sound.UI_LOOM_TAKE_RESULT, SoundCategory.BLOCKS, 1, 1);
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (designing.containsKey(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    /** Closing it keeps the design (the mod's Done) and goes back to the town hall. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Player p = (Player) e.getPlayer();
        Design d = finish(p);
        if (d != null && e.getReason() == InventoryCloseEvent.Reason.PLAYER)
            plugin.getServer().getScheduler().runTask(plugin, () -> { if (p.isOnline() && d.colony.huts.contains(d.townHall)) plugin.menus.hut(p, d.colony, d.townHall); });
    }

    /** Empties the loom, or vanilla would hand its items to the player, and takes over the design. */
    private Design finish(Player p) {
        Design d = designing.remove(p.getUniqueId());
        if (d == null) return null;
        Inventory loom = d.view.getTopInventory();
        ItemStack banner = loom.getItem(BANNER);
        for (int slot : new int[]{BANNER, DYE, PATTERN, RESULT}) loom.setItem(slot, null);
        if (banner == null || !d.colony.huts.contains(d.townHall)) return d;
        if (!d.colony.permissions.has(p, Action.MANAGE_HUTS)) { plugin.protection.deny(p, d.colony, Action.MANAGE_HUTS); return d; }
        if (!d.colony.flag.set(banner)) return d;
        repaint();
        plugin.save(d.colony);
        return d;
    }

    /** The plugin stops, which would leave the looms' items to the players. */
    void endAll() {
        for (UUID id : List.copyOf(designing.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) continue;
            finish(p);
            p.closeInventory();
        }
    }

    /** The items whose patterns a town hall of this level offers (the mod's EXCLUSION; those newer than the mod aren't excluded). */
    static List<Material> patternItems(int level) {
        List<Material> all = new ArrayList<>(List.of(Material.BORDURE_INDENTED_BANNER_PATTERN, Material.FLOW_BANNER_PATTERN, Material.GUSTER_BANNER_PATTERN));
        if (level >= 2) all.addAll(List.of(Material.FIELD_MASONED_BANNER_PATTERN, Material.FLOWER_BANNER_PATTERN));
        if (level >= 3) all.addAll(List.of(Material.SKULL_BANNER_PATTERN, Material.CREEPER_BANNER_PATTERN));
        if (level >= 4) all.addAll(List.of(Material.GLOBE_BANNER_PATTERN, Material.PIGLIN_BANNER_PATTERN));
        if (level >= 5) all.add(Material.MOJANG_BANNER_PATTERN);
        return all;
    }

    /** The one {@code step} places after {@code now}, round and round. */
    static <T> T next(List<T> all, T now, int step) {
        return all.get(Math.floorMod(all.indexOf(now) + step, all.size()));
    }

    private static ItemStack shown(ItemStack banner) {
        return labelled(banner, "The flag", "Pick a pattern and take the result to add it",
                "Right slot below: special patterns", "Left or right-click: the base colour", "Drop key: take the last pattern off",
                "Shift-click: the flag as it was", "Close to keep it");
    }

    private static ItemStack dye(DyeColor colour) {
        return labelled(new ItemStack(Material.valueOf(colour + "_DYE")), "The pattern's colour", "Left or right-click to change it");
    }

    private static ItemStack pattern(Material item) {
        return item == Material.AIR ? null : labelled(new ItemStack(item), "Special patterns", "Left or right-click for others", "More come with Town Hall levels");
    }

    private static ItemStack labelled(ItemStack item, String name, String... lore) {
        item.editMeta(m -> {
            m.customName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            m.lore(java.util.Arrays.stream(lore).map(l -> Component.text(l, NamedTextColor.GRAY)).toList());
        });
        return item;
    }

    // ---- colony banners --------------------------------------------------------------------------------------------

    /** A placed one belongs to the colony it stands in, else the player's own (the mod's setPlacedBy). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!Flag.is(e.getItemInHand())) return;
        Colony c = plugin.claims.colonyAt(e.getBlockPlaced().getLocation());
        Flag.tag(e.getBlockPlaced(), c != null ? c : plugin.colonyOf(e.getPlayer()));
    }

    /** Broken by a player, it drops as a Colony Banner. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!(e.getBlockState() instanceof Banner f) || !f.getPersistentDataContainer().has(Flag.KEY)) return;
        e.getItems().forEach(i -> { if (Tag.ITEMS_BANNERS.isTagged(i.getItemStack().getType())) i.setItemStack(Flag.item()); });
    }

    /** So it does when what it hung on is gone. */
    @EventHandler(ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent e) {
        if (!e.willDrop() || !Flag.is(e.getBlock())) return;
        e.setWillDrop(false);
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().toCenterLocation(), Flag.item());
    }

    /** One whose chunk wasn't loaded when its colony's flag changed, or that was placed before there was a colony here. */
    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        if (!e.isNewChunk()) repaint(e.getChunk());
    }

    private void repaint() {
        for (World w : plugin.getServer().getWorlds()) for (Chunk c : w.getLoadedChunks()) repaint(c);
    }

    private void repaint(Chunk chunk) {
        for (BlockState s : chunk.getTileEntities(Flag::is, false)) {
            Block b = s.getBlock();
            UUID owner = Flag.owner(b);
            Colony c = owner == null ? plugin.claims.colonyAt(b.getLocation()) : plugin.colonyOwnedBy(owner);
            if (c == null) continue;
            if (owner == null) Flag.tag(b, c);
            else c.flag.paint(b);
        }
    }
}
