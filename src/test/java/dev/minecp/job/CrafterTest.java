package dev.minecp.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Sawmill's wood rule (the mod's: above 0.75). The item tags need a server, so this checks the arithmetic. Runs without a server. */
class CrafterTest {
    private static boolean sawmill(int planksAndLogs, int otherWood, int rest) {
        return Crafter.woodRatio(planksAndLogs, otherWood, rest) > Crafter.MIN_WOOD_RATIO;
    }

    @Test
    void woodRatioMatchesTheMod() {
        assertTrue(sawmill(2, 0, 0), "stick: 2 planks, nothing else");
        assertTrue(sawmill(6, 0, 1), "shield: 6 planks + 1 iron ingot");
        assertTrue(sawmill(1, 1, 1), "tripwire hook: planks, a stick and an iron ingot (planks aren't counted below the line)");
        assertTrue(sawmill(0, 1, 0), "a stick alone counts as wood");
        assertFalse(sawmill(0, 0, 9), "iron block: no wood");
        assertFalse(sawmill(0, 1, 2), "1 stick + 2 other: 1/3");
        assertFalse(sawmill(0, 3, 1), "3 sticks + 1 other: 3/4 is not above 0.75");
    }
}
