package dev.minecp.build;

import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class MapBordersTest {
    /** Colony a owns x 0-31, b x 32-47, both z 0-31. */
    private final BiFunction<Integer, Integer, String> owner = (x, z) -> z < 0 || z > 31 || x < 0 || x > 47 ? null : x < 32 ? "a" : "b";

    @Test
    void outlinesRunInsideEachColony() {
        assertEquals("a", MapBorders.outlined(owner, 0, 10, 1));
        assertNull(MapBorders.outlined(owner, -1, 10, 1));
        assertNull(MapBorders.outlined(owner, 10, 10, 1));
        assertEquals("a", MapBorders.outlined(owner, 31, 10, 1));
        assertEquals("b", MapBorders.outlined(owner, 32, 10, 1));
    }

    @Test
    void aZoomedOutMapStillShowsThem() {
        assertEquals("a", MapBorders.outlined(owner, 0, 0, 16));
        assertEquals("b", MapBorders.outlined(owner, 32, 16, 16));
    }
}
