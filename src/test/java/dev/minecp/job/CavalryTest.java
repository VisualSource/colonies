package dev.minecp.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cavalry horses' fitness and the stablemaster's readying, the mod's numbers. Runs without a server. */
class CavalryTest {
    @Test
    void aHorseFightsWhileItsCooldownIsUnderTwoThirdsOfItsHealth() {
        assertFalse(CavalryHorse.fit(30, 30), "just trained: its cooldown is its health");
        assertTrue(CavalryHorse.fit(19.8, 30), "two thirds of 30 is fit");
        assertFalse(CavalryHorse.fit(20, 30), "a little more is not");
        assertTrue(CavalryHorse.fit(0, 30));
    }

    @Test
    void leatherTakesOffFourTimesAthleticsOverTwenty() {
        assertEquals(4.0, Stablemaster.recovery(20), 1e-9);
        assertEquals(1.0, Stablemaster.recovery(5), 1e-9);
        assertEquals(10.0, Stablemaster.recovery(50), 1e-9);
    }
}
