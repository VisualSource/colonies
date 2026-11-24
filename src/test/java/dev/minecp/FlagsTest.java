package dev.minecp;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlagsTest {
    @Test
    void patternsComeWithTownHallLevels() {
        assertFalse(Flags.patternItems(1).contains(Material.FLOWER_BANNER_PATTERN));
        assertTrue(Flags.patternItems(2).contains(Material.FIELD_MASONED_BANNER_PATTERN));
        assertFalse(Flags.patternItems(2).contains(Material.CREEPER_BANNER_PATTERN));
        assertFalse(Flags.patternItems(4).contains(Material.MOJANG_BANNER_PATTERN));
        assertEquals(10, Flags.patternItems(5).size());
    }

    @Test
    void clicksGoRoundAndRound() {
        assertEquals("a", Flags.next(List.of("a", "b", "c"), "c", 1));
        assertEquals("c", Flags.next(List.of("a", "b", "c"), "a", -1));
    }
}
