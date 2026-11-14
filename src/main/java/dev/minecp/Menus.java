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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Dialog screens (vanilla client, Minecraft 1.21.6+) for the town hall and the Builder's Hut. */
final class Menus {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private final MineCP plugin;

    Menus(MineCP plugin) {
        this.plugin = plugin;
    }

    void townHall(Player p, Colony c) {
        List<DialogBody> body = new ArrayList<>();
        body.add(text(Component.text("Citizens (" + c.colonists.size() + ")", NamedTextColor.GOLD)));
        for (Colonist x : c.colonists) body.add(text(Component.text(x.name + " — " + x.status())));
        body.add(text(Component.text(c.builder == null ? "No Builder's Hut yet."
                : "Builder's Hut: level " + c.builder.level, NamedTextColor.GRAY)));
        show(p, c.name, body, DialogType.notice());
    }

    void builderHut(Player p, Colony c) {
        Builder b = c.builder;
        if (b == null) return;
        List<DialogBody> body = new ArrayList<>();
        body.add(text(Component.text(b.level == 0 ? "Not built yet (level 0)" : "Level " + b.level + " of " + Builder.MAX_LEVEL,
                NamedTextColor.GOLD)));
        Colonist worker = c.colonists.stream().filter(x -> x.id.equals(b.worker)).findFirst().orElse(null);
        body.add(text(Component.text(worker == null ? "No builder." : worker.name + " — " + worker.status())));
        if (b.order != null) {
            int total = b.order.steps.size(), left = (int) b.order.upcoming().stream().filter(s -> !s.done()).count();
            body.add(text(Component.text("Building " + b.order.structure + ": " + (100 * (total - left) / Math.max(total, 1))
                    + "% (" + left + " blocks left)")));
            int kinds = b.missing().size();
            body.add(text(kinds == 0 ? Component.text("All materials are in the hut.", NamedTextColor.GREEN)
                    : Component.text(kinds + " kinds of materials missing from the hut chest.", NamedTextColor.RED)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (b.order == null && b.level < Builder.MAX_LEVEL)
            buttons.add(button(b.level == 0 ? "Build level 1" : "Upgrade to level " + (b.level + 1), p, b, c, player -> {
                String problem = b.upgrade(c.style);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
                plugin.save();
                builderHut(player, c);
            }));
        if (b.order != null)
            buttons.add(button("Cancel build", p, b, c, player -> {
                b.cancel();
                plugin.save();
                builderHut(player, c);
            }));
        if (b.order != null)
            buttons.add(button("Materials needed", p, b, c, player -> materials(player, c)));
        buttons.add(button("Open storage", p, b, c, player -> {
            Inventory chest = b.chest();
            if (chest != null) player.openInventory(chest);
        }));
        show(p, "Builder's Hut", body, DialogType.multiAction(buttons).columns(1)
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
        List<ActionButton> buttons = List.of(button("Back", p, b, c, player -> builderHut(player, c)));
        show(p, "Materials needed", body, DialogType.multiAction(buttons).columns(1)
                .exitAction(ActionButton.builder(Component.text("Close")).build()).build());
    }

    private static DialogBody text(Component c) {
        return DialogBody.plainMessage(c, 300);
    }

    /** A button whose action runs on the main thread, only for the player it was shown to, while the hut exists. */
    private ActionButton button(String label, Player shownTo, Builder b, Colony c, Consumer<Player> action) {
        return ActionButton.builder(Component.text(label)).width(200).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(shownTo.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (c.builder == b) action.accept(player); // the hut may have been removed while the menu was open
            });
        }, ONCE)).build();
    }

    private static void show(Player p, String title, List<DialogBody> body, DialogType type) {
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(type)));
    }
}
