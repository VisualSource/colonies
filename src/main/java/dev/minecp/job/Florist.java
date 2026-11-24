package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Flower Shop (the mod's BuildingFlorist, EntityAIWorkFlorist and TileEntityCompostedDirt), the mod's numbers unless
 * noted: it puts compost on its building's composted dirt, which then grows one of its flowers now and then for 5
 * minutes, and cuts what grew with shears. Which flowers: by hut level, less those players leave out in its flower list.
 */
public final class Florist extends Job {
    private static final int DECIDE_TICKS = 200, STEP_TICKS = 20;  // its decide state, and its harvest and compost steps
    private static final int COMPOST_TICKS = 6000;               // TICKER_LIMIT: 300 s of compost
    private static final int COMPOST_ASKED = 16, COMPOST_KEPT = 64; // COMPOST_REQUEST_QTY; it fetches and keeps a stack
    private static final int ACTIONS_PER_LEVEL = 10;             // HARVEST_ACTIONS_TO_DUMP, times its level
    private static final double XP_PER_FLOWER = 2;
    private static final int MAX_DISTANCE = 50;                  // farther from the hut, it walks back first
    /** The mod's minecolonies:florist_flowers tag. */
    private static final Set<Material> FLOWERS = EnumSet.of(Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
            Material.TALL_GRASS, Material.LARGE_FERN, Material.FERN, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
            Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
            Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY);

    /** Flowers players leave out (the mod's flower list: ticked ones aren't grown); saved. */
    public final Set<Material> excluded = EnumSet.noneOf(Material.class);
    private record Composted(Material flower, int until) {}
    private final Map<Block, Composted> composted = new HashMap<>(); // shortcut: not saved, as the mod's; a restart loses the compost on the ground
    private List<Block> spots = List.of();
    private int spotsLevel = -1, actions;
    private Block at; // the spot it is working
    private int breaking; // ticks left breaking a flower

    public Florist(Hut hut, Colony colony) {
        super(hut, colony);
    }

    // ---- what it may grow -----------------------------------------------------------------------------------------

    /** The mod's getPlantablesForBuildingLevel: poppies and dandelions up to level 1, small flowers at 2, all from 3. */
    public static Set<Material> allowed(int level) {
        if (level <= 1) return EnumSet.of(Material.POPPY, Material.DANDELION);
        Set<Material> out = EnumSet.copyOf(FLOWERS);
        if (level == 2) out.removeIf(m -> !Tag.SMALL_FLOWERS.isTagged(m));
        return out;
    }

    /** How many flowers players may leave out at a hut level (the mod's flower list view): none, 1, 1, 5, any. */
    public static int mostExcluded(int level) {
        return level <= 1 ? 0 : level <= 3 ? 1 : level == 4 ? 5 : FLOWERS.size();
    }

    private List<Material> growable() {
        return allowed(hut.level).stream().filter(m -> !excluded.contains(m)).toList();
    }

    /** Its building's composted dirt, from its blueprint (the mod registers each composted dirt block it builds). */
    private List<Block> spots() {
        if (spotsLevel != hut.level) {
            spotsLevel = hut.level;
            List<Block> out = new ArrayList<>();
            BuildOrder o = hut.level < 1 ? null : BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
            if (o != null) o.workSpots.forEach((b, tag) -> { if (tag.equals("compost")) out.add(b); });
            spots = out;
        }
        return spots;
    }

    // ---- the composted dirt ---------------------------------------------------------------------------------------

    /**
     * The mod's composted dirt each second: while its compost lasts it glints, and with a chance of 0.2% less 0.01% a
     * hut level it grows its flower where nothing stands. It can grow again after a harvest until the compost runs out.
     */
    @Override
    public void everySecond() {
        if (composted.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        double percent = 0.2 - hut.level * 0.01;
        var rnd = ThreadLocalRandom.current();
        composted.entrySet().removeIf(e -> {
            Block ground = e.getKey();
            if (!ground.getWorld().isChunkLoaded(ground.getX() >> 4, ground.getZ() >> 4)) return now >= e.getValue().until(); // reading it would load it
            if (now >= e.getValue().until() || !Tag.DIRT.isTagged(ground.getType())) return true;
            Block above = ground.getRelative(BlockFace.UP);
            ground.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, above.getLocation().add(0.5, 0.2, 0.5), 1, 0.3, 0.1, 0.3);
            if (above.getType().isAir() && rnd.nextDouble() * 100 <= percent) grow(above, e.getValue().flower());
            return false;
        });
    }

    private static void grow(Block at, Material flower) {
        var data = flower.createBlockData();
        if (data instanceof Bisected tall) { // sunflowers, lilacs, rose bushes, peonies, tall grass, large ferns: two blocks
            Block top = at.getRelative(BlockFace.UP);
            if (!top.getType().isAir()) return;
            tall.setHalf(Bisected.Half.BOTTOM);
            at.setBlockData(tall, false);
            Bisected upper = (Bisected) flower.createBlockData();
            upper.setHalf(Bisected.Half.TOP);
            top.setBlockData(upper, false);
        } else at.setBlockData(data, false);
    }

    // ---- its work -------------------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Florist: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (at != null) { work(w); return; }
        cooldown = DECIDE_TICKS;
        if (spots().isEmpty()) { idle(w, "Its building has no composted dirt (its structures need converting again: tools/convert_blueprints.py).", DECIDE_TICKS, hut.block); return; }
        if (actions >= ACTIONS_PER_LEVEL * hut.level || bagFull() && loaded()) {
            if (!atHut(w)) { goToHut(w); cooldown = STEP_TICKS; return; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return; }
            actions = 0;
        }
        if (!hasTool(Tool.SHEARS, null)) {
            if (!atHut(w)) { goToHut(w); cooldown = STEP_TICKS; return; }
            if (!fetchTool(Tool.SHEARS, null)) { idle(w, "Needs " + toolLabel(Tool.SHEARS) + " in the hut chest.", 100, hut.block); return; }
        }
        if (w.location().getWorld() != hut.block.getWorld() || w.location().distance(hut.block.getLocation()) > MAX_DISTANCE) { goToHut(w); cooldown = STEP_TICKS; return; }
        if (has(Compost::is) == 0 && inChest(Compost::is) > 0) {
            if (!atHut(w)) { goToHut(w); cooldown = STEP_TICKS; return; }
            fetch(Compost::is, COMPOST_KEPT);
        }
        note = null;
        for (Block b : spots()) // flowers first
            if (FLOWERS.contains(b.getRelative(BlockFace.UP).getType())) { start(w, b); return; }
        if (has(Compost::is) == 0) {
            if (composted.isEmpty()) idle(w, "Needs compost to grow flowers.", DECIDE_TICKS, hut.block);
            else { w.setStatus("Florist: waiting for flowers"); w.wander(hut.block.getLocation().toCenterLocation()); }
            return;
        }
        if (growable().isEmpty()) { idle(w, "Every flower it could grow is left out in its flower list.", DECIDE_TICKS, hut.block); return; }
        for (Block b : spots())
            if (!composted.containsKey(b) && Tag.DIRT.isTagged(b.getType()) && b.getRelative(BlockFace.UP).getType().isAir()) { start(w, b); return; }
        w.setStatus("Florist: waiting for flowers");
        w.wander(hut.block.getLocation().toCenterLocation());
    }

    private void start(Colonist w, Block spot) {
        at = spot;
        breaking = -1;
        cooldown = 1;
        w.setStatus("Florist: gardening");
    }

    /** Walks to the spot, then cuts what stands on it or puts compost on it. */
    private void work(Colonist w) {
        cooldown = STEP_TICKS;
        Block above = at.getRelative(BlockFace.UP);
        if (!w.isBeside(above)) { w.goBeside(above); return; }
        if (FLOWERS.contains(above.getType())) { // harvest: the block's own drops with shears, after its agility's break time
            if (breaking < 0) {
                breaking = breakTime();
                w.hold(tool(Tool.SHEARS));
            }
            w.swing();
            if ((breaking -= STEP_TICKS) > 0) return;
            Block top = above.getRelative(BlockFace.UP);
            if (above.getBlockData() instanceof Bisected && top.getType() == above.getType()) top.setType(Material.AIR, false);
            gather(breakWith(Tool.SHEARS, above, w), w);
            above.getWorld().playSound(above.getLocation(), Sound.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1, 1);
            above.setType(Material.AIR, false);
            experience(w, XP_PER_FLOWER);
            acted(w);
        } else if (above.getType().isAir() && !composted.containsKey(at) && use(Compost::is, 1) == 1) { // compost: the flower picked at random from those it may grow
            List<Material> flowers = growable();
            if (!flowers.isEmpty()) composted.put(at, new Composted(flowers.get(ThreadLocalRandom.current().nextInt(flowers.size())), Bukkit.getCurrentTick() + COMPOST_TICKS));
            at.getWorld().playSound(at.getLocation(), Sound.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 1, 1);
            w.swing();
            acted(w);
        }
        at = null;
        cooldown = STEP_TICKS;
    }

    /** One action done: towards the trip to the hut, and the mod's saturation use. */
    private void acted(Colonist w) {
        w.workedLittle();
        actions++;
    }

    /** The mod's getBlockMiningTime: 10 × (1 + max(0, 5 - 0.1 × agility / 2)) ticks, 60 for the least agile, 10 from 100. */
    private int breakTime() {
        return 10 * (int) (1 + Math.max(0, 5 - 0.1 * skill(1) / 2.0));
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        wantTool(wants, Tool.SHEARS);
        if (has(Compost::is) + inChest(Compost::is) == 0) wants.add(new Want("compost", Compost::is, COMPOST_ASKED));
        return wants;
    }

    /** Shears and a stack of compost stay; the flowers go to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.SHEARS, item, null)) return 1;
        return Compost.is(item) ? COMPOST_KEPT : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return Compost.is(i);
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The florist starts once level 1 is built.", NamedTextColor.GRAY));
        return List.of(Component.text("Puts compost on its building's " + spots().size() + " composted dirt blocks (" + composted.size()
                + " composted now), which grow its flowers for 5 minutes, and cuts them with shears. It grows "
                + growable().size() + " kinds of flower at this level; leave some out in its flower list.", NamedTextColor.GRAY));
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("excluded", excluded.stream().map(Material::name).toList());
        s.set("actions", actions);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String m : s.getStringList("excluded")) { Material x = Material.matchMaterial(m); if (x != null) excluded.add(x); }
        actions = s.getInt("actions");
    }
}
