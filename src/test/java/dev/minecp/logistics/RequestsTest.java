package dev.minecp.logistics;

import dev.minecp.job.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
