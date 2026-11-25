package dev.minecp.menu;

import dev.minecp.MineCP;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Connections;
import dev.minecp.colony.Connections.Event;
import dev.minecp.colony.Connections.Status;
import dev.minecp.colony.Hut;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** The town hall's Alliances page and a gatehouse's travel page (the mod's WindowAlliancePage, ConnectionModuleWindow), as dialogs. */
final class ConnectionMenus {
    private final MineCP plugin;
    private final Menus menus;

    ConnectionMenus(MineCP plugin, Menus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    /** The colonies connected to this one, and what they last did. */
    void alliances(Player p, Colony c, Hut townHall) {
        Connections conns = c.connections;
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        if (conns.all().isEmpty()) body.add(Dialogs.text(Component.text("Not connected to any colony yet. Sneak + right-click a Gatehouse with Colony Signs, "
                + "place one every 50 blocks to another colony, and sneak + right-click its Gatehouse or Colony Sign.", NamedTextColor.GRAY)));
        list(p, c, townHall, "Connected:", conns.direct(), body, buttons);
        list(p, c, townHall, "Through our allies:", conns.indirect(), body, buttons);
        List<Map.Entry<UUID, Event>> events = new ArrayList<>(conns.events().entrySet());
        for (var e : events.reversed()) {
            Colony other = plugin.colonyOwnedBy(e.getKey());
            if (other == null) continue;
            body.add(Dialogs.text(Component.text(other.name + " " + e.getValue().label + ".", NamedTextColor.YELLOW)));
            if (e.getValue() == Event.REQUESTED && conns.status(other.owner) != Status.ALLIES)
                buttons.addFirst(act("Accept " + other.name + " as an ally", p, c, townHall, other, Event.CONFIRMED));
        }
        buttons.add(button("Back", p, c, townHall, player -> menus.hut(player, c, townHall)));
        Dialogs.show(p, "Alliances", body, List.of(), buttons, 1);
    }

    private void list(Player p, Colony c, Hut townHall, String title, Map<UUID, Block> colonies, List<DialogBody> body, List<ActionButton> buttons) {
        boolean first = true;
        for (var e : colonies.entrySet()) {
            Colony other = plugin.colonyOwnedBy(e.getKey());
            if (other == null) continue;
            if (first) body.add(Dialogs.text(Component.text(title, NamedTextColor.GOLD)));
            first = false;
            Status status = c.connections.status(other.owner);
            body.add(Dialogs.text(Component.text(other.name + ": " + status.name().toLowerCase() + ", " + far(townHall.block, e.getValue()), color(status))));
            if (status == Status.NEUTRAL) {
                buttons.add(act("Ask " + other.name + " for an alliance", p, c, townHall, other, Event.REQUESTED));
                buttons.add(act("Declare a feud with " + other.name, p, c, townHall, other, Event.FEUD));
            } else buttons.add(act("Go back to neutral with " + other.name, p, c, townHall, other, Event.NEUTRAL));
        }
    }

    /** A button for those who may MANAGE_HUTS (the mod's). */
    private ActionButton act(String label, Player p, Colony c, Hut townHall, Colony other, Event what) {
        return button(label, p, c, townHall, player -> {
            if (!c.permissions.has(player, Action.MANAGE_HUTS)) { Menus.refuse(player, c, Action.MANAGE_HUTS); return; }
            String problem = plugin.colonyOwnedBy(other.owner) != other ? other.name + " doesn't exist any more." : c.connections.act(other, what);
            if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            else player.sendMessage(Component.text(what == Event.REQUESTED ? "Asked " + other.name + " for an alliance: they accept it at their Town Hall."
                    : c.name + " " + what.label + " with " + other.name + ".", NamedTextColor.GREEN));
            plugin.save(c);
            plugin.save(other);
            alliances(player, c, townHall);
        });
    }

    /** Where one can travel from this gatehouse. */
    void travel(Player p, Colony c, Hut gate) {
        boolean member = c.permissions.has(p, Action.ACCESS_HUTS);
        List<DialogBody> body = new ArrayList<>(List.of(Dialogs.text(Component.text(c.name, NamedTextColor.GOLD))));
        List<ActionButton> buttons = new ArrayList<>();
        for (var e : c.connections.all().entrySet()) {
            Colony other = plugin.colonyOwnedBy(e.getKey());
            if (other == null) continue;
            Status status = c.connections.status(other.owner);
            body.add(Dialogs.text(Component.text(other.name + ": " + status.name().toLowerCase() + ", " + far(gate.block, e.getValue()), color(status))));
            if (status != Status.ALLIES || e.getValue() == null) continue;
            int fare = plugin.colonySigns.fare(p, c, gate, e.getValue());
            buttons.add(button("Travel to " + other.name + (fare == 0 ? "" : " (" + fare + " gold nuggets)"), p, c, gate, player -> {
                String problem = plugin.colonySigns.travel(player, c, gate, other.owner);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            }));
        }
        if (buttons.isEmpty()) body.add(Dialogs.text(Component.text("It leads to no ally yet: you can travel to the gatehouses of allied colonies connected by Colony Signs.", NamedTextColor.GRAY)));
        if (member) buttons.add(button("Back", p, c, gate, player -> menus.hut(player, c, gate)));
        Dialogs.show(p, "Gatehouse", body, List.of(), buttons, 1);
    }

    private static String far(Block from, Block gate) {
        return gate == null ? "no gatehouse" : (int) Connections.distance(from, gate) + " blocks away";
    }

    private static NamedTextColor color(Status s) {
        return switch (s) {
            case ALLIES -> NamedTextColor.GREEN;
            case NEUTRAL -> NamedTextColor.WHITE;
            case HOSTILE -> NamedTextColor.RED;
        };
    }

    private ActionButton button(String label, Player p, Colony c, Hut h, Consumer<Player> action) {
        return Dialogs.button(plugin, label, 250, p, () -> c.huts.contains(h), (player, view) -> action.accept(player));
    }
}
