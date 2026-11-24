package dev.minecp;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.GuardPost;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The guard scepter and the rally banner (the mod's ItemScepterGuard and ItemBannerRallyGuards). */
public final class GuardTools implements Listener {
    private final MineCP plugin;

    GuardTools(MineCP plugin) {
        this.plugin = plugin;
    }

    /** A scepter for this guard hut, in place of any the player has (ours: the mod binds that one anew). */
    public void giveScepter(Player p, Hut h) {
        for (ItemStack i : p.getInventory().getContents()) if (plugin.items.isGuardScepter(i)) p.getInventory().removeItemAnySlot(i);
        ItemStack scepter = plugin.items.guardScepter(h.block, h.post.task() == GuardPost.Task.PATROL);
        p.getInventory().addItem(scepter).values().forEach(o -> p.getWorld().dropItem(p.getLocation(), o));
        p.closeDialog();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        ItemStack held = e.getItem();
        boolean scepter = plugin.items.isGuardScepter(held);
        if (!scepter && !plugin.items.isRallyBanner(held) || !e.getAction().isRightClick()) return;
        e.setCancelled(true); // neither is placed nor opens what it is used on
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block at = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock() : null;
        if (!scepter) banner(e.getPlayer(), held, at);
        else if (at != null) scepter(e.getPlayer(), held, at);
    }

    /** A patrol point or the spot to guard; a patrol ends with its last point clicked again, which uses the scepter up, as does a spot. */
    private void scepter(Player p, ItemStack scepter, Block at) {
        Block block = plugin.items.scepterHut(scepter), last = plugin.items.scepterLast(scepter);
        Colony c = block == null ? null : plugin.colonyAt(block);
        Hut h = c == null ? null : c.hutAt(block);
        if (h == null || h.post == null) { tell(p, "That scepter's guard hut is gone.", false); return; }
        if (!c.permissions.has(p, Action.MANAGE_HUTS)) { plugin.protection.deny(p, c, Action.MANAGE_HUTS); return; }
        if (at.equals(last)) {
            p.getInventory().setItemInMainHand(null);
            tell(p, "Patrol points set.", true);
            return;
        }
        if (!at.getWorld().equals(block.getWorld()) || Colonist.flat(at.getLocation(), block.getLocation()) > h.post.range()) {
            tell(p, "Too far from the " + h.type.title + ": at most " + h.post.range() + " blocks.", false);
            return;
        }
        String where = at.getX() + " " + at.getY() + " " + at.getZ();
        if (h.post.task() == GuardPost.Task.GUARD) {
            h.post.guard(at);
            p.getInventory().setItemInMainHand(null);
            tell(p, "They guard " + where + " now.", true);
        } else {
            h.post.addPatrol(at, last == null);
            plugin.items.scepterLast(scepter, at);
            p.getInventory().setItemInMainHand(scepter);
            tell(p, "Patrol point " + h.post.patrolPoints().size() + ": " + where + ". Click it again to finish.", true);
        }
        plugin.save(c);
    }

    /** On a guard hut it adds or removes it; sneaking, it starts or stops the rally; else it shows its list. */
    private void banner(Player p, ItemStack banner, Block at) {
        Colony c = at == null ? null : plugin.colonyAt(at);
        Hut h = c == null ? null : c.hutAt(at);
        if (h != null && h.post != null) toggleHut(p, banner, c, h);
        else if (p.isSneaking()) toggle(p, banner);
        else plugin.menus.rallyBanner(p, banner);
    }

    /** Adds a guard hut to the banner the player holds, or removes it (the mod's: a rally under way goes on without it, and one added joins the next). */
    private void toggleHut(Player p, ItemStack banner, Colony c, Hut h) {
        if (!c.permissions.has(p, Action.RALLY_GUARDS)) { plugin.protection.deny(p, c, Action.RALLY_GUARDS); return; }
        List<Block> blocks = new ArrayList<>(plugin.items.bannerHuts(banner));
        boolean removed = blocks.remove(h.block);
        if (!removed) blocks.add(h.block);
        plugin.items.bannerHuts(banner, blocks);
        p.getInventory().setItemInMainHand(banner);
        tell(p, removed ? "That guard hut no longer follows the banner." : "That guard hut follows the banner.", true);
    }

    /** Takes a guard hut, standing or gone, off the banner the player holds. */
    public void remove(Player p, ItemStack banner, Block hut) {
        List<Block> blocks = new ArrayList<>(plugin.items.bannerHuts(banner));
        if (!blocks.remove(hut)) return;
        plugin.items.bannerHuts(banner, blocks);
        p.getInventory().setItemInMainHand(banner);
    }

    /** Starts or stops the rally of the banner the player holds. */
    public void toggle(Player p, ItemStack banner) {
        List<Hut> huts = huts(banner);
        if (plugin.items.rallying(banner)) {
            stop(banner);
            p.getInventory().setItemInMainHand(banner);
            tell(p, "The rally is over.", true);
            return;
        }
        if (huts.isEmpty()) { tell(p, "Right-click guard huts with the banner first.", false); return; }
        int guards = 0;
        for (Hut h : huts) {
            Colony c = plugin.colonyAt(h.block);
            if (!c.permissions.has(p, Action.RALLY_GUARDS)) continue;
            h.post.rally(p);
            guards += c.workersAt(h).size();
        }
        plugin.items.rallying(banner, true);
        p.getInventory().setItemInMainHand(banner);
        tell(p, guards == 0 ? "No guards to rally." : guards + (guards == 1 ? " guard rallies" : " guards rally") + " to you.", guards > 0);
    }

    private void stop(ItemStack banner) {
        for (Hut h : huts(banner)) h.post.stopRally(plugin.colonyAt(h.block));
        plugin.items.rallying(banner, false);
    }

    /** The guard huts of a banner that still stand. */
    private List<Hut> huts(ItemStack banner) {
        return plugin.items.bannerHuts(banner).stream().map(b -> {
            Colony c = plugin.colonyAt(b);
            return c == null ? null : c.hutAt(b);
        }).filter(Objects::nonNull).filter(h -> h.post != null).toList();
    }

    /** Dropping an active banner ends the rally (the mod's onDroppedByPlayer). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (!plugin.items.rallying(item)) return;
        stop(item);
        e.getItemDrop().setItemStack(item);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.items.isRallyBanner(e.getItemInHand())) e.setCancelled(true);
    }

    private static void tell(Player p, String message, boolean good) {
        p.sendMessage(Component.text(message, good ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
