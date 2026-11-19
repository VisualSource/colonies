package dev.minecp;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

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
            case FIELD -> { field(p, c, h); return; }
            case WAREHOUSE -> {
                body.add(text(Component.text(h.level == 0 ? "Couriers work from the warehouse once level 1 is built."
                        : "Stores the colony's goods in its chests. Couriers bring huts what they need from here, and take "
                        + "what huts don't need (logs, harvest, leftovers) here.", NamedTextColor.GRAY)));
                Warehouse wh = c.warehouse();
                if (wh != null && wh.hut == h) {
                    var slots = wh.slots();
                    body.add(text(Component.text(wh.containers().size() + " chests, " + slots.used() + " of " + slots.total() + " slots used.",
                            slots.used() >= slots.total() ? NamedTextColor.RED : NamedTextColor.WHITE)));
                }
                long couriers = c.jobs.stream().filter(j -> j instanceof Courier && c.worker(j) != null).count();
                body.add(text(Component.text("Couriers: " + couriers + (couriers == 0 ? " (build a Courier's Hut)" : ""))));
            }
            case HOUSE -> {
                var residents = c.residents(h);
                body.add(text(Component.text("Residents: " + residents.size() + " of " + h.level, NamedTextColor.GOLD)));
                for (Colonist x : residents) body.add(text(Component.text(x.name)));
            }
            default -> {}
        }
        Job job = c.jobAt(h);
        if (job != null) { // every worker hut: who works it and what it is doing, then what that job shows
            Colonist worker = c.worker(job);
            body.add(text(Component.text(worker == null ? "No " + h.type.worker.toLowerCase() + ": every citizen has a job."
                    : worker.name + " — " + worker.status())));
            job.info(c).forEach(line -> body.add(text(line)));
            if (job.note != null) body.add(text(Component.text(job.note, NamedTextColor.YELLOW)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        if (h.type.isBuilding() && up == null && h.level < Hut.MAX_LEVEL)
            buttons.add(button(h.level == 0 ? "Build level 1" : "Upgrade to level " + (h.level + 1), p, c, h, player -> {
                String problem = c.requestUpgrade(h);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
                plugin.save(c);
                hut(player, c, h);
            }));
        if (up != null)
            buttons.add(button("Cancel upgrade", p, c, h, player -> {
                c.cancelUpgrade(h);
                plugin.save(c);
                hut(player, c, h);
            }));
        if (job != null && job.materials() != null) buttons.add(button("Materials needed", p, c, h, player -> {
            var missing = job.materials(); // null once the work ended while the menu was open
            materials(player, c, h, missing == null ? Map.of() : missing);
        }));
        if (h.type == Hut.Type.TOWN_HALL)
            buttons.add(button("Citizens", p, c, h, player -> citizens(player, c, h)));
        if (h.block.getState(false) instanceof Container)
            buttons.add(button("Open storage", p, c, h, player -> {
                if (h.block.getState(false) instanceof Container ct) player.openInventory(ct.getInventory());
            }));
        Dialogs.show(p, h.type.title, body, List.of(), buttons, 1);
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
            Material chosen = crop == null ? null : Material.matchMaterial(crop);
            h.seed = chosen != null && Farmer.CROPS.containsKey(chosen) ? chosen : null; // a modified client can send any name
            for (int i = 0; i < 4; i++) {
                Float v = view.getFloat(SIDES[i].toLowerCase());
                if (v != null) h.extent[i] = Math.clamp(Math.round(v), 0, Farmer.MAX_EXTENT);
            }
            plugin.save(c);
            field(player, c, h);
        }));
        Dialogs.show(p, "Field", body, inputs, buttons, 1);
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
                    + " (health " + (int) Math.ceil(x.health) + "/" + (int) Colonist.MAX_HEALTH
                    + ", food " + (int) Math.ceil(x.saturation) + "/" + (int) Food.MAX_SATURATION + ")")));
            if (x.hungerNote != null && x.saturation < Food.LOW_SATURATION)
                body.add(text(Component.text("   " + x.hungerNote, NamedTextColor.YELLOW)));
        }
        List<ActionButton> buttons = List.of(button("Back", p, c, townHall, player -> hut(player, c, townHall)));
        Dialogs.show(p, "Citizens", body, List.of(), buttons, 1);
    }

    /** What a hut's current work still needs that is not in its chest, with item icons. */
    void materials(Player p, Colony c, Hut h, Map<ItemStack, Integer> missing) {
        List<DialogBody> body = new ArrayList<>();
        if (missing.isEmpty()) body.add(text(Component.text("Nothing: all materials are in the hut.", NamedTextColor.GREEN)));
        missing.entrySet().stream().sorted(Map.Entry.<ItemStack, Integer>comparingByValue().reversed())
                .forEach(e -> body.add(DialogBody.item(e.getKey())
                        .description(DialogBody.plainMessage(Component.text(e.getValue() + " × ")
                                .append(e.getKey().effectiveName()), 250))
                        .showTooltip(true).build()));
        List<ActionButton> buttons = List.of(button("Back", p, c, h, player -> hut(player, c, h)));
        Dialogs.show(p, "Materials needed", body, List.of(), buttons, 1);
    }

    private static DialogBody text(Component c) {
        return Dialogs.text(c);
    }

    /** A button that runs only while the hut still exists (it may be removed while the menu is open). */
    private ActionButton button(String label, Player shownTo, Colony c, Hut h, Consumer<Player> action) {
        return button(label, shownTo, c, h, (player, view) -> action.accept(player));
    }

    private ActionButton button(String label, Player shownTo, Colony c, Hut h, BiConsumer<Player, DialogResponseView> action) {
        return Dialogs.button(plugin, label, 200, shownTo, () -> c.huts.contains(h), action);
    }
}
