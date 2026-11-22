package dev.minecp.build;

import dev.minecp.colony.Hut;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the build tool reads a style pack's file names. Runs without a server. */
class CatalogTest {
    @Test
    void hutsAndTheirVersions() {
        var normal = Catalog.entry("fundamentals/builder1");
        assertEquals(Hut.Type.BUILDER, normal.hut());
        assertEquals("fundamentals/builder", normal.path());
        assertEquals("Builder's Hut", normal.name());
        var alt = Catalog.entry("fundamentals/altbuilder1");
        assertEquals(Hut.Type.BUILDER, alt.hut());
        assertEquals("fundamentals/altbuilder", alt.path());
        assertEquals("Builder's Hut (alternative)", alt.name());
        assertEquals("Builder's Hut (megahall)", Catalog.entry("fundamentals/megahall/builder1").name()); // cavern's folders
        assertEquals("Builder's Hut", Catalog.entry("fundamentals/default/builder1").name());
        assertEquals(Hut.Type.FARMER, Catalog.entry("agriculture/horticulture/farmer1").hut());
        assertNull(Catalog.entry("fundamentals/builder2"), "upgrades aren't placed");
        assertEquals(Hut.Type.BARRACKS, Catalog.entry("military/barracks1").hut());
        assertNull(Catalog.entry("military/barrackstower1"), "comes with its barracks");
        assertNull(Catalog.entry("military/archery1"), "a hut MineCP doesn't have");
    }

    @Test
    void buildsWithoutAHut() {
        var well = Catalog.entry("decorations/misc/bigwell1");
        assertNull(well.hut());
        assertTrue(well.leveled());
        assertEquals("decorations/misc/bigwell1", well.structure("").substring(1));
        var small = Catalog.entry("decorations/misc/small_well");
        assertFalse(small.leveled());
        assertEquals("Small well", small.name());
        assertNull(Catalog.entry("decorations/misc/bigwell2"), "a decoration starts at level 1");
        assertNull(Catalog.entry("infrastructure/mineshafts/minermainshaft"), "the miner's");
        assertNull(Catalog.entry("decorations/supplies/supplycamp"), "placed from its item");
        assertEquals(Catalog.entry("walls/gate/gate1").path(), "walls/gate/gate");
    }
}
