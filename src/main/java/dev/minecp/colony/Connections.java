package dev.minecp.colony;

import dev.minecp.citizen.Route;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A colony's connections to others (the mod's ColonyConnectionManager, its numbers): chains of Colony Signs from a
 * gatehouse, each with a way on foot back to the one before it; where two colonies' chains meet, the colonies are
 * connected, and can be allies, neutral or hostile.
 */
public final class Connections {
    public enum Status { ALLIES, NEUTRAL, HOSTILE }

    /** What another colony last did (the mod's ConnectionEventType). */
    public enum Event {
        REQUESTED("asked for an alliance"), CONFIRMED("accepted the alliance"), FEUD("declared a feud"), NEUTRAL("went back to neutral");

        public final String label;

        Event(String label) {
            this.label = label;
        }
    }

    /** A sign: the sign or gatehouse before it, the sign after it (or the other colony's sign or gatehouse), and the colony it leads to. */
    static final class Node {
        final Block at;
        Block prev, next; // null: none
        UUID target;

        Node(Block at) {
            this.at = at;
        }
    }

    /** A new sign, one whose chain is mended, or the last sign towards another colony. */
    enum Kind { NEW, FIX, CONNECT }

    /** A link waiting for its way to be checked; {@code target}: the colony to connect to. */
    record Pending(Block at, Block to, Kind kind, UUID target) {}

    private static final int RANGE = 50;             // the mod's, between signs
    private static final int MAX_STACK = 5;  // the mod's MAX_SIGN_RANGE: fences and signs under a sign
    private static final int MAX_NODES = 20_000; // the mod's, for its way's search

    private final Colony colony;
    final Map<Block, Node> nodes = new LinkedHashMap<>();
    final List<Pending> pending = new ArrayList<>();
    final Map<UUID, Block> direct = new LinkedHashMap<>(); // by owner: the colony's gatehouse, null if that's gone
    private final Map<UUID, Status> statuses = new LinkedHashMap<>();
    private final Map<UUID, Event> events = new LinkedHashMap<>(); // the last of each colony, the newest last
    public Function<UUID, Colony> colonies = id -> null; // by owner; the plugin sets it
    Consumer<String> tell;
    Consumer<Block> breakSign = ColonySign::pop;

    Connections(Colony colony) {
        this.colony = colony;
        this.tell = colony::tell;
    }

    // ---- signs -----------------------------------------------------------------------------------------------------

    private boolean isGate(Block b) {
        Hut h = b == null ? null : colony.hutAt(b);
        return h != null && h.type == Hut.Type.GATE_HOUSE && h.level >= 1;
    }

    /** The gatehouse the chain of signs back from {@code at} starts at, or null where it's broken. */
    private Block gateOf(Block at) {
        Set<Block> seen = new HashSet<>();
        while (nodes.get(at) instanceof Node n && seen.add(at)) at = n.prev;
        return isGate(at) ? at : null;
    }

    private Node nearest(Block to, Predicate<Node> which) {
        Node best = null;
        for (Node n : nodes.values())
            if (which.test(n) && near(n.at, to) && (best == null || distance(n.at, to) < distance(best.at, to))) best = n;
        return best;
    }

    /** The nearest sign that leads back to a gatehouse and on to nowhere yet. The mod takes one cut off from its gatehouse too. */
    private Node nearestEnd(Block to) {
        return nearest(to, n -> n.next == null && gateOf(n.at) != null);
    }

    private static boolean near(Block a, Block b) {
        return distance(a, b) <= RANGE;
    }

    /** Infinite between worlds. */
    public static double distance(Block a, Block b) {
        if (a.getWorld() != b.getWorld()) return Double.POSITIVE_INFINITY;
        double x = a.getX() - b.getX(), y = a.getY() - b.getY(), z = a.getZ() - b.getZ();
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** A sign is being placed. @return why it can't be, or null: its way is checked soon. */
    public String place(Block sign) {
        if (!pending.isEmpty()) return "Not so fast: the last sign isn't connected yet.";
        Block prev = null;
        for (Hut h : colony.huts) if (isGate(h.block) && near(h.block, sign)) { prev = h.block; break; }
        if (prev == null && nearestEnd(sign) instanceof Node n) prev = n.at;
        if (prev == null) return tooFar(sign);
        pending.add(new Pending(sign, prev, Kind.NEW, null));
        return null;
    }

    /** Connects the nearest end to {@code theirs}, another colony's gatehouse or sign. @return why it can't be, or null: its way is checked soon. */
    public String connect(Block theirs, Colony other) {
        if (!pending.isEmpty()) return "Not so fast: the last sign isn't connected yet.";
        Node end = nearestEnd(theirs);
        if (end == null) return tooFar(theirs);
        pending.add(new Pending(end.at, theirs, Kind.CONNECT, other.owner));
        return null;
    }

    private String tooFar(Block from) {
        if (nearest(from, n -> n.next == null) != null) return "The signs don't lead back to a gatehouse: fix that first.";
        if (nearest(from, n -> true) != null) return "The signs near there are connected already.";
        double closest = nodes.keySet().stream().mapToDouble(b -> distance(b, from)).min().orElse(Double.POSITIVE_INFINITY);
        return "Too far from " + colony.name + "'s signs and gatehouses (" + RANGE + " blocks)"
                + (closest < Double.POSITIVE_INFINITY ? ": the closest sign is " + (int) closest + " blocks away." : ".");
    }

    /** Every second: checks a link that waits for it, and the signs. */
    void tick() {
        for (Pending p : pending) if (loaded(p.at, p.to)) { // one search a second
            // shortcut: on the main thread, 70 ms when there is no way; search a few thousand nodes a tick if that is ever felt
            settle(p, Route.connects(lowest(p.at), lowest(p.to), MAX_NODES));
            break;
        }
        for (Node n : List.copyOf(nodes.values())) {
            if (!n.at.getWorld().isChunkLoaded(n.at.getX() >> 4, n.at.getZ() >> 4)) continue;
            if (ColonySign.is(n.at)) ColonySign.write(n.at, text(n));
            else removed(n.at); // gone some way no event told us of
        }
    }

    private static boolean loaded(Block a, Block b) {
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++)
                if (!a.getWorld().isChunkLoaded(x, z)) return false;
        return true;
    }

    /** The bottom of the fences and signs a sign stands on (the mod's findLowestPoint). */
    private static Block lowest(Block b) {
        for (int i = 0; i < MAX_STACK; i++) {
            Block below = b.getRelative(BlockFace.DOWN);
            if (!Tag.FENCES.isTagged(below.getType()) && !ColonySign.is(below)) break;
            b = below;
        }
        return b;
    }

    /** {@code p}'s way was looked for. Ours: any link without one is given up and told; the mod's other kinds wait forever, and block new signs. */
    void settle(Pending p, boolean reached) {
        pending.remove(p);
        if (p.kind == Kind.FIX && nodes.get(p.at) instanceof Node mended && mended.prev != null) return; // another link was first
        Node to = nodes.get(p.to), n = p.kind == Kind.NEW ? new Node(p.at) : nodes.get(p.at);
        if (!reached || n == null || p.kind != Kind.CONNECT && (to == null ? !isGate(p.to) : to.next != null)) { // no way, or an end is gone or taken by now
            tell.accept("Couldn't find a way from " + where(p.at) + " to " + where(p.to) + ".");
            if (p.kind == Kind.NEW) breakSign.accept(p.at);
            return;
        }
        if (p.kind == Kind.CONNECT) { join(n, p); return; }
        nodes.put(p.at, n);
        n.prev = p.to;
        if (to != null) {
            to.next = p.at;
            if (n.target == null) n.target = to.target;
            else lead(p.to, n.target); // all the signs of a closed gap
        }
        tell.accept("Connected the sign at " + where(p.at) + " to " + where(p.to) + ".");
        if (p.kind == Kind.FIX) {
            Colony other = isGate(p.to) && n.target != null ? colonies.apply(n.target) : null;
            if (other != null && other.connections.direct.containsKey(colony.owner)) other.connections.direct.put(colony.owner, p.to); // travel goes here again
        } else if (nearest(p.at, x -> x.prev == null) instanceof Node gap) pending.add(new Pending(gap.at, p.at, Kind.FIX, null));
    }

    /** The last sign reaches the other colony. */
    private void join(Node n, Pending p) {
        Colony other = colonies.apply(p.target);
        Block gate = gateOf(n.at);
        if (other == null) { tell.accept("Couldn't connect: the other colony doesn't exist any more."); return; }
        if (gate == null) { tell.accept("Couldn't connect to " + other.name + ": the signs to " + where(n.at) + " don't lead back to a gatehouse."); return; }
        Connections theirs = other.connections;
        Node end = theirs.nodes.get(p.to);
        Block theirGate = theirs.gateOf(p.to);
        if (theirGate == null || n.next != null || end != null && end.next != null) {
            tell.accept("Couldn't connect to " + other.name + ": the sign is connected already, or doesn't lead to a gatehouse on their side.");
            return;
        }
        direct.put(other.owner, theirGate); // ours: a status they had already stays; the mod goes back to neutral
        theirs.direct.put(colony.owner, gate);
        n.next = p.to;
        if (end != null) end.next = n.at;
        lead(n.at, other.owner);
        theirs.lead(p.to, colony.owner);
        tell.accept("Connected " + colony.name + " to " + other.name + "!");
        theirs.tell.accept("Connected " + other.name + " to " + colony.name + "!");
    }

    /** The chain back from {@code at} now leads to {@code target}. */
    private void lead(Block at, UUID target) {
        Set<Block> seen = new HashSet<>();
        while (nodes.get(at) instanceof Node n && seen.add(at)) {
            n.target = target;
            at = n.prev;
        }
    }

    /** The sign at {@code sign} is gone. The colonies it connected stay connected, as in the mod. */
    public void removed(Block sign) {
        pending.removeIf(p -> p.at.equals(sign));
        Node n = nodes.remove(sign);
        if (n == null) return;
        if (nodes.get(n.prev) instanceof Node prev) {
            prev.next = null;
            disrupted(prev);
        }
        if (nodes.get(n.next) instanceof Node next) {
            next.prev = null;
            disrupted(next);
        } else if (n.next != null && n.target != null && colonies.apply(n.target) instanceof Colony other // ours: the mod leaves their side linked to nothing
                && other.connections.nodes.get(n.next) instanceof Node theirs && sign.equals(theirs.next)) theirs.next = null;
    }

    private void disrupted(Node n) {
        tell.accept("The connection at the sign at " + where(n.at) + " is broken.");
    }

    /** A gatehouse reached level 1: signs near it that lead nowhere back are connected to it, if they can be. */
    void gateBuilt(Block gate) {
        for (Node n : nodes.values()) if (n.prev == null && near(n.at, gate)) pending.add(new Pending(n.at, gate, Kind.FIX, null));
    }

    /** A gatehouse is gone: nobody travels to it any more. */
    void gateRemoved(Block gate) {
        for (Node n : nodes.values()) if (gate.equals(n.prev)) {
            n.prev = null;
            disrupted(n);
        }
        for (UUID id : direct.keySet()) if (colonies.apply(id) instanceof Colony other) {
            other.connections.direct.replace(colony.owner, gate, null);
            for (Node n : other.connections.nodes.values()) if (gate.equals(n.next)) { // ours: their last sign may connect again
                n.next = null;
                other.connections.disrupted(n);
            }
        }
    }

    /** That colony was disbanded. */
    public void forget(UUID other) {
        direct.remove(other);
        statuses.remove(other);
        events.remove(other);
        pending.removeIf(p -> other.equals(p.target));
        for (Node n : nodes.values()) if (other.equals(n.target)) {
            n.target = null;
            if (!nodes.containsKey(n.next)) n.next = null;
        }
    }

    private static String where(Block b) {
        return b.getX() + " " + b.getY() + " " + b.getZ();
    }

    /** Ours: the sign faces as it was placed (the mod's points back along the chain). */
    private List<String> text(Node n) {
        List<String> lines = new ArrayList<>(List.of(colony.name, blocks(toGate(n.at))));
        Colony other = n.target == null ? null : colonies.apply(n.target);
        int far = other == null ? -1 : toTarget(n, other);
        if (far >= 0) lines.addAll(List.of(other.name, blocks(far)));
        return lines;
    }

    private static String blocks(int n) {
        return n == 1 ? "1 block" : n + " blocks";
    }

    /** Blocks along the signs back to the gatehouse. */
    int toGate(Block at) {
        int sum = 0;
        Set<Block> seen = new HashSet<>();
        while (nodes.get(at) instanceof Node n && n.prev != null && seen.add(at)) {
            sum += (int) distance(at, n.prev);
            at = n.prev;
        }
        return sum;
    }

    /** Blocks along the signs on to the other colony's gatehouse, or -1 if they don't reach it. */
    int toTarget(Node n, Colony other) {
        int sum = 0;
        Set<Block> seen = new HashSet<>();
        while (n.next != null && seen.add(n.at)) {
            sum += (int) distance(n.at, n.next);
            Node next = nodes.get(n.next);
            if (next == null) return sum + other.connections.toGate(n.next);
            n = next;
        }
        return -1;
    }

    // ---- diplomacy -------------------------------------------------------------------------------------------------

    /** The colonies whose signs meet ours, and their gatehouse: null if it's gone. */
    public Map<UUID, Block> direct() {
        return direct;
    }

    /** The colonies our allies are connected to, and their allies', and their gatehouses. Ours: as it is now; the mod's list grows until the server restarts, and leads to the ally's gatehouse. */
    public Map<UUID, Block> indirect() {
        Map<UUID, Block> found = new LinkedHashMap<>();
        Deque<UUID> via = new ArrayDeque<>(direct.keySet());
        while (!via.isEmpty()) {
            UUID id = via.poll();
            if (status(id) == Status.ALLIES && colonies.apply(id) instanceof Colony ally) ally.connections.direct.forEach((far, gate) -> {
                if (far.equals(colony.owner) || direct.containsKey(far) || found.containsKey(far)) return;
                found.put(far, gate);
                via.add(far);
            });
        }
        return found;
    }

    /** Both, for travel. */
    public Map<UUID, Block> all() {
        Map<UUID, Block> all = new LinkedHashMap<>(direct);
        all.putAll(indirect());
        return all;
    }

    public Status status(UUID other) {
        return statuses.getOrDefault(other, Status.NEUTRAL);
    }

    /** What other colonies last did, by owner, the newest last. */
    public Map<UUID, Event> events() {
        return events;
    }

    /**
     * This colony asks {@code other} for an alliance, accepts theirs, declares a feud or goes back to neutral; a feud and
     * neutral need no answer. @return why it can't, or null. Ours: an alliance is accepted only if it was asked for, once.
     */
    public String act(Colony other, Event what) {
        Connections theirs = other.connections;
        if (!all().containsKey(other.owner) && !theirs.all().containsKey(colony.owner)) return other.name + " isn't connected to " + colony.name + " any more.";
        Status now = status(other.owner), to = switch (what) {
            case REQUESTED -> now;
            case CONFIRMED -> Status.ALLIES;
            case FEUD -> Status.HOSTILE;
            case NEUTRAL -> Status.NEUTRAL;
        };
        boolean fits = switch (what) {
            case REQUESTED, FEUD -> now == Status.NEUTRAL;
            case CONFIRMED -> events.get(other.owner) == Event.REQUESTED && now != Status.ALLIES;
            case NEUTRAL -> now != Status.NEUTRAL;
        };
        if (!fits) return what == Event.CONFIRMED ? other.name + " isn't asking for an alliance any more." : "That changed while the menu was open.";
        if (what != Event.REQUESTED) events.remove(other.owner, Event.REQUESTED); // answered
        theirs.events.remove(colony.owner); // so it's the newest
        theirs.events.put(colony.owner, what);
        statuses.put(other.owner, to);
        theirs.statuses.put(colony.owner, to);
        return null;
    }

    // ---- saving ----------------------------------------------------------------------------------------------------

    void save(ConfigurationSection s) {
        int i = 0;
        for (Node n : nodes.values()) {
            var ns = s.createSection("signs." + i++);
            put(ns, "at", n.at);
            put(ns, "prev", n.prev);
            put(ns, "next", n.next);
            if (n.target != null) ns.set("target", n.target.toString());
        }
        i = 0;
        for (Pending p : pending) {
            var ps = s.createSection("pending." + i++);
            put(ps, "at", p.at);
            put(ps, "to", p.to);
            ps.set("kind", p.kind.name());
            if (p.target != null) ps.set("target", p.target.toString());
        }
        direct.forEach((id, gate) -> put(s.createSection("direct." + id), "gate", gate));
        statuses.forEach((id, st) -> s.set("status." + id, st.name()));
        events.forEach((id, e) -> s.set("events." + id, e.name()));
    }

    void load(ConfigurationSection s) {
        for (String i : keys(s, "signs")) {
            var ns = s.getConfigurationSection("signs." + i);
            Block at = get(ns, "at");
            if (at == null) continue;
            Node n = new Node(at);
            n.prev = get(ns, "prev");
            n.next = get(ns, "next");
            n.target = uuid(ns.getString("target"));
            nodes.put(at, n);
        }
        for (String i : keys(s, "pending")) {
            var ps = s.getConfigurationSection("pending." + i);
            Block at = get(ps, "at"), to = get(ps, "to");
            if (at != null && to != null) pending.add(new Pending(at, to, Kind.valueOf(ps.getString("kind")), uuid(ps.getString("target"))));
        }
        for (String id : keys(s, "direct")) direct.put(UUID.fromString(id), get(s.getConfigurationSection("direct." + id), "gate"));
        for (String id : keys(s, "status")) statuses.put(UUID.fromString(id), Status.valueOf(s.getString("status." + id)));
        for (String id : keys(s, "events")) events.put(UUID.fromString(id), Event.valueOf(s.getString("events." + id)));
    }

    private static Set<String> keys(ConfigurationSection s, String key) {
        return s.isConfigurationSection(key) ? s.getConfigurationSection(key).getKeys(false) : Set.of();
    }

    private static void put(ConfigurationSection s, String key, Block b) {
        if (b != null) ColonyStore.putLocation(s, key, b.getLocation());
    }

    private static Block get(ConfigurationSection s, String key) {
        Location l = ColonyStore.getLocation(s, key);
        return l == null ? null : l.getBlock();
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
