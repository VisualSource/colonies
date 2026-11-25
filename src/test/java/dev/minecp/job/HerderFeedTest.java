package dev.minecp.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** How much older a fed young animal gets (the mod's ageUp: a tenth of the seconds left). */
class HerderFeedTest {
    @Test
    void growsATenthOfWhatIsLeft() {
        assertEquals(-21600, Herder.grown(-24000)); // newborn: 1200 s left, 120 s older
        assertEquals(-10800, Herder.grown(-12000));
        assertEquals(-199, Herder.grown(-199), "9 s left: a tenth is no whole second");
        assertEquals(0, Herder.grown(0));
    }
}
