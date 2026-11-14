package dev.minecp;

import org.bukkit.Material;
import org.bukkit.block.Block;

/** A colony building anchored on its hut block. Level 0 is just the block; level N means level N is built. */
final class Hut {
    static final int MAX_LEVEL = 5;

    enum Type {
        TOWN_HALL("Town Hall", Material.BELL, "fundamentals/townhall"),
        BUILDER("Builder's Hut", Material.CHEST, "fundamentals/builder"),
        HOUSE("House", Material.CHEST, "fundamentals/residence");

        final String title;
        final Material block;
        final String path; // structure path inside a style pack, + level

        Type(String title, Material block, String path) {
            this.title = title;
            this.block = block;
            this.path = path;
        }
    }

    /** An upgrade of a hut to a level, waiting for or being built by the builder. */
    record WorkOrder(Hut hut, int level) {}

    final Type type;
    final Block block;
    int level;

    Hut(Type type, Block block, int level) {
        this.type = type;
        this.block = block;
        this.level = level;
    }

    String structure(String style, int level) {
        return style + "/" + type.path + level;
    }
}
