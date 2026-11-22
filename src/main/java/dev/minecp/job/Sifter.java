package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Sifter (the mod's BuildingSifter and EntityAIWorkSifter): it sifts the dirt, gravel, sand and soul sand in its
 * chest through the best mesh there that its level allows, each sift using up the block, wearing the mesh by 1 and
 * giving what the mod's loot table for that mesh and block rolls, up to its daily limit. It asks for nothing: players
 * fill its chest, as the mod's.
 */
public final class Sifter extends Job {
    private static final int SIFT_TICKS = 10;      // the mod's TICK_DELAY: a swing every half second
    private static final int SIFT_STEPS = 51;      // its progress past MAX_LEVEL 50 (no skills): a sift every 51 swings
    private static final int PER_LEVEL = 64;       // BUILDING_LEVEL_MULTIPLIER: level² x 64 a day, no limit at level 5
    private static final int DUMP_CHANCE = 10;     // CHANCE_TO_DUMP_INV: 1 in 10 sifts count as a full action
    private static final int KEEP_MESHES = 4;      // the mod's keepX for meshes
    static final Material[] BLOCKS = {Material.DIRT, Material.GRAVEL, Material.SAND, Material.SOUL_SAND};

    /** One loot table: each item (null: nothing) and its weight, rolled once (the mod's recipes/<mesh>/<block>). */
    record Loot(Material[] items, int[] weights) {
        Material roll() {
            int total = 0;
            for (int w : weights) total += w;
            int r = ThreadLocalRandom.current().nextInt(total);
            for (int i = 0; i < items.length; i++) if ((r -= weights[i]) < 0) return items[i];
            return null;
        }
    }

    static final Map<Mesh, Map<Material, Loot>> LOOT = new EnumMap<>(Mesh.class);

    /** An item (null: nothing) and its weight in a loot table. */
    private record W(Material item, int weight) {}

    private static W w(Material item, int weight) {
        return new W(item, weight);
    }

    private static void loot(Mesh mesh, Material block, W... entries) {
        Material[] items = new Material[entries.length];
        int[] weights = new int[entries.length];
        for (int i = 0; i < entries.length; i++) { items[i] = entries[i].item(); weights[i] = entries[i].weight(); }
        LOOT.computeIfAbsent(mesh, m -> new EnumMap<>(Material.class)).put(block, new Loot(items, weights));
    }

    static {
        Material D = Material.DIRT, G = Material.GRAVEL, S = Material.SAND, SS = Material.SOUL_SAND;
        Material seeds = Material.WHEAT_SEEDS, oak = Material.OAK_SAPLING, birch = Material.BIRCH_SAPLING, spruce = Material.SPRUCE_SAPLING,
                jungle = Material.JUNGLE_SAPLING, dark = Material.DARK_OAK_SAPLING, acacia = Material.ACACIA_SAPLING, carrot = Material.CARROT,
                potato = Material.POTATO, pumpkin = Material.PUMPKIN_SEEDS, melon = Material.MELON_SEEDS, beet = Material.BEETROOT_SEEDS,
                nugget = Material.IRON_NUGGET, flint = Material.FLINT, coal = Material.COAL, redstone = Material.REDSTONE,
                lapis = Material.LAPIS_LAZULI, iron = Material.IRON_INGOT, gold = Material.GOLD_INGOT, emerald = Material.EMERALD,
                diamond = Material.DIAMOND, cactus = Material.CACTUS, cane = Material.SUGAR_CANE, goldNugget = Material.GOLD_NUGGET,
                cocoa = Material.COCOA_BEANS, wart = Material.NETHER_WART, quartz = Material.QUARTZ, glow = Material.GLOWSTONE_DUST,
                blaze = Material.BLAZE_POWDER, magma = Material.MAGMA_CREAM, head = Material.PLAYER_HEAD;
        loot(Mesh.STRING, D, w(null, 71), w(seeds, 25), w(oak, 1), w(birch, 1), w(spruce, 1), w(jungle, 1));
        loot(Mesh.STRING, G, w(null, 85), w(nugget, 5), w(flint, 5), w(coal, 5));
        loot(Mesh.STRING, S, w(null, 95), w(cactus, 2), w(cane, 2));
        loot(Mesh.STRING, SS, w(null, 90), w(wart, 5), w(quartz, 5));
        loot(Mesh.FLINT, D, w(null, 28), w(seeds, 50), w(oak, 5), w(birch, 5), w(spruce, 5), w(jungle, 5), w(carrot, 1), w(potato, 1));
        loot(Mesh.FLINT, G, w(null, 60), w(nugget, 10), w(flint, 10), w(coal, 10), w(redstone, 10));
        loot(Mesh.FLINT, S, w(null, 85), w(cactus, 5), w(cane, 5), w(goldNugget, 5));
        loot(Mesh.FLINT, SS, w(null, 70), w(wart, 10), w(quartz, 10), w(glow, 10));
        loot(Mesh.IRON, D, w(null, 3), w(seeds, 50), w(oak, 10), w(birch, 10), w(spruce, 10), w(jungle, 10),
                w(carrot, 1), w(potato, 1), w(pumpkin, 1), w(melon, 1), w(beet, 1), w(dark, 1), w(acacia, 1));
        loot(Mesh.IRON, G, w(null, 46), w(redstone, 15), w(nugget, 15), w(coal, 15),
                w(lapis, 5), w(iron, 1), w(gold, 1), w(emerald, 1), w(diamond, 1));
        loot(Mesh.IRON, S, w(null, 60), w(cactus, 10), w(cane, 10), w(goldNugget, 10), w(cocoa, 10));
        loot(Mesh.IRON, SS, w(null, 50), w(wart, 10), w(quartz, 10), w(glow, 10), w(blaze, 1), w(magma, 1));
        loot(Mesh.DIAMOND, D, w(null, 5), w(seeds, 25), w(oak, 10), w(birch, 10), w(spruce, 10), w(jungle, 10),
                w(carrot, 5), w(potato, 5), w(pumpkin, 5), w(melon, 5), w(beet, 5), w(dark, 5), w(acacia, 5));
        loot(Mesh.DIAMOND, G, w(null, 40), w(redstone, 20), w(nugget, 20), w(coal, 20),
                w(lapis, 10), w(iron, 2), w(gold, 2), w(emerald, 2), w(diamond, 2));
        loot(Mesh.DIAMOND, S, w(null, 40), w(cactus, 15), w(cane, 15), w(goldNugget, 15), w(cocoa, 15));
        loot(Mesh.DIAMOND, SS, w(null, 40), w(wart, 15), w(quartz, 15), w(glow, 15), w(blaze, 5), w(magma, 5), w(head, 5));
    }

    private int progress;
    private int siftedToday; // saved
    private int today = -1;  // the colony day it counts (saved)

    public Sifter(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** level² x 64 a day; no limit at the top level (the mod's getMaxDailyQuantity). */
    private int dailyLimit() {
        return hut.level >= Hut.MAX_LEVEL ? Integer.MAX_VALUE : hut.level * hut.level * PER_LEVEL;
    }

    private int leftToday() {
        if (today != colony.day) { today = colony.day; siftedToday = 0; } // the mod's onWakeUp
        return dailyLimit() - siftedToday;
    }

    /** The chest slot of the best mesh its level allows (ours: the best first; the mod takes its first recipe that can be made), or -1. */
    private int meshSlot(Inventory chest) {
        int best = -1;
        for (int i = 0; i < chest.getSize(); i++) {
            Mesh m = Mesh.of(chest.getItem(i));
            if (m != null && m.level <= hut.level && (best < 0 || m.ordinal() > Mesh.of(chest.getItem(best)).ordinal())) best = i;
        }
        return best;
    }

    private static Material block(Inventory chest) {
        for (Material b : BLOCKS) if (chest.contains(b)) return b;
        return null;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Sifter: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return;
        if (leftToday() <= 0) { idle(w, "It has sifted all it may today.", 100, hut.block); return; }
        int slot = meshSlot(chest);
        Material block = block(chest);
        if (slot < 0) { idle(w, "Needs a sifter mesh in its chest (crafters make them once research allows).", 100, hut.block); return; }
        if (block == null) { idle(w, "Put dirt, gravel, sand or soul sand in its chest to sift.", 100, hut.block); return; }
        if (!atHut(w)) { w.setStatus("Sifter: working"); goToHut(w); return; }
        note = null;
        w.setStatus("Sifter: sifting");
        w.hold(new ItemStack(block));
        w.swing();
        hut.block.getWorld().playSound(hut.block.getLocation(), Sound.ITEM_LEAD_BREAK, SoundCategory.BLOCKS, 0.5f, 1); // the mod's leash knot break sound, renamed since
        cooldown = SIFT_TICKS;
        if (++progress < SIFT_STEPS) return;
        progress = 0;
        sift(w, chest, slot, block);
    }

    /** One sift: the block is used up, the mesh worn by 1 (gone once worn out), and the loot table rolled. */
    private void sift(Colonist w, Inventory chest, int slot, Material block) {
        ItemStack meshItem = chest.getItem(slot);
        Mesh mesh = Mesh.of(meshItem);
        chest.getItem(chest.first(block)).subtract(); // that stack itself: a renamed one is used up too
        meshItem.editMeta(Damageable.class, d -> d.setDamage(d.getDamage() + 1));
        if (meshItem.getItemMeta() instanceof Damageable d && d.getDamage() >= mesh.durability) chest.setItem(slot, null);
        Material got = LOOT.get(mesh).get(block).roll();
        if (got != null) for (ItemStack over : chest.addItem(new ItemStack(got)).values())
            hut.block.getWorld().dropItemNaturally(hut.block.getLocation().toCenterLocation(), over);
        siftedToday++;
        w.workedLittle(); // the mod's: a small action each sift, a full one 1 time in 10 or when the day's limit is reached
        if (leftToday() <= 0 || ThreadLocalRandom.current().nextInt(DUMP_CHANCE) == 0) w.worked();
    }

    /**
     * With no mesh its level can use, it asks for one, which a crafter makes once research allows (ours: the mod's
     * sifter only tells players it has none, who order the mesh item).
     */
    @Override
    public List<Want> wants() {
        Inventory chest = chest();
        if (hut.level < 1 || chest == null || meshSlot(chest) >= 0) return List.of();
        return List.of(new Want("a sifter mesh", i -> { Mesh m = Mesh.of(i); return m != null && m.level <= hut.level; }, 1));
    }

    /** Keeps what it sifts and a few meshes; what it finds goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (Mesh.of(item) != null) return KEEP_MESHES;
        for (Material b : BLOCKS) if (item.getType() == b) return Integer.MAX_VALUE;
        return 0;
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>();
        if (hut.level == 0) return List.of(Component.text("The sifter starts once level 1 is built.", NamedTextColor.GRAY));
        lines.add(Component.text("Sifts the dirt, gravel, sand and soul sand in its chest through the best mesh there: string from level 1, "
                + "flint from 3, iron from 4, diamond from 5. Meshes come from the Fletcher, Stonemason, Blacksmith and Mechanic once research allows.",
                NamedTextColor.GRAY));
        int limit = dailyLimit();
        leftToday(); // a new day starts the count again
        lines.add(Component.text("Today: " + siftedToday + (limit == Integer.MAX_VALUE ? " (no limit)" : " of " + limit) + ".", NamedTextColor.GRAY));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("sifted_today", siftedToday);
        s.set("today", today);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        siftedToday = s.getInt("sifted_today");
        today = s.getInt("today", -1);
    }
}
