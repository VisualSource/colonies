package dev.minecp.colony;

import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim command's chunks (the mod's static claims) on top of the claims worked out from the huts. Runs without a server. */
class HandClaimsTest {
    /** A block at 0 0 0 that only answers its coordinates. */
    private static Block origin() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (p, m, a) -> switch (m.getName()) {
            case "getX", "getY", "getZ" -> 0;
            case "hashCode" -> 0;
            case "equals" -> p == a[0];
            default -> null;
        });
    }

    @Test
    void handClaimsAddAndLetGoOfDirectClaimsOnly() {
        Colony c = new Colony(UUID.randomUUID(), "Test", origin(), 0);
        assertTrue(c.claims().contains(Claims.key(4, 4)), "a first chunk");

        c.claimByHand(4, 4, 0, false);
        assertFalse(c.claims().contains(Claims.key(4, 4)), "let go");
        assertFalse(Claims.direct(c, Claims.key(4, 4)));

        c.claimByHand(30, 30, 1, true);
        assertTrue(c.claims().contains(Claims.key(30, 30)) && c.claims().contains(Claims.key(31, 31)), "claimed by hand, past the colony's size");
        assertTrue(Claims.direct(c, Claims.key(29, 29)));

        c.claimByHand(0, 0, 0, false);
        assertTrue(c.claims().contains(Claims.key(0, 0)), "the town hall's own claim stays, as a building's claims do in the mod");
        assertTrue(Claims.claimers(c, Claims.key(0, 0)).contains(c.townHall()));
    }
}
