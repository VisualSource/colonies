package dev.minecp;

import dev.minecp.colony.Claims;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Rank;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeping colonies loaded, the mod's numbers (Colony.updateChunkLoadTimer, its forceloadcolony, loadtime and
 * colonyloadstrictness settings, config.yml's chunk-loading): while an owner or officer is on a colony's land its
 * loaded chunks are kept loaded, and for so many minutes after they leave, the time running out three times as fast
 * with neither online. Only chunks enough of its huts claim count. Not kept over restarts, as the mod's.
 */
final class ChunkLoading {
    static final int PERIOD = 500; // the mod's MAX_TICKRATE: how often it checks

    boolean enabled = true;
    int minutes = 20, strictness = 3;
    private final MineCP plugin;
    private final Map<Colony, Integer> timers = new HashMap<>();
    private final Map<Colony, Set<Long>> ticketed = new HashMap<>();

    ChunkLoading(MineCP plugin) {
        this.plugin = plugin;
    }

    void tick() {
        if (!enabled) return;
        for (Colony c : plugin.allColonies()) {
            boolean here = false, online = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                Rank r = c.permissions.rank(p.getUniqueId());
                if (r != Rank.OWNER && r != Rank.OFFICER) continue;
                online = true;
                here |= plugin.claims.colonyAt(p.getLocation()) == c;
            }
            int left = here ? minutes * 20 * 60 : timers.getOrDefault(c, 0) - (online ? PERIOD : 3 * PERIOD);
            if (left > 0) {
                timers.put(c, left);
                keep(c);
            } else {
                timers.remove(c);
                release(c);
            }
        }
        for (Iterator<Colony> it = ticketed.keySet().iterator(); it.hasNext(); ) { // colonies disbanded meanwhile
            Colony c = it.next();
            if (plugin.colonyOwnedBy(c.owner) == c) continue;
            World w = c.townHall().block.getWorld();
            for (long k : ticketed.get(c)) w.removePluginChunkTicket(Claims.x(k), Claims.z(k), plugin);
            timers.remove(c);
            it.remove();
        }
    }

    /** Its claimed chunks that are loaded and that at least {@link #strictness} of its huts claim stay loaded (the mod's keepChunkColonyLoaded). */
    private void keep(Colony c) {
        World w = c.townHall().block.getWorld();
        Set<Long> held = ticketed.computeIfAbsent(c, k -> new HashSet<>());
        for (long k : c.claims()) {
            int x = Claims.x(k), z = Claims.z(k);
            if (held.contains(k) || !w.isChunkLoaded(x, z) || Claims.claimers(c, k).size() < strictness) continue;
            w.addPluginChunkTicket(x, z, plugin);
            held.add(k);
        }
    }

    private void release(Colony c) {
        Set<Long> held = ticketed.remove(c);
        if (held == null) return;
        World w = c.townHall().block.getWorld();
        for (long k : held) w.removePluginChunkTicket(Claims.x(k), Claims.z(k), plugin);
    }
}
