package dev.minecp.colony;

import java.util.HashSet;
import java.util.Set;

/**
 * The chunks a colony claims (the mod's {@code ChunkDataHelper}): a square around the town hall's chunk from the
 * start, plus a square around each hut's chunk by its level and every chunk its building covers, none farther from
 * the town hall's chunk than {@link #maxSize}. Worked out from the huts, so nothing extra is saved.
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

    /** The chunks {@code c} claims, as {@link #key} values in its town hall's world. */
    static Set<Long> of(Colony c) {
        Set<Long> out = new HashSet<>();
        int cx = c.townHall().block.getX() >> 4, cz = c.townHall().block.getZ() >> 4;
        square(out, cx, cz, initialSize, cx, cz);
        for (Hut h : c.huts) {
            square(out, h.block.getX() >> 4, h.block.getZ() >> 4, h.type.claimRadius(h.level), cx, cz);
            int[] f = c.footprint(h); // minX, maxX+1, minZ, maxZ+1 of the building as built, or null
            if (f != null) for (int x = f[0] >> 4; x <= (f[1] - 1) >> 4; x++) for (int z = f[2] >> 4; z <= (f[3] - 1) >> 4; z++)
                if (near(x, z, cx, cz)) out.add(key(x, z));
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
