package dev.minecp.colony;

import dev.minecp.Items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** The mod's Colony Sign (ItemColonySign, BlockColonySign): an oak sign with a tag, waxed once it stands. */
public final class ColonySign {
    private static final NamespacedKey KEY = NamespacedKey.fromString("minecp:colony_sign"); // its colony's owner; "" on an item not bound yet

    private ColonySign() {}

    public static ItemStack item(int amount) {
        ItemStack item = new ItemStack(Material.OAK_SIGN, amount);
        item.editMeta(m -> {
            m.itemName(Component.text("Colony Sign", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("Sneak + right-click a Gatehouse to begin", NamedTextColor.GRAY),
                    Component.text("Place one every 50 blocks to another colony, then", NamedTextColor.GRAY),
                    Component.text("sneak + right-click its Gatehouse or Colony Sign", NamedTextColor.GRAY)));
            m.setMaxStackSize(64); // the mod's
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "");
        });
        Items.model(item, "colony_sign");
        return item;
    }

    public static boolean is(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(KEY);
    }

    /** The owner of the colony an item is bound to, or null. */
    public static UUID colony(ItemStack item) {
        return owner(item.getPersistentDataContainer());
    }

    public static void bind(ItemStack item, Colony c) {
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, c.owner.toString());
            m.lore(List.of(Component.text("Colony: " + c.name, NamedTextColor.GRAY)));
        });
    }

    public static boolean is(Block b) {
        return b != null && b.getType() == Material.OAK_SIGN && b.getState(false) instanceof Sign s && s.getPersistentDataContainer().has(KEY);
    }

    /** The owner of the colony a standing one belongs to, or null. */
    public static UUID colony(Block b) {
        return owner(((Sign) b.getState(false)).getPersistentDataContainer());
    }

    private static UUID owner(PersistentDataContainerView tags) {
        String id = tags.getOrDefault(KEY, PersistentDataType.STRING, "");
        return id.isEmpty() ? null : UUID.fromString(id);
    }

    /** One was just placed at {@code b}. */
    public static void place(Block b, Colony c) {
        Sign s = (Sign) b.getState(false);
        s.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, c.owner.toString());
        s.setWaxed(true);
        s.update(true, false);
        write(b, List.of(c.name));
    }

    /** Puts these lines on both sides, if they aren't there already. */
    static void write(Block b, List<String> lines) {
        Sign s = (Sign) b.getState(false);
        boolean changed = false;
        for (Side side : Side.values()) for (int i = 0; i < 4; i++) {
            Component line = Component.text(i < lines.size() ? lines.get(i) : "");
            if (s.getSide(side).line(i).equals(line)) continue;
            s.getSide(side).line(i, line);
            changed = true;
        }
        if (changed) s.update(true, false);
    }

    /** Takes one down and drops it, if it still stands. */
    public static void pop(Block b) {
        if (!is(b)) return;
        b.setType(b.getBlockData() instanceof Waterlogged w && w.isWaterlogged() ? Material.WATER : Material.AIR);
        b.getWorld().dropItemNaturally(b.getLocation().toCenterLocation(), item(1));
    }
}
