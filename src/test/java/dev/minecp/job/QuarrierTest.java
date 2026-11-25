package dev.minecp.job;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quarrier's decisions on plain values. Runs without a server. */
class QuarrierTest {
    @Test
    void keptGroundIsLeftUnlessAnOreOrAGap() {
        assertFalse(Quarrier.keptGroundNeedsWork(true, false), "stone in a wall stays");
        assertTrue(Quarrier.keptGroundNeedsWork(true, true), "an ore in a wall is dug and replaced by the fill block");
        assertTrue(Quarrier.keptGroundNeedsWork(false, false), "a gap (air, water) is filled");
        assertTrue(Miner.isOre(Material.IRON_ORE) && Miner.isOre(Material.DEEPSLATE_DIAMOND_ORE) && Miner.isOre(Material.ANCIENT_DEBRIS));
        assertFalse(Miner.isOre(Material.STONE) || Miner.isOre(Material.COBBLESTONE));
    }

    @Test
    void handsInDugCobblestoneBeyondAStackOfFill() {
        Map<Material, Integer> left = new EnumMap<>(Material.class);
        left.put(Material.COBBLESTONE, 64); // the stack of fill it keeps
        left.put(Material.LADDER, 3);       // what the next layers need
        assertTrue(Quarrier.keepOne(left, Material.COBBLESTONE, 64), "the first stack of cobblestone stays, as fill");
        assertFalse(Quarrier.keepOne(left, Material.COBBLESTONE, 64), "the next stack it dug is handed in");
        assertFalse(Quarrier.keepOne(left, Material.COBBLESTONE, 10), "and so is every stack after it");
        assertTrue(Quarrier.keepOne(left, Material.LADDER, 3), "ladders the pit needs stay");
        assertFalse(Quarrier.keepOne(left, Material.LADDER, 1), "more than it needs go in");
        assertFalse(Quarrier.keepOne(left, Material.IRON_ORE, 5), "ores it dug always go in");
    }
}
