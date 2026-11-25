package dev.minecp.colony;

import dev.minecp.citizen.Colonist;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Free time comes as the mod's: 3 minutes, about every 120 / home level minutes. Runs without a server. */
class LeisureTest {
    /** How many times free time starts in {@code updates} 3-second updates, each started break ended at once. */
    private static int starts(Colonist c, Leisure l, int updates) {
        int n = 0;
        for (int i = 0; i < updates; i++) {
            c.leisureTime = 0;
            l.update(c);
            if (c.leisureTime > 0) n++;
        }
        return n;
    }

    @Test
    void freeTimeComesMoreOftenInABetterHome() {
        Colony colony = new Colony(UUID.randomUUID(), "Test", null, 1);
        Leisure l = new Leisure(colony);
        Colonist c = new Colonist(UUID.randomUUID(), "Bob", 0, new Location(null, 0, 0, 0));

        int homeless = starts(c, l, 480_000); // 1 in 2400: about 200
        assertTrue(homeless > 140 && homeless < 260, "no home counts as level 1: " + homeless);
        c.home = new Hut(Hut.Type.HOUSE, null, 5);
        int best = starts(c, l, 480_000); // 1 in 480: about 1000
        assertTrue(best > 880 && best < 1120, "a level 5 home: " + best);

        c.leisureTime = Leisure.TIME;
        l.update(c);
        assertEquals(Leisure.TIME - Leisure.UPDATE, c.leisureTime, "it runs down 3 s at a time");
    }
}
