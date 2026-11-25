package dev.minecp.colony;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a colony may attack another in PvP mode (the mod's AttackingPlayer.isValidAttack). */
class AttackersTest {
    @Test
    void attacksOnlyThoseAtWar() {
        assertFalse(Attackers.mayAttack(false, false, 1, 100));
        assertTrue(Attackers.mayAttack(true, false, 1000, 1), "hostile there: whatever the strength");
        assertTrue(Attackers.mayAttack(false, true, 20, 10), "twice as strong is the most");
        assertFalse(Attackers.mayAttack(false, true, 21, 10), "stronger than that picks on the weak");
        assertTrue(Attackers.mayAttack(true, true, 21, 10));
    }
}
