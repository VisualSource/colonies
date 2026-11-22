package dev.minecp.citizen;

import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mod's diseases (its data/minecolonies/colony/diseases): how common each is, and the cure: one of each item,
 * which the sick citizen must have in its own inventory to get well.
 */
public enum Disease {
    INFLUENZA("influenza", 100, Material.CARROT, Material.POTATO),
    MEASLES("measles", 10, Material.POPPY, Material.DANDELION, Material.KELP),
    SMALLPOX("smallpox", 1, Material.HONEY_BOTTLE, Material.GOLDEN_APPLE);

    public final String label;
    final int rarity; // its weight among the diseases
    public final List<Material> cures;

    Disease(String label, int rarity, Material... cures) {
        this.label = label;
        this.rarity = rarity;
        this.cures = List.of(cures);
    }

    /** A disease by weight (the mod's getRandomDisease). */
    public static Disease random() {
        int total = 0;
        for (Disease d : values()) total += d.rarity;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (Disease d : values()) if ((r -= d.rarity) < 0) return d;
        return INFLUENZA;
    }

    /** "a carrot and a potato". */
    public String cureLabel() {
        List<String> names = cures.stream().map(m -> "a " + m.name().toLowerCase().replace('_', ' ')).toList();
        return names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }
}
