package dev.minecp.job;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** What a trip to the Nether may turn up (the mod's loot tables trip1 to trip5), for {@link NetherWorker}. */
final class NetherTrip {
    private static final int PERIOD_DAYS = 3;

    private NetherTrip() {}

    /** A period's day after one more morning: 1, 2, 3, then 0, a new period (the mod's onWakeUp). */
    static int morning(int day) {
        return day < PERIOD_DAYS ? day + 1 : 0;
    }

    /** Mornings until a new period. */
    static int morningsLeft(int day) {
        return PERIOD_DAYS - day + 1;
    }

    /** A monster it may meet: what a hit of it does, the experience beating it gives, and its weight and quality in the table. */
    enum Foe {
        ZOMBIFIED_PIGLIN(5, 5, 5500, -10), MAGMA_CUBE(3, 4, 300, 10), HOGLIN(3, 5, 500, -1), GHAST(12, 5, 300, -3),
        ENDERMAN(7, 5, 300, -3), BLAZE(5, 10, 100, 1);

        final int damage, xp, weight, quality;

        Foe(int damage, int xp, int weight, int quality) {
            this.damage = damage;
            this.xp = xp;
            this.weight = weight;
            this.quality = quality;
        }
    }

    /** Blocks it may mine, from a hut level. */
    private record Vein(int level, Material block, int weight, int min, int max) {}

    private static final List<Vein> VEINS = List.of(new Vein(1, Material.NETHERRACK, 20, 5, 25), new Vein(1, Material.SOUL_SAND, 10, 1, 7),
            new Vein(1, Material.SOUL_SOIL, 8, 1, 5), new Vein(1, Material.GRAVEL, 10, 1, 10), new Vein(1, Material.NETHER_QUARTZ_ORE, 15, 1, 4),
            new Vein(2, Material.GLOWSTONE, 5, 2, 4), new Vein(2, Material.NETHER_WART, 3, 1, 2), new Vein(2, Material.BROWN_MUSHROOM, 10, 1, 2),
            new Vein(2, Material.RED_MUSHROOM, 10, 1, 2), new Vein(2, Material.CRIMSON_NYLIUM, 5, 1, 1), new Vein(2, Material.CRIMSON_FUNGUS, 10, 0, 1),
            new Vein(2, Material.CRIMSON_STEM, 5, 1, 3), new Vein(3, Material.BASALT, 5, 1, 3), new Vein(3, Material.WARPED_NYLIUM, 5, 1, 1),
            new Vein(3, Material.WARPED_FUNGUS, 10, 0, 1), new Vein(3, Material.WARPED_STEM, 5, 1, 3), new Vein(3, Material.OCHRE_FROGLIGHT, 2, 1, 1),
            new Vein(3, Material.PEARLESCENT_FROGLIGHT, 1, 1, 1), new Vein(3, Material.VERDANT_FROGLIGHT, 1, 1, 1),
            new Vein(4, Material.NETHER_GOLD_ORE, 5, 1, 2), new Vein(4, Material.BLACKSTONE, 5, 1, 3), new Vein(5, Material.ANCIENT_DEBRIS, 1, 1, 2));

    /** One thing on a trip: {@code n} blocks, or a fight ({@code foe}). */
    record Find(Material block, int n, Foe foe) {
        @Override
        public String toString() {
            return foe != null ? foe.name() : block.name() + " " + n;
        }

        static Find parse(String s) {
            int space = s.indexOf(' ');
            return space < 0 ? new Find(null, 0, Foe.valueOf(s)) : new Find(Material.valueOf(s.substring(0, space)), Integer.parseInt(s.substring(space + 1)), null);
        }
    }

    /** A trip's finds in a random order: 3 to 10 blocks and 2 to 6 fights, more with {@code luck} (its effective main skill). */
    static List<Find> finds(int level, int luck, Random rnd) {
        List<Find> out = new ArrayList<>();
        List<Vein> veins = VEINS.stream().filter(v -> v.level <= level).toList();
        int[] weights = veins.stream().mapToInt(Vein::weight).toArray();
        for (int i = rnd.nextInt(3, 11) + (int) (0.3 * luck); i > 0; i--) {
            Vein v = veins.get(pick(weights, rnd));
            int n = rnd.nextInt(v.min, v.max + 1);
            if (n > 0) out.add(new Find(v.block, n, null));
        }
        int[] foes = Arrays.stream(Foe.values()).mapToInt(f -> Math.max(0, f.weight + f.quality * luck)).toArray();
        for (int i = rnd.nextInt(2, 7) + (int) (0.1 * luck); i > 0; i--) out.add(new Find(null, 0, Foe.values()[pick(foes, rnd)]));
        Collections.shuffle(out, rnd);
        return out;
    }

    /** A random index, by weight. */
    static int pick(int[] weights, Random rnd) {
        int at = rnd.nextInt(Arrays.stream(weights).sum()), i = 0;
        while ((at -= weights[i]) >= 0) i++;
        return i;
    }
}
