package dev.minecp;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Dialog screens (vanilla client, Minecraft 1.21.6+) for the colony's huts. */
final class Menus {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private final MineCP plugin;

    Menus(MineCP plugin) {
        this.plugin = plugin;
    }

    /** The menu of any hut: level and upgrade, plus what that kind of hut shows. */
    void hut(Player p, Colony c, Hut h) {
        List<DialogBody> body = new ArrayList<>();
        if (h.type == Hut.Type.TOWN_HALL) body.add(text(Component.text(c.name, NamedTextColor.GOLD)));
        body.add(text(Component.text(h.level == 0 ? "Not built yet (level 0)" : "Level " + h.level + " of " + Hut.MAX_LEVEL,
                NamedTextColor.GOLD)));
        Hut.WorkOrder up = c.upgradeOf(h);
        Builder b = c.builder;
        if (up != null && b != null && b.work == up) {
            int total = b.order.steps.size(), left = (int) b.order.upcoming().stream().filter(s -> !s.done()).count();
            body.add(text(Component.text("Building level " + up.level() + ": " + (100 * (total - left) / Math.max(total, 1))
                    + "% (" + left + " blocks left)")));
        } else if (up != null)
            body.add(text(Component.text("Level " + up.level() + " is queued for the builder (#"
                    + (c.workOrders.indexOf(up) + 1) + ").")));

        switch (h.type) {
            case TOWN_HALL -> {
                body.add(text(Component.text("Citizens: " + c.colonists.size() + " of " + c.capacity()
                        + " (more houses or house levels make room)", NamedTextColor.GOLD)));
                for (Colonist x : c.colonists)
                    body.add(text(Component.text(x.name + " — " + x.status() + (x.home == null ? ", homeless" : ""))));
                if (!c.workOrders.isEmpty()) body.add(text(Component.text("Waiting for the builder: " + String.join(", ",
                        c.workOrders.stream().map(w -> w.hut().type.title + " " + w.level()).toList()), NamedTextColor.GRAY)));
            }
            case BUILDER -> {
                Colonist worker = b == null ? null : c.worker(b);
                body.add(text(Component.text(worker == null ? "No builder." : worker.name + " — " + worker.status())));
                if (b != null && b.order != null) {
                    if (b.work == null) body.add(text(Component.text("Building " + b.order.structure + ".")));
                    int kinds = b.missing().size();
                    body.add(text(kinds == 0 ? Component.text("All materials are in the hut.", NamedTextColor.GREEN)
                            : Component.text(kinds + " kinds of materials missing from the hut chest.", NamedTextColor.RED)));
                }
            }
            case LUMBERJACK -> {
                Job job = c.jobAt(h);
                Colonist worker = job == null ? null : c.worker(job);
                body.add(text(Component.text(worker == null ? "No lumberjack: every citizen has a job." : worker.name + " — " + worker.status())));
                body.add(text(Component.text(h.level == 0 ? "The lumberjack starts once level 1 is built."
                        : "Cuts natural trees within " + ((Lumberjack) job).radius() + " blocks and replants them.", NamedTextColor.GRAY)));
                if (job instanceof Lumberjack lj && lj.note != null) body.add(text(Component.text(lj.note, NamedTextColor.YELLOW)));
            }
            case HOUSE -> {
                var residents = c.residents(h);
                body.add(text(Component.text("Residents: " + residents.size() + " of " + h.level, NamedTextColor.GOLD)));
                for (Colonist x : residents) body.add(text(Component.text(x.name)));
            }
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (up == null && h.level < Hut.MAX_LEVEL)
            buttons.add(button(h.level == 0 ? "Build level 1" : "Upgrade to level " + (h.level + 1), p, c, h, player -> {
                String problem = c.requestUpgrade(h);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
                plugin.save();
                hut(player, c, h);
            }));
        if (up != null)
            buttons.add(button("Cancel upgrade", p, c, h, player -> {
                c.cancelUpgrade(h);
                plugin.save();
                hut(player, c, h);
            }));
        if (h.type == Hut.Type.BUILDER && b != null && b.order != null)
            buttons.add(button("Materials needed", p, c, h, player -> materials(player, c)));
        if (h.block.getState(false) instanceof Container)
            buttons.add(button("Open storage", p, c, h, player -> {
                if (h.block.getState(false) instanceof Container ct) player.openInventory(ct.getInventory());
            }));
        show(p, h.type.title, body, DialogType.multiAction(buttons).columns(1)
                .exitAction(ActionButton.builder(Component.text("Close")).build()).build());
    }

    /** Everything the current build still needs that is not in the hut chest, with item icons. */
    void materials(Player p, Colony c) {
        Builder b = c.builder;
        if (b == null) return;
        List<DialogBody> body = new ArrayList<>();
        Map<Material, Integer> missing = b.missing();
        if (missing.isEmpty()) body.add(text(Component.text("Nothing: all materials are in the hut.", NamedTextColor.GREEN)));
        missing.entrySet().stream().sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .forEach(e -> body.add(DialogBody.item(new ItemStack(e.getKey()))
                        .description(DialogBody.plainMessage(Component.text(e.getValue() + " × ")
                                .append(Component.translatable(e.getKey().translationKey())), 250))
                        .showTooltip(true).build()));
        List<ActionButton> buttons = List.of(button("Back", p, c, b.hut, player -> hut(player, c, b.hut)));
        show(p, "Materials needed", body, DialogType.multiAction(buttons).columns(1)
                .exitAction(ActionButton.builder(Component.text("Close")).build()).build());
    }

    private static DialogBody text(Component c) {
        return DialogBody.plainMessage(c, 300);
    }

    /** A button whose action runs on the main thread, only for the player it was shown to, while the hut exists. */
    private ActionButton button(String label, Player shownTo, Colony c, Hut h, Consumer<Player> action) {
        return ActionButton.builder(Component.text(label)).width(200).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(shownTo.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (c.huts.contains(h)) action.accept(player); // the hut may have been removed while the menu was open
            });
        }, ONCE)).build();
    }

    private static void show(Player p, String title, List<DialogBody> body, DialogType type) {
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(type)));
    }
}
