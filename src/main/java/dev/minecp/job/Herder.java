package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Settings;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mod's animal herders (AbstractEntityAIHerder and its five kinds): they keep the animals inside their hut's
 * building. Every 4 seconds a herder picks one thing to do: 1 in 10 picks up what lies about; then it may butcher an
 * adult (more likely the more there are, only while more than 3 adults are left); else it breeds two (one breeding item each, then a
 * minute's pause); the shepherd shears, and the cowhand milks a cow and fills a bowl at a mooshroom once a day. Drops
 * go to the hut after 10 actions. An axe is needed for butchering; the breeding item comes from the hut chest.
 * Ours: no skills, so butchering does an unskilled citizen's damage; feeding (which needs skill to help) is left out.
 */
public final class Herder extends Job {
    /** The five herders: their animals, breeding item and butchering hit (the mod's, unskilled). */
    public enum Kind {
        CHICKEN("chickens", Set.of(EntityType.CHICKEN), Material.WHEAT_SEEDS, 5, true),
        COW("cows, mooshrooms and goats", Set.of(EntityType.COW, EntityType.MOOSHROOM, EntityType.GOAT), Material.WHEAT, 1, false),
        SHEEP("sheep", Set.of(EntityType.SHEEP), Material.WHEAT, 1, false),
        PIG("pigs", Set.of(EntityType.PIG), Material.CARROT, 1, false),
        RABBIT("rabbits", Set.of(EntityType.RABBIT), Material.CARROT, 5, true);

        final String animals;
        final Set<EntityType> types;
        final Material food;
        final double damage;
        final boolean rarelyHits; // chicken and rabbit herders land a blow only if nextInt(21) <= 1 when unskilled

        Kind(String animals, Set<EntityType> types, Material food, double damage, boolean rarelyHits) {
            this.animals = animals;
            this.types = types;
            this.food = food;
            this.damage = damage;
            this.rarelyHits = rarelyHits;
        }
    }

    private static final int DECIDE_TICKS = 80, BREED_TICKS = 40, BUTCHER_TICKS = 20, PICKUP_TICKS = 20; // the mod's delays
    private static final int ANIMAL_MULTIPLIER = 2;    // about 2 animals per hut level are kept
    private static final int MIN_ADULTS = 3;           // never butchered at or below this
    private static final int BREED_DISTANCE = 10;      // partners this close
    private static final int BREED_PAUSE = 20 * 60, NO_PARTNER_PAUSE = 20 * 20;
    private static final int ACTIONS_FOR_DUMP = 10;
    private static final int FOOD_WANTED = 16, FOOD_MIN = 2; // 2 per breeding × 8, as the mod asks
    private static final int AREA_BELOW = 4, AREA_ABOVE = 8;  // ours: how far up and down the building's box reaches
    private static final int CHASE_TRIES = 30;          // ours: steps of chasing an animal before giving up on it

    private enum Act { NONE, PICKUP, BUTCHER, BREED, SHEAR, MILK }

    private final Colony colony;
    private final Kind kind;
    private Act act = Act.NONE;
    private Entity target, partner;
    private int tries, actions, breedUntil;
    // The mod's cowhand counters: milkings and stews since the last reset, which comes every "days between milkings".
    private int milked, stewed, milkDays, countedDay = -1;

    public Herder(Hut hut, Colony colony, Kind kind) {
        super(hut);
        this.colony = colony;
        this.kind = kind;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        wantTool(wants, Tool.AXE);
        if (kind == Kind.SHEEP) wantTool(wants, Tool.SHEARS);
        int food = inChest(i -> i.getType() == kind.food) + carried.getOrDefault(kind.food, 0);
        if (food < FOOD_MIN) wants.add(new Want(Job.name(kind.food), i -> i.getType() == kind.food, FOOD_WANTED - food, FOOD_MIN));
        if (kind == Kind.COW) { // the mod keeps 2 of each for one a day
            if (containers(Material.BUCKET) > 0 && inChest(i -> i.getType() == Material.BUCKET) == 0)
                wants.add(new Want("bucket", i -> i.getType() == Material.BUCKET, containers(Material.BUCKET), 1));
            if (containers(Material.BOWL) > 0 && inChest(i -> i.getType() == Material.BOWL) == 0)
                wants.add(new Want("bowl", i -> i.getType() == Material.BOWL, containers(Material.BOWL), 1));
        }
        return wants;
    }

    /** Tools, a stack of the breeding item, and for the cowhand 2 buckets and 2 bowls stay; the rest goes to the warehouse. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.AXE, item, null) || kind == Kind.SHEEP && fits(Tool.SHEARS, item, null)) return Integer.MAX_VALUE;
        if (item.getType() == kind.food) return 64;
        if (kind == Kind.COW && (item.getType() == Material.BUCKET || item.getType() == Material.BOWL)) return containers(item.getType());
        return 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The herder starts once level 1 is built.", NamedTextColor.GRAY));
        return List.of(Component.text("Keeps the " + kind.animals + " inside the building: " + animals().size()
                + " now, about " + ANIMAL_MULTIPLIER * hut.level + " kept. Breeds them with " + Job.name(kind.food)
                + " from this chest; butchers with an axe.", NamedTextColor.GRAY));
    }

    @Override
    public void tick(Colonist w) {
        String worker = hut.type.worker;
        if (hut.level < 1) {
            w.setStatus(worker + ": idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (act != Act.NONE) { carryOut(w); return; }
        if (actions >= ACTIONS_FOR_DUMP || !load.isEmpty() && actions == 0) { dump(w); return; }
        if (!hasTool(Tool.AXE, null) || kind == Kind.SHEEP && !hasTool(Tool.SHEARS, null)) {
            if (!atHut(w)) { goToHut(w); return; }
            fetchTool(Tool.AXE, null);
            if (kind == Kind.SHEEP) fetchTool(Tool.SHEARS, null);
            if (!hasTool(Tool.AXE, null) || kind == Kind.SHEEP && !hasTool(Tool.SHEARS, null)) {
                note = "Needs " + toolLabel(Tool.AXE) + (kind == Kind.SHEEP ? " and " + toolLabel(Tool.SHEARS) : "") + " in the hut chest.";
                w.setStatus(worker + ": idle");
                cooldown = 100;
                return;
            }
        }
        for (Material m : supplies()) // low on the breeding item (or the cowhand's buckets and bowls): more from the chest
            if (carried.getOrDefault(m, 0) <= (m == kind.food ? 1 : 0) && inChest(i -> i.isSimilar(new ItemStack(m))) > 0) { // plain ones only, as bring takes
                if (!atHut(w)) { goToHut(w); return; }
                bring(m);
            }
        note = null;
        decide(w);
    }

    /** What it carries to work with: the breeding item, and the cowhand's buckets and bowls. */
    private List<Material> supplies() {
        return kind == Kind.COW ? List.of(kind.food, Material.BUCKET, Material.BOWL) : List.of(kind.food);
    }

    /** The mod's DECIDE: one thing to do, or a wander about the building. */
    private void decide(Colonist w) {
        var rnd = ThreadLocalRandom.current();
        List<Animals> animals = animals();
        List<Item> items = items();
        cooldown = DECIDE_TICKS;
        if (rnd.nextDouble() < 0.1 && !items.isEmpty()) { start(Act.PICKUP, items.getFirst(), null); return; }
        List<Animals> adults = animals.stream().filter(Animals::isAdult).toList();
        int max = ANIMAL_MULTIPLIER * hut.level;
        if (adults.size() > MIN_ADULTS && rnd.nextDouble() < 0.5 * Math.pow(adults.size(), 4) / Math.pow(max, 4)) {
            Animals victim = farthestFromHerd(adults);
            if (victim != null) { start(Act.BUTCHER, victim, null); return; }
        }
        if (kind == Kind.SHEEP && hut.on(Settings.SHEARING)) {
            Animals sheep = adults.stream().filter(a -> a instanceof Sheep s && !s.isSheared()).findFirst().orElse(null);
            if (sheep != null) { start(Act.SHEAR, sheep, null); return; }
        }
        if (hut.on(Settings.BREEDING) && Bukkit.getCurrentTick() >= breedUntil && carried.getOrDefault(kind.food, 0) > 1) {
            List<Animals> ready = animals.stream().filter(a -> a.isAdult() && a.canBreed() && !a.isLoveMode()).toList();
            if (ready.size() < 2) pause(BREED_PAUSE);
            else { // the one nearest the middle of those that can breed, and its nearest partner
                Location centre = centre(ready);
                Animals first = ready.stream().min(Comparator.comparingDouble(a -> a.getLocation().distanceSquared(centre))).orElseThrow();
                Animals mate = ready.stream().filter(a -> a != first && a.getType() == first.getType()
                                && a.getLocation().distance(first.getLocation()) <= BREED_DISTANCE)
                        .min(Comparator.comparingDouble(a -> a.getLocation().distanceSquared(first.getLocation()))).orElse(null);
                if (mate != null) { start(Act.BREED, first, mate); return; }
                pause(NO_PARTNER_PAUSE);
            }
        }
        if (kind == Kind.COW) { // the mod's cowhand: so many milkings and stews, then again after so many days (its settings)
            if (countedDay != colony.day) { // a new day (the mod counts its wake-ups)
                if (countedDay >= 0) milkDays += colony.day - countedDay;
                countedDay = colony.day;
                if (milkDays >= hut.number(Settings.MILKING_DAYS)) { milked = 0; stewed = 0; milkDays = 0; }
            }
            Animals cow = null;
            if (milked < hut.number(Settings.MILKING)) cow = adults.stream().filter(a -> a.getType() != EntityType.MOOSHROOM).findFirst().orElse(null);
            if (cow == null && stewed < hut.number(Settings.STEWING)) cow = adults.stream().filter(a -> a instanceof MushroomCow).findFirst().orElse(null);
            if (cow != null && carried.getOrDefault(cow instanceof MushroomCow ? Material.BOWL : Material.BUCKET, 0) > 0) {
                start(Act.MILK, cow, null);
                return;
            }
        }
        w.setStatus(hut.type.worker + ": working");
        Location centre = animals.isEmpty() ? hut.block.getLocation().toCenterLocation() : centre(animals);
        w.wander(centre);
    }

    private void start(Act a, Entity t, Entity p) {
        act = a;
        target = t;
        partner = p;
        tries = 0;
        cooldown = 1;
    }

    /** Walks to the target and does the chosen thing once beside it. */
    private void carryOut(Colonist w) {
        w.setStatus(hut.type.worker + ": working");
        if (target == null || !target.isValid() || tries > CHASE_TRIES) { act = Act.NONE; return; }
        if (w.location().distance(target.getLocation()) > 2.5) {
            tries++; // walking steps only: the chicken and rabbit herders' misses don't count
            w.goBeside(target.getLocation().getBlock());
            cooldown = 10;
            return;
        }
        var rnd = ThreadLocalRandom.current();
        switch (act) {
            case PICKUP -> {
                pickUp(w);
                act = Act.NONE;
                cooldown = PICKUP_TICKS;
            }
            case BUTCHER -> {
                w.hold(tools.get(Tool.AXE));
                w.swing();
                cooldown = BUTCHER_TICKS;
                if (kind.rarelyHits && rnd.nextInt(21) > 1) return;
                wear(Tool.AXE, w);
                ((Animals) target).damage(kind.damage, w.living());
                if (target.isValid() && !target.isDead()) return;
                actions++;
                pickUp(w); // ours: what it dropped, at once (the mod leaves it for its 1 in 10 pick-up)
                act = Act.NONE;
            }
            case BREED -> {
                if (carried.getOrDefault(kind.food, 0) < 1) { act = Act.NONE; return; }
                ((Animals) target).setLoveModeTicks(600);
                take(kind.food);
                w.swing();
                actions++;
                cooldown = BREED_TICKS;
                if (partner != null) { target = partner; partner = null; tries = 0; return; }
                pause(BREED_PAUSE);
                act = Act.NONE;
            }
            case SHEAR -> {
                Sheep s = (Sheep) target;
                if (s.isSheared() || !hasTool(Tool.SHEARS, null)) { act = Act.NONE; return; }
                w.hold(tools.get(Tool.SHEARS));
                w.swing();
                wear(Tool.SHEARS, w);
                s.setSheared(true);
                s.getWorld().playSound(s.getLocation(), Sound.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1, 1);
                Material wool = Material.matchMaterial((s.getColor() == null ? DyeColor.WHITE : s.getColor()).name() + "_WOOL");
                if (wool != null) load.add(new ItemStack(wool)); // 1 wool: the mod's amount without Fortune
                if (hut.on(Settings.DYEING) && rnd.nextInt(100) <= hut.level) s.setColor(DyeColor.values()[rnd.nextInt(DyeColor.values().length)]); // the mod's dyeing
                actions++;
                act = Act.NONE;
                cooldown = BUTCHER_TICKS;
            }
            case MILK -> {
                boolean stew = target instanceof MushroomCow;
                Material from = stew ? Material.BOWL : Material.BUCKET;
                if (carried.getOrDefault(from, 0) < 1) { act = Act.NONE; return; }
                take(from);
                load.add(new ItemStack(stew ? Material.MUSHROOM_STEW : Material.MILK_BUCKET));
                target.getWorld().playSound(target.getLocation(), stew ? Sound.ENTITY_MOOSHROOM_MILK : Sound.ENTITY_COW_MILK, SoundCategory.PLAYERS, 1, 1);
                w.swing();
                if (stew) stewed++; else milked++;
                act = Act.NONE;
                actions = ACTIONS_FOR_DUMP; // the mod takes it to the hut straight away
            }
            default -> act = Act.NONE;
        }
    }

    /** No breeding for {@code ticks} (not saved: a restart ends the pause). */
    private void pause(int ticks) {
        breedUntil = Bukkit.getCurrentTick() + ticks;
    }

    private void pickUp(Colonist w) {
        for (Item i : items()) if (i.getLocation().distance(w.location()) <= 3) { load.add(i.getItemStack()); i.remove(); }
    }

    private void dump(Colonist w) {
        if (!atHut(w)) { goToHut(w); return; }
        java.util.Map<Material, Integer> kept = new java.util.EnumMap<>(Material.class); // what it works with stays with it
        for (Material m : supplies()) { Integer n = carried.remove(m); if (n != null) kept.put(m, n); }
        boolean ok = deliver();
        carried.putAll(kept);
        if (!ok) {
            note = "The hut chest is full.";
            w.setStatus(hut.type.worker + ": idle");
            cooldown = 100;
            return;
        }
        actions = 0;
    }

    /** Takes {@code m} from the hut chest, which it stands at: 16 of the breeding item, else 1. */
    private void bring(Material m) {
        Inventory chest = chest();
        if (chest == null) return;
        int want = (m == kind.food ? 16 : 1) - carried.getOrDefault(m, 0);
        if (want <= 0) return;
        int got = want - chest.removeItem(new ItemStack(m, want)).values().stream().mapToInt(ItemStack::getAmount).sum();
        if (got > 0) carried.merge(m, got, Integer::sum);
    }

    private void take(Material m) {
        carried.merge(m, -1, Integer::sum);
        carried.values().removeIf(n -> n <= 0);
    }

    /** The building's box (the mod's hut corners), a little up and down. */
    private BoundingBox area() {
        int[] f = colony.footprint(hut);
        int y = hut.block.getY();
        if (f == null) return BoundingBox.of(hut.block.getLocation(), 8, 8, 8);
        return new BoundingBox(f[0], y - AREA_BELOW, f[2], f[1], y + AREA_ABOVE, f[3]);
    }

    private List<Animals> animals() {
        List<Animals> out = new ArrayList<>();
        for (Entity e : hut.block.getWorld().getNearbyEntities(area()))
            if (e instanceof Animals a && kind.types.contains(e.getType()) && !a.isDead()) out.add(a);
        return out;
    }

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Entity e : hut.block.getWorld().getNearbyEntities(area())) if (e instanceof Item i && i.isValid()) out.add(i);
        return out;
    }

    private static Location centre(List<Animals> animals) {
        Vector sum = new Vector();
        for (Animals a : animals) sum.add(a.getLocation().toVector());
        return sum.multiply(1.0 / animals.size()).toLocation(animals.getFirst().getWorld());
    }

    /** The mod's pick: an adult not in love, farthest from the herd's middle, one under a roof first. */
    private static Animals farthestFromHerd(List<Animals> adults) {
        Location centre = centre(adults);
        return adults.stream().filter(a -> !a.isLoveMode())
                .max(Comparator.comparing((Animals a) -> a.getLocation().getBlock().getLightFromSky() < 15)
                        .thenComparingDouble(a -> a.getLocation().distanceSquared(centre))).orElse(null);
    }

    /** Buckets (milk) or bowls (stew) the cowhand keeps: the mod's ceil(2 × amount ÷ days), none when the amount is 0. */
    private int containers(Material m) {
        int amount = hut.number(m == Material.BUCKET ? Settings.MILKING : Settings.STEWING);
        return (int) Math.ceil(2.0 * amount / Math.max(1, hut.number(Settings.MILKING_DAYS)));
    }

    @Override
    public void save(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("actions", actions);
        s.set("milked", milked);
        s.set("stewed", stewed);
        s.set("milk-days", milkDays);
        s.set("counted-day", countedDay);
    }

    @Override
    public void load(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.load(s, c);
        actions = s.getInt("actions");
        milked = s.getInt("milked");
        stewed = s.getInt("stewed");
        milkDays = s.getInt("milk-days");
        countedDay = s.getInt("counted-day", -1);
    }
}
