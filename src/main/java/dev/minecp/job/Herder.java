package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Settings;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
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
 * Butchering hits as hard as the mod's with its skills. Feeding the young (which needs skill to help) is left out.
 */
public final class Herder extends Job {
    /** The five herders: their animals and breeding item; their butchering is {@link #butcherDamage} and {@link #lands}. */
    public enum Kind {
        CHICKEN("chickens", Set.of(EntityType.CHICKEN), Material.WHEAT_SEEDS),
        COW("cows, mooshrooms and goats", Set.of(EntityType.COW, EntityType.MOOSHROOM, EntityType.GOAT), Material.WHEAT),
        SHEEP("sheep", Set.of(EntityType.SHEEP), Material.WHEAT),
        PIG("pigs", Set.of(EntityType.PIG), Material.CARROT),
        RABBIT("rabbits", Set.of(EntityType.RABBIT), Material.CARROT);

        final String animals;
        final Set<EntityType> types;
        final Material food;

        Kind(String animals, Set<EntityType> types, Material food) {
            this.animals = animals;
            this.types = types;
            this.food = food;
        }
    }

    private static final double XP_PER_ACTION = 0.5, XP_PER_MILKING = 1; // AbstractEntityAIHerder; EntityAIWorkCowboy's 1.0
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

    private final Kind kind;
    private Act act = Act.NONE;
    private Entity target, partner;
    private int tries, actions, breedUntil;
    // The mod's cowhand counters: milkings and stews since the last reset, which comes every "days between milkings".
    private int milked, stewed, milkDays, countedDay = -1;

    @Override
    public double diseaseModifier() {
        return kind == Kind.PIG ? 2 : 1; // the mod's JobSwineHerder
    }

    public Herder(Hut hut, Colony colony, Kind kind) {
        super(hut, colony);
        this.kind = kind;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        wantTool(wants, Tool.AXE);
        if (kind == Kind.SHEEP) wantTool(wants, Tool.SHEARS);
        int food = inChest(i -> i.getType() == kind.food) + has(kind.food);
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
        if (actions >= ACTIONS_FOR_DUMP || loaded() && actions == 0) { dump(w); return; }
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
            if (has(m) <= (m == kind.food ? 1 : 0) && inChest(i -> i.isSimilar(new ItemStack(m))) > 0) { // plain ones only, as bring takes
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
        if (hut.on(Settings.BREEDING) && Bukkit.getCurrentTick() >= breedUntil && has(kind.food) > 1) {
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
            if (cow != null && has(cow instanceof MushroomCow ? Material.BOWL : Material.BUCKET) > 0) {
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
                w.hold(tool(Tool.AXE));
                act(w);
                experience(w, XP_PER_ACTION);
                cooldown = BUTCHER_TICKS;
                if (!lands()) return;
                wear(Tool.AXE, w);
                ItemStack axe = tool(Tool.AXE);
                if (axe != null && colony.research.effect(Effects.LOOTING) > 0 && axe.getEnchantmentLevel(Enchantment.LOOTING) < 1) {
                    // The mod's Skilled Butcher: the blow is struck with at least Looting I. Ours: a copy in hand for the blow only.
                    axe = axe.clone();
                    axe.addUnsafeEnchantment(Enchantment.LOOTING, 1);
                    w.hold(axe);
                }
                ((Animals) target).damage(butcherDamage(), w.living());
                w.hold(tool(Tool.AXE)); // its own axe back in hand
                if (target.isValid() && !target.isDead()) return;
                actions++;
                pickUp(w); // ours: what it dropped, at once (the mod leaves it for its 1 in 10 pick-up)
                act = Act.NONE;
            }
            case BREED -> {
                if (has(kind.food) < 1) { act = Act.NONE; return; }
                ((Animals) target).setLoveModeTicks(600);
                use(kind.food, 1);
                act(w);
                experience(w, XP_PER_ACTION);
                actions++;
                cooldown = BREED_TICKS;
                if (partner != null) { target = partner; partner = null; tries = 0; return; }
                pause(BREED_PAUSE);
                act = Act.NONE;
            }
            case SHEAR -> {
                Sheep s = (Sheep) target;
                if (s.isSheared() || !hasTool(Tool.SHEARS, null)) { act = Act.NONE; return; }
                w.hold(tool(Tool.SHEARS));
                act(w);
                experience(w, XP_PER_ACTION);
                wear(Tool.SHEARS, w);
                s.setSheared(true);
                s.getWorld().playSound(s.getLocation(), Sound.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1, 1);
                Material wool = Material.matchMaterial((s.getColor() == null ? DyeColor.WHITE : s.getColor()).name() + "_WOOL");
                if (wool != null) gather(List.of(new ItemStack(wool)), w); // 1 wool: the mod's amount without Fortune
                if (hut.on(Settings.DYEING) && rnd.nextInt(100) <= hut.level) s.setColor(DyeColor.values()[rnd.nextInt(DyeColor.values().length)]); // the mod's dyeing
                actions++;
                act = Act.NONE;
                cooldown = BUTCHER_TICKS;
            }
            case MILK -> {
                boolean stew = target instanceof MushroomCow;
                Material from = stew ? Material.BOWL : Material.BUCKET;
                if (has(from) < 1) { act = Act.NONE; return; }
                use(from, 1);
                gather(List.of(new ItemStack(stew ? Material.MUSHROOM_STEW : Material.MILK_BUCKET)), w);
                target.getWorld().playSound(target.getLocation(), stew ? Sound.ENTITY_MOOSHROOM_MILK : Sound.ENTITY_COW_MILK, SoundCategory.PLAYERS, 1, 1);
                act(w);
                experience(w, XP_PER_MILKING);
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
        Inventory bag = bag();
        if (bag == null) return;
        for (Item i : items()) {
            if (i.getLocation().distance(w.location()) > 3) continue;
            var left = bag.addItem(i.getItemStack()).values(); // what doesn't fit stays on the ground
            if (left.isEmpty()) i.remove();
            else i.setItemStack(left.iterator().next());
        }
    }

    private void dump(Colonist w) {
        if (!atHut(w)) { goToHut(w); return; }
        if (!deliver()) { // what it works with stays with it (keepsOnHand)
            note = "The hut chest is full.";
            w.setStatus(hut.type.worker + ": idle");
            cooldown = 100;
            return;
        }
        actions = 0;
    }

    /** Takes {@code m} from the hut chest, which it stands at: 16 of the breeding item, else 1. */
    private void bring(Material m) {
        fetch(m, (m == kind.food ? 16 : 1) - has(m));
    }

    /** It keeps the breeding item, and the cowhand its buckets and bowls, when it hands the rest in. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        return supplies().contains(i.getType()) && i.getPersistentDataContainer().isEmpty();
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

    /**
     * A butchering blow's damage (the mod's getButcheringAttackDamage): the cowhand's and swineherd's main skill / 10,
     * the shepherd's second / 10, at least 1; the chicken and rabbit herders' the mod's base 5.
     */
    private double butcherDamage() {
        return switch (kind) {
            case COW, PIG -> Math.max(1.0, skill(0) / 10.0);
            case SHEEP -> Math.max(1.0, skill(1) / 10.0);
            default -> 5;
        };
    }

    /** Whether the blow lands: the chicken and rabbit herders' only if nextInt(1 + (100 - skill) / 5) <= 1 (agility for both). */
    private boolean lands() {
        int skill = switch (kind) { case CHICKEN -> skill(1); case RABBIT -> skill(0); default -> -1; };
        return skill < 0 || ThreadLocalRandom.current().nextInt(1 + (100 - skill) / 5) <= 1;
    }
}
