package dev.minecp.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A past raid survives being saved and loaded. Runs without a server. */
class RaidHistoryTest {
    @Test
    void savedAndLoadedBack() {
        var past = new Raids.Past(123_456L, 27, "pirate_ship@10,62,-40 barbarian_raid@-200,70,15", 21, 4, 1.37);
        assertEquals(past, Raids.Past.load(past.save()));
        assertNull(Raids.Past.load("1;2;3"), "a broken line is left out");
    }
}
