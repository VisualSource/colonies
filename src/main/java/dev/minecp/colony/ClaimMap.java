package dev.minecp.colony;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Which colony owns each claimed chunk, from every colony's {@link Colony#claims()}. */
public final class ClaimMap {
    private Map<UUID, Map<Long, Colony>> owners = new HashMap<>(); // by world, then chunk (Claims.key)
    private int version; // goes up on every update (Border redraws)

    /**
     * Works the owners out again after any colony's claims changed. As in the mod, a chunk stays with the colony that
     * claimed it first: a colony's first chunks are always its own (no colony can be founded where they would overlap),
     * chunks a colony already held stay its own, and only new claims are shared out, the older colony first.
     */
    public void update(Collection<Colony> colonies) {
        version++;
        Map<UUID, Map<Long, Colony>> old = owners;
        owners = new HashMap<>();
        for (Colony c : colonies) for (long k : Claims.core(c)) chunks(c).put(k, c);
        for (Colony c : colonies) {
            var held = old.getOrDefault(world(c).getUID(), Map.of());
            for (long k : c.claims()) if (held.get(k) == c) chunks(c).putIfAbsent(k, c);
        }
        // shortcut: after a restart nothing was held yet, so contested building claims go by founding date; save the owners if that matters
        colonies.stream().sorted(Comparator.comparingLong(c -> c.founded))
                .forEach(c -> { for (long k : c.claims()) chunks(c).putIfAbsent(k, c); });
    }

    private Map<Long, Colony> chunks(Colony c) {
        return owners.computeIfAbsent(world(c).getUID(), w -> new HashMap<>());
    }

    private static World world(Colony c) {
        return c.townHall().block.getWorld();
    }

    public int version() {
        return version;
    }

    /** The colony owning this chunk, or null. */
    public Colony colonyAt(World world, int chunkX, int chunkZ) {
        var chunks = owners.get(world.getUID());
        return chunks == null ? null : chunks.get(Claims.key(chunkX, chunkZ));
    }

    /** The colony whose land {@code at} is on, or null. */
    public Colony colonyAt(Location at) {
        return colonyAt(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
    }

    /** Whether every chunk under a building's box (minX, maxX+1, minZ, maxZ+1) is {@code c}'s. */
    public boolean ownsAll(Colony c, World world, int[] box) {
        for (long k : Claims.chunks(box)) if (colonyAt(world, Claims.x(k), Claims.z(k)) != c) return false;
        return true;
    }

    /** Why a new colony can't be founded at {@code at}: too near another town hall, or its first chunks are claimed. */
    public String foundingProblem(Block at, Collection<Colony> colonies) {
        int range = Math.max(Claims.minDistance, Claims.initialSize) * 16; // the mod's isFarEnoughFromColonies
        for (Colony c : colonies) {
            Block th = c.townHall().block;
            if (th.getWorld().equals(at.getWorld()) && th.getLocation().distance(at.getLocation()) < range)
                return "Too close to " + c.name + ": town halls must be at least " + range + " blocks apart.";
        }
        int cx = at.getX() >> 4, cz = at.getZ() >> 4, r = Claims.initialSize;
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            Colony c = colonyAt(at.getWorld(), x, z);
            if (c != null) return "A colony here would claim land " + c.name + " already claims.";
        }
        return null;
    }
}
