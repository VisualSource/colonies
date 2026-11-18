package dev.minecp;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a citizen died (the mod's grave block): holds what it carried. A stone brick wall stands in for the block;
 * it turns mossy when it decays after 10 minutes, and 10 minutes later drops what is left and is gone, as in the mod.
 * Players open it to take things out.
 */
final class Grave implements InventoryHolder {
    static final int DECAY_TICKS = 20 * 60 * 10; // the mod's DEFAULT_DECAY_TIMER
    static final Material BLOCK = Material.STONE_BRICK_WALL, DECAYED_BLOCK = Material.MOSSY_STONE_BRICK_WALL;

    final Block block;
    final String name, job; // who lies here, for the undertaker's named grave
    final Inventory items;
    int timer = DECAY_TICKS;
    boolean decayed;

    Grave(Block block, String name, String job, List<ItemStack> contents) {
        this.block = block;
        this.name = name;
        this.job = job;
        this.items = Bukkit.createInventory(this, 54, Component.text("Grave of " + name));
        for (ItemStack i : contents) for (ItemStack over : items.addItem(i).values())
            block.getWorld().dropItemNaturally(block.getLocation().toCenterLocation(), over);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return items;
    }

    /**
     * Counts down by {@code ticks}. @return false once it has fully decayed: its items are dropped and the block is gone.
     */
    boolean decay(int ticks) {
        if ((timer -= ticks) > 0) return true;
        if (!decayed) {
            decayed = true;
            timer = DECAY_TICKS;
            if (block.getType() == BLOCK) block.setType(DECAYED_BLOCK);
            return true;
        }
        dropAll();
        if (block.getType() == BLOCK || block.getType() == DECAYED_BLOCK) block.setType(Material.AIR);
        return false;
    }

    boolean isLoaded() {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    void dropAll() {
        Location at = block.getLocation().toCenterLocation();
        for (ItemStack i : items.getContents()) if (i != null) at.getWorld().dropItemNaturally(at, i);
        items.clear();
        new ArrayList<>(items.getViewers()).forEach(org.bukkit.entity.HumanEntity::closeInventory);
    }

    /**
     * A spot for a grave near where it died, as the mod looks: the nearest air block on solid ground within 10.
     * Null in lava (the mod makes no grave there).
     */
    static Block spot(Block died) {
        if (died.getType() == Material.LAVA) return null;
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int dy = -10; dy <= 10; dy++) {
            Block b = died.getRelative(dx, dy, dz);
            if (!b.getType().isAir() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }
}
