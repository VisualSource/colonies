package dev.minecp.job;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which recipes crafters take. Item tags need a server, so these check the rules' logic on plain item lists. Runs without a server. */
class CrafterTest {
    private static boolean sawmillRatio(int planksAndLogs, int otherWood, int rest) {
        return Crafter.woodRatio(planksAndLogs, otherWood, rest) > Crafter.MIN_WOOD_RATIO;
    }

    @Test
    void woodRatioMatchesTheMod() {
        assertTrue(sawmillRatio(2, 0, 0), "stick: 2 planks, nothing else");
        assertTrue(sawmillRatio(6, 0, 1), "6 planks + 1 other");
        assertTrue(sawmillRatio(1, 1, 1), "planks, a stick and 1 other: planks aren't counted below the line");
        assertTrue(sawmillRatio(0, 1, 0), "a stick alone counts as wood");
        assertFalse(sawmillRatio(0, 0, 9), "no wood");
        assertFalse(sawmillRatio(0, 1, 2), "1 stick + 2 other: 1/3");
        assertFalse(sawmillRatio(0, 3, 1), "3 sticks + 1 other: 3/4 is not above 0.75");
    }

    @Test
    void tagRuleDecidesInTheModsOrder() {
        Set<Material> product = Set.of(Material.STONE_BRICKS), productNo = Set.of(Material.TORCH),
                ingredient = Set.of(Material.STONE), ingredientNo = Set.of(Material.STICK);
        var rule = new CraftRules.Rule(product::contains, productNo::contains, ingredient::contains, ingredientNo::contains);
        assertEquals(false, rule.decide(Material.TORCH, List.of(Material.STONE)), "an excluded product refuses, whatever goes in");
        assertEquals(true, rule.decide(Material.STONE_BRICKS, List.of(Material.STICK)), "a listed product allows, whatever goes in");
        assertEquals(false, rule.decide(Material.LEVER, List.of(Material.STONE, Material.STICK)), "then any excluded ingredient refuses");
        assertEquals(true, rule.decide(Material.LEVER, List.of(Material.COBBLESTONE, Material.STONE)), "then any listed ingredient allows");
        assertNull(rule.decide(Material.LEVER, List.of(Material.COBBLESTONE)), "else the tags don't decide");
    }
}
