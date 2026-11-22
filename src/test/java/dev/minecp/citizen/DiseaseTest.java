package dev.minecp.citizen;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mod's diseases: picked by rarity, cures read out. Runs without a server. */
class DiseaseTest {
    @Test
    void picksByRarity() {
        Map<Disease, Integer> seen = new EnumMap<>(Disease.class);
        for (int i = 0; i < 111_000; i++) seen.merge(Disease.random(), 1, Integer::sum);
        // rarities 100 : 10 : 1
        assertTrue(seen.get(Disease.INFLUENZA) > 95_000, seen.toString());
        assertTrue(seen.get(Disease.MEASLES) > 8_000 && seen.get(Disease.MEASLES) < 12_000, seen.toString());
        assertTrue(seen.get(Disease.SMALLPOX) > 600 && seen.get(Disease.SMALLPOX) < 1_500, seen.toString());
    }

    @Test
    void curesReadOut() {
        assertEquals("a carrot and a potato", Disease.INFLUENZA.cureLabel());
        assertEquals("a poppy, a dandelion and a kelp", Disease.MEASLES.cureLabel());
    }
}
