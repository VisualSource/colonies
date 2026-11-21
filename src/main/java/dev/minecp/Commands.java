package dev.minecp;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Rank;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Rack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** /colony: info, the Build Tool, test builds, movement stats, and (admins) hut items and racks. */
final class Commands implements TabExecutor {
    private final MineCP plugin;

    Commands(MineCP plugin) {
        this.plugin = plugin;
    }

    /** The colony whose land the player stands on, else their own. */
    private Colony here(Player p) {
        Colony c = plugin.claims.colonyAt(p.getLocation());
        return c != null ? c : plugin.colonyOf(p);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player) || args.length == 0) return false;
        Hut.Type hutType = Hut.Type.byCommand(args[0]);
        if (hutType != null || args[0].equals("rack")) {
            if (!player.hasPermission("minecp.admin")) {
                player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                return true;
            }
            player.getInventory().addItem(hutType != null ? plugin.items.hutItem(hutType) : Rack.item().asQuantity(16));
            return true;
        }
        Colony c = plugin.colonyOf(player);
        switch (args[0]) {
            case "buildtool" -> {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!hand.isSimilar(new ItemStack(Material.STICK))) { // plain sticks only, not renamed or Build Tools already
                    player.sendMessage(Component.text("Hold a stick to turn it into a Build Tool.", NamedTextColor.RED));
                    return true;
                }
                hand.subtract(); // one is enough: the Build Tool is kept when crafting
                player.getInventory().setItemInMainHand(hand.isEmpty() ? null : hand);
                player.getInventory().addItem(plugin.items.buildTool()).values().forEach(o -> player.getWorld().dropItem(player.getLocation(), o));
                player.sendMessage(Component.text("Build Tool made. Right-click the ground to place a hut; it's kept when you craft huts.", NamedTextColor.GREEN));
            }
            case "build" -> {
                if (c == null || c.builder == null) {
                    player.sendMessage(Component.text("You need a colony with a Builder's Hut.", NamedTextColor.RED));
                    return true;
                }
                if (args.length != 2) return false;
                if (args[1].equals("cancel")) {
                    c.builder.cancel();
                    player.sendMessage("Build cancelled.");
                    return true;
                }
                if (!player.hasPermission("minecp.admin")) { // any structure anywhere, past protection plugins: a test command
                    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                if (c.builder.order != null) {
                    player.sendMessage(Component.text("The builder is busy. Use /colony build cancel first.", NamedTextColor.RED));
                    return true;
                }
                Block target = player.getTargetBlockExact(10);
                if (target == null) {
                    player.sendMessage(Component.text("Look at the block to build on.", NamedTextColor.RED));
                    return true;
                }
                BuildOrder order = BuildOrder.create(args[1], target.getRelative(0, 1, 0).getLocation(),
                        BuildOrder.rotationFor(player.getFacing()), null);
                if (order == null) {
                    player.sendMessage(Component.text("No structure named " + args[1]
                            + ". Save one with a structure block first.", NamedTextColor.RED));
                    return true;
                }
                c.builder.start(order, null);
                plugin.save(c);
                player.sendMessage("Build of " + args[1] + " queued (" + order.steps.size() + " blocks). "
                        + "Put materials in the Builder's Hut. Huts are built from their own menus.");
            }
            case "info" -> {
                if (c == null) {
                    player.sendMessage("You don't have a colony. Place a Town Hall to found one.");
                    return true;
                }
                var th = c.townHall().block;
                player.sendMessage(Component.text(c.name, NamedTextColor.GOLD));
                player.sendMessage("Town hall: " + th.getX() + " " + th.getY() + " " + th.getZ()
                        + " (right-click it for the colony menu)");
                player.sendMessage("Citizens: " + c.colonists.size() + ", beds for " + c.capacity());
            }
            case "members" -> {
                Colony here = here(player);
                if (here == null) { player.sendMessage("You aren't in a colony and don't have one."); return true; }
                if (!here.permissions.isMember(player.getUniqueId()) && !player.hasPermission("minecp.admin")) {
                    player.sendMessage(Component.text("Only members of " + here.name + " can see its members.", NamedTextColor.RED));
                    return true;
                }
                player.sendMessage(Component.text(here.name + ": owner " + plugin.getServer().getOfflinePlayer(here.owner).getName(), NamedTextColor.GOLD));
                here.permissions.members().values().forEach(m -> player.sendMessage(m.name() + " — " + m.rank().title()));
            }
            case "member" -> { // member add <player> [officer|friend|hostile], member remove <player>
                Colony here = here(player);
                if (args.length < 3 || !(args[1].equals("add") || args[1].equals("remove"))) return false;
                if (here == null) { player.sendMessage("You aren't in a colony and don't have one."); return true; }
                if (!here.permissions.has(player, Action.EDIT_PERMISSIONS)) {
                    player.sendMessage(Component.text("You can't change members of " + here.name + ".", NamedTextColor.RED));
                    return true;
                }
                Rank rank = Rank.NEUTRAL;
                if (args[1].equals("add")) {
                    try {
                        rank = args.length > 3 ? Rank.valueOf(args[3].toUpperCase()) : Rank.FRIEND;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                    if (rank == Rank.OWNER || rank == Rank.NEUTRAL) return false;
                }
                String problem = here.permissions.add(args[2], rank);
                if (problem != null) {
                    player.sendMessage(Component.text(problem, NamedTextColor.RED));
                    return true;
                }
                plugin.save(here);
                player.sendMessage(args[2] + " is now " + rank.title() + " in " + here.name + ".");
            }
            case "raid" -> { // raid now|tonight [amount]: admins, on the colony they stand in (the mod's /mc colony raid)
                Colony here = here(player);
                if (!player.hasPermission("minecp.admin")) {
                    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                if (here == null || args.length < 2) return false;
                if (args[1].equals("tonight")) {
                    here.raids.raidTonight = true;
                    plugin.save(here);
                    player.sendMessage(here.name + " will be raided at the next nightfall.");
                    return true;
                }
                if (!args[1].equals("now")) return false;
                int amount;
                try {
                    amount = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                } catch (NumberFormatException e) {
                    return false;
                }
                String problem = here.raids.start(true, amount);
                player.sendMessage(problem != null ? Component.text(problem, NamedTextColor.RED) : Component.text("Raid started on " + here.name + "."));
            }
            case "movement" -> { // how often each citizen's walking went wrong
                if (c == null) {
                    player.sendMessage("You don't have a colony.");
                    return true;
                }
                for (Colonist w : c.colonists) {
                    var s = w.stats;
                    player.sendMessage(w.name + ": " + s.teleports + " teleports, "
                            + s.stuck + " stuck, " + s.searches + " path searches"
                            + (s.searches == 0 ? "" : String.format(" (%.2f ms avg)", s.searchNanos / 1e6 / s.searches)));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /** Suggestions as /colony is typed: only what the player may use (admins also get the hut items, rack and raid). */
    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        boolean admin = sender.hasPermission("minecp.admin");
        java.util.List<String> options = new java.util.ArrayList<>();
        switch (args.length) {
            case 1 -> {
                options.addAll(java.util.List.of("info", "buildtool", "members", "member", "build", "movement"));
                if (admin) { options.addAll(Hut.Type.commands()); options.add("rack"); options.add("raid"); }
            }
            case 2 -> {
                switch (args[0]) {
                    case "member" -> options.addAll(java.util.List.of("add", "remove"));
                    case "raid" -> { if (admin) options.addAll(java.util.List.of("now", "tonight")); }
                    case "build" -> {
                        options.add("cancel");
                        if (admin && !args[1].contains("/")) Colony.styles().forEach(st -> options.add(st + "/")); // styles first: thousands of structures
                        else if (admin) options.addAll(dev.minecp.build.Catalog.structures());
                    }
                    default -> {}
                }
            }
            case 3 -> { if (args[0].equals("member")) sender.getServer().getOnlinePlayers().forEach(p -> options.add(p.getName())); }
            case 4 -> { if (args[0].equals("member") && args[1].equals("add")) options.addAll(java.util.List.of("officer", "friend", "hostile")); }
            default -> {}
        }
        return org.bukkit.util.StringUtil.copyPartialMatches(args[args.length - 1], options, new java.util.ArrayList<>());
    }
}
