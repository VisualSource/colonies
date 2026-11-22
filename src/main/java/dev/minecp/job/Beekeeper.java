package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Bee;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The beekeeper (the mod's BuildingBeekeeper and EntityAIWorkBeekeeper), the mod's numbers unless noted: it empties
 * full hives with shears (3 honeycomb) or a glass bottle (a honey bottle), as its setting says, which may anger the
 * bees inside; and it breeds the bees around its hives with the flowers on its list, up to 3 a level. Its hives are its
 * building's own (ours: the mod's players assign hives with its beekeeper scepter), up to 2^(level - 1).
 */
public final class Beekeeper extends Job {
    private static final int DECIDE_TICKS = 40 + (99 / 1 - 1); // DECIDING_DELAY + 99 / its adaptability - 1 (no skills: the mod's least, 1)
    private static final int NONE_TICKS = 100;           // NO_HIVES_DELAY, NO_ANIMALS_DELAY, NO_FLOWERS_DELAY
    private static final int BREEDING_TICKS = 40;        // BREEDING_DELAY
    private static final int BEES_PER_LEVEL = 3;
    private static final int FLOWERS_TO_BREED = 2, FLOWERS_WANTED = 16;
    private static final int BREED_DISTANCE = 10;
    private static final int HIVE_RADIUS = 22;           // HIVE_BEE_RADIUS: the bees it counts around each hive
    private static final int DUMP_AFTER = 5;             // getActionsDoneUntilDumping
    private static final int COMBS = 3, BOTTLES = 1;     // getHoneycombsPerHarvest, getHoneyBottlesPerHarvest
    private static final int FULL = 5;                   // a hive's honey level when it can be emptied
    private static final double ANGER_CHANCE = 0.5;      // (50 - dexterity / 99 x 50) / 100; no skills: 50%
    private static final double ANGER_RANGE_SQ = 16;     // released bees this near turn on it
    private static final int ANGER_TICKS = 400, ANGER_SPREAD = 400;
    private static final int BOTTLES_KEPT = 4;           // the mod's keepX
    private static final double REACH = 2.5;             // ours: close enough to work a hive or a bee

    public final Set<Material> flowers = EnumSet.noneOf(Material.class); // its flower list (the mod's item list); saved
    private boolean lastBottle; // "both": it takes turns
    private Bee mate;           // the fed bee's partner, fed next
    private int actions;
    private List<Block> spots = List.of();
    private int spotsLevel = -1;

    public Beekeeper(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Its hives: the beehives and nests of its building as built, the first 2^(level - 1) (the mod's getMaximumHives). */
    private List<Block> hives() {
        if (spotsLevel != hut.level) {
            spotsLevel = hut.level;
            BuildOrder o = hut.level < 1 ? null : BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
            spots = o == null ? List.of() : o.steps.stream().filter(s -> isHive(s.data().getMaterial())).map(BuildOrder.Step::block)
                    .limit(1L << Math.max(0, hut.level - 1)).toList();
        }
        return spots.stream().filter(b -> isHive(b.getType())).toList();
    }

    private static boolean isHive(Material m) {
        return m == Material.BEEHIVE || m == Material.BEE_NEST;
    }

    private static int honey(Block hive) {
        return hive.getBlockData() instanceof org.bukkit.block.data.type.Beehive b ? b.getHoneyLevel() : 0;
    }

    private String mode() {
        return hut.setting(Settings.BEE_MODE);
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Beekeeper: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        cooldown = DECIDE_TICKS;
        if (actions >= DUMP_AFTER || bagFull() && loaded()) {
            if (!atHut(w)) { w.setStatus("Beekeeper: bringing in the harvest"); goToHut(w); return; }
            if (!deliver()) { idle(w, "The hut chest is full.", NONE_TICKS, hut.block); return; }
            actions = 0;
        }
        List<Block> hives = hives();
        if (hives.isEmpty()) { idle(w, "Its building has no beehives.", NONE_TICKS, hut.block); return; }
        boolean breeding = hut.on(Settings.BREEDING);
        if (breeding && flowers.isEmpty()) { idle(w, "Choose the flowers it breeds bees with: Flower list in the hut menu (or turn breeding off).", NONE_TICKS, hut.block); return; }
        Block full = hives.stream().filter(h -> honey(h) >= FULL).findFirst().orElse(null);
        if (full != null) { harvest(w, full); return; }
        List<Bee> bees = bees(hives);
        int inHives = hives.stream().mapToInt(h -> h.getState(false) instanceof org.bukkit.block.Beehive b ? b.getEntityCount() : 0).sum();
        if (bees.isEmpty()) {
            if (inHives == 0) { idle(w, "No bees around its hives: bring it some.", NONE_TICKS, hut.block); return; }
        } else if (breeding && bees.size() + inHives < hut.level * BEES_PER_LEVEL && breed(w, bees)) return;
        note = null;
        w.setStatus("Beekeeper: watching the hives");
        w.wander(hut.block.getLocation().toCenterLocation());
    }

    /** Empties a full hive: 3 honeycomb with shears, or a honey bottle for a glass bottle; half the time the bees come out angry. */
    private void harvest(Colonist w, Block hive) {
        String mode = mode();
        boolean combs = mode.equals("HONEYCOMB") || mode.equals("BOTH") && lastBottle;
        if (combs && !hasTool(Tool.SHEARS, null)) {
            if (!atHut(w)) { w.setStatus("Beekeeper: getting shears"); goToHut(w); return; }
            if (!fetchTool(Tool.SHEARS, null)) { idle(w, "Needs " + toolLabel(Tool.SHEARS) + " in the hut chest to take honeycomb.", NONE_TICKS, hut.block); return; }
        }
        if (!combs && has(Material.GLASS_BOTTLE) == 0) {
            if (!atHut(w)) { w.setStatus("Beekeeper: getting bottles"); goToHut(w); return; }
            if (fetch(Material.GLASS_BOTTLE, BOTTLES_KEPT) == 0) { idle(w, "Needs glass bottles in the hut chest to take honey.", NONE_TICKS, hut.block); return; }
        }
        note = null;
        w.setStatus("Beekeeper: taking honey");
        if (!near(w, hive.getLocation().toCenterLocation())) { w.goBeside(hive); return; }
        w.swing();
        if (combs) {
            wear(Tool.SHEARS, w);
            gather(List.of(new ItemStack(Material.HONEYCOMB, COMBS)), w);
            hive.getWorld().playSound(hive.getLocation(), Sound.BLOCK_BEEHIVE_SHEAR, SoundCategory.BLOCKS, 1, 1);
        } else {
            int n = use(Material.GLASS_BOTTLE, BOTTLES);
            gather(List.of(new ItemStack(Material.HONEY_BOTTLE, n)), w);
            hive.getWorld().playSound(hive.getLocation(), Sound.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 1, 1);
        }
        lastBottle = !combs;
        if (hive.getBlockData() instanceof org.bukkit.block.data.type.Beehive b) { b.setHoneyLevel(0); hive.setBlockData(b); }
        if (ThreadLocalRandom.current().nextDouble() < ANGER_CHANCE && hive.getState(false) instanceof org.bukkit.block.Beehive state)
            for (var bee : state.releaseEntities()) // the mod's emergency release: those near it go for it
                if (bee.getLocation().distanceSquared(w.location()) <= ANGER_RANGE_SQ && w.living() != null) {
                    bee.setAnger(ANGER_TICKS + ThreadLocalRandom.current().nextInt(ANGER_SPREAD));
                    bee.setTarget(w.living());
                }
        actions++;
        w.worked(); // incrementActionsDoneAndDecSaturation
    }

    /** The bees within 22 blocks of any of its hives (the mod's searchForAnimals). */
    private static List<Bee> bees(List<Block> hives) {
        Set<Bee> found = new LinkedHashSet<>();
        for (Block h : hives)
            for (var e : h.getWorld().getNearbyEntities(h.getLocation().toCenterLocation(), HIVE_RADIUS, HIVE_RADIUS, HIVE_RADIUS))
                if (e instanceof Bee b) found.add(b);
        return new ArrayList<>(found);
    }

    /**
     * Two grown bees within 10 blocks of each other, each fed a flower from its list (the mod's breedTwoAnimals): the
     * first, then its mate on the next decision. @return whether it is at it.
     */
    private boolean breed(Colonist w, List<Bee> bees) {
        if (mate != null && (!mate.isValid() || mate.isLoveMode() || !mate.canBreed())) mate = null;
        Bee next = mate;
        Bee other = null;
        if (next == null) {
            next = bees.stream().filter(b -> b.isAdult() && b.canBreed() && !b.isLoveMode()).findFirst().orElse(null);
            Bee one = next;
            other = one == null ? null : bees.stream().filter(b -> b != one && b.isAdult() && b.canBreed() && !b.isLoveMode()
                    && b.getLocation().distance(one.getLocation()) <= BREED_DISTANCE).findFirst().orElse(null);
            if (other == null) return false;
        }
        int needed = other != null ? FLOWERS_TO_BREED : 1; // a pair takes 2; its mate, the one left
        if (has(this::listed) < needed) {
            if (inChest(this::listed) + has(this::listed) < needed) return false; // it asks for them (wants)
            if (!atHut(w)) { w.setStatus("Beekeeper: getting flowers"); goToHut(w); return true; }
            fetch(this::listed, FLOWERS_WANTED);
            return true;
        }
        w.setStatus("Beekeeper: breeding bees");
        if (!near(w, next.getLocation())) { w.walk(next.getLocation()); if (other != null) mate = null; return true; }
        w.swing();
        use(this::listed, 1);
        next.setLoveModeTicks(600); // vanilla's love time
        if (other != null) mate = other; // its partner gets the next flower
        else { mate = null; actions++; w.workedLittle(); } // the pair is fed: one action, as the mod's
        cooldown = BREEDING_TICKS;
        return true;
    }

    private static boolean near(Colonist w, org.bukkit.Location at) {
        return w.location().getWorld() == at.getWorld() && w.location().distance(at) <= REACH;
    }

    private boolean listed(ItemStack i) {
        return i != null && flowers.contains(i.getType());
    }

    /** Flowers bees breed with (vanilla's flowers tag), which its list may hold. */
    public static boolean flower(Material m) {
        return Tag.ITEMS_FLOWERS.isTagged(m);
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        if (!mode().equals("HONEY")) wantTool(wants, Tool.SHEARS);
        if (!mode().equals("HONEYCOMB") && has(Material.GLASS_BOTTLE) + inChest(i -> i.getType() == Material.GLASS_BOTTLE) == 0)
            wants.add(new Want(name(Material.GLASS_BOTTLE), i -> i.getType() == Material.GLASS_BOTTLE, 1));
        if (hut.on(Settings.BREEDING) && !flowers.isEmpty() && has(this::listed) + inChest(this::listed) < FLOWERS_TO_BREED)
            wants.add(new Want("flowers", this::listed, FLOWERS_WANTED, FLOWERS_TO_BREED));
        return wants;
    }

    /** Shears, 4 bottles and a stack of each listed flower stay (the mod's keepX keeps any flower); the honey goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.SHEARS, item, null)) return 1;
        if (item.getType() == Material.GLASS_BOTTLE) return BOTTLES_KEPT;
        return listed(item) ? item.getMaxStackSize() : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return i.getType() == Material.GLASS_BOTTLE || listed(i);
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The beekeeper starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>();
        String takes = switch (mode()) { case "HONEY" -> "honey bottles (glass bottles)"; case "BOTH" -> "honeycomb and honey bottles in turn"; default -> "honeycomb (shears)"; };
        lines.add(Component.text("Takes " + takes + " from its building's full hives (up to " + (1 << Math.max(0, hut.level - 1))
                + "), and breeds the bees around them, up to " + hut.level * BEES_PER_LEVEL + ", with the flowers on its list.", NamedTextColor.GRAY));
        List<Block> hives = hives();
        lines.add(Component.text("Hives: " + hives.size() + (hives.isEmpty() ? "" : " (honey " + String.join(", ", hives.stream().map(h -> honey(h) + "/5").toList()) + ")")));
        lines.add(Component.text(flowers.isEmpty() ? "Flower list: empty." : "Flower list: " + String.join(", ", flowers.stream().map(Job::name).toList()) + ".",
                flowers.isEmpty() && c.huts.contains(hut) && hut.on(Settings.BREEDING) ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("flowers", flowers.stream().map(Enum::name).toList());
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String m : s.getStringList("flowers")) { Material mat = Material.matchMaterial(m); if (mat != null) flowers.add(mat); }
    }
}
