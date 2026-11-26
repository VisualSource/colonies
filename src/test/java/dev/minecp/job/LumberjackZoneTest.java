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
        assertTrue(Lumberjack.columns(200_000, 200_000) > Lumberjack.MAX_COLUMNS, "spanning the world is refused");
        assertEquals(1, Lumberjack.columns(0, 0), "one column");
        assertTrue(Lumberjack.columns(99, 99) <= Lumberjack.MAX_COLUMNS, "100 by 100 is fine");
        assertTrue(Lumberjack.columns(100, 100) > Lumberjack.MAX_COLUMNS, "101 by 101 isn't");
    }
}
