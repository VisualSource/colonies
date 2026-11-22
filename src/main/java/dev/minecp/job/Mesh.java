package dev.minecp.job;

import dev.minecp.MineCP;
import dev.minecp.research.Effects;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * The mod's sifter meshes (ItemSifterMesh): paper with a tag stands in for its models, worn down by sifting as the
 * mod's durability. Made only by crafters, from one item each, once the hidden Unlockables research for it is done
 * (the mod's crafterrecipes: the Fletcher's string, the Stonemason's flint, the Blacksmith's iron, the Mechanic's diamond).
 */
public enum Mesh {
    STRING("String", 500, 1, Material.STRING, Effects.SIFTER_STRING),
    FLINT("Flint", 1000, 3, Material.FLINT, Effects.SIFTER_FLINT),
    IRON("Iron", 1500, 4, Material.IRON_INGOT, Effects.SIFTER_IRON),
    DIAMOND("Diamond", 2000, 5, Material.DIAMOND, Effects.SIFTER_DIAMOND);

    private static final NamespacedKey KEY = new NamespacedKey(MineCP.NAMESPACE, "mesh");

    final String label;
    final int durability; // the mod's
    final int level;      // the sifter level its recipes need (the mod's min-building-level)
    final Material from;
    final String unlock;  // the research effect that lets crafters make it

    Mesh(String label, int durability, int level, Material from, String unlock) {
        this.label = label;
        this.durability = durability;
        this.level = level;
        this.from = from;
        this.unlock = unlock;
    }

    public ItemStack item() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.setData(DataComponentTypes.MAX_DAMAGE, durability);
        item.editMeta(m -> {
            m.itemName(Component.text(label + " Sifter Mesh", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("For a Sifter from level " + level, NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, name());
        });
        dev.minecp.Items.model(item, "mesh/" + name().toLowerCase());
        return item;
    }

    /** The mesh this item is, or null (an unknown tag too). */
    static Mesh of(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return null;
        String m = item.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        for (Mesh mesh : values()) if (mesh.name().equals(m)) return mesh;
        return null;
    }

    /** Teaches {@code c} to make this mesh from its one item, once its Unlockables research is done (the mod's crafterrecipes). */
    public Crafter teachTo(Crafter c) {
        c.learn(Crafter.own("mesh_" + name().toLowerCase(), item(), Map.of(from, 1)), 1, s -> s.effect(unlock) > 0);
        return c;
    }
}
