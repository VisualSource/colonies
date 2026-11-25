package dev.minecp.colony;

import java.util.HashSet;
import java.util.Set;

/**
 * The chunks a colony claims (the mod's {@code ChunkDataHelper}): a square around the town hall's chunk from the
 * start, plus a square around each hut's chunk by its level and every chunk its building covers, none farther from
 * the town hall's chunk than {@link #maxSize}. Worked out from the huts; only the claim command's chunks (claimed
 * anywhere in its world, or first chunks let go) are saved.
 */
public final class Claims {
    // The mod's server config defaults; MineCP sets them from config.yml.
    public static int initialSize = 4;  // initialColonySize: radius in chunks claimed when the colony is founded
    public static int minDistance = 8;  // minColonyDistance: in chunks, from another colony's town hall
    public static int maxSize = 20;     // maxColonySize: no claim farther from the town hall's chunk, in chunks

    private Claims() {}

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }

    /** The chunks a building's box (minX, maxX+1, minZ, maxZ+1) covers, as {@link #key} values. */
    public static long[] chunks(int[] box) {
        int x0 = box[0] >> 4, x1 = (box[1] - 1) >> 4, z0 = box[2] >> 4, z1 = (box[3] - 1) >> 4;
        long[] out = new long[(x1 - x0 + 1) * (z1 - z0 + 1)];
        int i = 0;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) out[i++] = key(x, z);
        return out;
    }

    /** The chunks claimed when the colony was founded, less those let go by the claim command: never given up while it stands. */
    static Set<Long> core(Colony c) {
        Set<Long> out = new HashSet<>();
        int cx = c.townHall().block.getX() >> 4, cz = c.townHall().block.getZ() >> 4;
        square(out, cx, cz, initialSize, cx, cz);
        out.removeAll(c.claimsRemoved);
        return out;
    }

    /** Whether {@code c} claims this chunk directly (the mod's static claims): a first chunk, or claimed by hand. */
    public static boolean direct(Colony c, long key) {
        return c.claimsAdded.contains(key) || core(c).contains(key);
    }

    /** The huts whose claims (a square by level, or the building's own chunks) reach this chunk (the mod's building claims). */
    public static java.util.List<Hut> claimers(Colony c, long key) {
        java.util.List<Hut> out = new java.util.ArrayList<>();
        int cx = c.townHall().block.getX() >> 4, cz = c.townHall().block.getZ() >> 4, x = x(key), z = z(key);
        if (!near(x, z, cx, cz)) return out;
        for (Hut h : c.huts) {
            int r = c.claimRadius(h), hx = h.block.getX() >> 4, hz = h.block.getZ() >> 4;
            int[] box = c.footprint(h);
            boolean inBox = false;
            if (box != null) for (long k : chunks(box)) inBox |= k == key;
            if (Math.abs(x - hx) <= r && Math.abs(z - hz) <= r || inBox) out.add(h);
        }
        return out;
    }

    /** All the chunks {@code c} claims, as {@link #key} values in its town hall's world. */
    static Set<Long> of(Colony c) {
        Set<Long> out = core(c);
        out.addAll(c.claimsAdded); // ours: anywhere in its world, as the mod's claim command doesn't check the distance
        int cx = c.townHall().block.getX() >> 4, cz = c.townHall().block.getZ() >> 4;
        for (Hut h : c.huts) {
            square(out, h.block.getX() >> 4, h.block.getZ() >> 4, c.claimRadius(h), cx, cz);
            int[] box = c.footprint(h); // the building as built, or null
            if (box != null) for (long k : chunks(box)) if (near(x(k), z(k), cx, cz)) out.add(k);
        }
        return out;
    }

    private static void square(Set<Long> out, int x0, int z0, int r, int cx, int cz) {
        for (int x = x0 - r; x <= x0 + r; x++) for (int z = z0 - r; z <= z0 + r; z++) if (near(x, z, cx, cz)) out.add(key(x, z));
    }

    private static boolean near(int x, int z, int cx, int cz) {
        return (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz) <= (long) maxSize * maxSize;
    }
}
