package dev.minecp.job;

import dev.minecp.MineCP;
import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Nms;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Requests;
import dev.minecp.logistics.Warehouse;
import dev.minecp.research.Effects;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The Alchemist (the mod's BuildingAlchemist and EntityAIWorkAlchemist), the mod's numbers unless noted: it is taught
 * brewing recipes and brews what the colony's requests ask for in its building's brewing stands, three potions at a
 * time, with blaze powder it asks for; it knows the magic potion (with research). With nothing to make it now and then
 * harvests or plants nether wart on its building's soul sand, or cuts at its leaves with shears until mistletoe falls.
 */
public final class Alchemist extends Crafter {
    private static final int FUEL = 8;               // BREWING_MIN_FUEL_COUNT: blaze powder put in a stand, and asked for per stand
    private static final double XP_PER_POTION = 5;   // BASE_XP_GAIN
    private static final int GATHER_ODDS = 30;       // DELAY_TO_HARVEST_NETHERWART, _MISTLETOE: 2 times in this it goes to do one
    private static final int MISTLETOE_ODDS = 40;    // a hit at the leaves a second, 1 in this gives mistletoe
    private static final int WART_AGE = 2;           // it harvests from this age on (grown is 3)
    private static final int WART_ASKED = 16;        // asked for with none; also what the hut keeps
    private static final int WANDER_TICKS = 400, WANDER_RANGE = 10; // between its looks for something to do
    private static final int STEP_TICKS = 20;        // TICKS_SECOND
    private static final double REACH = 4;           // ours: close enough to a stand or leaves

    private static final String MISTLETOE = "mistletoe", MAGIC_POTION = "magic_potion"; // its items' tags, and the pack's models
    private static final NamespacedKey KEY = new NamespacedKey(MineCP.NAMESPACE, "alchemy");

    private final Recipe magicPotion;
    private List<Block> stands = List.of();
    private final List<Block> soils = new ArrayList<>(), leaves = new ArrayList<>(); // its building's; those gone or out of reach dropped
    private int spotsLevel = -1;
    private Block soil, leaf; // where it is at work

    public Alchemist(Hut hut, Colony colony) {
        super(hut, colony, r -> true, "potions in its brewing stands, from brewing recipes", Kind.BREW);
        ShapelessRecipe r = new ShapelessRecipe(new NamespacedKey(MineCP.NAMESPACE, "alchemist_magic_potion"), magicPotion());
        r.addIngredient(new RecipeChoice.ExactChoice(mistletoe()));
        r.addIngredient(new RecipeChoice.ExactChoice(waterBottle()));
        learn(magicPotion = r, 1, s -> s.effect(Effects.DRUID_POTIONS) > 0);
    }

    // ---- its items ------------------------------------------------------------------------------------------------

    /**
     * The mod's mistletoe and magic potion: trial keys with a tag (no recipe uses them, nor can they be placed, eaten or
     * drunk) that look like {@code look}.
     */
    private static ItemStack item(String kind, String name, String hint, String look) {
        ItemStack item = new ItemStack(Material.TRIAL_KEY);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(look));
        item.editMeta(m -> {
            m.itemName(Component.text(name, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(hint, NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, kind);
        });
        dev.minecp.Items.model(item, kind);
        return item;
    }

    static ItemStack mistletoe() {
        return item(MISTLETOE, "Mistletoe", "Alchemists cut it and make magic potions from it", "lily_of_the_valley");
    }

    static ItemStack magicPotion() {
        ItemStack item = item(MAGIC_POTION, "Magic Potion", "Makes a druid's potions stronger", "dragon_breath");
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        return item;
    }

    /** A vanilla water bottle: ours, in place of the mod's large water bottle. */
    public static ItemStack waterBottle() {
        ItemStack item = new ItemStack(Material.POTION);
        item.editMeta(PotionMeta.class, m -> m.setBasePotionType(PotionType.WATER));
        return item;
    }

    private static boolean is(ItemStack item, String kind) {
        return item != null && kind.equals(item.getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }

    public static boolean isMistletoe(ItemStack item) {
        return is(item, MISTLETOE);
    }

    static boolean isMagicPotion(ItemStack item) {
        return is(item, MAGIC_POTION);
    }

    // ---- brewing recipes ------------------------------------------------------------------------------------------

    /** shortcut: read once, as recipes are only known to change with a restart; clear it if data packs get reloaded with other brewing recipes. */
    private static final Map<NamespacedKey, ItemStack> INPUTS = new HashMap<>();

    /** The bottle a brewing recipe starts from (the API leaves out which potion it holds), or null if it is no potion. */
    public static ItemStack input(BrewingRecipe r) {
        if (!INPUTS.containsKey(r.getKey())) INPUTS.put(r.getKey(), findInput(r));
        return INPUTS.get(r.getKey());
    }

    private static ItemStack findInput(BrewingRecipe r) {
        for (Material bottle : List.of(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)) {
            ItemStack item = new ItemStack(bottle);
            if (!r.getInput().test(item)) continue;
            for (PotionType t : Registry.POTION) {
                item.editMeta(PotionMeta.class, m -> m.setBasePotionType(t));
                if (Nms.brewsFrom(r.getKey(), item)) return item;
            }
        }
        return null;
    }

    private static Predicate<ItemStack> bottle(BrewingRecipe r) {
        ItemStack in = input(r);
        return i -> i.isSimilar(in);
    }

    /** Potions are brewed in the stands, the magic potion made at the hut. */
    @Override
    boolean craftsAtHut(Recipe r) {
        return !(r instanceof BrewingRecipe);
    }

    /** The potions its stands brew with {@code r} or have brewed (what is being brewed into something else no longer counts). */
    @Override
    int inProgress(Recipe r) {
        if (!(r instanceof BrewingRecipe b)) return 0;
        int n = 0;
        for (BrewerInventory inv : stands()) {
            ItemStack reagent = inv.getIngredient();
            if (!spent(inv) && !b.getIngredient().test(reagent)) continue;
            Predicate<ItemStack> counts = spent(inv) ? r.getResult()::isSimilar : bottle(b);
            for (int i = 0; i < BOTTLES; i++) if (!empty(inv.getItem(i)) && counts.test(inv.getItem(i))) n++;
        }
        return n;
    }

    /** The bottle comes back (the mod's large bottle does too). */
    @Override
    List<ItemStack> extras(Recipe r) {
        return r == magicPotion ? List.of(new ItemStack(Material.GLASS_BOTTLE)) : List.of();
    }

    // ---- its stands -----------------------------------------------------------------------------------------------

    /** Its building's brewing stands, soul sand and leaves, from its blueprint (the mod registers them as they are built). */
    private void findSpots() {
        if (spotsLevel == hut.level) return;
        spotsLevel = hut.level;
        soil = leaf = null;
        soils.clear();
        leaves.clear();
        BuildOrder o = BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
        stands = o == null ? List.of() : Trainee.blocks(o, Material.BREWING_STAND);
        if (o == null) return;
        soils.addAll(Trainee.blocks(o, Material.SOUL_SAND));
        for (BuildOrder.Step s : o.steps) if (Tag.LEAVES.isTagged(s.data().getMaterial())) leaves.add(s.block());
    }

    private static BrewingStand stand(Block b) {
        return b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && b.getState(false) instanceof BrewingStand s ? s : null;
    }

    private List<BrewerInventory> stands() {
        List<BrewerInventory> out = new ArrayList<>();
        for (Block b : stands) if (stand(b) instanceof BrewingStand s) out.add(s.getInventory());
        return out;
    }

    private boolean brewing() {
        for (Block b : stands) if (stand(b) instanceof BrewingStand s && s.getBrewingTime() > 0) return true;
        return false;
    }

    private static boolean empty(ItemStack i) {
        return Furnaces.empty(i);
    }

    /** Done, or never begun. */
    private static boolean spent(BrewerInventory inv) {
        return empty(inv.getIngredient()) || inv.getIngredient().getType() == Material.GLASS_BOTTLE; // dragon's breath leaves its bottle
    }

    /** What to take out of slot {@code i} of a spent stand: a potion it knows how to brew, or the left bottle. Other bottles are a player's. */
    private boolean toCollect(BrewerInventory inv, int i) {
        ItemStack item = inv.getItem(i);
        if (empty(item)) return false;
        if (i == BOTTLES) return true;
        for (NamespacedKey key : recipes) if (resultOf(key) instanceof ItemStack r && r.isSimilar(item)) return true;
        return false;
    }

    /** Whether a spent stand holds something to collect that fits in the chest ({@code fits}), or that doesn't. */
    private boolean toCollect(BrewerInventory inv, Inventory chest, boolean fits) {
        if (spent(inv)) for (int i = 0; i <= BOTTLES; i++) if (toCollect(inv, i) && Furnaces.fits(chest, inv.getItem(i)) == fits) return true;
        return false;
    }

    /**
     * One step at the first stand that needs it: what it brewed goes into the chest (what doesn't fit stays), an empty
     * one is filled for a task, three bottles and the ingredient at once (ours: straight from the chest), and one with
     * something to brew and no powder gets some; but no more stands brew at once than its dexterity / 10 + 1.
     */
    @Override
    boolean tend(Colonist w, Inventory chest) {
        findSpots();
        int busy = 0, most = skill(0) / 10 + 1; // getMaxUsableBrewingStands
        for (BrewerInventory inv : stands()) if (!spent(inv)) busy++;
        for (Block b : stands) {
            BrewingStand s = stand(b);
            if (s == null || s.getBrewingTime() > 0) continue;
            BrewerInventory inv = s.getInventory();
            boolean bottles = false;
            for (int i = 0; i < BOTTLES; i++) bottles |= !empty(inv.getItem(i));
            boolean collect = toCollect(inv, chest, true);
            BrewingRecipe r = !bottles && empty(inv.getIngredient()) && busy < most ? nextBrew(chest) : null;
            boolean fuel = (r != null || !spent(inv)) && empty(inv.getFuel()) && inChest(Alchemist::isFuel) > 0;
            if (!collect && r == null && !fuel) continue;
            w.setStatus("Alchemist: brewing");
            if (w.location().distance(b.getLocation().toCenterLocation()) > REACH) { w.goBeside(b); return true; }
            if (collect) collect(w, inv, chest);
            if (r != null) fill(inv, chest, r);
            if (fuel) inv.setFuel(take(chest, Alchemist::isFuel, FUEL));
            note = null;
            act(w);
            cooldown = STEP_TICKS;
            return true;
        }
        return false;
    }

    private void collect(Colonist w, BrewerInventory inv, Inventory chest) {
        for (int i = 0; i <= BOTTLES; i++) { // the bottles, then the ingredient's slot
            if (!toCollect(inv, i)) continue;
            ItemStack item = inv.getItem(i);
            ItemStack left = chest.addItem(item.clone()).values().stream().findFirst().orElse(null);
            inv.setItem(i, left);
            if (left == null && i < BOTTLES) experience(w, XP_PER_POTION);
        }
    }

    private void fill(BrewerInventory inv, Inventory chest, BrewingRecipe r) {
        for (int i = 0; i < BOTTLES; i++) inv.setItem(i, take(chest, bottle(r), 1));
        inv.setIngredient(take(chest, r.getIngredient()::test, 1));
    }

    private static ItemStack take(Inventory chest, Predicate<ItemStack> test, int n) {
        List<ItemStack> taken = new ArrayList<>();
        int got = Warehouse.take(chest, test, n, Integer.MAX_VALUE, taken);
        return taken.isEmpty() ? null : taken.getFirst().asQuantity(got);
    }

    /** A brewing recipe a task still needs that the chest has everything for, or null. */
    private BrewingRecipe nextBrew(Inventory chest) {
        for (Requests.Request t : tasks)
            if (recipeFor(t.test()) instanceof BrewingRecipe r && craftsLeft(t, r, false) > 0 && hasIngredients(chest, r)) return r;
        return null;
    }

    private boolean toBrew() {
        return tasks.stream().anyMatch(t -> recipeFor(t.test()) instanceof BrewingRecipe r && craftsLeft(t, r, true) > 0);
    }

    /** Whether a task still waits for something it can't make yet, or a filled stand for powder. */
    private boolean toMake() {
        return tasks.stream().anyMatch(t -> recipeFor(t.test()) instanceof Recipe r && craftsLeft(t, r, false) > 0) || unfuelled();
    }

    /** A stand is filled but has no powder. */
    private boolean unfuelled() {
        for (Block b : stands) if (stand(b) instanceof BrewingStand s && s.getFuelLevel() <= 0 && empty(s.getInventory().getFuel()) && !empty(s.getInventory().getIngredient())) return true;
        return false;
    }

    private static boolean isFuel(ItemStack i) {
        return i.getType() == Material.BLAZE_POWDER;
    }

    /** Its worker speeds its stands up: its mana / 10 x 2 ticks a second (the mod's accelerateBrewingStand). Ours: one tick short of done, so vanilla finishes. */
    @Override
    public void everySecond() {
        int ticks = colony.worker(this) == null ? 0 : skill(1) / 10 * Furnaces.SPEED_UP_PER_10;
        if (ticks > 0) for (Block b : stands)
            if (stand(b) instanceof BrewingStand s && s.getBrewingTime() > 1) s.setBrewingTime(Math.max(1, s.getBrewingTime() - ticks));
    }

    // ---- gathering ------------------------------------------------------------------------------------------------

    /** Nothing to make right now: nether wart and mistletoe, unless a stand is brewing. */
    @Override
    boolean otherWork(Colonist w, Inventory chest) {
        findSpots();
        if (leaf != null) { cutMistletoe(w); return true; }
        if (soil != null) { tendWart(w); return true; }
        if (loaded()) {
            if (!atHut(w)) { w.setStatus("Alchemist: working"); goToHut(w); }
            else if (!deliver()) idle(w, "The hut chest is full.", 100, hut.block);
            return true;
        }
        if (brewing()) { idle(w, null, STEP_TICKS, hut.block); w.setStatus("Alchemist: brewing"); return true; }
        w.setStatus("Alchemist: idle");
        note = stands().stream().anyMatch(i -> toCollect(i, chest, false)) ? "The hut chest is full." : !toMake() ? null : stands.isEmpty() && toBrew() ? "No brewing stand in the building."
                : unfuelled() ? "Waiting for blaze powder in the hut chest."
                : "Waiting for ingredients in the hut chest.";
        var rnd = ThreadLocalRandom.current();
        if (rnd.nextInt(GATHER_ODDS) <= 1 && (soil = Trainee.random(soils)) != null) return true;
        if (rnd.nextInt(GATHER_ODDS) <= 1 && (leaf = Trainee.random(leaves)) != null) return true;
        int[] box = colony.footprint(hut);
        Block at = w.location().getBlock();
        if (box == null || at.getX() < box[0] || at.getX() >= box[1] || at.getZ() < box[2] || at.getZ() >= box[3]) { cooldown = STEP_TICKS; goToHut(w); return true; }
        int x = rnd.nextInt(Math.max(box[0], at.getX() - WANDER_RANGE), Math.min(box[1], at.getX() + WANDER_RANGE + 1)),
                z = rnd.nextInt(Math.max(box[2], at.getZ() - WANDER_RANGE), Math.min(box[3], at.getZ() + WANDER_RANGE + 1)); // within the building
        w.walkRandom(at.getWorld().getBlockAt(x, at.getY(), z).getLocation(), 0);
        cooldown = WANDER_TICKS;
        return true;
    }

    /** Harvests the nether wart on the soul sand it picked, or plants some. */
    private void tendWart(Colonist w) {
        w.setStatus("Alchemist: tending nether wart");
        cooldown = STEP_TICKS;
        Block above = soil.getRelative(BlockFace.UP);
        if (soil.getType() != Material.SOUL_SAND) { soils.remove(soil); soil = null; return; }
        boolean plant = above.getType().isAir();
        if (plant && has(Material.NETHER_WART) == 0) {
            if (inChest(Alchemist::isWart) == 0) { soil = null; return; } // asked for (wants)
            if (!atHut(w)) goToHut(w);
            else fetch(Material.NETHER_WART, 1);
            return;
        }
        if (!w.isBeside(above)) { w.goBeside(above); return; }
        if (plant) {
            use(Material.NETHER_WART, 1);
            above.setType(Material.NETHER_WART);
            w.swing();
            w.workedLittle();
        } else if (!(above.getType() == Material.NETHER_WART && ((Ageable) above.getBlockData()).getAge() < WART_AGE)) {
            gather(takeContents(above), w); // emptied first, or its contents would be lost
            gather(breakWith(null, above, w), w);
            above.getWorld().playSound(above.getLocation(), above.getBlockData().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1, 1);
            above.setType(Material.AIR);
            w.swing();
            w.workedLittle();
        }
        soil = null;
    }

    /** Hits the leaves it picked once a second until mistletoe falls, which it takes to the hut. */
    private void cutMistletoe(Colonist w) {
        w.setStatus("Alchemist: cutting mistletoe");
        cooldown = STEP_TICKS;
        if (!hasTool(Tool.SHEARS, null)) {
            if (inChest(i -> fits(Tool.SHEARS, i, null)) == 0) { note = "Needs " + toolLabel(Tool.SHEARS) + " to cut mistletoe."; leaf = null; return; }
            if (!atHut(w)) { goToHut(w); return; }
            if (!fetchTool(Tool.SHEARS, null)) leaf = null;
            return;
        }
        if (!Tag.LEAVES.isTagged(leaf.getType())) { leaves.remove(leaf); leaf = null; return; }
        var centre = leaf.getLocation().toCenterLocation();
        if (w.location().distance(centre) > REACH) {
            if (!w.approach(centre, REACH)) { leaves.remove(leaf); leaf = null; } // ours: leaves out of its reach are left alone
            return;
        }
        w.face(centre);
        w.hold(tool(Tool.SHEARS));
        w.swing();
        leaf.getWorld().playSound(centre, leaf.getBlockData().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1, 1);
        leaf.getWorld().spawnParticle(Particle.BLOCK, centre, 10, 0.4, 0.4, 0.4, leaf.getBlockData());
        if (ThreadLocalRandom.current().nextInt(MISTLETOE_ODDS) > 0) return;
        gather(List.of(mistletoe()), w);
        wear(Tool.SHEARS, w);
        w.workedLittle();
        leaf = null;
    }

    private static boolean isWart(ItemStack i) {
        return i.getType() == Material.NETHER_WART;
    }

    // ---- what it asks for -----------------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        findSpots(); // first: what its stands brew counts
        List<Want> wants = new ArrayList<>(super.wants());
        if (hut.level < 1) return wants;
        if (!leaves.isEmpty()) wantTool(wants, Tool.SHEARS);
        if (!soils.isEmpty() && has(Material.NETHER_WART) + inChest(Alchemist::isWart) == 0
                && soils.stream().anyMatch(s -> s.getRelative(BlockFace.UP).getType().isAir())) // nothing else needs it for planting
            wants.add(new Want("nether wart to plant", Alchemist::isWart, WART_ASKED, 1));
        if ((toBrew() || unfuelled()) && !stands.isEmpty() && inChest(Alchemist::isFuel) == 0)
            wants.add(new Want("blaze powder as fuel", Alchemist::isFuel, FUEL * stands.size(), 1));
        return wants;
    }

    /** Shears, an axe, 16 nether wart (the mod's keepX) and (ours) as much fuel as it asks for stay, besides its ingredients. */
    @Override
    int keep(ItemStack item) {
        if (fits(Tool.SHEARS, item, null) || fits(Tool.AXE, item, null)) return 1;
        if (isFuel(item)) return FUEL * stands.size() + super.keep(item);
        return isWart(item) ? Math.max(WART_ASKED, super.keep(item)) : super.keep(item);
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>(super.info(c));
        if (hut.level == 0) return lines;
        findSpots();
        lines.add(Component.text(stands.size() + (stands.size() == 1 ? " brewing stand" : " brewing stands") + " (blaze powder as fuel). With nothing to make it"
                + (soils.isEmpty() ? "" : " tends nether wart on its soul sand and") + " cuts mistletoe from its leaves with shears.", NamedTextColor.GRAY));
        return lines;
    }
}
