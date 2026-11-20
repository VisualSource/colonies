package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonyStore;
import dev.minecp.colony.Hut;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Fisherman's Hut (the mod's EntityAIWorkFisherman): finds ponds of water 5 by 5 and 2 deep within 100 blocks,
 * remembers up to 20, stands on the shore and fishes with a rod from its hut, and brings its catch back after 10.
 * Ours: no bobber (a vanilla bobber needs a player); it casts, waits the mod's bite time and reels in.
 */
public final class Fisherman extends Job {
    private static final int MAX_RANGE = 100, POND_RADIUS = 2, POND_DEPTH = 2, MAX_PONDS = 20;
    private static final int NEAR_POND = 7;       // a new pond must be farther than this (manhattan) from a known one
    private static final int STAND_RANGE = 10, TO_WATER = 3; // the shore spot: within 10 of the pond, within 3 of water
    private static final int MAX_CATCHES = 10;    // the mod's MAX_FISHES_IN_INV
    private static final double NEW_POND = 0.05;  // the mod's CHANCE_NEW_POND, after each catch
    private static final int FAIL_TIMEOUT = 100;  // the mod's FISHING_TIMEOUT: 5 AI ticks of a second
    private static final int SEARCH_STEP = 2;     // ours: every other column is checked for a pond

    /** A pond: its middle water block and where the fisherman stands. */
    private record Pond(Block water, Block stand) {}

    private enum State { FIND, TO_POND, CAST, WAIT, TO_HUT }

    private State state = State.FIND;
    private final List<Pond> ponds = new ArrayList<>();
    private Pond pond;
    private boolean searchFailed;
    private int catches;

    public Fisherman(Hut hut, Colony colony) {
        super(hut);
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        wantTool(wants, Tool.ROD);
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        return fits(Tool.ROD, item, null) ? Integer.MAX_VALUE : 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The fisherman starts once level 1 is built.", NamedTextColor.GRAY));
        return List.of(Component.text("Fishes in ponds (water 5 by 5 and 2 deep) within " + MAX_RANGE + " blocks; knows "
                + ponds.size() + " of up to " + MAX_PONDS + ".", NamedTextColor.GRAY));
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Fisherman: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        switch (state) {
            case FIND -> {
                if (catches >= MAX_CATCHES || !load.isEmpty() && catches == 0) { state = State.TO_HUT; return; }
                if (!hasTool(Tool.ROD, null)) {
                    if (!atHut(w)) { goToHut(w); return; }
                    if (!fetchTool(Tool.ROD, null)) {
                        note = "Needs " + toolLabel(Tool.ROD) + " in the hut chest.";
                        w.setStatus("Fisherman: idle");
                        cooldown = 100;
                        return;
                    }
                }
                if (pond == null) pond = choosePond();
                if (pond == null) {
                    note = "No pond (water 5 by 5 and 2 deep, with a shore to stand on) within " + MAX_RANGE + " blocks.";
                    w.setStatus("Fisherman: idle");
                    w.wander(hut.block.getLocation().toCenterLocation());
                    searchFailed = false; // look again after the pause
                    cooldown = 1200; // shortcut: a full search can read many blocks in one tick; a minute apart, spread it over ticks if it lags
                    return;
                }
                note = null;
                w.setStatus("Fisherman: working");
                state = State.TO_POND;
            }
            case TO_POND -> {
                if (!isPond(pond.water) || !pond.stand.isPassable()) { forget(); return; }
                if (!w.isBeside(pond.stand)) { w.goBeside(pond.stand); return; }
                state = State.CAST;
            }
            case CAST -> {
                if (!hasTool(Tool.ROD, null)) { state = State.FIND; return; } // it broke
                if (!w.isBeside(pond.stand)) { state = State.TO_POND; return; } // back from bed, a meal or the rain
                w.hold(tools.get(Tool.ROD));
                w.swing();
                if (failed()) { cooldown = FAIL_TIMEOUT; return; }
                var rnd = ThreadLocalRandom.current();
                Location at = pond.water.getLocation().toCenterLocation();
                at.getWorld().playSound(w.location(), Sound.ENTITY_FISHING_BOBBER_THROW, SoundCategory.NEUTRAL, 0.5f, 0.4f);
                // The mod's bobber: a bite after 1060..1300 ticks less 100 per lure speed (5 unskilled, no Lure), then 20..80 to bite.
                int lure = 5 + tools.get(Tool.ROD).getEnchantmentLevel(Enchantment.LURE);
                cooldown = Math.max(5, rnd.nextInt(1060, 1301) - lure * 100) + rnd.nextInt(20, 81);
                state = State.WAIT;
            }
            case WAIT -> {
                if (!w.isBeside(pond.stand)) { state = State.TO_POND; return; } // called away mid-cast: the fish is lost
                Location at = pond.water.getLocation().toCenterLocation().add(0, 0.5, 0);
                at.getWorld().spawnParticle(Particle.SPLASH, at, 10, 0.2, 0, 0.2, 0);
                at.getWorld().playSound(at, Sound.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 0.25f, 1);
                w.swing();
                state = State.CAST;
                if (failed()) { cooldown = FAIL_TIMEOUT; return; } // it got away
                load.addAll(catchFrom(pond.water));
                wear(Tool.ROD, w);
                catches++;
                cooldown = 20;
                if (ThreadLocalRandom.current().nextDouble() < NEW_POND) pond = null;
                if (catches >= MAX_CATCHES || pond == null) state = State.FIND;
            }
            case TO_HUT -> {
                if (!atHut(w)) { goToHut(w); return; }
                if (!deliver()) {
                    note = "The hut chest is full.";
                    w.setStatus("Fisherman: idle");
                    cooldown = 100;
                    return;
                }
                catches = 0;
                state = State.FIND;
            }
        }
    }

    /** The mod's roll for casting and reeling in: nextInt(10 + skill / 5) <= 2, so 3 in 10 for us (no skills). */
    private static boolean failed() {
        return ThreadLocalRandom.current().nextInt(10) <= 2;
    }

    private void forget() {
        ponds.remove(pond);
        pond = null;
        state = State.FIND;
    }

    /** A new pond while it knows fewer than 20 and the last search found one, else one it knows, at random. */
    private Pond choosePond() {
        if (ponds.size() < MAX_PONDS && !searchFailed) {
            Pond p = search();
            if (p != null) { ponds.add(p); return p; }
            searchFailed = true;
        }
        return ponds.isEmpty() ? null : ponds.get(ThreadLocalRandom.current().nextInt(ponds.size()));
    }

    /** The nearest pond to the hut, in rings out to 100 blocks, through loaded chunks only. */
    private Pond search() {
        Block hutBlock = hut.block;
        World w = hutBlock.getWorld();
        for (int r = 0; r <= MAX_RANGE; r += SEARCH_STEP)
            for (int dx = -r; dx <= r; dx += SEARCH_STEP) for (int dz = -r; dz <= r; dz += SEARCH_STEP) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // the ring only
                int x = hutBlock.getX() + dx, z = hutBlock.getZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block top = w.getHighestBlockAt(x, z);
                if (!isPond(top) || known(top)) continue;
                Block stand = shore(top);
                if (stand != null) return new Pond(top, stand);
            }
        return null;
    }

    private boolean known(Block water) {
        for (Pond p : ponds)
            if (Math.abs(p.water.getX() - water.getX()) + Math.abs(p.water.getY() - water.getY()) + Math.abs(p.water.getZ() - water.getZ()) <= NEAR_POND)
                return true;
        return false;
    }

    /** The mod's Pond.checkPond: 5 by 5 of water, each column 2 deep (flowing water counts). */
    private static boolean isPond(Block middle) {
        for (int dx = -POND_RADIUS; dx <= POND_RADIUS; dx++) for (int dz = -POND_RADIUS; dz <= POND_RADIUS; dz++)
            for (int dy = 0; dy < POND_DEPTH; dy++)
                if (middle.getRelative(dx, -dy, dz).getType() != Material.WATER) return false;
        return middle.getRelative(0, 1, 0).isEmpty();
    }

    /** A dry spot to stand on within 10 of the pond and 3 of its water, nearest the pond. */
    private static Block shore(Block water) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        World w = water.getWorld();
        for (int dx = -STAND_RANGE; dx <= STAND_RANGE; dx++) for (int dz = -STAND_RANGE; dz <= STAND_RANGE; dz++) {
            if (Math.abs(dx) + Math.abs(dz) > STAND_RANGE) continue;
            int x = water.getX() + dx, z = water.getZ() + dz;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block ground = w.getHighestBlockAt(x, z);
            if (!ground.getType().isSolid() || Math.abs(ground.getY() - water.getY()) > 2) continue;
            Block stand = ground.getRelative(0, 1, 0);
            if (!stand.isPassable() || !stand.getRelative(0, 1, 0).isPassable() || !nearWater(stand)) continue;
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = stand; }
        }
        return best;
    }

    private static boolean nearWater(Block stand) {
        for (int dx = -TO_WATER; dx <= TO_WATER; dx++) for (int dz = -TO_WATER; dz <= TO_WATER; dz++)
            if (stand.getRelative(dx, -1, dz).getType() == Material.WATER) return true;
        return false;
    }

    // ---- the catch ----------------------------------------------------------------------------------------------

    /**
     * The mod's fisherman loot: junk 10, fish 85 and (only in an ocean: no research here) treasure 5, with vanilla's
     * quality per Luck of the Sea level; each from vanilla's fishing tables. From hut level 3 a bonus: prismarine shard
     * or crystals, 25 in 1000 each, and from level 4 a sponge, 1 in 1000.
     */
    private List<ItemStack> catchFrom(Block water) {
        var rnd = ThreadLocalRandom.current();
        int luck = tools.containsKey(Tool.ROD) ? tools.get(Tool.ROD).getEnchantmentLevel(Enchantment.LUCK_OF_THE_SEA) : 0;
        boolean ocean = water.getWorld().getBiome(water.getLocation()).getKey().getKey().contains("ocean");
        int junk = Math.max(0, 10 - 2 * luck), fish = Math.max(0, 85 - luck), treasure = ocean ? 5 + 2 * luck : 0;
        int roll = rnd.nextInt(junk + fish + treasure);
        List<ItemStack> out = new ArrayList<>();
        out.add(roll < junk ? junk() : roll < junk + fish ? fish() : treasure());
        if (hut.level >= 3) {
            int bonus = rnd.nextInt(1000);
            if (bonus < 25) out.add(new ItemStack(Material.PRISMARINE_SHARD));
            else if (bonus < 50) out.add(new ItemStack(Material.PRISMARINE_CRYSTALS));
            else if (bonus < 51 && hut.level >= 4) out.add(new ItemStack(Material.SPONGE));
        }
        return out;
    }

    /** Vanilla's gameplay/fishing/fish table. */
    private static ItemStack fish() {
        int r = ThreadLocalRandom.current().nextInt(100);
        return new ItemStack(r < 60 ? Material.COD : r < 85 ? Material.SALMON : r < 87 ? Material.TROPICAL_FISH : Material.PUFFERFISH);
    }

    /** Vanilla's gameplay/fishing/junk table (bamboo, jungle only there, left out). */
    private static ItemStack junk() {
        Object[] table = {Material.LILY_PAD, 17, Material.LEATHER_BOOTS, 10, Material.LEATHER, 10, Material.BONE, 10,
                Material.POTION, 10, Material.STRING, 5, Material.FISHING_ROD, 2, Material.BOWL, 10, Material.STICK, 5,
                Material.INK_SAC, 1, Material.TRIPWIRE_HOOK, 10, Material.ROTTEN_FLESH, 10};
        var rnd = ThreadLocalRandom.current();
        int roll = rnd.nextInt(100);
        Material m = Material.STICK;
        for (int i = 0; i < table.length; i += 2) if ((roll -= (int) table[i + 1]) < 0) { m = (Material) table[i]; break; }
        ItemStack item = new ItemStack(m);
        if (m == Material.POTION) item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents().potion(PotionType.WATER).build());
        if (m == Material.LEATHER_BOOTS || m == Material.FISHING_ROD) damage(item, 0, 0.9);
        if (m == Material.INK_SAC) item.setAmount(10);
        return item;
    }

    /** Vanilla's gameplay/fishing/treasure table: enchanted at level 30, treasure enchantments allowed. */
    private static ItemStack treasure() {
        Material[] table = {Material.NAME_TAG, Material.SADDLE, Material.BOW, Material.FISHING_ROD, Material.BOOK, Material.NAUTILUS_SHELL};
        Material m = table[ThreadLocalRandom.current().nextInt(table.length)];
        ItemStack item = new ItemStack(m);
        if (m == Material.BOW || m == Material.FISHING_ROD) damage(item, 0, 0.25);
        if (m == Material.BOW || m == Material.FISHING_ROD || m == Material.BOOK)
            item = Bukkit.getItemFactory().enchantWithLevels(item, 30, true, ThreadLocalRandom.current());
        return item;
    }

    private static void damage(ItemStack item, double min, double max) {
        int most = item.getType().getMaxDurability();
        item.setData(DataComponentTypes.DAMAGE, (int) (most * ThreadLocalRandom.current().nextDouble(min, max)));
    }

    // ---- saving ---------------------------------------------------------------------------------------------------

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("catches", catches);
        for (int i = 0; i < ponds.size(); i++) {
            ColonyStore.putLocation(s, "ponds." + i + ".water", ponds.get(i).water.getLocation());
            ColonyStore.putLocation(s, "ponds." + i + ".stand", ponds.get(i).stand.getLocation());
        }
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        catches = s.getInt("catches");
        var ps = s.getConfigurationSection("ponds");
        if (ps != null) for (String k : ps.getKeys(false)) {
            Location water = ColonyStore.getLocation(ps, k + ".water"), stand = ColonyStore.getLocation(ps, k + ".stand");
            if (water != null && stand != null) ponds.add(new Pond(water.getBlock(), stand.getBlock()));
        }
    }
}
