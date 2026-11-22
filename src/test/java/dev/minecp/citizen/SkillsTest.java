package dev.minecp.citizen;

import org.junit.jupiter.api.Test;

import static dev.minecp.citizen.Skills.Skill.*;
import static org.junit.jupiter.api.Assertions.*;

/** The mod's skill numbers: experience per level, the home's level limit, and losing experience. Runs without a server. */
class SkillsTest {
    @Test
    void experienceForTheNextLevel() {
        assertEquals(6.005, Skills.needed(1), 1e-9);      // 1 + 5 + 0.005
        assertEquals(56, Skills.needed(10), 1e-9);        // 1 + 50 + 5
        assertEquals(1, Skills.needed(0));
    }

    @Test
    void levelsUpAndCarriesTheRest() {
        Skills s = new Skills();
        assertTrue(s.add(STRENGTH, 10, 5, 5)); // 6.005 to level 2, 4 left of the 11.01 needed for 3
        assertEquals(2, s.level(STRENGTH));
        assertEquals(10 - 6.005, s.xp(STRENGTH), 1e-9);
    }

    @Test
    void theHomeLimitsIt() {
        Skills s = new Skills();
        s.load(java.util.List.of("STRENGTH:10:0"));
        assertFalse(s.add(STRENGTH, 1000, 0, 5), "homeless: no experience from level 10");
        assertEquals(10, s.level(STRENGTH));
        assertTrue(s.add(STRENGTH, 1000, 1, 5), "a level 1 home allows up to 20");
        Skills top = new Skills();
        top.load(java.util.List.of("STRENGTH:60:0"));
        assertTrue(top.add(STRENGTH, 10000, 5, 5), "a home at its top level has no limit");
        Skills tavern = new Skills();
        tavern.load(java.util.List.of("STRENGTH:40:0"));
        assertFalse(tavern.add(STRENGTH, 10000, 3, 3), "a tavern (top level 3) stops at 40 even when full");
    }

    @Test
    void losesExperienceAndLevelsButNeverBelowOne() {
        Skills s = new Skills();
        s.load(java.util.List.of("FOCUS:3:2"));
        s.remove(FOCUS, 5); // 2 gone, then 3 of level 2's 11.04
        assertEquals(2, s.level(FOCUS));
        assertEquals(Skills.needed(2) - 3, s.xp(FOCUS), 1e-9);
        s.remove(FOCUS, 1000);
        assertEquals(1, s.level(FOCUS));
        assertEquals(0, s.xp(FOCUS));
    }

    @Test
    void rollsWithinTheCap() {
        Skills s = new Skills();
        for (int i = 0; i < 50; i++) {
            s.roll(5);
            for (var k : Skills.Skill.values()) assertTrue(s.level(k) >= 1 && s.level(k) <= 4, k + " " + s.level(k));
        }
        s.roll(1);
        assertEquals(1, s.level(KNOWLEDGE));
    }
}
