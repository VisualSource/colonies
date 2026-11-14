package dev.minecp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** A job at a worker hut: one colonist works it, using the hut's chest. */
abstract class Job {
    final Hut hut;
    UUID worker; // colonist id, or null while nobody has the job
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // what the worker holds; saved with the colony

    Job(Hut hut) {
        this.hut = hut;
    }

    /** One tick of work for the worker, whose entities are spawned. */
    abstract void tick(Colonist w);

    Inventory chest() {
        return hut.block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    /** Puts what the worker carries into the chest; what doesn't fit stays carried. @return true if all fit. */
    boolean deliver() {
        Inventory chest = chest();
        if (chest == null) return false;
        carried.replaceAll((m, n) -> n <= 0 ? 0 : chest.addItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum());
        carried.values().removeIf(n -> n <= 0);
        return carried.isEmpty();
    }

    /** The hut is gone or the job ends: drop what the worker carries at the hut. */
    void dropCarried() {
        Location at = hut.block.getLocation().toCenterLocation();
        carried.forEach((m, n) -> { if (n > 0) at.getWorld().dropItemNaturally(at, new ItemStack(m, n)); });
        carried.clear();
    }

    /** Within reach of the hut chest to take or put things. */
    boolean atHut(Colonist w) {
        return w.location().distance(hut.block.getLocation().toCenterLocation()) <= 3;
    }

    /** Walks to the hut chest (teleports if no way leads there). */
    void goToHut(Colonist w) {
        if (w.walkBeside(hut.block) == null) w.teleportNear(hut.block);
    }
}
