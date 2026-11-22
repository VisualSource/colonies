package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Plantation (the mod's BuildingPlantation, EntityAIWorkPlanter and its upwards-growing plant modules), the mod's
 * numbers unless noted: its building's own plots of sugar cane, cactus and bamboo (the blueprint's work tags, up to 20
 * of each), as many kinds at once as half its level rounded up. Each kind is a field worked once a colony day, up to 10
 * actions: plant where a plot is bare, clear what doesn't belong, and cut a plant grown tall enough from its second
 * block, leaving the stump to grow again.
 */
public final class Plantation extends Job {
    private static final int STEP_TICKS = 20;     // the mod's TICKS_20 between the planter's steps
    private static final int ACTIONS = 10;        // UpwardsGrowingPlantModule.getActionLimit: per field per day
    private static final int MAX_PLOTS = 20;      // AbstractPlantationModule.DEFAULT_MAX_PLANTS
    private static final double REACH = 3.5;      // ours: close enough to work a plot from beside it

    /** A kind of plant (the mod's sugar cane, cactus and bamboo modules): its block, work tag, harvest heights, tool and research. */
    enum Kind {
        // UpwardsGrowingPlantModule's DEFAULT_MINIMUM_PLANT_LENGTH 3; BambooPlantModule's MIN_HEIGHT 6 and BambooStalkBlock.MAX_HEIGHT 16
        SUGAR_CANE(Material.SUGAR_CANE, Material.SUGAR_CANE, "sugar", 3, 0, null, null, Settings.GROW_SUGAR),
        CACTUS(Material.CACTUS, Material.CACTUS, "cactus", 3, 0, null, null, Settings.GROW_CACTUS),
        BAMBOO(Material.BAMBOO, Material.BAMBOO_SAPLING, "bamboo", 6, 16, Tool.AXE, Effects.PLANTATION_JUNGLE, Settings.GROW_BAMBOO);

        final Material plant;
        final Material planted; // the block a new plant starts as
        final String tag;
        final int minHeight, maxHeight; // harvested once this tall; with a maximum, by chance: its height / the maximum
        final Tool tool;
        final String research;
        final String setting;

        Kind(Material plant, Material planted, String tag, int minHeight, int maxHeight, Tool tool, String research, String setting) {
            this.plant = plant;
            this.planted = planted;
            this.tag = tag;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.tool = tool;
            this.research = research;
            this.setting = setting;
        }

        boolean grown(Block b) {
            return b.getType() == plant || b.getType() == planted;
        }
    }

    private enum Action { PLANT, CLEAR, HARVEST }

    private final Map<Kind, Integer> workedDay = new EnumMap<>(Kind.class); // the colony day each field was last done; saved
    private Kind current;
    private int actions;
    private boolean delivering;
    private Map<Kind, List<Block>> plots = Map.of();
    private int plotsLevel = -1;

    public Plantation(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Its building's plots of each kind at its level now (the ground under each plant), at most 20 each. */
    private Map<Kind, List<Block>> plots() {
        if (plotsLevel != hut.level) {
            plotsLevel = hut.level;
            Map<Kind, List<Block>> all = new EnumMap<>(Kind.class);
            BuildOrder o = hut.level < 1 ? null : BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
            if (o != null) o.plants.forEach((b, tag) -> {
                for (Kind k : Kind.values())
                    if (k.tag.equals(tag) && all.computeIfAbsent(k, x -> new ArrayList<>()).size() < MAX_PLOTS) all.get(k).add(b);
            });
            plots = all;
        }
        return plots;
    }

    /**
     * How many kinds it grows at once: half its level rounded up (the mod's getMaxConcurrentPlants; its larger-plantation
     * research adds a field, not a kind, and MineCP has no fields beyond the building's own).
     */
    private int allowed() {
        return (hut.level + 1) / 2;
    }

    /** As many kinds as it may grow, of those its building has, its settings allow and research has opened. */
    List<Kind> kinds() {
        List<Kind> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            if (out.size() >= allowed()) break;
            if (plots().containsKey(k) && hut.on(k.setting) && (k.research == null || colony.research.effect(k.research) > 0)) out.add(k);
        }
        return out;
    }

    /** What a plot needs (the mod's decideWorkAction as it looks for work: bamboo is cut by chance), or null. */
    private static Action action(Kind k, Block ground) {
        Block above = ground.getRelative(BlockFace.UP);
        if (above.getType().isAir()) return Action.PLANT;
        if (!k.grown(above)) return Action.CLEAR;
        if (k.maxHeight > 0) {
            int height = 0;
            for (int h = k.minHeight; h <= k.maxHeight && k.grown(ground.getRelative(0, h, 0)); h++) height = h;
            return ThreadLocalRandom.current().nextFloat() < height / (float) k.maxHeight ? Action.HARVEST : null;
        }
        return k.grown(ground.getRelative(0, k.minHeight, 0)) ? Action.HARVEST : null;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Planter: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        cooldown = STEP_TICKS;
        if (delivering || bagFull() && loaded()) { // the mod dumps after a field's actions
            delivering = true;
            if (!atHut(w)) { w.setStatus("Planter: bringing the harvest"); goToHut(w); return; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return; }
            delivering = false;
        }
        List<Kind> kinds = kinds();
        if (kinds.isEmpty()) { idle(w, plots().isEmpty() ? "Its building has no plots." : "Nothing to grow: check its settings (bamboo needs research).", 100, hut.block); return; }
        if (current != null && !kinds.contains(current)) current = null;
        if (current == null) { // the field not yet done today that was done longest ago
            for (Kind k : kinds) if (workedDay.getOrDefault(k, -1) < colony.day && (current == null || workedDay.getOrDefault(k, -1) < workedDay.getOrDefault(current, -1))) current = k;
            actions = 0;
        }
        if (current == null) {
            if (loaded()) { delivering = true; return; }
            idle(w, "Every field has had its work today.", 100, hut.block);
            return;
        }
        Kind k = current;
        Block plot = null;
        Action act = null;
        boolean canPlant = has(k.plant) + inChest(i -> i.getType() == k.plant) > 0; // ours: without one, bare plots wait and the rest are still cut
        for (Block b : plots().get(k)) if ((act = action(k, b)) != null && (act != Action.PLANT || canPlant)) { plot = b; break; }
        if (plot == null) { fieldDone(k); return; }
        if (act == Action.PLANT && has(k.plant) == 0) { // the mod goes back for the plant, and asks for it
            if (!atHut(w)) { w.setStatus("Planter: getting " + name(k.plant)); goToHut(w); return; }
            if (fetch(k.plant, plantsToKeep(k)) == 0) { note = "Needs " + name(k.plant) + " to plant."; fieldDone(k); return; }
        }
        if (act == Action.HARVEST && k.tool != null && !hasTool(k.tool, null)) {
            if (!atHut(w)) { w.setStatus("Planter: getting " + toolLabel(k.tool)); goToHut(w); return; }
            if (!fetchTool(k.tool, null)) { note = "Needs " + toolLabel(k.tool) + " to cut " + name(k.plant) + "."; fieldDone(k); return; }
        }
        note = null;
        w.setStatus("Planter: working");
        Block above = plot.getRelative(BlockFace.UP);
        if (w.location().getWorld() != plot.getWorld() || w.location().distance(above.getLocation().toCenterLocation()) > REACH) { w.goBeside(above); return; }
        switch (act) {
            case PLANT -> {
                use(k.plant, 1);
                above.setType(k.planted);
                above.getWorld().playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1, 1);
            }
            case CLEAR -> {
                gather(breakWith(Tool.forBlock(above.getType(), false), above, w), w);
                above.setType(Material.AIR);
            }
            case HARVEST -> { // from the top down to its second block, so nothing pops off and the stump grows again
                Block top = plot.getRelative(0, 2, 0);
                while (k.grown(top.getRelative(BlockFace.UP))) top = top.getRelative(BlockFace.UP);
                for (Block b = top; b.getY() >= plot.getY() + 2; b = b.getRelative(BlockFace.DOWN)) {
                    gather(breakWith(k.tool, b, w), w);
                    b.setType(Material.AIR, false);
                }
                above.getWorld().playSound(above.getLocation(), Sound.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1, 1);
            }
        }
        w.swing();
        w.workedLittle(); // the mod's decreaseSaturationForContinuousAction per action
        if (++actions >= ACTIONS) fieldDone(k);
    }

    /** This field is done for today (the mod's resetCurrentExtension); the harvest goes to the hut. */
    private void fieldDone(Kind k) {
        workedDay.put(k, colony.day);
        current = null;
        actions = 0;
        if (loaded()) delivering = true;
    }

    /** A quarter stack of each plant it grows (the mod's getPlantsToRequest). */
    private static int plantsToKeep(Kind k) {
        return (int) Math.ceil(k.plant.getMaxStackSize() / 4.0);
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        for (Kind k : kinds()) {
            if (has(k.plant) + inChest(i -> i.getType() == k.plant) == 0) wants.add(new Want(name(k.plant), i -> i.getType() == k.plant, plantsToKeep(k), 1));
            if (k.tool != null) wantTool(wants, k.tool);
        }
        return wants;
    }

    /** An axe, and a quarter stack of each plant it grows, stay; the harvest goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.AXE, item, null)) return 1;
        for (Kind k : kinds()) if (item.getType() == k.plant) return plantsToKeep(k);
        return 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The planter starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>();
        int allowed = allowed();
        lines.add(Component.text("Grows up to " + allowed + (allowed == 1 ? " kind" : " kinds") + " of plant at a time on its building's plots"
                + " (each kind worked once a day): sugar cane, cactus, and bamboo once researched. Choose them in Settings.", NamedTextColor.GRAY));
        for (Kind k : kinds()) lines.add(Component.text(name(k.plant) + ": " + plots().get(k).size() + " plots"
                + (workedDay.getOrDefault(k, -1) >= c.day ? ", done today" : "")));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        workedDay.forEach((k, d) -> s.set("worked." + k.name(), d));
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        var ws = s.getConfigurationSection("worked");
        if (ws != null) for (String k : ws.getKeys(false)) workedDay.put(Kind.valueOf(k), ws.getInt(k));
    }
}
