package dev.minecp.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rotated and mirrored blueprints: easy to get off by one, checked against vanilla's own placement. Runs without a server. */
class BuildOrderTest {
    @Test
    void transformMatchesVanillaStructurePlacement() {
        int[][] points = {{0, 0, 0}, {3, 1, 0}, {0, 2, 5}, {4, 0, -2}, {-3, 7, 6}};
        for (StructureRotation r : StructureRotation.values())
            for (boolean mirror : new boolean[]{false, true})
                for (int[] p : points) {
                    BlockPos want = StructureTemplate.transform(new BlockPos(p[0], p[1], p[2]),
                            mirror ? Mirror.FRONT_BACK : Mirror.NONE, Rotation.valueOf(r.name()), BlockPos.ZERO);
                    BlockVector got = BuildOrder.transform(p[0], p[1], p[2], r, mirror);
                    String at = r + (mirror ? " mirrored " : " ") + java.util.Arrays.toString(p);
                    assertEquals(want.getX(), got.getBlockX(), at + " x");
                    assertEquals(want.getY(), got.getBlockY(), at + " y");
                    assertEquals(want.getZ(), got.getBlockZ(), at + " z");
                }
    }
}
