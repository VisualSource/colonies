package dev.minecp.colony;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may do what in a colony: the mod's default rank table (Permissions.loadRanks) and its fixed rules. */
class PermissionsTest {
    private final UUID owner = new UUID(0, 1);
    private final Permissions perms = new Permissions(owner);

    private Set<Action> allowed(Rank r) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        for (Action a : Action.values()) if (perms.has(r, a)) out.add(a);
        return out;
    }

    @Test
    void defaultsAreTheMods() {
        assertEquals(EnumSet.of(Action.ACCESS_TOGGLEABLES), allowed(Rank.NEUTRAL), "neutral may only use doors and gates");
        assertEquals(EnumSet.of(Action.HURT_CITIZEN, Action.HURT_VISITOR), allowed(Rank.HOSTILE));
        assertFalse(perms.has(Rank.FRIEND, Action.BREAK_BLOCKS));
        assertFalse(perms.has(Rank.FRIEND, Action.OPEN_CONTAINER));
        assertTrue(perms.has(Rank.FRIEND, Action.ACCESS_HUTS));
        assertTrue(perms.has(Rank.OFFICER, Action.BREAK_HUTS));
        assertFalse(perms.has(Rank.OFFICER, Action.EDIT_PERMISSIONS));
        for (Rank r : Rank.values())
            if (r != Rank.HOSTILE) assertFalse(perms.has(r, Action.HURT_CITIZEN), r + " can't hurt citizens properly by default");
        // each rank above neutral has everything the one below it has
        assertTrue(allowed(Rank.OWNER).containsAll(allowed(Rank.OFFICER)));
        assertTrue(allowed(Rank.OFFICER).containsAll(allowed(Rank.FRIEND)));
        assertTrue(allowed(Rank.FRIEND).containsAll(allowed(Rank.NEUTRAL)));
    }

    @Test
    void fixedRules() {
        perms.allow(Rank.OWNER, Action.EDIT_PERMISSIONS, false);
        perms.allow(Rank.OWNER, Action.ACCESS_HUTS, false);
        assertTrue(perms.has(Rank.OWNER, Action.EDIT_PERMISSIONS), "the owner always keeps their settings");
        assertTrue(perms.has(Rank.OWNER, Action.ACCESS_HUTS));
        perms.allow(Rank.NEUTRAL, Action.EDIT_PERMISSIONS, true);
        assertFalse(perms.has(Rank.NEUTRAL, Action.EDIT_PERMISSIONS), "neutral never edits permissions, as in the mod");
        perms.allow(Rank.NEUTRAL, Action.TELEPORT_TO_COLONY, true);
        assertFalse(perms.has(Rank.NEUTRAL, Action.TELEPORT_TO_COLONY), "nor travels");
    }

    @Test
    void ranksOfPlayers() {
        UUID bob = new UUID(0, 2);
        assertEquals(Rank.OWNER, perms.rank(owner));
        assertEquals(Rank.NEUTRAL, perms.rank(bob));
        assertFalse(perms.setRank(owner, "owner", Rank.FRIEND), "the owner's rank can't change");
        perms.setRank(bob, "Bob", Rank.OFFICER);
        assertEquals(Rank.OFFICER, perms.rank(bob));
        perms.setRank(bob, "Bob", Rank.NEUTRAL);
        assertEquals(Rank.NEUTRAL, perms.rank(bob));
        assertTrue(perms.members().isEmpty(), "neutral removes them");
    }
}
