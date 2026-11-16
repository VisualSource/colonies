package dev.minecp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The mod's storage rack, stood in for by a chest tagged {@link BuildOrder#RACK_MARKER} (block and item), so it can get
 * a custom model later. Buildings need racks, not plain chests, and only racks count as warehouse storage.
 */
final class Rack {
    private Rack() {}

    static ItemStack item() {
        ItemStack item = new ItemStack(Material.CHEST);
        item.editMeta(m -> {
            m.itemName(Component.text("Storage Rack", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Storage for colony buildings", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(BuildOrder.RACK_MARKER, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.CHEST && item.getPersistentDataContainer().has(BuildOrder.RACK_MARKER);
    }

    static boolean is(Block b) {
        return b.getType() == Material.CHEST && b.getState(false) instanceof TileState ts && ts.getPersistentDataContainer().has(BuildOrder.RACK_MARKER);
    }

    /** Marks a chest in the world as a rack. */
    static void tag(Block b) {
        if (!(b.getState() instanceof TileState ts) || ts.getPersistentDataContainer().has(BuildOrder.RACK_MARKER)) return;
        ts.getPersistentDataContainer().set(BuildOrder.RACK_MARKER, PersistentDataType.BYTE, (byte) 1);
        ts.update(true, false);
    }
}
