package dev.minecp.research;

import dev.minecp.colony.Hut;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** research.json as converted, and the rules read from it. Runs without a server. */
class ResearchTreeTest {
    @Test
    void treeIsWhole() {
        assertEquals(3, ResearchTree.branches().size()); // civilian, combat, technology; unlockables hidden
        for (var r : ResearchTree.all()) {
            if (r.parent() != null) {
                var parent = ResearchTree.get(r.parent());
                assertNotNull(parent, r.id() + "'s parent");
                assertEquals(parent.depth() + 1, r.depth(), r.id() + " is one deeper than its parent");
            } else assertTrue(ResearchTree.children(r.branch()).contains(r), r.id() + " starts its branch");
            assertTrue(r.depth() >= 1 && r.depth() <= ResearchTree.MAX_DEPTH, r.id());
        }
    }

    @Test
    void stepsDoubleWithDepth() {
        var hot = ResearchTree.get("technology/hot"); // depth 1: 72 steps of 25 s, half an hour
        assertEquals(72, ResearchTree.steps(hot));
        assertEquals(144, ResearchTree.steps(ResearchTree.get("technology/thoselungs")));
    }

    @Test
    void savedProgressComesBack() {
        var saved = new org.bukkit.configuration.file.YamlConfiguration();
        saved.set("technology|woodwork", -1);  // finished
        saved.set("civilian|stamina", 36);     // half way
        saved.set("civilian|gone", 5);         // no longer in the tree: dropped
        var first = new Studies(null);         // saving and loading never touch the colony
        first.load(saved);
        var again = new org.bukkit.configuration.file.YamlConfiguration();
        first.save(again);
        var back = new Studies(null);
        back.load(again);
        assertTrue(back.isDone("technology/woodwork"));
        assertEquals(5.0, back.effect("blockhutsawmill"));
        assertEquals(0.5, back.progress(ResearchTree.get("civilian/stamina")));
        assertEquals(java.util.List.of(ResearchTree.get("civilian/stamina")), back.inProgress());
        assertTrue(!back.isStarted("civilian/gone"));
    }

    @Test
    void citizenEffectsAreDescribed() {
        for (String id : new String[]{"civilian/firstaid", "civilian/haste", "civilian/bandaid", "civilian/resistance", "civilian/gourmand",
                "civilian/circus", "civilian/nurture", "civilian/outpost", "civilian/nightowl", "civilian/gravedecaybonus1",
                "civilian/resurrectchance1", "civilian/undertakeremergency"})
            for (var e : ResearchTree.get(id).effects()) assertTrue(!Studies.describe(e).contains("not in Colonies"), id);
        assertEquals("Citizens walk 5% faster", Studies.describe(ResearchTree.get("civilian/haste").effects().getFirst()));
        assertEquals("Citizens heal at the full rate with 0.5 less food in them",
                Studies.describe(ResearchTree.get("civilian/resistance").effects().getFirst()));
    }

    @Test
    void hutsUnlockedByResearch() {
        assertEquals("technology/woodwork", Studies.unlockOf(Hut.Type.SAWMILL).id());
        assertEquals("technology/hot", Studies.unlockOf(Hut.Type.SMELTERY).id());
        assertEquals("technology/stonecake", Studies.unlockOf(Hut.Type.STONEMASON).id());
        assertEquals("technology/theflintstones", Studies.unlockOf(Hut.Type.STONE_SMELTERY).id());
        assertEquals("technology/hittingiron", Studies.unlockOf(Hut.Type.BLACKSMITH).id());
        assertEquals("technology/whatyaneed", Studies.unlockOf(Hut.Type.MECHANIC).id());
        assertEquals("civilian/remembrance", Studies.unlockOf(Hut.Type.GRAVEYARD).id());
        for (Hut.Type t : new Hut.Type[]{Hut.Type.BUILDER, Hut.Type.HOUSE, Hut.Type.UNIVERSITY, Hut.Type.WAREHOUSE, Hut.Type.BAKER})
            assertNull(Studies.unlockOf(t), t + " needs no research");
    }
}
