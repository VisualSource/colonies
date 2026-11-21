package dev.minecp.menu;

import dev.minecp.MineCP;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.ResearchTree;
import dev.minecp.research.ResearchTree.Research;
import dev.minecp.research.Studies;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The University's research screens (the mod's research tree window), as dialogs: the branches, then a branch's first
 * researches, then one research at a time with its cost, requirements and effects, and buttons to the ones after it.
 */
final class ResearchMenus {
    private final MineCP plugin;
    private final Menus menus;

    ResearchMenus(MineCP plugin, Menus menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    /** The branches, and what is under way. */
    void branches(Player p, Colony c, Hut uni) {
        List<DialogBody> body = new ArrayList<>();
        var on = c.research.inProgress();
        body.add(Dialogs.text(Component.text(on.isEmpty() ? "No research under way." : "Under way:", NamedTextColor.GOLD)));
        for (Research r : on) body.add(Dialogs.text(Component.text(r.name() + " — " + percent(c, r))));
        body.add(Dialogs.text(Component.text("This University shows research " + (uni.level >= Hut.Type.UNIVERSITY.maxLevel() ? "of any depth"
                : "up to depth " + uni.level) + "; each level deeper takes twice as long.", NamedTextColor.GRAY)));
        List<ActionButton> buttons = new ArrayList<>();
        for (var b : ResearchTree.branches()) buttons.add(button(b.name(), p, c, uni, player -> branch(player, c, uni, b.id())));
        buttons.add(button("Back", p, c, uni, player -> menus.hut(player, c, uni)));
        Dialogs.show(p, "Research", body, List.of(), buttons, 1);
    }

    /** A branch's first researches. */
    void branch(Player p, Colony c, Hut uni, String branchId) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Research r : ResearchTree.children(branchId)) buttons.add(open(label(p, c, uni, r), p, c, uni, r));
        buttons.add(button("Back", p, c, uni, player -> branches(player, c, uni)));
        Dialogs.show(p, ResearchTree.branch(branchId).name(), List.of(Dialogs.text(Component.text(
                "✔ done, % under way, ✖ can't be started yet.", NamedTextColor.GRAY))), List.of(), buttons, 1);
    }

    /** One research: what it does and costs, what it needs, and the researches after it. */
    void research(Player p, Colony c, Hut uni, Research r) {
        List<DialogBody> body = new ArrayList<>();
        Studies s = c.research;
        int steps = ResearchTree.steps(r);
        body.add(Dialogs.text(Component.text(ResearchTree.branch(r.branch()).name() + ", depth " + r.depth() + " — "
                + hours(steps) + " of study", NamedTextColor.GOLD)));
        body.add(Dialogs.text(Component.text(s.isDone(r.id()) ? "Done." : s.isStarted(r.id()) ? "Under way: " + percent(c, r) + "."
                : "Not started.", s.isDone(r.id()) ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
        for (var e : r.effects()) body.add(Dialogs.text(Component.text("• " + Studies.describe(e))));
        if (!r.costs().isEmpty())
            body.add(Dialogs.text(Component.text("Costs: " + String.join(", ", r.costs().stream().map(Studies::describe).toList()))));
        for (var q : r.requirements())
            body.add(Dialogs.text(Component.text((s.met(q) ? "✔ Needs " : "✖ Needs ") + Studies.describe(q), s.met(q) ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (r.parent() != null && ResearchTree.parent(r).onlyChild())
            body.add(Dialogs.text(Component.text("Only one research after " + ResearchTree.parent(r).name() + " can be chosen.", NamedTextColor.GRAY)));
        if (!s.isStarted(r.id())) {
            String problem = s.problem(p, uni, r);
            if (problem != null) body.add(Dialogs.text(Component.text(problem, NamedTextColor.YELLOW)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        boolean finishNow = s.isStarted(r.id()) && !s.isDone(r.id()) && p.getGameMode() == org.bukkit.GameMode.CREATIVE && Studies.creativeCompletion;
        if (!s.isStarted(r.id()) || finishNow) buttons.add(manage(finishNow ? "Finish now (creative)" : "Start", p, c, uni, player -> {
            String problem = s.start(player, uni, r);
            if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            else {
                if (!s.isDone(r.id())) player.sendMessage(Component.text("Research started: " + r.name() + ".", NamedTextColor.GREEN));
                plugin.save(c);
            }
            research(player, c, uni, r);
        }));
        if (s.isStarted(r.id()) && (!s.isDone(r.id()) || !r.noReset())) buttons.add(manage(s.isDone(r.id()) ? "Undo (costs "
                + Studies.resetCost() + ")" : "Stop (its cost is lost)", p, c, uni, player -> {
            String problem = s.reset(player, r);
            if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            else plugin.save(c);
            research(player, c, uni, r);
        }));
        for (Research ch : ResearchTree.children(r.id())) buttons.add(open("→ " + label(p, c, uni, ch), p, c, uni, ch));
        if (r.parent() != null) buttons.add(button("← " + ResearchTree.parent(r).name(), p, c, uni, player -> research(player, c, uni, ResearchTree.parent(r))));
        buttons.add(button("Back to " + ResearchTree.branch(r.branch()).name(), p, c, uni, player -> branch(player, c, uni, r.branch())));
        Dialogs.show(p, r.name(), body, List.of(), buttons, 1);
    }

    /** "✔ Stamina", "42% Hot", "✖ Woodwork" (can't be started now, whatever the reason: Studies.problem), "Ambition". */
    private static String label(Player p, Colony c, Hut uni, Research r) {
        Studies s = c.research;
        if (s.isDone(r.id())) return "✔ " + r.name();
        if (s.isStarted(r.id())) return percent(c, r) + " " + r.name();
        return (s.problem(p, uni, r) != null ? "✖ " : "") + r.name();
    }

    private static String percent(Colony c, Research r) {
        return (int) (100 * c.research.progress(r)) + "%";
    }

    /** "30 min", "8 h": the mod's time at 25 s a step. */
    private static String hours(int steps) {
        int minutes = steps * 25 / 60;
        return minutes < 60 ? minutes + " min" : (minutes % 60 == 0 ? minutes / 60 + " h" : String.format("%.1f h", minutes / 60.0));
    }

    /** Starting and undoing need MANAGE_HUTS (the mod's default for building messages). */
    private ActionButton manage(String label, Player shownTo, Colony c, Hut uni, Consumer<Player> action) {
        return button(label, shownTo, c, uni, player -> {
            if (c.permissions.has(player, Action.MANAGE_HUTS)) action.accept(player);
            else player.sendMessage(Component.text("You can't " + Action.MANAGE_HUTS.label.toLowerCase() + " in " + c.name + ".", NamedTextColor.RED));
        });
    }

    /** A button to a research, its tooltip what it does, how long it takes and what it costs. */
    private ActionButton open(String label, Player shownTo, Colony c, Hut uni, Research r) {
        var tip = Component.text().append(Component.text(r.name(), NamedTextColor.GOLD));
        for (var e : r.effects()) tip.appendNewline().append(Component.text("• " + Studies.describe(e)));
        tip.appendNewline().append(Component.text(hours(ResearchTree.steps(r)) + " of study", NamedTextColor.GRAY));
        if (!r.costs().isEmpty())
            tip.appendNewline().append(Component.text("Costs: " + String.join(", ", r.costs().stream().map(Studies::describe).toList()), NamedTextColor.GRAY));
        return Dialogs.button(plugin, label, tip.build(), 250, shownTo, () -> c.huts.contains(uni), (player, view) -> research(player, c, uni, r));
    }

    private ActionButton button(String label, Player shownTo, Colony c, Hut uni, Consumer<Player> action) {
        return Dialogs.button(plugin, label, 250, shownTo, () -> c.huts.contains(uni), (player, view) -> action.accept(player));
    }
}
