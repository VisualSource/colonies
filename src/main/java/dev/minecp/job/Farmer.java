package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Works the colony's fields: tills soil with a hoe, plants the crop chosen by the seed in each field's barrel,
 * harvests ripe crops, and brings the harvest to the hut chest. Seeds to plant come from what it harvested,
 * then from the hut chest. A Farmer's Hut works one field per level.
 */
public final class Farmer extends Job {
    /** A field is the soil around its block, at the level the block stands on, up to this far out each way (as in the mod). */
    public static final int MAX_EXTENT = 5;
    /** Seed item -> crop block, for the crops a farmer plants. */
    public static final Map<Material, Material> CROPS = Map.of(Material.WHEAT_SEEDS, Material.WHEAT, Material.CARROT, Material.CARROTS,
            Material.POTATO, Material.POTATOES, Material.BEETROOT_SEEDS, Material.BEETROOTS,
            Material.PUMPKIN_SEEDS, Material.PUMPKIN_STEM, Material.MELON_SEEDS, Material.MELON_STEM);
    /** Stems -> the fruit they grow beside them, which is what gets harvested. */
    private static final Map<Material, Material> FRUIT = Map.of(Material.PUMPKIN_STEM, Material.PUMPKIN,
            Material.MELON_STEM, Material.MELON, Material.ATTACHED_PUMPKIN_STEM, Material.PUMPKIN,
            Material.ATTACHED_MELON_STEM, Material.MELON);

    /** What the player sees as the crop of a seed: the fruit for stems, else the crop. */
    public static Material produce(Material seed) {
        Material crop = CROPS.get(seed);
        return FRUIT.getOrDefault(crop, crop);
    }
    private static final Set<Material> TILLABLE = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.DIRT_PATH);
    // Ours, not the mod's:
    private static final double REACH = 4;
    private static final int DELIVER_AT = 128; // items carried before a trip to the hut
    private static final int SEEDS_PER_TRIP = 64;

    private enum Kind { TILL, PLANT, HARVEST } // a field's stages, in the order the mod cycles them
    private record Task(Kind kind, Block soil, Material seed) {
        Block crop() { return soil.getRelative(0, 1, 0); }
    }

    private Hut current;      // the field being worked today
    private int skipped;      // its stages passed over with nothing to do
    private boolean didWork;  // whether it did this field's stage
    private boolean delivering;
    private final Set<Block> fertilized = new java.util.HashSet<>(); // fertilized this harvest pass: once each, as the mod's one pass over the field

    public Farmer(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** The seed the field is planted with, chosen in its menu; null if none. */
    public static Material seedOf(Hut field) {
        return field.seed;
    }

    private static final int SURFACE_DEPTH = 5; // how far up or down a field follows the ground, as in the mod

    /**
     * The ground of one plot, as the mod finds it: starting at the level below the field block, up to the top of
     * solid ground, or down to the first solid block, at most {@link #SURFACE_DEPTH} either way. Pumpkins, melons
     * and cobwebs aren't ground; water is (and is then not soil). @return null if there is none in range.
     */
    static Block surface(Block start) {
        Block b = start;
        for (int depth = 0; Math.abs(depth) <= SURFACE_DEPTH; ) {
            Material m = b.getType();
            boolean ground = (m.isSolid() && m != Material.PUMPKIN && m != Material.MELON && m != Material.COBWEB) || b.isLiquid();
            if (ground) {
                if (depth < 0) return b;
                b = b.getRelative(0, 1, 0);
                depth++;
            } else {
                if (depth > 0) return b.getRelative(0, -1, 0);
                b = b.getRelative(0, -1, 0);
                depth--;
            }
        }
        return null;
    }

    /** The ground of the field's plot at this offset from the field block, or null. */
    public static Block plot(Hut field, int dx, int dz) {
        return surface(field.block.getRelative(dx, -1, dz));
    }

    /** Soil a farmer can farm: what a hoe tills, or farmland already. */
    static boolean isSoil(Material m) {
        return m == Material.FARMLAND || TILLABLE.contains(m);
    }

    /** {usable, total} plots of the field: soil with room above it (or a crop already). */
    public static int[] plots(Hut field) {
        int usable = 0, total = 0;
        int[] e = field.extent;
        for (int dx = -e[3]; dx <= e[1]; dx++) for (int dz = -e[0]; dz <= e[2]; dz++) {
            if (dx == 0 && dz == 0) continue;
            total++;
            Block soil = plot(field, dx, dz);
            if (soil == null) continue;
            Block above = soil.getRelative(0, 1, 0);
            if (isSoil(soil.getType()) && (above.isEmpty() || CROPS.containsValue(above.getType()) || FRUIT.containsValue(above.getType())
                    || FRUIT.containsKey(above.getType()) || (above.isReplaceable() && !above.isLiquid()))) usable++;
        }
        return new int[]{usable, total};
    }

    @Override
    public double hungerFactor() {
        return 1.2;
    }

    private static final int SEED_STOCK = 64; // ours: seeds of each field's crop it keeps in the hut chest

    @Override
    public List<Want> wants() {
        List<Want> wants = new java.util.ArrayList<>();
        if (hut.level < 1) return wants;
        for (Material seed : seeds()) {
            int have = inChest(i -> i.getType() == seed) + has(seed);
            // Ask once below half, for a full stock: not a courier trip for every few seeds planted.
            if (have < SEED_STOCK / 2) wants.add(new Want(name(seed), i -> i.getType() == seed, SEED_STOCK - have, 1)); // minimum 1, as the mod
        }
        wantTool(wants, Tool.HOE);
        // The mod's: with its fertilize setting (on by default), a stack of compost or bone meal once it has none
        if (fertilizes() && has(Compost::fertilizer) + inChest(Compost::fertilizer) <= 0)
            wants.add(new Want("compost or bone meal", Compost::fertilizer, FERTILIZER_STACK, 1));
        return wants;
    }

    private static final int FERTILIZER_STACK = 64; // the mod's STACKSIZE: asked for and taken; ours: kept in the chest too

    private boolean fertilizes() {
        return hut.on(dev.minecp.colony.Settings.FERTILIZE);
    }

    /** Its fertilizer stays with it when it hands in the harvest. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        return Compost.fertilizer(i);
    }

    /** Hoes and a stock of seeds stay; the harvest goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.HOE, item, null)) return Integer.MAX_VALUE;
        if (Compost.fertilizer(item)) return FERTILIZER_STACK;
        return seeds().contains(item.getType()) ? SEED_STOCK : 0;
    }

    private Set<Material> seeds() {
        Set<Material> seeds = EnumSet.noneOf(Material.class);
        for (Hut f : colony.fieldsOf(this)) { Material s = seedOf(f); if (s != null) seeds.add(s); }
        return seeds;
    }

    @Override
    public List<Component> info(Colony c) {
        return List.of(Component.text(hut.level == 0 ? "The farmer starts once level 1 is built."
                : "Works " + c.fieldsOf(this).size() + " of up to " + hut.level + " fields (one per level). Seeds to plant go in this chest.",
                NamedTextColor.GRAY));
    }

    private int actionTicks() {
        return Math.max(4, 16 - 2 * hut.level);
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Farmer: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (delivering || holding().values().stream().mapToInt(Integer::intValue).sum() >= DELIVER_AT || bagFull() && loaded()) {
            delivering = true;
            if (!atHut(w)) { goToHut(w); return; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return; }
            delivering = false;
        }
        if (has(Compost::fertilizer) == 0 && inChest(Compost::fertilizer) > 0) { // the mod gathers a stack first, setting or not
            if (!atHut(w)) { w.setStatus("Farmer: getting fertilizer"); goToHut(w); return; }
            fetch(Compost::fertilizer, FERTILIZER_STACK);
        }
        List<Hut> fields = colony.fieldsOf(this);
        if (fields.size() == Hut.MAX_LEVEL) colony.milestones.maxFields(); // the mod: as many fields as the hut's top level
        if (fields.isEmpty()) {
            idle(w, "No field to work: place a Field near the hut (one per hut level).", 100, hut.block);
            return;
        }

        // One field at a time, one action (till, plant or harvest all of it) per field per colony day, as in the mod.
        if (current != null && !fields.contains(current)) current = null;
        if (current == null) {
            for (Hut f : fields) if (f.workedDay < colony.day && (current == null || f.workedDay < current.workedDay)) current = f;
            skipped = 0;
            didWork = false;
            fertilized.clear();
        }
        if (current == null) {
            if (loaded()) { delivering = true; return; } // done for the day: drop off the harvest
            idle(w, "Every field has had its work today.", 100, hut.block);
            return;
        }
        Hut field = current;
        Kind stage = Kind.values()[field.stage % 3];
        Material seed = seedOf(field);
        if (seed == null) { idle(w, "Choose a crop in each field's menu.", 100, hut.block); current = null; field.workedDay = colony.day; return; }
        Inventory chest = chest();
        boolean fertilizing = has(Compost::fertilizer) > 0; // the mod's setting only stops it asking
        Task best = null;
        double bestDistance = Double.MAX_VALUE;
        int[] e = field.extent;
        for (int dx = -e[3]; dx <= e[1]; dx++) for (int dz = -e[0]; dz <= e[2]; dz++) {
            if (dx == 0 && dz == 0) continue; // the field block stands there
            Block soil = plot(field, dx, dz);
            if (soil == null) continue;
            // Stems go in every other column, starting one in from the west edge; the rest stays free for the fruit.
            boolean free = FRUIT.containsKey(CROPS.get(seed)) && (dx + e[3]) % 2 == 0;
            if (work(soil, free, fertilizing && !fertilized.contains(soil)) != stage) continue;
            double d = w.location().distanceSquared(soil.getLocation().add(0.5, 1, 0.5));
            if (d < bestDistance) { best = new Task(stage, soil, seed); bestDistance = d; }
        }
        if (best == null) { // this stage is done (or had nothing to do): on to the next one
            field.stage = (field.stage + 1) % 3;
            fertilized.clear();
            if (didWork || ++skipped >= 4) { field.workedDay = colony.day; current = null; } // the mod gives up after 4 empty stages
            return;
        }
        // Only tilling needs a hoe; harvesting and planting go on without one.
        if (best.kind == Kind.TILL && !hasTool(Tool.HOE, null)
                && (chest == null || java.util.Arrays.stream(chest.getContents()).noneMatch(i -> fits(Tool.HOE, i, null)))) {
            idle(w, "Needs a hoe in the hut chest to till new farmland.", 100, hut.block);
            return;
        }
        if (best.kind != Kind.HARVEST && has(seed) <= 0 && (chest == null || !chest.contains(seed))) {
            idle(w, "Needs " + name(seed) + " in the hut chest to plant.", 100, hut.block);
            return;
        }
        didWork = true;
        if (best.kind == Kind.TILL && !hasTool(Tool.HOE, null)) { // canTill: the hut chest has one
            if (!atHut(w)) { goToHut(w); return; }
            fetchTool(Tool.HOE, null);
            return;
        }
        // Fetch seeds from the hut before tilling too, not after the first plot.
        if (best.kind != Kind.HARVEST && has(best.seed) <= 0) {
            if (!atHut(w)) { goToHut(w); return; }
            if (fetch(best.seed, SEEDS_PER_TRIP) == 0) { // only renamed or otherwise changed seeds, or no room: don't walk back and forth
                idle(w, "Needs plain " + name(best.seed) + " in the hut chest to plant (and room in its inventory).", 100, hut.block);
                return;
            }
            return;
        }
        note = null;
        w.setStatus("Farmer: working");
        Block crop = best.crop();
        if (w.location().distance(crop.getLocation().toCenterLocation()) > REACH) {
            w.goBeside(crop);
            return;
        }
        switch (best.kind) {
            case HARVEST -> {
                if (crop.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) { // the mod's: fertilize, harvest if it ripened
                    use(Compost::fertilizer, 1);
                    fertilized.add(best.soil);
                    crop.applyBoneMeal(org.bukkit.block.BlockFace.UP);
                    if (!(crop.getBlockData() instanceof Ageable now) || now.getAge() < now.getMaximumAge()) break;
                }
                double doubleChance = colony.research.effect(Effects.FARMING); // research: each drop doubled with this chance, as the mod
                for (ItemStack drop : breakWith(null, crop, w))
                    gather(List.of(drop.asQuantity(drop.getAmount() * (ThreadLocalRandom.current().nextDouble() < doubleChance ? 2 : 1))), w);
                crop.setType(Material.AIR, false);
                crop.getWorld().playSound(crop.getLocation(), Sound.BLOCK_CROP_BREAK, SoundCategory.BLOCKS, 1, 1);
            }
            case TILL -> {
                wear(Tool.HOE, w);
                if (!crop.isEmpty()) crop.setType(Material.AIR, false); // grass or a flower in the way
                best.soil.setType(Material.FARMLAND);
                crop.getWorld().playSound(best.soil.getLocation(), Sound.ITEM_HOE_TILL, SoundCategory.BLOCKS, 1, 1);
            }
            case PLANT -> {
                use(best.seed, 1);
                crop.setType(CROPS.get(best.seed));
                crop.getWorld().playSound(crop.getLocation(), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1, 1);
            }
        }
        act(w);
        cooldown = actionTicks();
    }

    /**
     * What a plot needs: harvest a ripe crop or a grown pumpkin or melon (any crop while {@code fertilizing}: it is
     * fertilized first, as the mod's; not stems), or (unless the plot is kept {@code free} for fruit) till bare soil
     * and plant farmland.
     */
    private static Kind work(Block soil, boolean free, boolean fertilizing) {
        Block above = soil.getRelative(0, 1, 0);
        if (FRUIT.containsValue(above.getType())) return Kind.HARVEST;
        if (FRUIT.containsKey(above.getType())) return null; // a stem: it keeps growing fruit
        if (CROPS.containsValue(above.getType()))
            return fertilizing || above.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() ? Kind.HARVEST : null;
        if (free || !(above.isEmpty() || (above.isReplaceable() && !above.isLiquid()))) return null;
        if (soil.getType() == Material.FARMLAND) return Kind.PLANT;
        return TILLABLE.contains(soil.getType()) ? Kind.TILL : null;
    }
}
