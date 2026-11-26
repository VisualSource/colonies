package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonyStore;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.block.data.type.SeaPickle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The Plantation (the mod's BuildingPlantation, EntityAIWorkPlanter and its plant modules), the mod's numbers unless
 * noted. It works fields: its building's own plots of sugar cane, cactus and bamboo, and the colony's plantation fields
 * (built from their blueprints), up to half its level rounded up (one more with research), of as many kinds. Each field
 * is worked once a colony day, up to its kind's number of actions: plant where a plot is bare, clear what doesn't
 * belong, harvest what has grown, or bone meal the ground. It is also a crafter (the mod's plantation crafting module),
 * crafting first: paper, books and sugar, and what is made from bamboo. It doesn't eat what its fields grow.
 */
public final class Plantation extends Crafter {
    private static final int STEP_TICKS = 20;        // the mod's TICKS_20 between the planter's steps
    private static final int MAX_PLOTS = 20;         // AbstractPlantationModule.DEFAULT_MAX_PLANTS
    private static final int BONEMEAL_TO_KEEP = 16;  // EntityAIWorkPlanter.BONEMEAL_TO_KEEP
    private static final double REACH = 3.5;         // ours: close enough to work a plot from beside it
    /** The block standing in for the mod's plantation field block (tools/convert_blueprints.py); also its leisure site. */
    public static final Material FIELD_BLOCK = Material.OAK_TRAPDOOR;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};
    private static final BlockFace[] AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    /** How a kind grows, the mod's generic plant modules. */
    enum Shape {
        UP(10),        // UpwardsGrowingPlantModule: plots are the ground, the plant grows up from it
        DOWN(10),      // DownwardsGrowingPlantModule: plots are the ceiling, the plant hangs from it
        SIDE(5),       // TreeSidePlantModule: plots are the air beside the tagged logs
        SHARE(5),      // PercentageHarvestPlantModule: plots are where it grows, a share of them kept grown
        BONEMEAL(1);   // BoneMealedPlantModule: plots are the ground, bone mealed to grow plants on it

        final int actions; // getActionLimit: per field per day

        Shape(int actions) {
            this.actions = actions;
        }
    }

    /** A kind of plant (the mod's plant modules): its field and work tags, how it grows, item, blocks and research. */
    public enum Kind {
        // UpwardsGrowingPlantModule's DEFAULT_MINIMUM_PLANT_LENGTH 3; BambooPlantModule's 6 and BambooStalkBlock.MAX_HEIGHT 16;
        // kelp's and the nether vines' 2 and GrowingPlantHeadBlock.MAX_AGE 25
        SUGAR_CANE("sugar_field", "sugar", Shape.UP, Material.SUGAR_CANE, Material.SUGAR_CANE, Material.SUGAR_CANE, 3, 0, null),
        CACTUS("cactus_field", "cactus", Shape.UP, Material.CACTUS, Material.CACTUS, Material.CACTUS, 3, 0, null),
        BAMBOO("bamboo_field", "bamboo", Shape.UP, Material.BAMBOO, Material.BAMBOO_SAPLING, Material.BAMBOO, 6, 16, Effects.PLANTATION_JUNGLE),
        COCOA("cocoa_field", "cocoa", Shape.SIDE, Material.COCOA_BEANS, Material.COCOA, Material.COCOA, 0, 0, Effects.PLANTATION_JUNGLE),
        VINE("vine_field", "vine", Shape.SHARE, Material.VINE, Material.VINE, Material.VINE, 0, 0, Effects.PLANTATION_JUNGLE),
        KELP("kelp_field", "kelp", Shape.UP, Material.KELP, Material.KELP, Material.KELP_PLANT, 2, 25, Effects.PLANTATION_SEA),
        SEAGRASS("seagrass_field", "seagrass", Shape.BONEMEAL, Material.SEAGRASS, Material.SEAGRASS, Material.TALL_SEAGRASS, 0, 0, Effects.PLANTATION_SEA),
        SEA_PICKLE("seapickle_field", "seapickle", Shape.BONEMEAL, Material.SEA_PICKLE, Material.SEA_PICKLE, Material.SEA_PICKLE, 0, 0, null),
        GLOW_BERRIES("glowb_field", "glowb_vine", Shape.DOWN, Material.GLOW_BERRIES, Material.CAVE_VINES, Material.CAVE_VINES_PLANT, 3, 0, Effects.PLANTATION_EXOTIC),
        WEEPING_VINES("weepv_field", "weepv_vine", Shape.DOWN, Material.WEEPING_VINES, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, 2, 25, Effects.PLANTATION_NETHER),
        TWISTING_VINES("twistv_field", "twistv_vine", Shape.UP, Material.TWISTING_VINES, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT, 2, 25, Effects.PLANTATION_NETHER),
        CRIMSON("crimsonp_field", "crimsonp_ground", Shape.BONEMEAL, Material.CRIMSON_FUNGUS, Material.CRIMSON_FUNGUS, Material.CRIMSON_FUNGUS, 0, 0, Effects.PLANTATION_NETHER),
        WARPED("warpedp_field", "warpedp_ground", Shape.BONEMEAL, Material.WARPED_FUNGUS, Material.WARPED_FUNGUS, Material.WARPED_FUNGUS, 0, 0, Effects.PLANTATION_NETHER);

        final String field, tag; // the mod's field tag (on the field block) and work tag (on its plots)
        final Shape shape;
        final Material item;     // what it plants and asks for
        final Material planted;  // the block a new plant starts as
        final Material body;     // the plant's other block (its stalk, a vine's middle, tall seagrass)
        final int minHeight, maxHeight; // harvested once this long; with a maximum, by chance: its length / the maximum
        final String research;

        Kind(String field, String tag, Shape shape, Material item, Material planted, Material body, int minHeight, int maxHeight, String research) {
            this.field = field;
            this.tag = tag;
            this.shape = shape;
            this.item = item;
            this.planted = planted;
            this.body = body;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.research = research;
        }

        /** What it cuts with: an axe for bamboo and cocoa, shears for vines and (ours: it drops nothing without them; the mod's planter needs no tool) seagrass. */
        Tool tool() {
            return switch (this) {
                case BAMBOO, COCOA -> Tool.AXE;
                case VINE, SEAGRASS -> Tool.SHEARS;
                default -> null;
            };
        }

        boolean grown(Block b) {
            return b.getType() == planted || b.getType() == body;
        }

        /** Kelp, seagrass and sea pickles grow in water, which takes their place when they are cut. */
        boolean water() {
            return this == KELP || this == SEAGRASS || this == SEA_PICKLE;
        }

        /** Whether it plants (all but the bone mealed kinds; sea pickles are planted, then bone mealed). */
        boolean plants() {
            return shape != Shape.BONEMEAL || this == SEA_PICKLE;
        }

        /** What takes a cut plant's place: water for the water plants, else air. */
        Material empty() {
            return water() ? Material.WATER : Material.AIR;
        }

        /** Its Settings switch (the mod's players assign fields from a list). */
        public String setting() {
            return "grow_" + name().toLowerCase();
        }

        /** Its switch's label: "Grow sugar cane", "Grow kelp (needs research)". */
        public String label() {
            return "Grow " + name().toLowerCase().replace('_', ' ') + (research == null ? "" : " (needs research)");
        }

        /** What it asks for to bone meal with. */
        String feed() {
            return water() ? "bone meal" : "bone meal or compost";
        }

        /** At most this many plots (getMaxWorkingPositions): bone mealed fields 121, sea pickles 10, the rest 20. */
        int maxPlots() {
            return this == SEA_PICKLE ? 10 : shape == Shape.BONEMEAL ? 121 : MAX_PLOTS;
        }

        /** Of 100, that a bone mealed field with nothing to harvest is worked today (getPercentageChance). */
        int chance() {
            return this == CRIMSON || this == WARPED ? 35 : 50;
        }

        /** What it bone meals with: plain bone meal under water (the mod's: compost can't grow water plants), else compost too. */
        boolean fertilizer(ItemStack i) {
            return water() ? i != null && i.getType() == Material.BONE_MEAL && i.getPersistentDataContainer().isEmpty() : Compost.fertilizer(i);
        }

        static Kind byField(String field) {
            for (Kind k : values()) if (k.field.equals(field)) return k;
            return null;
        }
    }

    enum Action { PLANT, CLEAR, HARVEST, BONEMEAL }

    /** What to do next on a field, on which block, decided from which plot (looked at again on arrival). */
    record Work(Action act, Block at, Block plot) {}

    private final Map<String, Integer> workedDay = new HashMap<>(); // the colony day each field was last done; saved
    private Colony.PlantField current;
    private Work work;
    private int actions;
    private boolean delivering;
    private List<Colony.PlantField> own = List.of();
    private int ownLevel = -1;

    public Plantation(Hut hut, Colony colony) {
        super(hut, colony, CraftRules.PLANTATION, "paper, books, sugar and things of bamboo");
    }

    /**
     * The fields a built structure brings (the mod's BlockPlantationField.setPlacedBy): one for each kind its field
     * block is tagged with, on the plots tagged for that kind; none if it has no plots.
     */
    public static List<Colony.PlantField> fieldsOf(BuildOrder o) {
        List<Colony.PlantField> out = new ArrayList<>();
        o.plantFields.forEach((block, tags) -> {
            for (String t : tags) {
                Kind k = Kind.byField(t);
                List<Block> plots = k == null ? List.of() : plots(k, o);
                if (!plots.isEmpty()) out.add(new Colony.PlantField(block, k, plots));
            }
        });
        return out;
    }

    /** A kind's plots in a structure (the mod's getValidWorkingPositions): its tagged blocks, cocoa's the air beside them, at most so many. */
    private static List<Block> plots(Kind k, BuildOrder o) {
        List<Block> tagged = new ArrayList<>();
        for (var e : o.workSpots.entrySet()) if (e.getValue().equals(k.tag)) tagged.add(e.getKey());
        if (k.shape == Shape.SIDE) {
            Set<Block> beside = new LinkedHashSet<>();
            for (Block b : tagged) for (BlockFace f : SIDES) if (b.getRelative(f).getType().isAir()) beside.add(b.getRelative(f));
            tagged = new ArrayList<>(beside);
            Collections.shuffle(tagged);
        }
        return List.copyOf(tagged.subList(0, Math.min(tagged.size(), k.maxPlots())));
    }

    /** Its building's own fields at its level now (the mod's BuildingPlantation.updateFields): sugar cane, cactus, bamboo. */
    private List<Colony.PlantField> own() {
        if (ownLevel != hut.level) {
            ownLevel = hut.level;
            List<Colony.PlantField> all = new ArrayList<>();
            BuildOrder o = hut.built(colony.style);
            if (o != null) for (Kind k : List.of(Kind.SUGAR_CANE, Kind.CACTUS, Kind.BAMBOO)) {
                List<Block> plots = plots(k, o);
                if (!plots.isEmpty()) all.add(new Colony.PlantField(hut.block, k, plots));
            }
            own = all;
        }
        return own;
    }

    /** As many kinds at once as half its level rounded up (the mod's getMaxConcurrentPlants). */
    private int allowed() {
        return (hut.level + 1) / 2;
    }

    /** How many fields it works: as many, one more with Crop Rotation (the mod's getMaxExtensionCount). */
    private int room() {
        return allowed() + (colony.research.effect(Effects.PLANTATION_LARGE) > 0 ? 1 : 0);
    }

    /**
     * The fields it works (the mod's PlantationFieldsModule, claiming free fields on its own): plantations, oldest
     * first, each take their building's fields and then the colony's plantation fields as they were built, while there
     * is room: half its level rounded up (one more with the larger-plantation research), of as many kinds, each a kind
     * its settings allow and research has opened.
     */
    List<Colony.PlantField> fields() {
        Set<Colony.PlantField> taken = new HashSet<>();
        for (Job j : colony.jobs) if (j instanceof Plantation p) {
            List<Colony.PlantField> mine = p.claim(taken);
            if (p == this) return mine;
        }
        return List.of();
    }

    private List<Colony.PlantField> claim(Set<Colony.PlantField> taken) {
        List<Colony.PlantField> out = new ArrayList<>();
        if (hut.level < 1) return out;
        int room = room();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        List<Colony.PlantField> all = new ArrayList<>(own());
        all.addAll(colony.plantFields);
        for (Colony.PlantField f : all) {
            Kind k = f.kind();
            if (out.size() >= room) break;
            if (taken.contains(f) || !hut.on(k.setting()) || k.research != null && colony.research.effect(k.research) <= 0) continue;
            if (!kinds.contains(k) && kinds.size() >= allowed()) continue;
            kinds.add(k);
            out.add(f);
            taken.add(f);
        }
        return out;
    }

    private static String key(Colony.PlantField f) {
        return f.kind().field + "@" + ColonyStore.spot(f.block());
    }

    private int worked(Colony.PlantField f) {
        return workedDay.getOrDefault(key(f), -1);
    }

    // ---- what a plot needs (the mod's decideWorkAction and getNextWorkingPosition) --------------------------------

    /** What its plot needs next, or null (the mod's getNextWorkingPosition and decideFieldWork); only actions {@code able} allows. */
    private static Work next(Kind k, List<Block> plots, Predicate<Action> able) {
        Work x = switch (k.shape) {
            case UP, DOWN, SIDE -> {
                for (Block p : plots) {
                    Work w = decide(k, p, true);
                    if (w != null && able.test(w.act)) yield w;
                }
                yield null;
            }
            case SHARE -> share(k, plots);
            case BONEMEAL -> {
                for (Block p : plots) {
                    Work w = boneHarvest(k, p.getRelative(BlockFace.UP)) ? decide(k, p, false) : null;
                    if (w != null && able.test(w.act)) yield w; // ours: a sea pickle plot with none to plant waits, full ones are still picked
                }
                yield ThreadLocalRandom.current().nextInt(100) < k.chance() ? decide(k, plots.get(ThreadLocalRandom.current().nextInt(plots.size())), false) : null;
            }
        };
        return x != null && able.test(x.act) ? x : null;
    }

    /** What a plot needs; {@code chance}: a plant with a top length is harvested by chance (its length / the top), as the mod looks for work. */
    static Work decide(Kind k, Block p, boolean chance) {
        Material empty = k.empty();
        switch (k.shape) {
            case UP, DOWN -> {
                int dir = k.shape == Shape.UP ? 1 : -1;
                Block at = p.getRelative(0, dir, 0);
                if (at.getType() == empty) return new Work(Action.PLANT, at, p);
                if (k.water() && at.getType().isAir()) return null; // ours: a drained plot waits
                if (!k.grown(at)) return new Work(Action.CLEAR, at, p);
                boolean ripe;
                if (k.maxHeight > 0 && chance) {
                    int length = 0;
                    for (int h = k.minHeight; h <= k.maxHeight && k.grown(p.getRelative(0, dir * h, 0)); h++) length = h;
                    ripe = ThreadLocalRandom.current().nextFloat() < length / (float) k.maxHeight;
                } else ripe = k.grown(p.getRelative(0, dir * k.minHeight, 0));
                return ripe ? new Work(Action.HARVEST, p.getRelative(0, 2 * dir, 0), p) : null;
            }
            case SIDE, SHARE -> {
                if (p.getType().isAir()) return new Work(Action.PLANT, p, p);
                if (!k.grown(p)) return new Work(Action.CLEAR, p, p);
                boolean ripe = !(p.getBlockData() instanceof Cocoa c) || c.getAge() >= c.getMaximumAge();
                return ripe ? new Work(Action.HARVEST, p, p) : null;
            }
            default -> { // BONEMEAL: the block above the ground
                Block at = p.getRelative(BlockFace.UP);
                if (k == Kind.SEA_PICKLE && at.getType() == Material.WATER) return new Work(Action.PLANT, at, p); // planted first, then bone mealed
                if (boneHarvest(k, at)) return new Work(Action.HARVEST, at, p);
                if (boneMeal(k, at)) return new Work(Action.BONEMEAL, k == Kind.SEA_PICKLE ? at : p, p);
                return null;
            }
        }
    }

    /** Whether what stands on a bone mealed plot is cut (isValidHarvestBlock): seagrass; a full sea pickle; anything on nylium. */
    private static boolean boneHarvest(Kind k, Block at) {
        if (k == Kind.SEAGRASS) return k.grown(at);
        if (at.getBlockData() instanceof SeaPickle s) return s.getPickles() >= s.getMaximumPickles();
        return !at.getType().isAir();
    }

    /** Whether a bone mealed plot gets bone meal (isValidBonemealLocation): water over seagrass ground, a sea pickle not yet full, air over nylium. */
    private static boolean boneMeal(Kind k, Block at) {
        if (k == Kind.SEAGRASS) return at.getType() == Material.WATER;
        if (at.getBlockData() instanceof SeaPickle s) return s.getPickles() < s.getMaximumPickles();
        return at.getType().isAir();
    }

    /**
     * Vines (PercentageHarvestPlantModule, 20% kept grown): something that doesn't belong is cleared first; with fewer
     * grown than that share, it plants away from the grown ones; with more, it cuts one beside another.
     */
    private static Work share(Kind k, List<Block> plots) {
        List<Block> shuffled = new ArrayList<>(plots);
        Collections.shuffle(shuffled);
        List<Block> grown = new ArrayList<>();
        int least = (int) Math.ceil(0.2 * shuffled.size()); // getMinimumPlantPercentage 20
        for (Block p : shuffled) {
            Work w = decide(k, p, false);
            if (w == null) continue;
            if (w.act == Action.CLEAR) return w;
            if (w.act == Action.HARVEST) grown.add(p);
        }
        if (least > grown.size()) {
            Set<Block> near = new HashSet<>(grown);
            for (Block g : grown) for (BlockFace f : AROUND) near.add(g.getRelative(f));
            for (Block p : shuffled) if (!near.contains(p)) return decide(k, p, false);
            return null;
        }
        if (least < grown.size()) {
            Set<Block> all = new HashSet<>(grown), seen = new HashSet<>();
            for (Block g : grown) {
                if (!seen.add(g)) return new Work(Action.HARVEST, g, g);
                for (BlockFace f : AROUND) if (all.contains(g.getRelative(f)) && !seen.add(g.getRelative(f))) return new Work(Action.HARVEST, g.getRelative(f), g.getRelative(f));
            }
            return new Work(Action.HARVEST, grown.getFirst(), grown.getFirst());
        }
        return null;
    }

    // ---- the worker -------------------------------------------------------------------------------------------

    /**
     * Its fields, when no crafting request is ready (the mod's planter crafts first, as its crafting AI decides): a step
     * a second. @return true: it always does something or waits here.
     */
    @Override
    boolean otherWork(Colonist w, org.bukkit.inventory.Inventory chest) {
        cooldown = STEP_TICKS;
        if (delivering || bagFull() && loaded()) { // the mod dumps after a field's actions
            delivering = true;
            if (!atHut(w)) { w.setStatus("Planter: bringing the harvest"); goToHut(w); return true; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return true; }
            delivering = false;
        }
        List<Colony.PlantField> fields = fields();
        if (fields.isEmpty()) {
            idle(w, own().isEmpty() && colony.plantFields.isEmpty() ? "No fields: build plantation fields (Build Tool: Agriculture, Fields)."
                    : "Nothing it may grow: check its settings and research.", 100, hut.block);
            return true;
        }
        if (current != null && !fields.contains(current)) current = null;
        if (current == null) { // the field not yet done today that was done longest ago
            for (Colony.PlantField f : fields) if (worked(f) < colony.day && (current == null || worked(f) < worked(current))) current = f;
            actions = 0;
            work = null;
        }
        if (current == null) {
            if (loaded()) { delivering = true; return true; }
            idle(w, "Every field has had its work today.", 100, hut.block);
            return true;
        }
        Colony.PlantField f = current;
        if (!f.block().equals(hut.block) && f.block().getType() != FIELD_BLOCK) { // its field block is gone: so is the field
            colony.plantFields.removeIf(x -> x.block().equals(f.block()));
            current = null;
            return true;
        }
        Kind k = f.kind();
        if (work == null) {
            boolean canPlant = has(k.item) + inChest(i -> i.getType() == k.item) > 0; // ours: without it, bare plots wait and the rest are still cut
            boolean canFeed = has(k::fertilizer) + inChest(k::fertilizer) > 0;
            work = next(k, f.plots(), a -> a == Action.PLANT ? canPlant : a != Action.BONEMEAL || canFeed);
            if (work == null) {
                if (!canPlant && k.plants()) note = "Needs " + name(k.item) + " to plant.";
                else if (!canFeed && k.shape == Shape.BONEMEAL) note = "Needs " + k.feed() + ".";
                fieldDone(f);
                return true;
            }
        }
        Action act = work.act;
        if (act == Action.PLANT && has(k.item) == 0) { // the mod goes back for the plant, and asks for it
            if (!atHut(w)) { w.setStatus("Planter: getting " + name(k.item)); goToHut(w); return true; }
            if (fetch(k.item, plantsToKeep(k)) == 0) { note = "Needs " + name(k.item) + " to plant."; fieldDone(f); return true; }
        }
        if (act == Action.BONEMEAL && has(k::fertilizer) == 0) {
            if (!atHut(w)) { w.setStatus("Planter: getting bone meal"); goToHut(w); return true; }
            if (fetch(k::fertilizer, BONEMEAL_TO_KEEP) == 0) { note = "Needs " + k.feed() + "."; fieldDone(f); return true; }
        }
        if (act == Action.HARVEST && k.tool() != null && !hasTool(k.tool(), null)) {
            if (!atHut(w)) { w.setStatus("Planter: getting " + toolLabel(k.tool())); goToHut(w); return true; }
            if (!fetchTool(k.tool(), null)) { note = "Needs " + toolLabel(k.tool()) + " to cut " + name(k.item) + "."; fieldDone(f); return true; }
        }
        note = null;
        w.setStatus("Planter: working");
        Block at = work.at;
        Block stand = k.water() ? surface(at) : at; // the mod walks to the first air above a water plant
        if (w.location().getWorld() != at.getWorld() || w.location().distance(stand.getLocation().toCenterLocation()) > REACH) { w.goBeside(stand); return true; }
        Work now = decide(k, work.plot, false); // the plot may have changed while it walked (a player built there, or cut the plant)
        if (now == null || now.act != act || !now.at.equals(at)) { work = null; return true; }
        Material empty = k.empty();
        switch (act) {
            case PLANT -> {
                use(k.item, 1);
                at.setBlockData(plantData(k, at));
                at.getWorld().playSound(at.getLocation(), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1, 1);
            }
            case CLEAR -> {
                gather(breakWith(Tool.forBlock(at.getType(), false), at, w), w);
                at.setType(empty);
            }
            case HARVEST -> {
                if (k.shape == Shape.UP || k.shape == Shape.DOWN) { // from its far end back to here, so nothing pops off and the stump grows again
                    int dir = k.shape == Shape.UP ? 1 : -1;
                    Block end = at;
                    while (k.grown(end.getRelative(0, dir, 0))) end = end.getRelative(0, dir, 0);
                    for (Block b = end; ; b = b.getRelative(0, -dir, 0)) {
                        gather(breakWith(k.tool(), b, w), w);
                        b.setType(empty, false);
                        if (b.equals(at)) break;
                    }
                    Block stump = at.getRelative(0, -dir, 0);
                    if (stump.getType().name().endsWith("_PLANT")) stump.setType(k.planted, false); // a vine's middle becomes its tip again
                } else {
                    gather(breakWith(k.tool(), at, w), w);
                    if (at.getType() == Material.TALL_SEAGRASS && at.getRelative(BlockFace.UP).getType() == Material.TALL_SEAGRASS) at.getRelative(BlockFace.UP).setType(empty, false);
                    at.setType(empty, false);
                }
                at.getWorld().playSound(at.getLocation(), Sound.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1, 1);
                experience(w, 1); // the mod's XP_PER_HARVEST
            }
            case BONEMEAL -> {
                if (at.applyBoneMeal(BlockFace.UP)) use(k::fertilizer, 1);
            }
        }
        w.swing();
        work = null;
        if (act != Action.CLEAR) { // the mod counts all but clearing (increasesActionCount)
            w.workedLittle(); // decreaseSaturationForContinuousAction
            if (++actions >= k.shape.actions) fieldDone(f);
        }
        return true;
    }

    /** The block a plant starts as: cocoa facing a jungle log beside it, a vine on the sides that hold it (getPlantingBlockState). */
    private static BlockData plantData(Kind k, Block at) {
        BlockData d = k.planted.createBlockData();
        if (d instanceof Cocoa c) for (BlockFace f : SIDES) if (at.getRelative(f).getType() == Material.JUNGLE_LOG) { c.setFacing(f); break; }
        if (k == Kind.VINE && d instanceof MultipleFacing m)
            for (BlockFace f : m.getAllowedFaces()) m.setFace(f, at.getRelative(f).getBlockData().isFaceSturdy(f.getOppositeFace(), BlockSupport.FULL));
        return d;
    }

    /** The first air above a block under water (the mod's KelpPlantModule.getPositionToWalkTo), or the block. */
    private static Block surface(Block b) {
        for (int i = 0; i <= Kind.KELP.maxHeight + 1; i++) if (b.getRelative(0, i, 0).getType().isAir()) return b.getRelative(0, i, 0);
        return b;
    }

    /** This field is done for today (the mod's resetCurrentExtension); the harvest goes to the hut. */
    private void fieldDone(Colony.PlantField f) {
        workedDay.put(key(f), colony.day);
        current = null;
        work = null;
        actions = 0;
        if (loaded()) delivering = true;
    }

    /** A quarter stack of each plant it grows (the mod's getPlantsToRequest). */
    private static int plantsToKeep(Kind k) {
        return (int) Math.ceil(k.item.getMaxStackSize() / 4.0);
    }

    private Set<Kind> kinds() {
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        for (Colony.PlantField f : fields()) out.add(f.kind());
        return out;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>(super.wants()); // a crafter's ingredients
        if (hut.level < 1) return wants;
        boolean fed = false;
        for (Kind k : kinds()) {
            if (k.plants() && has(k.item) + inChest(i -> i.getType() == k.item) == 0) wants.add(new Want(name(k.item), i -> i.getType() == k.item, plantsToKeep(k), 1));
            if (!fed && k.shape == Shape.BONEMEAL && has(k::fertilizer) + inChest(k::fertilizer) == 0) {
                wants.add(new Want(k.feed(), k::fertilizer, BONEMEAL_TO_KEEP, 1));
                fed = true;
            }
            if (k.tool() != null) wantTool(wants, k.tool());
        }
        return wants;
    }

    /** An axe, shears, a quarter stack of each plant it grows and 16 bone meal stay; the harvest goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        int crafting = super.keep(item); // a crafter's ingredients
        if (fits(Tool.AXE, item, null) || fits(Tool.SHEARS, item, null)) return Math.max(crafting, 1);
        for (Kind k : kinds()) {
            if (item.getType() == k.item) return Math.max(crafting, plantsToKeep(k));
            if (k.shape == Shape.BONEMEAL && k.fertilizer(item)) return Math.max(crafting, BONEMEAL_TO_KEEP);
        }
        return crafting;
    }

    /** It doesn't eat what its fields grow (the mod's BuildingPlantation.canEat): glow berries stay for planting. */
    @Override
    public boolean canEat(ItemStack item) {
        for (Kind k : kinds()) if (item.getType() == k.item) return false;
        return true;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The planter starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>(super.info(c)); // what it crafts
        int allowed = allowed(), room = room();
        lines.add(Component.text("Works up to " + room + (room == 1 ? " field" : " fields") + " of up to " + allowed + (allowed == 1 ? " kind" : " kinds")
                + ", each once a day: its building's own plots, then the colony's plantation fields (Build Tool: Agriculture, Fields)."
                + " Choose the kinds in Settings; most need research.", NamedTextColor.GRAY));
        for (Colony.PlantField f : fields()) {
            Block b = f.block();
            lines.add(Component.text(name(f.kind().item) + ": " + f.plots().size() + " plots"
                    + (b.equals(hut.block) ? " here" : " at " + b.getX() + " " + b.getY() + " " + b.getZ())
                    + (worked(f) >= c.day ? ", done today" : "")));
        }
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("worked", workedDay.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String e : s.getStringList("worked")) {
            int eq = e.lastIndexOf('=');
            if (eq > 0) workedDay.put(e.substring(0, eq), Integer.parseInt(e.substring(eq + 1)));
        }
    }
}
