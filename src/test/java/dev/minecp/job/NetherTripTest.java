package dev.minecp.job;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What trips to the Nether turn up. Runs without a server. */
class NetherTripTest {
    @Test
    void findsByLevelAndLuck() {
        Random rnd = new Random(1);
        for (int i = 0; i < 2000; i++) {
            List<NetherTrip.Find> one = NetherTrip.finds(1, 0, rnd);
            assertTrue(one.size() >= 5 && one.size() <= 16, "3 to 10 blocks, 2 to 6 fights");
            assertTrue(one.stream().noneMatch(f -> f.block() == Material.GLOWSTONE || f.block() == Material.ANCIENT_DEBRIS), "not at level 1");
            long fights = NetherTrip.finds(5, 100, rnd).stream().filter(f -> f.foe() != null).count();
            assertTrue(fights >= 12 && fights <= 16, "10 more fights with the most luck: " + fights);
        }
    }

    @Test
    void everyFourthMorningStartsANewPeriod() {
        int day = 0;
        for (int expected : new int[]{1, 2, 3, 0, 1, 2, 3, 0}) assertEquals(expected, day = NetherTrip.morning(day));
        assertEquals(4, NetherTrip.morningsLeft(0));
        assertEquals(1, NetherTrip.morningsLeft(3));
    }

    @Test
    void picksByWeight() {
        int[] n = new int[3];
        Random rnd = new Random(1);
        for (int i = 0; i < 10000; i++) n[NetherTrip.pick(new int[]{70, 0, 30}, rnd)]++;
        assertEquals(0, n[1]);
        assertEquals(7000, n[0], 300);
    }

    @Test
    void savedFindsReadBack() {
        for (NetherTrip.Find f : NetherTrip.finds(5, 50, new Random(1))) assertEquals(f, NetherTrip.Find.parse(f.toString()));
    }
}
