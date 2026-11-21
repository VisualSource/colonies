package dev.minecp.logistics;

import dev.minecp.build.BuildOrder;

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
public final class Rack {
    /** Its item's name and its chest's name (what the optional pack's chest model goes by: tools/build_resourcepack.py). */
    public static final String NAME = "Storage Rack";

    private Rack() {}

    public static ItemStack item() {
        ItemStack item = new ItemStack(Material.CHEST);
        item.editMeta(m -> {
            m.itemName(Component.text(NAME, NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Storage for colony buildings", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(BuildOrder.RACK_MARKER, PersistentDataType.BYTE, (byte) 1);
        });
        dev.minecp.Items.model(item, "rack");
        return item;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getType() == Material.CHEST && item.getPersistentDataContainer().has(BuildOrder.RACK_MARKER);
    }

    public static boolean is(Block b) {
        return b.getType() == Material.CHEST && b.getState(false) instanceof TileState ts && ts.getPersistentDataContainer().has(BuildOrder.RACK_MARKER);
    }

    /** Marks a chest in the world as a rack. */
    public static void tag(Block b) {
        if (!(b.getState() instanceof TileState ts)) return;
        if (!ts.getPersistentDataContainer().has(BuildOrder.RACK_MARKER)) {
            ts.getPersistentDataContainer().set(BuildOrder.RACK_MARKER, PersistentDataType.BYTE, (byte) 1);
            ts.update(true, false);
        }
        // Named even when already tagged: a pasted structure's chest (supplies) brings the tag but not the name.
        dev.minecp.ChestNames.name(b, NAME); // its inventory title, and the pack's model choice
    }
}
