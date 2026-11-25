package dev.minecp;

import dev.minecp.colony.Hut;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** How long hut blocks take to break in PvP mode, and when a town hall's breaking is told. */
class SlowBreakingTest {
    @Test
    void takesAMinuteATownHallTwelve() {
        assertEquals(60 * 20, SlowBreaking.needed(Hut.Type.BUILDER));
        assertEquals(12 * 60 * 20, SlowBreaking.needed(Hut.Type.TOWN_HALL));
        assertEquals(30, SlowBreaking.needed(Hut.Type.POST_BOX));
        assertEquals(30, SlowBreaking.needed(Hut.Type.STASH));
    }

    @Test
    void breaksOnceDugNearlyThrough() {
        assertFalse(SlowBreaking.done(839, 1200));
        assertTrue(SlowBreaking.done(840, 1200));
    }

    @Test
    void warnsOnceAtEachShare() {
        assertEquals(List.of(25, 50, 75, 90), IntStream.rangeClosed(1, 14400).map(t -> SlowBreaking.warning(t, 14400)).filter(p -> p > 0).boxed().toList());
        assertEquals(25, SlowBreaking.warning(3600, 14400));
    }
}
