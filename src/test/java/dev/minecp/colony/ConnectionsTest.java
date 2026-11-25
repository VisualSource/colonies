package dev.minecp.colony;

import dev.minecp.colony.Connections.Event;
import dev.minecp.colony.Connections.Status;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Colony Signs and diplomacy. Runs without a server, with stand-in blocks along a line. */
class ConnectionsTest {
    private final Map<UUID, Colony> all = new HashMap<>();
    private final List<Block> broken = new ArrayList<>();
    private final Map<Integer, Block> blocks = new HashMap<>();

    private Block at(int x) {
        return blocks.computeIfAbsent(x, k -> (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode", "getX" -> x;
            case "getY", "getZ" -> 0;
            case "getWorld" -> null;
            case "toString" -> "block " + x;
            default -> throw new UnsupportedOperationException(method.getName());
        }));
    }

    private Colony colony(String name, int gate) {
        Colony c = new Colony(UUID.randomUUID(), name, at(gate - 5), 1);
        c.huts.add(new Hut(Hut.Type.GATE_HOUSE, at(gate), 1));
        c.connections.colonies = all::get;
        c.connections.tell = s -> {};
        c.connections.breakSign = broken::add;
        all.put(c.owner, c);
        return c;
    }

    private final Colony a = colony("A", 0), b = colony("B", 200), far = colony("C", 1000);

    /** Places a sign and finds its way, and any gap's it closes. */
    private void place(Colony c, int x) {
        assertNull(c.connections.place(at(x)));
        while (!c.connections.pending.isEmpty()) c.connections.settle(c.connections.pending.getFirst(), true);
    }

    private void connect() {
        for (int x : new int[]{40, 80, 120}) place(a, x);
        place(b, 160);
        assertNull(a.connections.connect(at(160), b));
        a.connections.settle(a.connections.pending.getFirst(), true);
    }

    @Test
    void signsChainBackToTheGatehouse() {
        assertNotNull(a.connections.place(at(60)), "too far from the gatehouse");
        place(a, 40);
        assertNotNull(a.connections.place(at(100)), "too far from the sign");
        assertNull(a.connections.place(at(80)));
        assertNotNull(a.connections.place(at(90)), "one at a time");
        a.connections.settle(a.connections.pending.getFirst(), false);
        assertEquals(List.of(at(80)), broken, "no way: it breaks");
        place(a, 80);
        assertSame(at(40), a.connections.nodes.get(at(80)).prev);
        assertSame(at(80), a.connections.nodes.get(at(40)).next);
        assertEquals(80, a.connections.toGate(at(80)));
    }

    @Test
    void chainsMeet() {
        connect();
        assertSame(at(200), a.connections.direct().get(b.owner));
        assertSame(at(0), b.connections.direct().get(a.owner));
        assertSame(at(120), b.connections.nodes.get(at(160)).next);
        for (int x : new int[]{40, 80, 120}) assertEquals(b.owner, a.connections.nodes.get(at(x)).target);
        assertEquals(160, a.connections.toTarget(a.connections.nodes.get(at(40)), b), "from there to B's gatehouse");
        assertNotNull(a.connections.connect(at(200), b), "no open end any more");
    }

    @Test
    void aChainThatLeadsNowhereDoesntConnect() {
        for (int x : new int[]{40, 80, 120}) place(a, x);
        assertNull(a.connections.connect(at(160), b)); // not one of B's signs
        a.connections.settle(a.connections.pending.getFirst(), true);
        assertTrue(a.connections.direct().isEmpty());
        assertTrue(a.connections.pending.isEmpty());
        assertNull(a.connections.connect(at(160), b), "a failure blocks nothing");
        a.connections.settle(a.connections.pending.getFirst(), false);
        assertTrue(a.connections.pending.isEmpty());
    }

    @Test
    void aGapIsClosed() {
        connect();
        a.connections.removed(at(80));
        assertNull(a.connections.nodes.get(at(40)).next);
        assertNull(a.connections.nodes.get(at(120)).prev);
        assertEquals(-1, a.connections.toTarget(a.connections.nodes.get(at(40)), b));
        assertTrue(a.connections.direct().containsKey(b.owner), "still connected, as in the mod");
        place(a, 82);
        assertSame(at(82), a.connections.nodes.get(at(120)).prev);
        assertEquals(160, a.connections.toTarget(a.connections.nodes.get(at(40)), b));

        a.connections.removed(at(40));
        a.connections.removed(at(82));
        place(a, 42);
        place(a, 84);
        for (int x : new int[]{42, 84, 120}) assertEquals(b.owner, a.connections.nodes.get(at(x)).target, "sign " + x + " of a gap of two");
        assertNotNull(a.connections.connect(at(160), b));
        assertTrue(a.connections.connect(at(160), b).contains("connected already"));

        a.connections.removed(at(120));
        assertNull(b.connections.nodes.get(at(160)).next, "B's end is open again");
    }

    @Test
    void gatehousesComeAndGo() {
        connect();
        Hut gate = b.hutAt(at(200));
        b.huts.remove(gate);
        b.connections.gateRemoved(at(200));
        assertNull(b.connections.nodes.get(at(160)).prev);
        assertTrue(a.connections.direct().containsKey(b.owner));
        assertNull(a.connections.direct().get(b.owner), "nowhere to travel to");
        assertNotNull(b.connections.place(at(170)), "the chain leads to no gatehouse");

        b.huts.add(new Hut(Hut.Type.GATE_HOUSE, at(190), 1));
        b.connections.gateBuilt(at(190));
        b.connections.settle(b.connections.pending.getFirst(), true);
        assertSame(at(190), b.connections.nodes.get(at(160)).prev);
        assertSame(at(190), a.connections.direct().get(b.owner));
    }

    @Test
    void aRebuiltGatehouseCanBeConnectedAgain() {
        for (int x : new int[]{40, 80, 120, 160}) place(a, x);
        assertNull(a.connections.connect(at(200), b)); // B has no signs
        a.connections.settle(a.connections.pending.getFirst(), true);
        b.huts.remove(b.hutAt(at(200)));
        b.connections.gateRemoved(at(200));
        assertNull(a.connections.direct().get(b.owner));
        b.huts.add(new Hut(Hut.Type.GATE_HOUSE, at(200), 1));
        assertNull(a.connections.connect(at(200), b));
        a.connections.settle(a.connections.pending.getFirst(), true);
        assertSame(at(200), a.connections.direct().get(b.owner));
    }

    @Test
    void anEndTakenInTheMeantimeStaysAsItIs() {
        for (int x : new int[]{40, 80, 120}) place(a, x);
        place(b, 160);
        assertNull(b.connections.place(at(125))); // not found a way yet, and meanwhile:
        assertNull(a.connections.connect(at(160), b));
        a.connections.settle(a.connections.pending.getFirst(), true);
        b.connections.settle(b.connections.pending.getFirst(), true);
        assertEquals(List.of(at(125)), broken);
        assertSame(at(120), b.connections.nodes.get(at(160)).next);

        a.connections.removed(at(120));
        place(a, 121);
        assertNull(a.connections.connect(at(160), b));
        assertNull(b.connections.connect(at(121), a)); // both at once
        a.connections.settle(a.connections.pending.getFirst(), true);
        b.connections.settle(b.connections.pending.getFirst(), true);
        assertSame(at(160), a.connections.nodes.get(at(121)).next);
        assertSame(at(121), b.connections.nodes.get(at(160)).next);
    }

    @Test
    void alliesNeedBothSides() {
        connect();
        assertNotNull(a.connections.act(b, Event.CONFIRMED), "B didn't ask");
        assertNotNull(a.connections.act(far, Event.REQUESTED), "not connected");
        assertNull(a.connections.act(b, Event.REQUESTED));
        assertEquals(Status.NEUTRAL, a.connections.status(b.owner));
        assertNull(b.connections.act(a, Event.CONFIRMED));
        assertEquals(Status.ALLIES, a.connections.status(b.owner));
        assertEquals(Status.ALLIES, b.connections.status(a.owner));
        assertNull(a.connections.act(b, Event.NEUTRAL));
        assertNotNull(b.connections.act(a, Event.CONFIRMED), "the old request is answered");
        assertNull(b.connections.act(a, Event.FEUD));
        assertEquals(Status.HOSTILE, a.connections.status(b.owner));
    }

    @Test
    void alliesOfAlliesAreConnected() {
        connect();
        b.connections.direct.put(far.owner, at(1000));
        far.connections.direct.put(b.owner, at(200));
        assertEquals(Set.of(), a.connections.indirect().keySet());
        a.connections.act(b, Event.REQUESTED);
        b.connections.act(a, Event.CONFIRMED);
        assertEquals(Map.of(far.owner, at(1000)), a.connections.indirect());
        assertNull(a.connections.act(far, Event.REQUESTED));
        assertNull(far.connections.act(a, Event.CONFIRMED), "though C has no ally to see A through");

        all.remove(b.owner);
        a.connections.forget(b.owner);
        assertEquals(Map.of(), a.connections.all());
        assertNull(a.connections.nodes.get(at(120)).next);
    }
}
