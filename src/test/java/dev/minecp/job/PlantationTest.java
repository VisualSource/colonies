package dev.minecp.job;

import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planter's kinds against the mod's tags, its settings and the converter. Runs without a server. */
class PlantationTest {
    @Test
    void everyKindHasItsTagsASwitchAndConvertedPlots() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.exists(dir.resolve("tools/convert_blueprints.py"))) dir = dir.getParent(); // tests run in build/test-run
        String converter = Files.readString(dir.resolve("tools/convert_blueprints.py"));
        Set<String> switches = new HashSet<>();
        for (Settings.Setting s : Settings.of(Hut.Type.PLANTATION)) switches.add(s.key());
        Set<String> tags = new HashSet<>();
        for (Plantation.Kind k : Plantation.Kind.values()) {
            assertSame(k, Plantation.Kind.byField(k.field));
            assertTrue(tags.add(k.tag) && tags.add(k.field), k + ": its tags are its own");
            assertTrue(switches.contains(k.setting()), k + " has a switch");
            assertTrue(converter.contains("\"" + k.tag + "\""), k + "'s plots are marked by the converter");
        }
        assertEquals(121, Plantation.Kind.CRIMSON.maxPlots());
        assertEquals(10, Plantation.Kind.SEA_PICKLE.maxPlots());
        assertEquals(1, Plantation.Kind.SEAGRASS.shape.actions);
    }
}
