package dev.minecp;

import dev.minecp.build.BuildOrder;
import dev.minecp.build.Placement;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.colony.Action;
import dev.minecp.colony.Claims;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Rank;
import dev.minecp.job.Job;
import dev.minecp.logistics.Rack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /colonies: the mod's /mc commands we keep (its {@code colony} and {@code citizens} subtrees, forceunloadchunks and
 * resetsupplies; {@code core/commands}), and ours: the Build Tool, test builds, members, movement stats and (admins)
 * hut items. A colony is named by its owner's name where the mod takes a colony id; a citizen by its number in
 * {@code citizens list}. Admins (minecp.admin) stand in for the mod's operators.
 */
final class Commands implements TabExecutor {
    private static final String ADMIN = "minecp.admin";
    private static final int CITIZENS_ON_PAGE = 9;             // the mod's CommandCitizenList
    private static final int WALK_TICKS = 20 * 60 * 3, CLAIM_RANGE = 10; // the mod's: a walk gives up after 3 minutes; claim's range 0 to 10
    private static final List<String> COLONY = List.of("info", "addOfficer", "setRank", "raid", "claim", "claiminfo", "chunkstatus", "reclaimchunks");
    private static final List<String> CITIZENS = List.of("list", "info", "kill", "teleport", "walk", "spawnNew", "reload", "modify", "trackPath", "trackPathType");
    private static final List<String> RANKS = List.of("officer", "friend", "neutral", "hostile");

    private final MineCP plugin;
    private final Map<UUID, Colonist> sent = new HashMap<>();       // walk: the citizen each player last sent (walk stop)
    private final Map<UUID, Colonist> tracked = new HashMap<>();    // trackPath: the citizen whose path each player sees
    private final Map<UUID, String> trackedJobs = new HashMap<>();  // trackPathType: the jobs whose paths each player sees

    Commands(MineCP plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::showPaths, 10, 10);
    }

    /** The colony whose land the player stands on, else their own. */
    private Colony here(Player p) {
        Colony c = plugin.claims.colonyAt(p.getLocation());
        return c != null ? c : plugin.colonyOf(p);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player) || args.length == 0) return false;
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "give" -> give(player, rest);
            case "buildtool" -> buildTool(player);
            case "build" -> build(player, rest);
            case "members" -> members(player);
            case "member" -> member(player, rest);
            case "movement" -> movement(player);
            case "colony" -> rest.length > 0 && colony(player, rest[0], Arrays.copyOfRange(rest, 1, rest.length));
            case "citizens" -> rest.length > 0 && citizens(player, rest[0], Arrays.copyOfRange(rest, 1, rest.length));
            case "forceunloadchunks" -> forceUnload(player);
            case "resetsupplies" -> resetSupplies(player, rest);
            default -> false;
        };
    }

    // ---- who may -------------------------------------------------------------------------------------------------

    private static Component red(String s) {
        return Component.text(s, NamedTextColor.RED);
    }

    /** The mod's operator commands. */
    private static boolean admin(Player p) {
        if (p.hasPermission(ADMIN)) return true;
        p.sendMessage(red("You don't have permission."));
        return false;
    }

    /** The mod's officer commands: admins, or the colony's owner and officers (its isColonyManager). */
    private static boolean officer(Player p, Colony c) {
        Rank r = c.permissions.rank(p.getUniqueId());
        if (p.hasPermission(ADMIN) || r == Rank.OWNER || r == Rank.OFFICER) return true;
        p.sendMessage(red("Only the owner and officers of " + c.name + " can do that."));
        return false;
    }

    /** A command the mod's server config can close to players (config.yml's commands section); admins always may. */
    private boolean allowed(Player p, String setting) {
        if (p.hasPermission(ADMIN) || plugin.getConfig().getBoolean("commands." + setting)) return true;
        p.sendMessage(red("That command is turned off in the server's config."));
        return false;
    }

    /** The colony an argument names by its owner's name; null after saying so. */
    private Colony colonyNamed(Player p, String owner) {
        for (Colony c : plugin.allColonies()) if (owner.equalsIgnoreCase(Bukkit.getOfflinePlayer(c.owner).getName())) return c;
        p.sendMessage(red("No colony is owned by " + owner + "."));
        return null;
    }

    /** A citizen by its number in {@code citizens list}; null after saying so. */
    private static Colonist citizen(Player p, Colony c, String number) {
        try {
            int i = Integer.parseInt(number);
            if (i >= 1 && i <= c.colonists.size()) return c.colonists.get(i - 1);
        } catch (NumberFormatException ignored) {}
        p.sendMessage(red("No citizen " + number + " in " + c.name + ": see /colonies citizens list."));
        return null;
    }

    /** x y z, with ~ for the player's own (as vanilla's coordinates); null after saying so. */
    private static Location position(Player p, String[] a, int from) {
        if (a.length < from + 3) return null;
        Location at = p.getLocation();
        double[] base = {at.getX(), at.getY(), at.getZ()}, out = new double[3];
        try {
            for (int i = 0; i < 3; i++) {
                String s = a[from + i];
                out[i] = s.startsWith("~") ? base[i] + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0) : Double.parseDouble(s);
            }
        } catch (NumberFormatException e) {
            p.sendMessage(red("Give a position as x y z."));
            return null;
        }
        return new Location(p.getWorld(), out[0], out[1], out[2]);
    }

    // ---- ours ----------------------------------------------------------------------------------------------------

    /** give <hut>|rack: a hut's item, or 16 racks (admins). */
    private boolean give(Player p, String[] a) {
        if (a.length != 1) return false;
        Hut.Type type = Hut.Type.byCommand(a[0]);
        if (type == null && !a[0].equals("rack")) return false;
        if (!admin(p)) return true;
        p.getInventory().addItem(type != null ? plugin.items.hutItem(type) : Rack.item().asQuantity(16));
        return true;
    }

    private boolean buildTool(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!hand.isSimilar(new ItemStack(Material.STICK))) { // plain sticks only, not renamed or Build Tools already
            player.sendMessage(red("Hold a stick to turn it into a Build Tool."));
            return true;
        }
        hand.subtract(); // one is enough: the Build Tool is kept when crafting
        player.getInventory().setItemInMainHand(hand.isEmpty() ? null : hand);
        player.getInventory().addItem(plugin.items.buildTool()).values().forEach(o -> player.getWorld().dropItem(player.getLocation(), o));
        player.sendMessage(Component.text("Build Tool made. Right-click the ground to place a hut; it's kept when you craft huts.", NamedTextColor.GREEN));
        return true;
    }

    private boolean build(Player player, String[] a) {
        Colony c = plugin.colonyOf(player);
        if (c == null || c.builder == null) {
            player.sendMessage(red("You need a colony with a Builder's Hut."));
            return true;
        }
        if (a.length != 1) return false;
        if (a[0].equals("cancel")) {
            c.builder.cancel();
            player.sendMessage("Build cancelled.");
            return true;
        }
        if (!admin(player)) return true; // any structure anywhere, past protection plugins: a test command
        if (c.builder.order != null) {
            player.sendMessage(red("The builder is busy. Use /colonies build cancel first."));
            return true;
        }
        Block target = player.getTargetBlockExact(10);
        if (target == null) {
            player.sendMessage(red("Look at the block to build on."));
            return true;
        }
        BuildOrder order = BuildOrder.create(a[0], target.getRelative(0, 1, 0).getLocation(), BuildOrder.rotationFor(player.getFacing()), null);
        if (order == null) {
            player.sendMessage(red("No structure named " + a[0] + ". Save one with a structure block first."));
            return true;
        }
        c.builder.start(order, null);
        plugin.save(c);
        player.sendMessage("Build of " + a[0] + " queued (" + order.steps.size() + " blocks). "
                + "Put materials in the Builder's Hut. Huts are built from their own menus.");
        return true;
    }

    private boolean members(Player player) {
        Colony here = here(player);
        if (here == null) { player.sendMessage("You aren't in a colony and don't have one."); return true; }
        if (!here.permissions.isMember(player.getUniqueId()) && !player.hasPermission(ADMIN)) {
            player.sendMessage(red("Only members of " + here.name + " can see its members."));
            return true;
        }
        player.sendMessage(Component.text(here.name + ": owner " + Bukkit.getOfflinePlayer(here.owner).getName(), NamedTextColor.GOLD));
        here.permissions.members().values().forEach(m -> player.sendMessage(m.name() + " — " + m.rank().title()));
        return true;
    }

    /** member add <player> [officer|friend|hostile], member remove <player>: the colony the player stands in, else their own. */
    private boolean member(Player player, String[] a) {
        Colony here = here(player);
        if (a.length < 2 || !(a[0].equals("add") || a[0].equals("remove"))) return false;
        if (here == null) { player.sendMessage("You aren't in a colony and don't have one."); return true; }
        if (!here.permissions.has(player, Action.EDIT_PERMISSIONS)) {
            player.sendMessage(red("You can't change members of " + here.name + "."));
            return true;
        }
        Rank rank = Rank.NEUTRAL;
        if (a[0].equals("add")) {
            try {
                rank = a.length > 2 ? Rank.valueOf(a[2].toUpperCase()) : Rank.FRIEND;
            } catch (IllegalArgumentException e) {
                return false;
            }
            if (rank == Rank.OWNER || rank == Rank.NEUTRAL) return false;
        }
        String problem = here.permissions.add(a[1], rank);
        if (problem != null) {
            player.sendMessage(red(problem));
            return true;
        }
        plugin.save(here);
        player.sendMessage(a[1] + " is now " + rank.title() + " in " + here.name + ".");
        return true;
    }

    /** How often each citizen's walking went wrong. */
    private boolean movement(Player player) {
        Colony c = plugin.colonyOf(player);
        if (c == null) {
            player.sendMessage("You don't have a colony.");
            return true;
        }
        for (Colonist w : c.colonists) {
            var s = w.stats;
            player.sendMessage(w.name + ": " + s.teleports + " teleports, " + s.stuck + " stuck, " + s.searches + " path searches"
                    + (s.searches == 0 ? "" : String.format(" (%.2f ms avg)", s.searchNanos / 1e6 / s.searches)));
        }
        return true;
    }

    // ---- the mod's top level -------------------------------------------------------------------------------------

    /** forceunloadchunks (CommandUnloadForcedChunks): in creative, the force-load flag off every loaded chunk of this world. */
    private static boolean forceUnload(Player p) {
        if (p.getGameMode() != GameMode.CREATIVE) {
            p.sendMessage(red("Only in creative mode."));
            return true;
        }
        for (Chunk ch : p.getWorld().getLoadedChunks()) if (ch.isForceLoaded()) ch.setForceLoaded(false);
        p.sendMessage("Successfully removed forceload flag!");
        return true;
    }

    /** resetsupplies <player> (CommandResetPlayerSupplies): an online player may place a supply camp or ship again. */
    private static boolean resetSupplies(Player p, String[] a) {
        if (a.length != 1) return false;
        if (!admin(p)) return true;
        Player target = Bukkit.getPlayerExact(a[0]);
        if (target == null) {
            p.sendMessage(red("No player called " + a[0] + " is online."));
            return true;
        }
        Placement.resetSupplies(target);
        p.sendMessage("Reset the supplies of " + target.getName() + ".");
        if (target != p) target.sendMessage("You may place a supply camp or ship again.");
        return true;
    }

    // ---- colony --------------------------------------------------------------------------------------------------

    private boolean colony(Player p, String sub, String[] a) {
        switch (sub) {
            case "info" -> { // [owner] (CommandColonyInfo): ours, the colony you stand in or own when none is named
                Colony c = a.length > 0 ? colonyNamed(p, a[0]) : here(p);
                if (c == null) { if (a.length == 0) p.sendMessage("You aren't in a colony and don't have one."); return true; }
                if (!officer(p, c) || !allowed(p, "colony-info")) return true;
                Block th = c.townHall().block;
                p.sendMessage(Component.text(c.name, NamedTextColor.GOLD));
                p.sendMessage("Owner: " + Bukkit.getOfflinePlayer(c.owner).getName());
                p.sendMessage("Citizens: " + c.colonists.size() + "/" + c.capacity());
                p.sendMessage(Component.text("Town hall: x=" + th.getX() + " y=" + th.getY() + " z=" + th.getZ(), NamedTextColor.GREEN));
                if (!dev.minecp.colony.Raids.enabled) p.sendMessage("This colony is unable to be raided");
            }
            case "addOfficer" -> { // <owner> <player> (CommandAddOfficer): the player must be online, as the mod's
                if (a.length != 2) return false;
                Colony c = colonyNamed(p, a[0]);
                if (c == null || !officer(p, c) || !allowed(p, "add-officer")) return true;
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { p.sendMessage(red("No player called " + a[1] + " is online.")); return true; }
                if (!c.permissions.setRank(target.getUniqueId(), target.getName(), Rank.OFFICER)) { p.sendMessage(red("That's the owner.")); return true; }
                plugin.save(c);
                p.sendMessage(target.getName() + " is now an officer of " + c.name + ".");
            }
            case "setRank" -> { // <owner> <player> <rank> (CommandSetRank, operators)
                if (a.length != 3) return false;
                if (!admin(p)) return true;
                Colony c = colonyNamed(p, a[0]);
                if (c == null) return true;
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { p.sendMessage(red("No player called " + a[1] + " is online.")); return true; }
                if (!RANKS.contains(a[2].toLowerCase())) { p.sendMessage(red("Rank does not exist")); return true; }
                if (!c.permissions.setRank(target.getUniqueId(), target.getName(), Rank.valueOf(a[2].toUpperCase()))) { p.sendMessage(red("That's the owner.")); return true; }
                plugin.save(c);
                p.sendMessage("Set player: " + target.getName() + " to rank: " + a[2].toLowerCase());
            }
            case "raid" -> { // <owner> <now|tonight> [type] [amount] (CommandRaid, operators; no ships: ours has land raids only)
                if (a.length < 2 || a.length > 4) return false;
                if (!admin(p)) return true;
                Colony c = colonyNamed(p, a[0]);
                if (c == null) return true;
                String type = null;
                int amount = 0;
                for (int i = 2; i < a.length; i++) {
                    try {
                        amount = Integer.parseInt(a[i]);
                        if (amount < 1) return false;
                    } catch (NumberFormatException e) {
                        type = a[i];
                    }
                }
                String problem;
                if (a[1].equals("now")) problem = c.raids.start(amount, type);
                else if (a[1].equals("tonight")) problem = c.raids.raidTonight(amount, type);
                else return false;
                if (problem != null) { p.sendMessage(red(problem)); return true; }
                plugin.save(c);
                p.sendMessage(a[1].equals("now") ? "Raid started on " + c.name + "." : c.name + " will be raided at the next nightfall.");
            }
            case "claim" -> { // <owner> <range> <true|false> (CommandClaimChunks, operators): around the chunk you stand in
                if (a.length != 3 || !(a[2].equals("true") || a[2].equals("false"))) return false;
                if (!admin(p)) return true;
                Colony c = colonyNamed(p, a[0]);
                if (c == null) return true;
                int range;
                try { range = Integer.parseInt(a[1]); } catch (NumberFormatException e) { return false; }
                if (range < 0 || range > CLAIM_RANGE) return false;
                if (range > Claims.maxSize) { p.sendMessage(red("That range is larger than a colony may be (" + Claims.maxSize + " chunks).")); return true; }
                if (p.getWorld() != c.townHall().block.getWorld()) { p.sendMessage(red(c.name + " is in another world.")); return true; }
                boolean add = a[2].equals("true");
                c.claimByHand(p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4, range, add);
                plugin.save(c);
                p.sendMessage(add ? "Successfully claimed the chunks." : "Successfully removed the claims.");
            }
            case "claiminfo" -> { // [x y z] (CommandShowClaim, operators): who claims the chunk, directly and by building
                if (!admin(p)) return true;
                Location at = a.length == 0 ? p.getLocation() : position(p, a, 0);
                if (at == null) return a.length >= 3;
                int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
                long key = Claims.key(cx, cz);
                Colony owner = plugin.claims.colonyAt(at.getWorld(), cx, cz);
                p.sendMessage(Component.text("Claim data of chunk at: " + at.getBlockX() + "X " + at.getBlockZ() + "Z", NamedTextColor.DARK_AQUA));
                p.sendMessage(Component.text("Owner: " + (owner == null ? "none" : owner.name), NamedTextColor.GOLD));
                for (Colony c : plugin.allColonies()) {
                    if (c.townHall().block.getWorld() != at.getWorld()) continue;
                    if (Claims.direct(c, key)) p.sendMessage("Direct claim: " + c.name);
                    for (Hut h : Claims.claimers(c, key))
                        p.sendMessage(c.name + ": " + h.type.title + " at " + h.block.getX() + " " + h.block.getY() + " " + h.block.getZ());
                }
            }
            case "chunkstatus" -> { // <owner> (CommandColonyChunks): its claimed chunks loaded, and what holds them
                if (a.length != 1) return false;
                Colony c = colonyNamed(p, a[0]);
                if (c == null || !officer(p, c)) return true;
                var world = c.townHall().block.getWorld();
                int loaded = 0, forced = 0;
                java.util.Set<String> tickets = new java.util.TreeSet<>();
                for (long k : c.claims()) {
                    int x = Claims.x(k), z = Claims.z(k);
                    if (!world.isChunkLoaded(x, z)) continue;
                    loaded++;
                    Chunk ch = world.getChunkAt(x, z);
                    if (ch.isForceLoaded()) forced++;
                    ch.getPluginChunkTickets().forEach(pl -> tickets.add(pl.getName()));
                }
                p.sendMessage(Component.text(c.name, NamedTextColor.YELLOW));
                p.sendMessage(Component.text("Loaded chunks: ").append(Component.text(loaded + " of " + c.claims().size(), NamedTextColor.YELLOW)));
                p.sendMessage(Component.text("Force-loaded: ").append(Component.text(forced, NamedTextColor.YELLOW))
                        .append(Component.text(tickets.isEmpty() ? "" : ", plugin tickets: " + String.join(", ", tickets))));
            }
            case "reclaimchunks" -> { // <owner> (CommandReclaimChunks, operators): ours are worked out from the huts, so a refresh
                if (a.length != 1) return false;
                if (!admin(p)) return true;
                Colony c = colonyNamed(p, a[0]);
                if (c == null) return true;
                c.claimsChanged();
                p.sendMessage("Successfully claimed the chunks.");
            }
            default -> { return false; }
        }
        return true;
    }

    // ---- citizens ------------------------------------------------------------------------------------------------

    private boolean citizens(Player p, String sub, String[] a) {
        if (sub.equals("trackPathType")) { // <job|clear> (CommandTrackType, operators). Ours: by job, the mod's by path search kind
            if (a.length != 1) return false;
            if (!admin(p)) return true;
            if (a[0].equals("clear")) { trackedJobs.remove(p.getUniqueId()); p.sendMessage("Removed tracking for player"); }
            else { trackedJobs.put(p.getUniqueId(), a[0].toLowerCase()); p.sendMessage("Tracking enabled for jobs containing: " + a[0]); }
            return true;
        }
        if (sub.equals("walk") && a.length == 1 && a[0].equals("stop")) {
            Colonist w = sent.remove(p.getUniqueId());
            if (w != null) w.sentTo = null;
            return true;
        }
        if (!CITIZENS.contains(sub) || a.length == 0) return false;
        Colony c = colonyNamed(p, a[0]);
        if (c == null) return true;
        if (sub.equals("spawnNew")) { // <owner> (CommandCitizenSpawnNew, operators): room or not
            if (!admin(p)) return true;
            Colonist w = c.spawnNew();
            plugin.save(c);
            p.sendMessage("Spawned " + w.name + " in " + c.name + ".");
            return true;
        }
        if (!officer(p, c)) return true;
        if (sub.equals("list")) { // <owner> [page] (CommandCitizenList)
            int page = 1;
            if (a.length > 1) try { page = Integer.parseInt(a[1]); } catch (NumberFormatException e) { return false; }
            list(p, c, a[0], page);
            return true;
        }
        if (a.length < 2) return false;
        Colonist w = citizen(p, c, a[1]);
        if (w == null) return true;
        String who = "#" + a[1] + " " + w.name;
        switch (sub) {
            case "info" -> info(p, c, w, who);
            case "kill" -> { // (CommandCitizenKill; off for players by default)
                if (!allowed(p, "kill-citizens")) return true;
                Location at = w.location();
                c.health.kill(w);
                plugin.save(c);
                p.sendMessage(who + " was killed at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + ".");
            }
            case "teleport" -> { // <x y z> (CommandCitizenTeleport): within its world
                Location to = position(p, a, 2);
                if (to == null) return a.length >= 5;
                if (to.getWorld() != w.location().getWorld()) { p.sendMessage(red(w.name + " is in another world.")); return true; }
                w.teleportTo(to.getBlock());
            }
            case "walk" -> { // <x y z> (CommandCitizenTriggerWalkTo): until within 4 blocks or 3 minutes, then it stands 5 s
                Location to = position(p, a, 2);
                if (to == null) return a.length >= 5;
                if (to.getWorld() != w.location().getWorld()) { p.sendMessage(red(w.name + " is in another world.")); return true; }
                Colonist before = sent.put(p.getUniqueId(), w);
                if (before != null && before != w) before.sentTo = null; // the mod keeps one walk per player
                w.sentTo = to.getBlock().getLocation().add(0.5, 0, 0.5);
                w.sentUntil = to.getWorld().getGameTime() + WALK_TICKS;
                w.stopWalking();
            }
            case "reload" -> { // (CommandCitizenReload): its entities spawned again from its data
                w.despawn();
                p.sendMessage("Reloaded " + who + ".");
            }
            case "modify" -> { // saturation <=|+|-> <value> (CommandCitizenModify; in creative, off for players by default)
                if (a.length != 5 || !a[2].equals("saturation")) return false;
                if (!allowed(p, "modify-citizens")) return true;
                if (p.getGameMode() != GameMode.CREATIVE) { p.sendMessage(red("Only in creative mode.")); return true; }
                double v;
                try { v = Double.parseDouble(a[4]); } catch (NumberFormatException e) { return false; }
                if (v < 0 || v > Food.MAX_SATURATION) return false;
                switch (a[3]) {
                    case "=" -> w.saturation = v;
                    case "+" -> w.saturation = Math.min(Food.MAX_SATURATION, w.saturation + v);
                    case "-" -> w.saturation = Math.max(0, w.saturation - v);
                    default -> { return false; }
                }
                p.sendMessage("Set saturation of " + w.name + " to " + w.saturation);
            }
            case "trackPath" -> { // (CommandCitizenTrack): its route shown to you, again to stop
                if (tracked.get(p.getUniqueId()) == w) { tracked.remove(p.getUniqueId()); p.sendMessage("Path tracking disabled."); }
                else { tracked.put(p.getUniqueId(), w); p.sendMessage("Path tracking enabled for " + who + "."); }
            }
            default -> { return false; }
        }
        return true;
    }

    /** A page of the colony's citizens, each a link to its info, with page links (the mod's 9 a page). */
    private static void list(Player p, Colony c, String owner, int page) {
        int pages = Math.max(1, (c.colonists.size() + CITIZENS_ON_PAGE - 1) / CITIZENS_ON_PAGE);
        if (page < 1 || page > pages) page = 1;
        p.sendMessage(Component.text("Citizens of " + c.name + ", page " + page + " of " + pages, NamedTextColor.GOLD));
        for (int i = (page - 1) * CITIZENS_ON_PAGE; i < Math.min(page * CITIZENS_ON_PAGE, c.colonists.size()); i++) {
            Colonist w = c.colonists.get(i);
            Location at = w.location();
            p.sendMessage(Component.text("#" + (i + 1) + " " + w.name, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/colonies citizens info " + owner + " " + (i + 1)))
                    .append(Component.text(" at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ(), NamedTextColor.GRAY)));
        }
        p.sendMessage(Component.text("<- Prev", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/colonies citizens list " + owner + " " + Math.max(1, page - 1)))
                .append(Component.text(" | ", NamedTextColor.GRAY))
                .append(Component.text("Next ->", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/colonies citizens list " + owner + " " + Math.min(pages, page + 1)))));
    }

    /** One citizen (CommandCitizenInfo): position, health, home, work, job and what it is doing, stuck count, food. */
    private static void info(Player p, Colony c, Colonist w, String who) {
        p.sendMessage(Component.text(who, NamedTextColor.GOLD));
        p.sendMessage(spot("Position", w.location().getBlock()).append(Component.text(w.living() == null ? " (not loaded)" : "")));
        p.sendMessage(String.format("Health: %.1f/%.1f", w.health, c.health.max(w)));
        p.sendMessage(w.home == null ? Component.text("No home") : spot("Home", w.home.block));
        Job job = c.jobOf(w);
        p.sendMessage(job == null ? Component.text("No job") : spot(job.title() + " at", job.hut.block));
        p.sendMessage("Doing: " + w.status());
        p.sendMessage("Stuck: " + w.stats.stuck + " times, " + w.stats.teleports + " teleports");
        p.sendMessage(String.format("Saturation: %.1f/%.0f, last eaten: %s", w.saturation, Food.MAX_SATURATION,
                w.diet.isEmpty() ? "nothing" : String.join(", ", w.diet.stream().map(m -> m.name().toLowerCase().replace('_', ' ')).toList())));
    }

    /** "label: x y z", a click teleports an admin there (the mod's /tp link). */
    private static Component spot(String label, Block b) {
        String xyz = b.getX() + " " + b.getY() + " " + b.getZ();
        return Component.text(label + ": " + xyz).clickEvent(ClickEvent.runCommand("/tp " + xyz));
    }

    /** trackPath and trackPathType: every half second, the routes tracked citizens still have to walk, in particles only that player sees. */
    private void showPaths() {
        if (tracked.isEmpty() && trackedJobs.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<Colonist> show = new ArrayList<>();
            Colonist one = tracked.get(p.getUniqueId());
            if (one != null) show.add(one);
            String job = trackedJobs.get(p.getUniqueId());
            if (job != null) for (Colony c : plugin.allColonies()) for (Colonist w : c.colonists) {
                Job j = c.jobOf(w);
                if (j != null && j.title().toLowerCase().contains(job)) show.add(w);
            }
            for (Colonist w : show) for (Location at : w.path())
                if (at.getWorld() == p.getWorld() && at.distanceSquared(p.getLocation()) < 64 * 64)
                    p.spawnParticle(Particle.HAPPY_VILLAGER, at.add(0, 0.2, 0), 1, 0, 0, 0, 0);
        }
    }

    // ---- suggestions ---------------------------------------------------------------------------------------------

    /** Suggestions as /colonies is typed: only what the player may use (admins also get hut items and the operator commands). */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        boolean admin = sender.hasPermission(ADMIN);
        List<String> options = new ArrayList<>();
        List<String> owners = plugin.allColonies().stream().map(c -> Bukkit.getOfflinePlayer(c.owner).getName()).filter(java.util.Objects::nonNull).toList();
        List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        String first = args[0];
        switch (args.length) {
            case 1 -> {
                options.addAll(List.of("buildtool", "members", "member", "build", "movement", "colony", "citizens", "forceunloadchunks"));
                if (admin) options.addAll(List.of("give", "resetsupplies"));
            }
            case 2 -> {
                switch (first) {
                    case "member" -> options.addAll(List.of("add", "remove"));
                    case "give" -> { if (admin) { options.addAll(Hut.Type.commands()); options.add("rack"); } }
                    case "resetsupplies" -> { if (admin) options.addAll(online); }
                    case "colony" -> options.addAll(COLONY);
                    case "citizens" -> options.addAll(CITIZENS);
                    case "build" -> {
                        options.add("cancel");
                        if (admin && !args[1].contains("/")) Colony.styles().forEach(st -> options.add(st + "/")); // styles first: thousands of structures
                        else if (admin) options.addAll(dev.minecp.build.Catalog.structures());
                    }
                    default -> {}
                }
            }
            case 3 -> {
                if (first.equals("member")) options.addAll(online);
                else if (first.equals("citizens") && args[1].equals("trackPathType")) options.add("clear");
                else if (first.equals("citizens") && args[1].equals("walk")) { options.add("stop"); options.addAll(owners); }
                else if (first.equals("colony") && !args[1].equals("claiminfo") || first.equals("citizens")) options.addAll(owners);
            }
            case 4 -> {
                if (first.equals("member") && args[1].equals("add")) options.addAll(List.of("officer", "friend", "hostile"));
                else if (first.equals("colony") && (args[1].equals("addOfficer") || args[1].equals("setRank"))) options.addAll(online);
                else if (first.equals("colony") && args[1].equals("raid")) options.addAll(List.of("now", "tonight"));
                else if (first.equals("colony") && args[1].equals("claim")) options.addAll(List.of("0", "1", "2", "5", "10"));
            }
            case 5 -> {
                if (first.equals("colony") && args[1].equals("setRank")) options.addAll(RANKS);
                else if (first.equals("colony") && args[1].equals("raid")) options.addAll(dev.minecp.colony.Raids.types());
                else if (first.equals("colony") && args[1].equals("claim")) options.addAll(List.of("true", "false"));
                else if (first.equals("citizens") && args[1].equals("modify")) options.add("saturation");
            }
            case 6 -> { if (first.equals("citizens") && args[1].equals("modify")) options.addAll(List.of("=", "+", "-")); }
            default -> {}
        }
        return org.bukkit.util.StringUtil.copyPartialMatches(args[args.length - 1], options, new ArrayList<>());
    }
}
