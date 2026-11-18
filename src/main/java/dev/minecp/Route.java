package dev.minecp;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Our own route search for citizens (new movement), written from the mod's pathfinding rules
 * (docs/research/citizen-movement-system.md): A* over block positions with ladders, drops, doors and wading,
 * on the main thread with live block reads, like vanilla's search.
 */
final class Route {
    /** A place to stand on the route: block position of the feet, the height the feet are at, and how it is used. */
    record Step(int x, int y, int z, double h, boolean ladder, boolean door) {}

    /** What a search found: the steps from start to end (start excluded), and whether the end is the goal. */
    record Result(List<Step> steps, boolean reached) {}

    /** Where a search may end. */
    interface Goal {
        boolean accepts(int x, int y, int z);
    }

    // Costs from the mod's PathingOptions defaults (dropCost is |dy|^3); onPath is 1/2 here, the mod's 1/6 with an
    // adaptive heuristic we don't have.
    private static final double JUMP = 2, TOGGLE = 2, WADE = 4, ON_PATH = 0.5, DIAGONAL = Math.sqrt(2);
    private static final int MAX_DROP = 10;           // the mod scans 2..10 blocks down for a floor
    private static final double MAX_JUMP = 1.25;      // vanilla jump height (the mod allows < 1.3)
    private static final double STEP_UP = 0.6;        // what the legs step up without jumping
    private static final int MAX_NODES = 8000;        // the mod's MAX_NODES
    private static final double HEURISTIC = 2;

    private static final Set<Material> DANGER = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
            Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.CACTUS, Material.WITHER_ROSE, Material.LAVA_CAULDRON);

    /** How a block state can be used, worked out once per state from its collision shape. */
    private record Info(boolean body, double top, boolean floor, boolean partial, boolean danger, boolean climb,
                        boolean door, boolean water, boolean onPath) {}

    private static final Map<BlockData, Info> INFO = new HashMap<>(); // main thread only
    private static final Info UNLOADED = new Info(false, 0, false, false, true, false, false, false, false);
    /** Per search: each position is read from the world once (a search asks about most blocks several times). */
    private static final Map<Long, Info> SEEN = new HashMap<>();

    private static Info info(World w, int x, int y, int z) {
        long k = key(x, y, z);
        Info i = SEEN.get(k);
        if (i != null) return i;
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) i = UNLOADED; // nothing to stand on
        else {
            BlockData d = w.getBlockData(x, y, z);
            i = INFO.get(d);
            if (i == null) INFO.put(d, i = classify(d, w, x, y, z));
        }
        SEEN.put(k, i);
        return i;
    }

    private static Info classify(BlockData d, World w, int x, int y, int z) {
        Material m = d.getMaterial();
        boolean danger = DANGER.contains(m), climb = Tag.CLIMBABLE.isTagged(m) && m != Material.SCAFFOLDING;
        boolean onPath = m == Material.DIRT_PATH || Tag.PLANKS.isTagged(m) || Tag.WOODEN_SLABS.isTagged(m)
                || Tag.WOOL_CARPETS.isTagged(m) || m.name().endsWith("_CONCRETE") || m.name().contains("STONE_BRICK")
                || m.name().startsWith("POLISHED_") || m == Material.GRAVEL;
        if (m == Material.WATER) return new Info(true, 0, false, false, false, false, false, true, false);
        double top = 0, minX = 1, maxX = 0, minZ = 1, maxZ = 0;
        for (BoundingBox b : d.getCollisionShape(new org.bukkit.Location(w, x, y, z)).getBoundingBoxes()) {
            top = Math.max(top, b.getMaxY());
            minX = Math.min(minX, b.getMinX()); maxX = Math.max(maxX, b.getMaxX());
            minZ = Math.min(minZ, b.getMinZ()); maxZ = Math.max(maxZ, b.getMaxZ());
        }
        boolean wide = maxX - minX >= 0.85 && maxZ - minZ >= 0.85;
        // Doors, gates and trapdoors: open ones are walked through; closed wooden doors too (Colonist opens them).
        if (d instanceof Door door) {
            boolean wooden = Tag.MOB_INTERACTABLE_DOORS.isTagged(m);
            return new Info(door.isOpen() || wooden, 0, false, false, false, false, !door.isOpen(), false, false);
        }
        if (d instanceof Gate gate) return new Info(gate.isOpen(), 0, false, false, false, false, false, false, false);
        if (d instanceof TrapDoor t && t.isOpen()) return new Info(true, 0, false, false, false, false, false, false, false);
        if (climb) return new Info(true, 0, false, false, danger, true, false, false, false);
        boolean empty = top == 0;
        boolean fenceLike = top > 1.0;                                  // fences and walls: never stood on or walked through
        boolean partial = !empty && top <= 0.5 && wide && !fenceLike;   // slabs, carpets, snow: stood on inside their block
        boolean floor = !empty && top > 0.5 && !fenceLike && (wide || d instanceof Openable);
        return new Info(empty, top, floor, partial, danger, false, false, false, onPath);
    }

    /** Height the feet are at when standing at (x,y,z), or NaN if one can't stand there. */
    private static double stand(World w, int x, int y, int z) {
        Info feet = info(w, x, y, z), head = info(w, x, y + 1, z);
        if (feet.danger || head.danger || head.water) return Double.NaN;
        if (feet.climb) return head.body || head.climb ? y : Double.NaN;  // on a ladder
        if (!head.body) return Double.NaN;
        if (feet.body) {
            Info below = info(w, x, y - 1, z);
            return below.floor && !below.danger ? y - 1 + below.top : Double.NaN;
        }
        if (feet.partial && info(w, x, y + 2, z).body) return y + feet.top; // 1.95 tall: a slab raises the head into y+2
        return Double.NaN;
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final double h;
        final boolean ladder;
        Node parent;
        double g, f;

        Node(int x, int y, int z, double h, boolean ladder) {
            this.x = x; this.y = y; this.z = z; this.h = h; this.ladder = ladder;
        }

        @Override public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    /**
     * Searches from the stand spot at or next to (sx,sy,sz) toward (gx,gy,gz) until {@code goal} accepts a node.
     * @return null if the start can't be stood on; else the route to the goal, or (reached false) to the node closest to it
     */
    static Result find(World w, int sx, int sy, int sz, int gx, int gy, int gz, Goal goal) {
        try {
            return search(w, sx, sy, sz, gx, gy, gz, goal);
        } finally {
            SEEN.clear(); // blocks change between searches
        }
    }

    private static Result search(World w, int sx, int sy, int sz, int gx, int gy, int gz, Goal goal) {
        Node start = null;
        for (int dy : new int[]{0, 1, -1}) {
            double h = stand(w, sx, sy + dy, sz);
            if (!Double.isNaN(h)) { start = new Node(sx, sy + dy, sz, h, info(w, sx, sy + dy, sz).climb); break; }
        }
        if (start == null) return null;
        int d = Math.abs(gx - sx) + Math.abs(gy - sy) + Math.abs(gz - sz);
        double r = Math.min(20, 3 + d / 20.0);
        int maxNodes = (int) Math.min(MAX_NODES, 300 + 16.0 * d + 2.0 * d * r * r); // the mod's node limit for a distance
        Map<Long, Node> seen = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        start.f = heuristic(start, gx, gy, gz);
        seen.put(key(start.x, start.y, start.z), start);
        open.add(start);
        Node best = start, found = null;
        double bestH = start.f;
        int visited = 0;
        while (!open.isEmpty() && visited++ < maxNodes) {
            Node n = open.poll();
            if (seen.get(key(n.x, n.y, n.z)) != n) { visited--; continue; } // superseded by a cheaper way here
            if (goal.accepts(n.x, n.y, n.z) && !n.ladder) { found = n; break; } // never end hanging on a ladder
            double hn = heuristic(n, gx, gy, gz);
            if (hn < bestH && !n.ladder) { bestH = hn; best = n; }
            for (Node next : neighbours(w, n)) {
                long k = key(next.x, next.y, next.z);
                Node old = seen.get(k);
                if (old != null && old.g <= next.g) continue;
                next.parent = n;
                next.f = next.g + heuristic(next, gx, gy, gz);
                seen.put(k, next);
                open.add(next);
            }
        }
        Node end = found != null ? found : best;
        List<Step> steps = new ArrayList<>();
        for (Node n = end; n != null && n != start; n = n.parent) {
            Info feet = info(w, n.x, n.y, n.z), head = info(w, n.x, n.y + 1, n.z);
            steps.addFirst(new Step(n.x, n.y, n.z, n.h, n.ladder, feet.door || head.door));
        }
        return new Result(steps, found != null);
    }

    /** Distance left, weighted like the mod's starting heuristicMod of 2: far fewer nodes, routes a little less short. */
    private static double heuristic(Node n, int gx, int gy, int gz) {
        int dx = Math.abs(n.x - gx), dz = Math.abs(n.z - gz);
        return HEURISTIC * (Math.max(dx, dz) + (DIAGONAL - 1) * Math.min(dx, dz) + Math.abs(n.y - gy));
    }

    private static List<Node> neighbours(World w, Node n) {
        List<Node> out = new ArrayList<>(8);
        double here = moveCost(w, n);
        if (n.ladder) { // up and down the ladder
            for (int dy : new int[]{1, -1}) {
                int y = n.y + dy;
                if (info(w, n.x, y, n.z).climb && !Double.isNaN(stand(w, n.x, y, n.z))) add(out, n, n.x, y, n.z, y, true, 1);
                else if (dy < 0) { double h = stand(w, n.x, y, n.z); if (!Double.isNaN(h)) add(out, n, n.x, y, n.z, h, false, 1); }
            }
        }
        boolean[] clear = new boolean[4];
        int[][] sides = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            int x = n.x + sides[i][0], z = n.z + sides[i][1];
            clear[i] = info(w, x, n.y, z).body && info(w, x, n.y + 1, z).body;
            double h = stand(w, x, n.y, z); // level
            if (!Double.isNaN(h) && h - n.h <= MAX_JUMP && (h - n.h <= STEP_UP || roomToJump(w, n))) {
                add(out, n, x, n.y, z, h, info(w, x, n.y, z).climb, here + (h - n.h > STEP_UP ? JUMP : 0) + toggle(w, x, n.y, z));
                continue;
            }
            h = stand(w, x, n.y + 1, z); // up a block
            if (!Double.isNaN(h) && h - n.h <= MAX_JUMP && roomToJump(w, n)) {
                add(out, n, x, n.y + 1, z, h, info(w, x, n.y + 1, z).climb, here + (h - n.h > STEP_UP ? JUMP : 0) + toggle(w, x, n.y + 1, z));
                continue;
            }
            if (!clear[i] || n.ladder) continue; // the mod doesn't drop from ladders
            for (int k = 1; k <= MAX_DROP; k++) { // down: the column must be open all the way
                h = stand(w, x, n.y - k, z);
                if (!Double.isNaN(h)) { add(out, n, x, n.y - k, z, h, info(w, x, n.y - k, z).climb, here + k * k * k); break; }
                if (!info(w, x, n.y - k, z).body) break;
            }
        }
        for (int i = 0; i < 4; i++) { // diagonals on the level, where both sides are open (no cutting corners)
            int j = (i + 1) % 4;
            if (!clear[i] || !clear[j] || n.ladder) continue;
            int x = n.x + sides[i][0] + sides[j][0], z = n.z + sides[i][1] + sides[j][1];
            double h = stand(w, x, n.y, z);
            if (!Double.isNaN(h) && Math.abs(h - n.h) <= STEP_UP && !info(w, x, n.y, z).climb && !info(w, x, n.y, z).door)
                add(out, n, x, n.y, z, h, false, here * DIAGONAL);
        }
        return out;
    }

    private static void add(List<Node> out, Node from, int x, int y, int z, double h, boolean ladder, double cost) {
        Node n = new Node(x, y, z, h, ladder);
        n.g = from.g + cost;
        out.add(n);
    }

    /** Base cost of a step from here: cheaper on path blocks, dearer wading. */
    private static double moveCost(World w, Node n) {
        if (info(w, n.x, n.y, n.z).water) return WADE;
        Info floor = info(w, n.x, n.h > n.y ? n.y : n.y - 1, n.z);
        return floor.onPath ? ON_PATH : 1;
    }

    private static double toggle(World w, int x, int y, int z) {
        return info(w, x, y, z).door || info(w, x, y + 1, z).door ? TOGGLE : 0;
    }

    /** Head room to jump: the block above the head must be open. */
    private static boolean roomToJump(World w, Node n) {
        Info above = info(w, n.x, n.y + 2, n.z);
        return above.body || above.climb;
    }

    /** Whether one can still stand at a step (blocks may have changed since the search). */
    static boolean standable(World w, Step s) {
        return !Double.isNaN(stand(w, s.x(), s.y(), s.z()));
    }
}
