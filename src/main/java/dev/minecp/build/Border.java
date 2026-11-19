package dev.minecp.build;

import dev.minecp.MineCP;
import dev.minecp.colony.Colony;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * While a player wears Build Goggles, colony borders near them stand as large see-through glass panes, only for
 * them: lime for colonies they belong to, red for others. The panes follow as they walk (the mod shows the border on
 * its own client-side renderer).
 */
public final class Border implements Listener {
    private static final int RANGE = 4;          // chunks around the player whose edges are drawn
    private static final float HEIGHT = 40, BELOW = 16, THICK = 0.05f;
    private static final int REDRAW_DY = 8;      // redraw when the player climbs or drops this far from the panes' level

    /** What a player sees now, so it is redrawn only when they move to another chunk or level, or claims change. */
    private record View(World world, int cx, int cz, int y, int version, Map<String, BlockDisplay> panes) {}

    private final MineCP plugin;
    private final Map<UUID, View> views = new HashMap<>();

    public Border(MineCP plugin) {
        this.plugin = plugin;
    }

    /** Twice a second: draws, moves or removes each player's panes. */
    public void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            View v = views.get(p.getUniqueId());
            if (!plugin.items.isGoggles(p.getInventory().getHelmet())) {
                if (v != null) remove(p.getUniqueId());
                continue;
            }
            Location at = p.getLocation();
            int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4, y = at.getBlockY();
            if (v != null && v.world == at.getWorld() && v.cx == cx && v.cz == cz && Math.abs(v.y - y) < REDRAW_DY
                    && v.version == plugin.claims.version()) continue;
            if (v != null && v.world != at.getWorld()) { remove(p.getUniqueId()); v = null; }
            int level = v != null && Math.abs(v.y - y) < REDRAW_DY ? v.y : y; // same level: panes still standing are kept
            Map<String, BlockDisplay> panes = draw(p, at.getWorld(), cx, cz, level, v == null ? Map.of() : v.panes);
            if (v != null) v.panes.forEach((k, d) -> { if (panes.get(k) != d) d.remove(); }); // edges no longer in view
            views.put(p.getUniqueId(), new View(at.getWorld(), cx, cz, level, plugin.claims.version(), panes));
        }
    }

    /**
     * A pane on every edge between two chunks with different owners, within {@link #RANGE} of the player's chunk;
     * panes already standing ({@code old}) are reused, so walking doesn't make them flicker.
     */
    private Map<String, BlockDisplay> draw(Player p, World w, int pcx, int pcz, int y, Map<String, BlockDisplay> old) {
        Map<String, BlockDisplay> panes = new HashMap<>();
        for (int cx = pcx - RANGE; cx <= pcx + RANGE; cx++) for (int cz = pcz - RANGE; cz <= pcz + RANGE; cz++) {
            Colony here = plugin.claims.colonyAt(w, cx, cz);
            Colony north = plugin.claims.colonyAt(w, cx, cz - 1), west = plugin.claims.colonyAt(w, cx - 1, cz);
            if (here != north) pane(panes, old, p, w, cx << 4, y - BELOW, cz << 4, true, member(p, here) || member(p, north));
            if (here != west) pane(panes, old, p, w, cx << 4, y - BELOW, cz << 4, false, member(p, here) || member(p, west));
            // the far edges of the drawn area
            if (cz == pcz + RANGE && here != plugin.claims.colonyAt(w, cx, cz + 1))
                pane(panes, old, p, w, cx << 4, y - BELOW, (cz + 1) << 4, true, member(p, here) || member(p, plugin.claims.colonyAt(w, cx, cz + 1)));
            if (cx == pcx + RANGE && here != plugin.claims.colonyAt(w, cx + 1, cz))
                pane(panes, old, p, w, (cx + 1) << 4, y - BELOW, cz << 4, false, member(p, here) || member(p, plugin.claims.colonyAt(w, cx + 1, cz)));
        }
        return panes;
    }

    private static boolean member(Player p, Colony c) {
        return c != null && c.permissions.isMember(p.getUniqueId());
    }

    /** One 16-block-wide pane starting at (x, z): along x ({@code alongX}) or along z; reused from {@code old} if there. */
    private void pane(Map<String, BlockDisplay> panes, Map<String, BlockDisplay> old, Player p, World w, int x, float y, int z, boolean alongX, boolean own) {
        String key = x + "," + z + "," + y + "," + alongX + "," + own;
        BlockDisplay had = old.get(key);
        if (had != null && had.isValid()) { panes.put(key, had); return; }
        BlockData glass = (own ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS).createBlockData();
        Vector3f scale = alongX ? new Vector3f(16, HEIGHT, THICK) : new Vector3f(THICK, HEIGHT, 16);
        Vector3f shift = alongX ? new Vector3f(0, 0, -THICK / 2) : new Vector3f(-THICK / 2, 0, 0);
        BlockDisplay d = w.spawn(new Location(w, x, y, z), BlockDisplay.class, e -> {
            e.setBlock(glass);
            e.setTransformation(new Transformation(shift, new AxisAngle4f(), scale, new AxisAngle4f()));
            e.setBrightness(new Display.Brightness(15, 15));
            // The client skips a display whose box is off screen: give it the pane's size, or it vanishes when its corner does.
            e.setDisplayWidth(34);
            e.setDisplayHeight(HEIGHT);
            e.setViewRange(2); // about 128 blocks
            e.setVisibleByDefault(false);
            e.setPersistent(false);
        });
        p.showEntity(plugin, d);
        panes.put(key, d);
    }

    private void remove(UUID player) {
        View v = views.remove(player);
        if (v != null) v.panes.values().forEach(org.bukkit.entity.Entity::remove);
    }

    public void endAll() {
        for (UUID id : List.copyOf(views.keySet())) remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
    }
}
