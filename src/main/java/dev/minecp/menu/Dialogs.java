package dev.minecp.menu;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Dialog screens (vanilla client, Minecraft 1.21.6+): the pieces the hut menus and the build tool share. */
public final class Dialogs {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private Dialogs() {}

    public static DialogBody text(Component c) {
        return DialogBody.plainMessage(c, 300);
    }

    /**
     * A button whose action runs on the main thread, only for the player it was shown to, and only while
     * {@code stillValid} holds (the hut may be gone, or another placement started, while the dialog was open).
     */
    public static ActionButton button(Plugin plugin, String label, int width, Player shownTo, BooleanSupplier stillValid,
                               BiConsumer<Player, DialogResponseView> action) {
        return button(plugin, label, null, width, shownTo, stillValid, action);
    }

    /** The same, with a tooltip shown on hover (none if null). */
    static ActionButton button(Plugin plugin, String label, Component tooltip, int width, Player shownTo, BooleanSupplier stillValid,
                               BiConsumer<Player, DialogResponseView> action) {
        return ActionButton.builder(Component.text(label)).tooltip(tooltip).width(width).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(shownTo.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (stillValid.getAsBoolean()) action.accept(player, view);
            });
        }, ONCE)).build();
    }

    /** Shows a dialog of text, optional inputs and buttons, with a Close button (only that, with no buttons). */
    public static void show(Player p, String title, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons, int columns) {
        ActionButton close = ActionButton.builder(Component.text("Close")).build();
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).inputs(inputs).build())
                .type(buttons.isEmpty() ? DialogType.notice(close) // a multi-action dialog must have a button
                        : DialogType.multiAction(buttons).columns(columns).exitAction(close).build())));
    }
}
