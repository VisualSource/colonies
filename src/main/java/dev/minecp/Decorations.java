package dev.minecp;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.minecp.build.BuildOrder;
import dev.minecp.build.DecorationController;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.menu.Dialogs;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoration controllers in the world (the mod's WindowDecorationController, DecorationBuildRequestMessage): their menu, and who may break them. */
final class Decorations implements Listener {
    private final MineCP plugin;

    Decorations(MineCP plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (e.useInteractedBlock() == Event.Result.DENY || e.getPlayer().isSneaking() || Items.ours(e.getItem())) return; // refused, or our own tools
        if (!DecorationController.is(e.getClickedBlock())) return;
        e.setCancelled(true);
        menu(e.getPlayer(), e.getClickedBlock());
    }

    /** The mod's: BREAK_HUTS, and nothing drops. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!DecorationController.is(e.getBlock())) return;
        Colony c = plugin.claims.colonyAt(e.getBlock().getLocation());
        if (c != null && !c.permissions.has(e.getPlayer(), Action.BREAK_HUTS)) {
            e.setCancelled(true);
            plugin.protection.deny(e.getPlayer(), c, Action.BREAK_HUTS);
        }
        e.setDropItems(false);
    }

    /** A sign needs the block behind it; the mod's controller doesn't. */
    @EventHandler(ignoreCancelled = true)
    public void onSupportGone(BlockDestroyEvent e) {
        if (DecorationController.is(e.getBlock())) e.setCancelled(true);
    }

    private void menu(Player p, Block b) {
        DecorationController now = DecorationController.at(b);
        if (now == null) return;
        Colony c = plugin.claims.colonyAt(b.getLocation());
        List<DialogBody> body = new ArrayList<>(List.of(Dialogs.text(Component.text(now.structure(), NamedTextColor.GRAY))));
        List<ActionButton> buttons = new ArrayList<>();
        if (c == null) body.add(Dialogs.text(Component.text("It isn't inside a colony's border.")));
        else {
            DecorationController up = now.upgraded();
            Map<Colony.Decoration, BuildOrder> orders = new LinkedHashMap<>(); // the upgrade, the repair
            for (DecorationController to : up == null ? List.of(now) : List.of(up, now)) {
                BuildOrder o = to.order(b);
                if (o != null) // the mod offers only what has a controller too
                    orders.put(new Colony.Decoration(to.structure(), to.name() + (to == now ? " repair" : ""), o.origin, to.rotation(), to.mirror()), o);
            }
            Colony.Decoration ordered = ordered(c, orders.keySet()); // one order at a time, as in the mod
            if (ordered != null) {
                body.add(Dialogs.text(Component.text(ordered.equals(c.building) ? "The builder is on it." : "Waiting for the builder.", NamedTextColor.GOLD)));
                buttons.add(button(ordered.structure().equals(now.structure()) ? "Cancel repair" : "Cancel build", p, b, c, () -> {
                    if (ordered.equals(c.building)) c.cancelBuilding();
                    else c.decorations.remove(ordered);
                    return null;
                }));
            } else orders.forEach((d, o) -> buttons.add(button(d.structure().equals(now.structure()) ? "Repair" : "Upgrade to level " + d.name().substring(d.name().length() - 1), p, b, c,
                    () -> ordered(c, orders.keySet()) != null ? "It is ordered already." // by someone else, while this was open
                            : plugin.claims.ownsAll(c, b.getWorld(), o.footprint()) ? c.queueDecoration(d) : "It would reach outside " + c.name + "'s border.")));
        }
        Dialogs.show(p, now.name(), body, List.of(), buttons, 1);
    }

    /** Which of these the builder is on or will be, or null. */
    private static Colony.Decoration ordered(Colony c, Collection<Colony.Decoration> orders) {
        for (Colony.Decoration d : orders) if (d.equals(c.building) || c.decorations.contains(d)) return d;
        return null;
    }

    /** A button for those who may MANAGE_HUTS (the mod's), while the controller still says the same; {@code action} returns what's wrong, or null. */
    private ActionButton button(String label, Player p, Block b, Colony c, java.util.function.Supplier<String> action) {
        DecorationController shown = DecorationController.at(b);
        return Dialogs.button(plugin, label, 200, p, () -> shown.equals(DecorationController.at(b)) && plugin.claims.colonyAt(b.getLocation()) == c, (player, view) -> {
            if (!c.permissions.has(player, Action.MANAGE_HUTS)) { plugin.protection.deny(player, c, Action.MANAGE_HUTS); return; }
            String problem = action.get();
            if (problem != null) { player.sendMessage(Component.text(problem, NamedTextColor.RED)); return; }
            plugin.save(c);
            menu(player, b);
        });
    }
}
