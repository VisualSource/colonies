package dev.minecp.colony;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test
    void defaultsAreTheMods() {
        Hut miner = new Hut(Hut.Type.MINER, null, 1);
        assertEquals(Material.COBBLESTONE, miner.block(Settings.FILL_BLOCK));
        assertEquals(Settings.NO_MAX_DEPTH, miner.number(Settings.MAX_DEPTH));
        Hut hall = new Hut(Hut.Type.TOWN_HALL, null, 1);
        for (String k : new String[]{Settings.MOVE_IN, Settings.AUTO_HIRING, Settings.AUTO_HOUSING, Settings.TAPE, Settings.ENTER_LEAVE})
            assertTrue(hall.on(k), k);
        assertEquals(Settings.Hiring.DEFAULT, miner.hiring);
    }

    @Test
    void onlyChangesAreStored() {
        Hut cow = new Hut(Hut.Type.COWBOY, null, 1);
        cow.set(Settings.MILKING, "3");
        assertEquals(3, cow.number(Settings.MILKING));
        cow.set(Settings.MILKING, "1"); // back to the default: nothing left to save
        assertTrue(cow.settings.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> cow.setting(Settings.FILL_BLOCK)); // not a cowhand's setting
    }

    @Test
    void keysAreValidDialogInputNames() { // Paper refuses other characters (a hyphen threw when the menu opened)
        for (Hut.Type t : Hut.Type.values())
            for (Settings.Setting st : Settings.of(t)) assertTrue(st.key().matches("[a-z0-9_]+"), st.key());
    }

    @Test
    void researchUnlocksGuardTypes() {
        Colony colony = new Colony(java.util.UUID.randomUUID(), "Test", null, 1);
        assertTrue(Settings.unlocked("KNIGHT", colony));
        assertFalse(Settings.unlocked("HUSCARL", colony));
        assertFalse(Settings.unlocked("MARKSMAN", colony));
        research(colony, "combat|slicedanddecided");
        assertTrue(Settings.unlocked("HUSCARL", colony));
        assertFalse(Settings.unlocked("MARKSMAN", colony));
        research(colony, "combat|thathitthemark");
        assertTrue(Settings.unlocked("MARKSMAN", colony));
    }

    private static void research(Colony colony, String id) {
        var saved = new org.bukkit.configuration.file.YamlConfiguration();
        saved.set(id, -1); // finished
        colony.research.load(saved);
    }

    @Test
    void whoHires() {
        assertTrue(Settings.hires(Hut.Type.LUMBERJACK));
        assertTrue(Settings.hires(Hut.Type.HOUSE));
        assertFalse(Settings.hires(Hut.Type.TOWN_HALL));
        assertFalse(Settings.hires(Hut.Type.FIELD));
    }
}
