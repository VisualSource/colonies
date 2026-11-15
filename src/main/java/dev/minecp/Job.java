package dev.minecp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** A job at a worker hut: one colonist works it, using the hut's chest. */
abstract class Job {
    final Hut hut;
    UUID worker; // colonist id, or null while nobody has the job
    final Map<Material, Integer> carried = new EnumMap<>(Material.class); // what the worker holds; saved with the colony
    final Map<Tool, ItemStack> tools = new EnumMap<>(Tool.class);          // tools taken from the hut chest; saved too

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

    /** The hut is gone or the job ends: drop what the worker carries, tools included, at the hut. */
    void dropCarried() {
        Location at = hut.block.getLocation().toCenterLocation();
        carried.forEach((m, n) -> { if (n > 0) at.getWorld().dropItemNaturally(at, new ItemStack(m, n)); });
        carried.clear();
        tools.values().forEach(t -> at.getWorld().dropItemNaturally(at, t));
        tools.clear();
    }

    /** Whether the worker holds a tool of this kind that can break {@code block} (null: any block). */
    boolean hasTool(Tool kind, Block block) {
        return kind.fits(tools.get(kind), block);
    }

    /**
     * Takes a fitting tool of this kind from the hut chest (the worker must be at the hut), putting back the one it
     * replaces. @return false if the chest has none.
     */
    boolean fetchTool(Tool kind, Block block) {
        Inventory chest = chest();
        if (chest == null) return false;
        for (int i = 0; i < chest.getSize(); i++) {
            ItemStack item = chest.getItem(i);
            if (!kind.fits(item, block)) continue;
            ItemStack old = tools.put(kind, item.asOne());
            item.subtract();
            chest.setItem(i, item.isEmpty() ? null : item);
            if (old != null) for (ItemStack overflow : chest.addItem(old).values())
                hut.block.getWorld().dropItemNaturally(hut.block.getLocation().toCenterLocation(), overflow);
            return true;
        }
        return false;
    }

    /**
     * Breaks {@code block} with the held tool of this kind (null: by hand): drops as that tool would get them,
     * the tool shows in the worker's hand and wears down, and is gone when it breaks.
     */
    Collection<ItemStack> breakWith(Tool kind, Block block, Colonist w) {
        ItemStack tool = kind == null ? null : tools.get(kind);
        Collection<ItemStack> drops = tool == null ? block.getDrops() : block.getDrops(tool);
        if (tool != null) {
            w.hold(tool);
            ItemStack worn = tool.damage(1, w.living()); // Unbreaking and the break animation included
            if (worn.isEmpty()) {
                tools.remove(kind);
                w.hold(null);
            } else tools.put(kind, worn);
        }
        return drops;
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
