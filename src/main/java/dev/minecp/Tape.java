package dev.minecp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Stands in for the mod's construction tape: four thin yellow bars around a building's footprint. Display entities
 * stay put without being resent, unlike particles, and four per building cost next to nothing.
 */
final class Tape {
    private static final float THICK = 0.1f;

    private Tape() {}

    /**
     * Bars along the edges of {@code f} (minX, maxX+1, minZ, maxZ+1) at height {@code y}, not saved with the world.
     * @param visibleToAll false: hidden until shown to a player (a preview)
     */
    static List<BlockDisplay> around(World w, int[] f, double y, boolean visibleToAll) {
        float dx = f[1] - f[0], dz = f[3] - f[2], half = THICK / 2;
        return List.of(
                bar(w, new Location(w, f[0], y, f[2]), new Vector3f(0, 0, -half), new Vector3f(dx, THICK, THICK), visibleToAll),
                bar(w, new Location(w, f[0], y, f[3]), new Vector3f(0, 0, -half), new Vector3f(dx, THICK, THICK), visibleToAll),
                bar(w, new Location(w, f[0], y, f[2]), new Vector3f(-half, 0, 0), new Vector3f(THICK, THICK, dz), visibleToAll),
                bar(w, new Location(w, f[1], y, f[2]), new Vector3f(-half, 0, 0), new Vector3f(THICK, THICK, dz), visibleToAll));
    }

    private static BlockDisplay bar(World w, Location at, Vector3f offset, Vector3f size, boolean visibleToAll) {
        return w.spawn(at, BlockDisplay.class, d -> {
            d.setBlock(Material.YELLOW_CONCRETE.createBlockData());
            d.setTransformation(new Transformation(offset, new AxisAngle4f(), size, new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setVisibleByDefault(visibleToAll);
            d.setPersistent(false);
        });
    }
}
