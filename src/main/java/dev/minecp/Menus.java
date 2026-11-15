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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import org.bukkit.Particle;
import org.bukkit.block.Block;

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
        if (h.type.isBuilding()) body.add(text(Component.text(h.level == 0 ? "Not built yet (level 0)" : "Level " + h.level + " of " + Hut.MAX_LEVEL,
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
                long hungry = c.colonists.stream().filter(x -> x.saturation < Food.LOW_SATURATION).count();
                long homeless = c.colonists.stream().filter(x -> x.home == null).count();
                if (hungry > 0 || homeless > 0) body.add(text(Component.text(
                        (hungry > 0 ? hungry + " hungry" : "") + (hungry > 0 && homeless > 0 ? ", " : "")
                        + (homeless > 0 ? homeless + " homeless" : ""), NamedTextColor.YELLOW)));
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
                if (b != null && b.note != null) body.add(text(Component.text(b.note, NamedTextColor.YELLOW)));
            }
            case LUMBERJACK -> {
                Job job = c.jobAt(h);
                Colonist worker = job == null ? null : c.worker(job);
                body.add(text(Component.text(worker == null ? "No lumberjack: every citizen has a job." : worker.name + " — " + worker.status())));
                body.add(text(Component.text(h.level == 0 ? "The lumberjack starts once level 1 is built."
                        : "Cuts natural trees within " + ((Lumberjack) job).radius() + " blocks and replants them.", NamedTextColor.GRAY)));
                if (job instanceof Lumberjack lj && lj.note != null) body.add(text(Component.text(lj.note, NamedTextColor.YELLOW)));
            }
            case FARMER -> {
                Job job = c.jobAt(h);
                Colonist worker = job == null ? null : c.worker(job);
                body.add(text(Component.text(worker == null ? "No farmer: every citizen has a job." : worker.name + " — " + worker.status())));
                body.add(text(Component.text(h.level == 0 ? "The farmer starts once level 1 is built."
                        : "Works " + c.fieldsOf(job).size() + " of up to " + h.level + " fields (one per level). Seeds to plant go in this chest.",
                        NamedTextColor.GRAY)));
                if (job != null && job.note != null) body.add(text(Component.text(job.note, NamedTextColor.YELLOW)));
            }
            case FIELD -> { field(p, c, h); return; }
            case COOK -> {
                Job job = c.jobAt(h);
                Colonist worker = job == null ? null : c.worker(job);
                body.add(text(Component.text(worker == null ? "No cook: every citizen has a job." : worker.name + " — " + worker.status())));
                body.add(text(Component.text(h.level == 0 ? "The restaurant opens once level 1 is built."
                        : "Hungry citizens without food in their hut or house come here to be served. Put food in this chest, "
                        + "or raw food and fuel: the cook cooks it in the furnaces and smokers nearby.", NamedTextColor.GRAY)));
                if (job instanceof Cook cook && !cook.waiting.isEmpty())
                    body.add(text(Component.text("Waiting to be served: " + cook.waiting.size())));
                if (job != null && job.note != null) body.add(text(Component.text(job.note, NamedTextColor.YELLOW)));
            }
            case HOUSE -> {
                var residents = c.residents(h);
                body.add(text(Component.text("Residents: " + residents.size() + " of " + h.level, NamedTextColor.GOLD)));
                for (Colonist x : residents) body.add(text(Component.text(x.name)));
            }
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (h.type.isBuilding() && up == null && h.level < Hut.MAX_LEVEL)
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
        if (h.type == Hut.Type.TOWN_HALL)
            buttons.add(button("Citizens", p, c, h, player -> citizens(player, c, h)));
        if (h.block.getState(false) instanceof Container)
            buttons.add(button("Open storage", p, c, h, player -> {
                if (h.block.getState(false) instanceof Container ct) player.openInventory(ct.getInventory());
            }));
        show(p, h.type.title, body, DialogType.multiAction(buttons).columns(1)
                .exitAction(ActionButton.builder(Component.text("Close")).build()).build());
    }

    private static final String[] SIDES = {"North", "East", "South", "West"};

    /** A field's menu, like the mod's scarecrow screen: the crop to plant and how far the field reaches each way. */
    void field(Player p, Colony c, Hut h) {
        outline(p, h);
        List<DialogBody> body = new ArrayList<>();
        Job farmer = c.farmerOf(h);
        Colonist worker = farmer == null ? null : c.worker(farmer);
        body.add(text(farmer == null
                ? Component.text("No farmer has room for this field: a Farmer's Hut works one field per level.", NamedTextColor.YELLOW)
                : Component.text("Worked by " + (worker == null ? "nobody yet (no free citizen)" : worker.name) + ".")));
        int[] plots = Farmer.plots(h);
        body.add(text(Component.text(plots[0] + " of " + plots[1] + " plots can be farmed (grass, dirt or farmland with room above, up to 5 blocks above or below this block's level).",
                plots[0] == 0 ? NamedTextColor.RED : NamedTextColor.WHITE)));
        body.add(text(Component.text("The field is the ground around this block, shown with particles. Seeds to plant go in the "
                + "Farmer's Hut chest. Water nearby makes crops grow faster.", NamedTextColor.GRAY)));

        Material seed = Farmer.seedOf(h);
        List<SingleOptionDialogInput.OptionEntry> crops = new ArrayList<>();
        crops.add(SingleOptionDialogInput.OptionEntry.create("none", Component.text("Nothing"), seed == null));
        for (Material s : List.of(Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS,
                Material.PUMPKIN_SEEDS, Material.MELON_SEEDS))
            crops.add(SingleOptionDialogInput.OptionEntry.create(s.name(), Component.translatable(Farmer.produce(s).translationKey()), s == seed));
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.singleOption("crop", Component.text("Crop"), crops).width(200).build());
        for (int i = 0; i < 4; i++)
            inputs.add(DialogInput.numberRange(SIDES[i].toLowerCase(), Component.text(SIDES[i]), 0, Farmer.MAX_EXTENT)
                    .step(1f).initial((float) h.extent[i]).width(200).build());

        List<ActionButton> buttons = List.of(button("Save", p, c, h, (player, view) -> {
            String crop = view.getText("crop");
            h.seed = crop == null || crop.equals("none") ? null : Material.matchMaterial(crop);
            for (int i = 0; i < 4; i++) {
                Float v = view.getFloat(SIDES[i].toLowerCase());
                if (v != null) h.extent[i] = Math.clamp(Math.round(v), 0, Farmer.MAX_EXTENT);
            }
            plugin.save();
            field(player, c, h);
        }));
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text("Field")).body(body).inputs(inputs).build())
                .type(DialogType.multiAction(buttons).columns(1).exitAction(ActionButton.builder(Component.text("Close")).build()).build())));
    }

    /** Shows the field's edge to the player with particles for a few seconds. */
    private void outline(Player p, Hut field) {
        int[] e = field.extent;
        Block b = field.block;
        // Points along the outer edges of the border plots, each at its plot's ground (fields follow the terrain).
        List<org.bukkit.Location> points = new ArrayList<>();
        for (int dx = -e[3]; dx <= e[1]; dx++) for (int dz = -e[0]; dz <= e[2]; dz++) {
            boolean w = dx == -e[3], east = dx == e[1], n = dz == -e[0], s = dz == e[2];
            if (!(w || east || n || s)) continue;
            Block ground = Farmer.plot(field, dx, dz);
            double y = (ground == null ? b.getY() - 1 : ground.getY()) + 1.1;
            double x = b.getX() + dx, z = b.getZ() + dz;
            for (double t = 0; t < 1; t += 0.5) {
                if (n) points.add(new org.bukkit.Location(b.getWorld(), x + t, y, z));
                if (s) points.add(new org.bukkit.Location(b.getWorld(), x + t, y, z + 1));
                if (w) points.add(new org.bukkit.Location(b.getWorld(), x, y, z + t));
                if (east) points.add(new org.bukkit.Location(b.getWorld(), x + 1, y, z + t));
            }
        }
        int[] runs = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            if (++runs[0] > 8 || !p.isOnline()) { task.cancel(); return; }
            for (var at : points) p.spawnParticle(Particle.HAPPY_VILLAGER, at, 1, 0, 0, 0, 0);
        }, 0, 10);
    }

    /** Every citizen with what it is doing, its home and its food, from the town hall menu. */
    void citizens(Player p, Colony c, Hut townHall) {
        List<DialogBody> body = new ArrayList<>();
        body.add(text(Component.text("Citizens: " + c.colonists.size() + " of " + c.capacity(), NamedTextColor.GOLD)));
        for (Colonist x : c.colonists) {
            String food = x.saturation <= 0 ? ", starving" : x.saturation < Food.LOW_SATURATION ? ", hungry" : "";
            body.add(text(Component.text(x.name + " — " + x.status() + (x.home == null ? ", homeless" : "") + food
                    + " (food " + (int) Math.ceil(x.saturation) + "/" + (int) Food.MAX_SATURATION + ")")));
            if (x.hungerNote != null && x.saturation < Food.LOW_SATURATION)
                body.add(text(Component.text("   " + x.hungerNote, NamedTextColor.YELLOW)));
        }
        List<ActionButton> buttons = List.of(button("Back", p, c, townHall, player -> hut(player, c, townHall)));
        show(p, "Citizens", body, DialogType.multiAction(buttons).columns(1)
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
        return button(label, shownTo, c, h, (player, view) -> action.accept(player));
    }

    /** Same, for a dialog with inputs: the action also gets what the player entered. */
    private ActionButton button(String label, Player shownTo, Colony c, Hut h, BiConsumer<Player, DialogResponseView> action) {
        return ActionButton.builder(Component.text(label)).width(200).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(shownTo.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (c.huts.contains(h)) action.accept(player, view); // the hut may have been removed while the menu was open
            });
        }, ONCE)).build();
    }

    private static void show(Player p, String title, List<DialogBody> body, DialogType type) {
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(type)));
    }
}
