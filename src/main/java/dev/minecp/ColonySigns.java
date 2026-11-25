package dev.minecp;

import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonySign;
import dev.minecp.colony.Connections;
import dev.minecp.colony.Hut;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Colony Signs in players' hands and in the world, and travel between gatehouses (the mod's ItemColonySign, TeleportToColonyMessage). */
public final class ColonySigns implements Listener {
    private static final int BLOCKS_PER_NUGGET = 125, REACH = 15; // the mod's

    private final MineCP plugin;

    ColonySigns(MineCP plugin) {
        this.plugin = plugin;
    }

    // ---- signs -----------------------------------------------------------------------------------------------------

    /** Sneak + right-click a gatehouse or a sign: binds the item to its colony, or connects the colony it is bound to with this one. */
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.useItemInHand() == Event.Result.DENY || !ColonySign.is(e.getItem())) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        boolean sign = ColonySign.is(b);
        if (!p.isSneaking()) {
            if (sign) {
                e.setCancelled(true);
                stack(p, b, e.getItem(), e.getHand());
            }
            return;
        }
        Colony c = sign ? plugin.colonyOwnedBy(ColonySign.colony(b)) : plugin.colonyAt(b);
        if (!sign && (c == null || c.hutAt(b).type != Hut.Type.GATE_HOUSE)) return; // placed against it
        e.setCancelled(true);
        if (c == null) { tell(p, "That sign's colony doesn't exist any more.", false); return; }
        if (!sign && c.hutAt(b).level < 1) { tell(p, "That gatehouse isn't built yet.", false); return; }
        Colony bound = plugin.colonyOwnedBy(ColonySign.colony(e.getItem()));
        Colony asked = bound == null ? c : bound; // whose signs these are
        if (!asked.permissions.has(p, Action.MANAGE_HUTS)) { plugin.protection.deny(p, asked, Action.MANAGE_HUTS); return; }
        if (asked != c) {
            String problem = bound.connections.connect(b, c);
            tell(p, problem == null ? "Looking for a way from " + bound.name + "'s last sign to this one..." : problem, problem == null);
            plugin.save(bound);
            return;
        }
        ColonySign.bind(e.getItem(), c);
        tell(p, "Noted " + c.name + " on the sign.", true);
    }

    /** Puts a sign on top of another: a waxed sign takes the click, so vanilla places nothing there. */
    private static void stack(Player p, Block on, ItemStack held, EquipmentSlot hand) {
        Block b = on.getRelative(BlockFace.UP);
        if (!b.isEmpty() || b.getY() >= b.getWorld().getMaxHeight() || p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SPECTATOR) return;
        BlockState was = b.getState();
        var sign = (Waterlogged) on.getBlockData(); // facing as the one below
        sign.setWaterlogged(false);
        b.setBlockData(sign, false);
        BlockPlaceEvent placed = new BlockPlaceEvent(b, was, on, held, p, true, hand);
        if (!placed.callEvent() || !placed.canBuild()) {
            was.update(true, false);
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) held.subtract();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        if (!ColonySign.is(e.getItemInHand())) return;
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        Colony c = plugin.colonyOwnedBy(ColonySign.colony(e.getItemInHand()));
        String problem = c == null ? "No colony set: sneak + right-click a Gatehouse or Colony Sign with it."
                : Tag.WALL_SIGNS.isTagged(b.getType()) ? "Place it on the ground, a fence or another sign." // ours: the way is looked for from its foot
                : c.connections.place(b);
        p.playSound(p, problem == null ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.BLOCKS, 0.3f, 1);
        if (problem != null) {
            e.setCancelled(true);
            tell(p, problem, false);
            return;
        }
        ColonySign.place(b, c);
        plugin.save(c);
    }

    /** Its text is the plugin's. */
    @EventHandler
    public void onEdit(PlayerOpenSignEvent e) {
        if (ColonySign.is(e.getSign().getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!ColonySign.is(b)) return;
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) b.getWorld().dropItemNaturally(b.getLocation().toCenterLocation(), ColonySign.item(1));
        gone(b);
    }

    /** What it stood on is gone. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSupportGone(BlockDestroyEvent e) {
        if (!ColonySign.is(e.getBlock())) return;
        e.setWillDrop(false);
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().toCenterLocation(), ColonySign.item(1));
        gone(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        blownUp(e.blockList());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onExplode(BlockExplodeEvent e) {
        blownUp(e.blockList());
    }

    private void blownUp(List<Block> blocks) {
        blocks.removeIf(b -> {
            if (!ColonySign.is(b)) return false;
            gone(b);
            ColonySign.pop(b); // ours: it always drops
            return true;
        });
    }

    private void gone(Block b) {
        Colony c = plugin.colonyOwnedBy(ColonySign.colony(b));
        if (c == null) return;
        c.connections.removed(b);
        plugin.save(c);
    }

    // ---- travel ----------------------------------------------------------------------------------------------------

    /** What it costs {@code p} to travel from this gatehouse to that one: nothing for those who may open its menu (the mod's). */
    public int fare(Player p, Colony c, Hut gate, Block to) {
        return c.permissions.has(p, Action.ACCESS_HUTS) ? 0 : (int) Connections.distance(gate.block, to) / BLOCKS_PER_NUGGET;
    }

    /** @return why {@code p} can't travel from {@code c}'s gatehouse to the colony {@code owner} owns, or null when there. */
    public String travel(Player p, Colony c, Hut gate, UUID owner) {
        Colony other = plugin.colonyOwnedBy(owner);
        Block to = c.connections.all().get(owner);
        Hut there = other == null || to == null ? null : other.hutAt(to);
        if (there == null || there.type != Hut.Type.GATE_HOUSE) return "There's no gatehouse to travel to any more.";
        if (c.connections.status(owner) != Connections.Status.ALLIES) return other.name + " isn't an ally of " + c.name + ".";
        if (!c.permissions.has(p, Action.TELEPORT_TO_COLONY) && !other.permissions.has(p, Action.TELEPORT_TO_COLONY))
            return "Neither " + c.name + " nor " + other.name + " lets you travel.";
        if (Connections.distance(p.getLocation().getBlock(), gate.block) > REACH) return "You are too far from the gatehouse."; // ours: the mod's check is on the other gatehouse, which nobody is near
        int fare = fare(p, c, gate, to);
        ItemStack nuggets = new ItemStack(Material.GOLD_NUGGET);
        if (fare > 0 && !p.getInventory().containsAtLeast(nuggets, fare)) return "That costs " + fare + " gold nuggets."; // ours: the mod also wants one more than that
        List<Block> spots = there.workSpots(other.style, "gate");
        Block spot = spots.isEmpty() ? null : Scrolls.spotAround(spots.get(ThreadLocalRandom.current().nextInt(spots.size())));
        if (spot == null) spot = Scrolls.spotAround(to);
        if (spot == null) return "There's no room at " + other.name + "'s gatehouse.";
        p.leaveVehicle();
        if (!p.teleport(spot.getLocation().add(0.5, 0, 0.5))) return "Something kept you from travelling.";
        if (fare > 0) p.getInventory().removeItem(nuggets.asQuantity(fare));
        if (fare / 2 > 0 && to.getState(false) instanceof Container chest) // half of it; what doesn't fit is lost, as in the mod
            for (int left = fare / 2; left > 0; left -= 64) chest.getInventory().addItem(nuggets.asQuantity(Math.min(left, 64)));
        p.playSound(p, Sound.ENTITY_PLAYER_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1);
        tell(p, "Travelled to " + other.name + ".", true);
        return null;
    }

    private static void tell(Player p, String msg, boolean ok) {
        p.sendMessage(Component.text(msg, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
