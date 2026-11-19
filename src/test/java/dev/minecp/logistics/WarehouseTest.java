package dev.minecp.logistics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The stack-limit arithmetic of a courier's load. Runs without a server. */
class WarehouseTest {
    @Test
    void roomCountsWholeStacksPerKind() {
        // Empty load: the full allowance of the item's own stack size.
        assertEquals(128, Warehouse.room(List.of(), 0, 64, 2));
        // 10 planks already loaded, 1 stack allowed: 54 more planks.
        assertEquals(54, Warehouse.room(List.of(), 10, 64, 1));
        // A hoe (stack size 1) takes a whole stack: 2 stacks allowed leaves one stack of planks.
        assertEquals(64, Warehouse.room(List.of(new Warehouse.Kind(1, 1)), 0, 64, 2));
        // 65 cobblestone fill 2 stacks, 2 hoes 2 more: 4 used of 4, no room left.
        assertEquals(0, Warehouse.room(List.of(new Warehouse.Kind(65, 64), new Warehouse.Kind(2, 1)), 0, 64, 4));
        // Never negative, even over the allowance.
        assertEquals(0, Warehouse.room(List.of(new Warehouse.Kind(200, 64)), 30, 64, 2));
        // 17 ender pearls (stack size 16) fill 2 stacks: 1 of 3 left, minus the 5 snowballs (16) already loaded.
        assertEquals(11, Warehouse.room(List.of(new Warehouse.Kind(17, 16)), 5, 16, 3));
    }
}
