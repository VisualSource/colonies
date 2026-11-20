package dev.minecp.menu;

import dev.minecp.MineCP;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Permissions;
import dev.minecp.colony.Rank;
import dev.minecp.job.Builder;
import dev.minecp.job.Courier;
import dev.minecp.job.Crafter;
import dev.minecp.job.Farmer;
import dev.minecp.job.Job;
import dev.minecp.logistics.Requests;
import dev.minecp.logistics.Warehouse;

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
public final class Menus {
    private final MineCP plugin;

    public Menus(MineCP plugin) {
        this.plugin = plugin;
    }

    /** The menu of any hut: level and upgrade, plus what that kind of hut shows. */
    public void hut(Player p, Colony c, Hut h) {
        List<DialogBody> body = new ArrayList<>();
        if (h.type == Hut.Type.TOWN_HALL) body.add(text(Component.text(c.name, NamedTextColor.GOLD)));
        if (h.type.isBuilding()) body.add(text(Component.text(h.level == 0 ? "Not built yet (level 0)" : "Level " + h.level + " of " + Hut.MAX_LEVEL,
                NamedTextColor.GOLD)));
        Hut.WorkOrder up = c.upgradeOf(h);
        Builder b = c.builder;
        if (up != null && b != null && b.work == up) {
            int total = b.order.steps.size(), left = (int) b.order.upcoming().stream().filter(s -> !s.done()).count();
            body.add(text(Component.text((up.remove() ? "Taking it down" : "Building level " + up.level()) + ": " + (100 * (total - left) / Math.max(total, 1))
                    + "% (" + left + " blocks left)")));
        } else if (up != null)
            body.add(text(Component.text(up.what() + " is queued for the builder (#"
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
                        c.workOrders.stream().map(w -> w.hut().type.title + (w.remove() ? " (take down)" : " " + w.level())).toList()), NamedTextColor.GRAY)));
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
        if (job != null && !c.requests.of(job).isEmpty()) // a list of its own: the builder can wait for dozens of things
            buttons.add(button("Requests (" + c.requests.of(job).size() + ")", p, c, h, player -> requests(player, c, h, job)));
        if (h.type.isBuilding() && up == null && h.level < Hut.MAX_LEVEL)
            buttons.add(order(h.level == 0 ? "Build level 1" : "Upgrade to level " + (h.level + 1), p, c, h, () -> c.requestUpgrade(h, plugin.claims)));
        if (h.type.removable() && up == null && h.level > 0)
            buttons.add(order("Remove building", p, c, h, () -> c.requestRemoval(h)));
        if (up != null)
            buttons.add(button(up.remove() ? "Cancel removal" : "Cancel upgrade", p, c, h, Action.MANAGE_HUTS, player -> {
                c.cancelUpgrade(h);
                plugin.save(c);
                hut(player, c, h);
            }));
        if (job != null && job.materials() != null) buttons.add(button("Materials needed", p, c, h, player -> {
            var missing = job.materials(); // null once the work ended while the menu was open
            materials(player, c, h, missing == null ? Map.of() : missing);
        }));
        if (job instanceof Crafter cr && h.level > 0 && !cr.oreSmelter) { // the Smeltery knows every ore by itself
            buttons.add(button("Recipes (" + cr.recipes.size() + " of " + cr.maxRecipes() + ")", p, c, h, player -> recipes(player, c, h, cr)));
            buttons.add(button("Teach a recipe", p, c, h, Action.MANAGE_HUTS, player -> {
                if (cr.smelts) teachSmelting(player, c, h, cr);
                else plugin.teaching.start(player, c, cr);
            }));
        }
        if (h.type == Hut.Type.TOWN_HALL) {
            buttons.add(button("Citizens", p, c, h, player -> citizens(player, c, h)));
            buttons.add(button("Requests (" + c.requests.all().size() + ")", p, c, h, player -> requests(player, c, h, null)));
            if (c.permissions.has(p, Action.EDIT_PERMISSIONS)) buttons.add(button("Permissions", p, c, h, player -> permissions(player, c, h)));
            if (c.permissions.has(p, Action.MANAGE_HUTS)) buttons.add(button("Style: " + c.style, p, c, h, player -> styles(player, c, h)));
        }
        if (h.block.getState(false) instanceof Container && c.permissions.has(p, Action.OPEN_CONTAINER))
            buttons.add(button("Open storage", p, c, h, Action.OPEN_CONTAINER, player -> {
                if (h.block.getState(false) instanceof Container ct) player.openInventory(ct.getInventory());
            }));
        Dialogs.show(p, h.type.title, body, List.of(), buttons, 1);
    }

    /** The colony's building style (the mod's town hall setting): every installed style, one button each. */
    void styles(Player p, Colony c, Hut townHall) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String style : Colony.styles())
            buttons.add(button((style.equals(c.style) ? "▶ " : "") + style, p, c, townHall, Action.MANAGE_HUTS, player -> {
                c.style = style;
                plugin.save(c);
                hut(player, c, townHall);
            }));
        buttons.add(button("Back", p, c, townHall, player -> hut(player, c, townHall)));
        Dialogs.show(p, "Style", List.of(text(Component.text("New buildings and upgrades are built in this style; what stands now stays as it is.",
                NamedTextColor.GRAY))), List.of(), buttons, 1);
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
            if (!c.permissions.has(player, Action.MANAGE_HUTS)) { refuse(player, c, Action.MANAGE_HUTS); return; }
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
                    + " (health " + (int) Math.ceil(x.health) + "/" + (int) c.health.max(x)
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

    /** "Builder's Hut: 40 × oak planks — being crafted at the Sawmill"; waiting ones stand out. */
    private static Component request(Requests.Request r, boolean withHut) {
        return Component.text((withHut ? r.job.hut.type.title + ": " : "Waiting for ") + r.amount() + " × " + r.label() + " — " + r.stateText(),
                r.state == Requests.State.WAITING ? NamedTextColor.YELLOW : NamedTextColor.GRAY);
    }

    /**
     * The open requests of one hut's job, or of the whole colony ({@code job} null, from the town hall: the mod's request
     * list; the clipboard comes later). Back returns to {@code from}.
     */
    void requests(Player p, Colony c, Hut from, Job job) {
        List<DialogBody> body = new ArrayList<>();
        var list = job == null ? c.requests.all() : c.requests.of(job);
        if (list.isEmpty()) body.add(text(Component.text(job == null ? "No hut is waiting for anything." : "Nothing is needed now.", NamedTextColor.GREEN)));
        for (Requests.Request r : list) body.add(text(request(r, job == null)));
        body.add(text(Component.text("Fill a request by putting the items in " + (job == null ? "that hut's" : "the hut's") + " chest.", NamedTextColor.GRAY)));
        Dialogs.show(p, job == null ? "Requests" : from.type.title + ": requests", body, List.of(),
                List.of(button("Back", p, c, from, player -> hut(player, c, from))), 1);
    }

    /** A crafter's taught recipes, each with a button to forget it. */
    void recipes(Player p, Colony c, Hut h, Crafter cr) {
        List<DialogBody> body = new ArrayList<>();
        body.add(text(Component.text(cr.recipes.size() + " of " + cr.maxRecipes() + " recipes. The " + h.type.worker.toLowerCase()
                + " makes " + cr.makes + ".", NamedTextColor.GOLD)));
        List<ActionButton> buttons = new ArrayList<>();
        for (var key : List.copyOf(cr.recipes)) {
            ItemStack result = Crafter.resultOf(key);
            if (result == null) continue;
            body.add(DialogBody.item(result).description(DialogBody.plainMessage(Component.text(result.getAmount() + " × ")
                    .append(result.effectiveName()), 250)).showTooltip(true).build());
            buttons.add(button("Forget " + Job.name(result.getType()), p, c, h, Action.MANAGE_HUTS, player -> {
                cr.recipes.remove(key);
                plugin.save(c);
                recipes(player, c, h, cr);
            }));
        }
        buttons.add(button("Back", p, c, h, player -> hut(player, c, h)));
        Dialogs.show(p, "Recipes", body, List.of(), buttons, 1);
    }

    /**
     * The furnace recipes a smelter can still learn, one button each (the mod teaches them in a furnace screen; a
     * furnace window can't show a recipe's result without smelting it, so this lists them).
     */
    void teachSmelting(Player p, Colony c, Hut h, Crafter cr) {
        List<ActionButton> buttons = new ArrayList<>();
        for (var r : cr.teachable()) {
            var in = ((org.bukkit.inventory.CookingRecipe<?>) r).getInputChoice().getItemStack();
            String label = Job.name(in.getType()) + " → " + Job.name(r.getResult().getType());
            buttons.add(button(label, p, c, h, Action.MANAGE_HUTS, player -> {
                String problem = cr.teach(r);
                if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
                else plugin.save(c);
                teachSmelting(player, c, h, cr);
            }));
        }
        buttons.add(button("Back", p, c, h, player -> hut(player, c, h)));
        Dialogs.show(p, "Teach a recipe", List.of(text(Component.text(cr.recipes.size() + " of " + cr.maxRecipes()
                + " recipes. Click one for the " + h.type.worker.toLowerCase() + " to learn.", NamedTextColor.GOLD))), List.of(), buttons, 2);
    }

    private static DialogBody text(Component c) {
        return Dialogs.text(c);
    }

    // ---- permissions --------------------------------------------------------------------------------------

    /** The colony's members and their ranks, from the town hall menu (needs EDIT_PERMISSIONS). */
    void permissions(Player p, Colony c, Hut townHall) {
        List<DialogBody> body = new ArrayList<>();
        body.add(text(Component.text("Owner: " + plugin.getServer().getOfflinePlayer(c.owner).getName(), NamedTextColor.GOLD)));
        var members = c.permissions.members();
        if (members.isEmpty()) body.add(text(Component.text("No other members. Everyone else is Neutral.", NamedTextColor.GRAY)));
        List<ActionButton> buttons = new ArrayList<>();
        members.forEach((id, m) -> buttons.add(button(m.name() + " — " + m.rank().title(), p, c, townHall, Action.EDIT_PERMISSIONS,
                player -> member(player, c, townHall, id, m))));
        buttons.add(button("Add a player", p, c, townHall, Action.EDIT_PERMISSIONS, player -> addMember(player, c, townHall)));
        buttons.add(button("What each rank may do", p, c, townHall, Action.EDIT_PERMISSIONS, player -> ranks(player, c, townHall)));
        buttons.add(button("Back", p, c, townHall, player -> hut(player, c, townHall)));
        Dialogs.show(p, "Permissions", body, List.of(), buttons, 1);
    }

    private static final List<Rank> MEMBER_RANKS = List.of(Rank.OFFICER, Rank.FRIEND, Rank.HOSTILE);

    private void member(Player p, Colony c, Hut townHall, java.util.UUID id, Permissions.Member m) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Rank r : MEMBER_RANKS) if (r != m.rank())
            buttons.add(button("Make " + r.title(), p, c, townHall, Action.EDIT_PERMISSIONS, player -> setRank(player, c, townHall, id, m.name(), r)));
        buttons.add(button("Remove (Neutral)", p, c, townHall, Action.EDIT_PERMISSIONS, player -> setRank(player, c, townHall, id, m.name(), Rank.NEUTRAL)));
        buttons.add(button("Back", p, c, townHall, player -> permissions(player, c, townHall)));
        Dialogs.show(p, m.name(), List.of(text(Component.text(m.name() + " is " + m.rank().title() + " in " + c.name + "."))), List.of(), buttons, 1);
    }

    private void setRank(Player p, Colony c, Hut townHall, java.util.UUID id, String name, Rank r) {
        c.permissions.setRank(id, name, r);
        plugin.save(c);
        permissions(p, c, townHall);
    }

    /** Adds a player who has been on the server, by name, at a rank. */
    private void addMember(Player p, Colony c, Hut townHall) {
        List<SingleOptionDialogInput.OptionEntry> ranks = new ArrayList<>();
        for (Rank r : MEMBER_RANKS) ranks.add(SingleOptionDialogInput.OptionEntry.create(r.name(), Component.text(r.title()), r == Rank.FRIEND));
        List<DialogInput> inputs = List.of(DialogInput.text("name", Component.text("Player name")).width(200).build(),
                DialogInput.singleOption("rank", Component.text("Rank"), ranks).width(200).build());
        List<ActionButton> buttons = List.of(button("Add", p, c, townHall, (player, view) -> {
            if (!c.permissions.has(player, Action.EDIT_PERMISSIONS)) { refuse(player, c, Action.EDIT_PERMISSIONS); return; }
            String name = view.getText("name") == null ? "" : view.getText("name").trim();
            Rank r = Rank.FRIEND;
            for (Rank m : MEMBER_RANKS) if (m.name().equals(view.getText("rank"))) r = m;
            String problem = c.permissions.add(name, r);
            if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            else plugin.save(c);
            permissions(player, c, townHall);
        }), button("Back", p, c, townHall, player -> permissions(player, c, townHall)));
        Dialogs.show(p, "Add a player", List.of(text(Component.text("They join " + c.name + " at this rank."))), inputs, buttons, 1);
    }

    private void ranks(Player p, Colony c, Hut townHall) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Rank r : Rank.values()) if (r != Rank.OWNER)
            buttons.add(button(r.title(), p, c, townHall, Action.EDIT_PERMISSIONS, player -> rankActions(player, c, townHall, r)));
        buttons.add(button("Back", p, c, townHall, player -> permissions(player, c, townHall)));
        Dialogs.show(p, "Ranks", List.of(text(Component.text("Neutral is everyone who isn't a member. Pick a rank to change what it may do inside "
                + c.name + "'s border.", NamedTextColor.GRAY))), List.of(), buttons, 1);
    }

    private void rankActions(Player p, Colony c, Hut townHall, Rank r) {
        List<Action> shown = new ArrayList<>(List.of(Action.values()));
        if (r == Rank.NEUTRAL) shown.remove(Action.EDIT_PERMISSIONS); // never theirs, as in the mod
        List<DialogInput> inputs = new ArrayList<>();
        for (Action a : shown) inputs.add(DialogInput.bool(a.name().toLowerCase(), Component.text(a.label)).initial(c.permissions.has(r, a)).build());
        List<ActionButton> buttons = List.of(button("Save", p, c, townHall, (player, view) -> {
            if (!c.permissions.has(player, Action.EDIT_PERMISSIONS)) { refuse(player, c, Action.EDIT_PERMISSIONS); return; }
            boolean own = c.permissions.rank(player.getUniqueId()) == r;
            for (Action a : shown) {
                Boolean on = view.getBoolean(a.name().toLowerCase());
                if (on == null || own && a == Action.EDIT_PERMISSIONS && !on) continue; // as the mod: no taking your own away
                c.permissions.allow(r, a, on);
            }
            plugin.save(c);
            ranks(player, c, townHall);
        }), button("Back", p, c, townHall, player -> ranks(player, c, townHall)));
        Dialogs.show(p, r.title(), List.of(text(Component.text("What a " + r.title() + " may do inside " + c.name + "'s border."))), inputs, buttons, 1);
    }

    private static void refuse(Player p, Colony c, Action a) {
        p.sendMessage(Component.text("You can't " + a.label.toLowerCase() + " in " + c.name + ".", NamedTextColor.RED));
    }

    /** A button that queues builder's work (MANAGE_HUTS): says why not, if it can't, and shows the hut menu again. */
    private ActionButton order(String label, Player shownTo, Colony c, Hut h, java.util.function.Supplier<String> queue) {
        return button(label, shownTo, c, h, Action.MANAGE_HUTS, player -> {
            String problem = queue.get();
            if (problem != null) player.sendMessage(Component.text(problem, NamedTextColor.RED));
            plugin.save(c);
            hut(player, c, h);
        });
    }

    /** A button that also needs {@code need} when clicked: ranks can change while the menu is open. */
    private ActionButton button(String label, Player shownTo, Colony c, Hut h, Action need, Consumer<Player> action) {
        return button(label, shownTo, c, h, player -> {
            if (c.permissions.has(player, need)) action.accept(player);
            else refuse(player, c, need);
        });
    }

    /** A button that runs only while the hut still exists (it may be removed while the menu is open). */
    private ActionButton button(String label, Player shownTo, Colony c, Hut h, Consumer<Player> action) {
        return button(label, shownTo, c, h, (player, view) -> action.accept(player));
    }

    private ActionButton button(String label, Player shownTo, Colony c, Hut h, BiConsumer<Player, DialogResponseView> action) {
        return Dialogs.button(plugin, label, 200, shownTo, () -> c.huts.contains(h), action);
    }
}
