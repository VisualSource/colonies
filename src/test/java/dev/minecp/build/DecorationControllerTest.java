package dev.minecp.build;

import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecorationControllerTest {
    private static DecorationController of(String structure) {
        return new DecorationController(structure, StructureRotation.CLOCKWISE_90, true);
    }

    @Test
    void levelsFollowTheLastDigit() {
        var well = of("medievaloak/decorations/misc/smallwell1");
        assertEquals("Smallwell level 1", well.name());
        assertEquals(of("medievaloak/decorations/misc/smallwell2"), well.upgraded()); // turned the same way
    }

    @Test
    void withoutLevelsNothingToUpgrade() {
        var bench = of("cavern/decorations/misc/stone_bench");
        assertEquals("Stone bench", bench.name());
        assertNull(bench.upgraded());
    }
}
