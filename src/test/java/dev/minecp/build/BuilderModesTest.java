package dev.minecp.build;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each builder mode visits every column of a layer once (Structurize's iterators). Runs without a server. */
class BuilderModesTest {
    @Test
    void everyModeCoversALayerOnce() {
        for (String mode : BuildOrder.MODES) for (int[] size : new int[][]{{1, 1}, {5, 3}, {7, 7}, {4, 9}, {12, 5}}) {
            List<int[]> order = BuildOrder.layerOrder(mode, size[0], size[1]);
            Set<Long> seen = new HashSet<>();
            for (int[] p : order) seen.add((long) p[0] << 32 | p[1]);
            assertEquals(size[0] * size[1], order.size(), mode + " " + size[0] + "x" + size[1]);
            assertEquals(order.size(), seen.size(), mode + ": no column twice");
        }
    }

    @Test
    void defaultTurnsAtEachEndAndCirclesGoInward() {
        List<int[]> d = BuildOrder.layerOrder("default", 3, 2);
        assertArrayEquals(new int[]{2, 1}, new int[]{d.get(3)[0], d.get(3)[1]}, "the second row starts at the far end");
        List<int[]> c = BuildOrder.layerOrder("inwardcircle", 3, 3);
        assertEquals(1, c.getLast()[2], "the middle is the last ring");
        assertArrayEquals(new int[]{1, 1}, new int[]{c.getLast()[0], c.getLast()[1]});
    }
}
