package dev.minecp.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PiercingTest {
    @Test
    void shareGrowsWithAdaptability() {
        assertEquals(0.5, Piercing.share(0));
        assertEquals(0.505, Piercing.share(1), 1e-9); // half a percent a level
        assertEquals(0.995, Piercing.share(99), 1e-9);
    }

    @Test
    void marksmansArrowAndWhatGoesThrough() {
        assertEquals(4, Piercing.arrow(8, 0.5));
        assertEquals(20, Piercing.through(Piercing.arrow(8, 0.5), 0.5)); // from the reduced damage: from the full it would be 40
        assertEquals(0.04, Piercing.arrow(8, 0.995), 1e-9);
        assertEquals(0.398, Piercing.through(Piercing.arrow(8, 0.995), 0.995), 1e-9);
    }
}
