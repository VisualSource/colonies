package dev.minecp.logistics;

import dev.minecp.job.Crafter;
import dev.minecp.job.Job;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How much of a request a courier fetches from the warehouse, in each state. Runs without a server. */
class RequestsTest {
    private static Requests.Request request(Requests.State state, int amount, int crafterShare, int inTransit) {
        var r = new Requests.Request(null, new Job.Want("planks", i -> true, amount));
        r.state = state;
        r.crafterShare = crafterShare;
        r.inTransit = inTransit;
        return r;
    }

    @Test
    void fromWarehouseInEachState() {
        assertEquals(64, Requests.fromWarehouse(request(Requests.State.WAREHOUSE, 64, 0, 0)));
        assertEquals(40, Requests.fromWarehouse(request(Requests.State.WAREHOUSE, 64, 0, 24)), "less what is on its way");
        assertEquals(0, Requests.fromWarehouse(request(Requests.State.WAREHOUSE, 64, 0, 80)), "never negative");
        assertEquals(5, Requests.fromWarehouse(request(Requests.State.CRAFTING, 81, 76, 0)), "the part the crafter isn't making");
        assertEquals(0, Requests.fromWarehouse(request(Requests.State.CRAFTING, 81, 76, 10)), "never negative");
        assertEquals(0, Requests.fromWarehouse(request(Requests.State.CRAFTING, 64, 64, 0)), "all crafted");
        assertEquals(0, Requests.fromWarehouse(request(Requests.State.DELIVERING, 64, 0, 64)));
        assertEquals(0, Requests.fromWarehouse(request(Requests.State.WAITING, 64, 0, 0)));
    }

    private static Crafter crafter() {
        return new Crafter(null, null, r -> true, "", Crafter.Kind.OWN);
    }

    private static Requests.Request asks(Job job, String what) {
        return new Requests.Request(job, new Job.Want(what, i -> true, 1));
    }

    /** Whether making {@code r} loops; {@code made from}: what each thing is made from. */
    private static boolean loops(Requests.Request r, Map<Crafter, List<Requests.Request>> tasks, Map<String, Set<String>> from) {
        return Requests.cycle(t -> from.get(r.label()).contains(t.label()), r, tasks,
                (i, t) -> from.getOrDefault(t.label(), Set.of()).contains(i.label()), new HashSet<>());
    }

    @Test
    void aCraftersOwnChainIsNoLoop() {
        Crafter alchemist = crafter();
        var splash = asks(null, "splash");
        var swiftness = asks(alchemist, "swiftness");
        var awkward = asks(alchemist, "awkward");
        var from = Map.of("splash", Set.of("swiftness"), "swiftness", Set.of("awkward"), "awkward", Set.of("water"));
        var tasks = Map.of(alchemist, List.of(splash, swiftness, awkward));
        for (var r : List.of(splash, swiftness, awkward)) assertFalse(loops(r, tasks, from), r.label());
    }

    @Test
    void twoCraftersWaitingOnEachOtherLoop() {
        Crafter smith = crafter(), smeltery = crafter();
        var ingot = asks(smith, "ingot");
        var block = asks(smeltery, "block");
        assertTrue(loops(block, Map.of(smeltery, List.of(ingot)), Map.of("ingot", Set.of("block"), "block", Set.of("ingot"))), "an ingredient");
        assertTrue(loops(block, Map.of(smeltery, List.of(ingot)), Map.of("ingot", Set.of("ore"), "block", Set.of("ingot"))), "fuel: no ingredient of any task");
    }
}
