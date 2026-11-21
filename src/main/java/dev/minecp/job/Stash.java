package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Hut;

import org.bukkit.inventory.ItemStack;

/**
 * The mod's stash: a chest whose contents couriers take to the warehouse (its pickup request). Nobody works here: it
 * keeps nothing, so couriers collect it as they collect any hut's surplus. Ours: after their deliveries, where the mod
 * gives it the highest pickup priority, and no "a courier will come" message. Right-clicking opens the chest, so it has
 * no menu.
 */
public final class Stash extends Job {
    public Stash(Hut hut) {
        super(hut);
    }

    @Override
    public void tick(Colonist w) {}

    @Override
    int keep(ItemStack item) {
        return 0;
    }
}
