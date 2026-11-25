package dev.minecp.build;

import dev.minecp.MineCP;
import dev.minecp.colony.Colony;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

/**
 * Colony Maps: two vanilla maps per colony, near and far, centred on its town hall, with the borders drawn on it: each colony's outline
 * just inside its land, lime for colonies the viewer belongs to, red for others, as the Build Goggles' (ours: the mod's
 * Colony Map is a screen of its own).
 */
public final class MapBorders extends MapRenderer implements Listener {
    private static final Color OWN = new Color(127, 204, 25), OTHER = new Color(255, 0, 0);
    private static final int SIZE = 128;
    private static final MapView.Scale[] SCALES = {MapView.Scale.NORMAL, MapView.Scale.FAR}; // level 2 and 3: the mod's largest colony only fits the far one

    private final MineCP plugin;
    private final Map<MapCanvas, Integer> drawn = new WeakHashMap<>(); // what each canvas (one per player and map) shows: state()

    public MapBorders(MineCP plugin) {
        super(true); // drawn for each player
        this.plugin = plugin;
    }

    /** Each time the server loads or makes a map: renderers aren't saved. */
    @EventHandler
    public void onMap(MapInitializeEvent e) {
        for (Colony c : plugin.allColonies()) for (int i = 0; i < SCALES.length; i++) if (isMap(c, i, e.getMap())) e.getMap().addRenderer(this);
    }

    /** The map to turn {@code held} into on a hut of {@code c}: the near one, or the far one if it's the near one already. */
    public MapView next(Colony c, ItemStack held) {
        MapView now = held.getItemMeta() instanceof MapMeta m && m.hasMapView() ? m.getMapView() : null;
        return mapOf(c, now != null && now.getId() == c.mapIds[0] ? 1 : 0);
    }

    /** Blocks a map shows from side to side. */
    public static int across(MapView map) {
        return SIZE << map.getScale().ordinal();
    }

    /** Not by its number alone: after a crash the server can give a number the colony saved to another map. */
    private static boolean isMap(Colony c, int which, MapView map) {
        Block hall = c.townHall().block;
        return map.getId() == c.mapIds[which] && map.getScale() == SCALES[which] && hall.getWorld().equals(map.getWorld())
                && map.getCenterX() == hall.getX() && map.getCenterZ() == hall.getZ();
    }

    /** The colony's near (0) or far (1) map, made the first time it's asked for. */
    private MapView mapOf(Colony c, int which) {
        MapView map = c.mapIds[which] < 0 ? null : Bukkit.getMap(c.mapIds[which]);
        if (map != null && isMap(c, which, map)) return map;
        Block hall = c.townHall().block;
        map = Bukkit.createMap(hall.getWorld());
        map.setScale(SCALES[which]);
        map.setCenterX(hall.getX());
        map.setCenterZ(hall.getZ());
        map.setTrackingPosition(true);
        map.addRenderer(this);
        c.mapIds[which] = map.getId();
        return map;
    }

    /** Called several times a second while a player has the map, so it draws only what changed the borders or their colours. */
    @Override
    public void render(MapView map, MapCanvas canvas, Player p) {
        World world = map.getWorld();
        if (world == null) return;
        int state = state(p);
        Integer had = drawn.put(canvas, state);
        if (had != null && had == state) return;
        int step = 1 << map.getScale().ordinal(); // blocks to a pixel
        int left = map.getCenterX() - SIZE / 2 * step, top = map.getCenterZ() - SIZE / 2 * step;
        int cx = (left >> 4) - 1, cz = (top >> 4) - 1; // the map's chunks and one more all round, each looked up once
        Colony[][] owners = new Colony[SIZE * step / 16 + 3][SIZE * step / 16 + 3];
        for (int x = 0; x < owners.length; x++) for (int z = 0; z < owners.length; z++) owners[x][z] = plugin.claims.colonyAt(world, cx + x, cz + z);
        for (int px = 0; px < SIZE; px++) for (int pz = 0; pz < SIZE; pz++) {
            Colony c = outlined((x, z) -> owners[(x >> 4) - cx][(z >> 4) - cz], left + px * step, top + pz * step, step);
            canvas.setPixelColor(px, pz, c == null ? null : c.permissions.isMember(p.getUniqueId()) ? OWN : OTHER);
        }
    }

    /** Whose outline the pixel at this block is part of: the owner there, if it isn't the owner of all four pixels around. */
    static <T> T outlined(BiFunction<Integer, Integer, T> owner, int x, int z, int step) {
        T here = owner.apply(x, z);
        return here != null && (owner.apply(x + step, z) != here || owner.apply(x - step, z) != here
                || owner.apply(x, z + step) != here || owner.apply(x, z - step) != here) ? here : null;
    }

    /** Changes with the claims and with the colonies {@code p} belongs to. */
    private int state(Player p) {
        int state = plugin.claims.version();
        for (Colony c : plugin.allColonies()) state = 31 * state + (c.permissions.isMember(p.getUniqueId()) ? 1 : 0);
        return state;
    }
}
