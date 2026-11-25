package dev.minecp.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Forest Tool's zone limits. Runs without a server. */
class LumberjackZoneTest {
    @Test
    void volumeIsTheModsPlainDifferences() {
        assertEquals(0, Lumberjack.volume(200_000, 0, 200_000), "a flat zone counts 0 by the mod's rule");
        assertEquals(1000, Lumberjack.volume(10, 10, 10));
        assertEquals(250_000, Lumberjack.MAX_ZONE);
    }

    @Test
    void aFlatZoneIsStillLimitedByItsColumns() {
        assertTrue(Lumberjack.columns(200_000, 200_000) > Lumberjack.MAX_ZONE, "spanning the world is refused");
        assertEquals(1, Lumberjack.columns(0, 0), "one column");
        assertTrue(Lumberjack.columns(499, 499) <= Lumberjack.MAX_ZONE, "500 by 500 is fine");
        assertTrue(Lumberjack.columns(500, 500) > Lumberjack.MAX_ZONE, "501 by 501 isn't");
    }
}
