package dev.minecp.job;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sifter's loot tables, as written out from the mod's. Runs without a server. */
class SifterTest {
    @Test
    void everyMeshAndBlockHasATable() {
        for (Mesh m : Mesh.values())
            for (Material b : Sifter.BLOCKS) {
                Sifter.Loot loot = Sifter.LOOT.get(m).get(b);
                assertNotNull(loot, m + " " + b);
                for (int i = 0; i < 200; i++) {
                    Material got = loot.roll();
                    assertTrue(got == null || Arrays.asList(loot.items()).contains(got), m + " " + b + " rolled " + got);
                }
            }
    }
}
