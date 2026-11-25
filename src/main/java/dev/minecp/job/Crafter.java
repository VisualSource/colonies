package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Settings;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Requests;
import dev.minecp.logistics.Warehouse;
import dev.minecp.research.Effects;
import dev.minecp.research.Studies;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Block;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A crafter (the mod's AbstractEntityAICrafting and crafting modules): it is taught recipes of its kind
 * ({@link CraftRules}), up to 2^level x 5, and crafts what the colony's requests ask for (Requests assigns them). It
 * asks for the ingredients like any hut; what it makes waits in its chest until a courier takes it straight to the hut
 * that asked, as the mod's delivery requests do. Smelters (the mod's AbstractEntityAIRequestSmelter) learn furnace
 * recipes and smelt in the furnaces of their building, with fuel they ask for; the Smeltery knows every ore and also
 * smelts whatever ore it is brought. The Dyer and Glassblower do both, as the mod's crafting and smelting modules.
 */
public class Crafter extends Job {
    private static final int HITTING_TIME = 3, PROGRESS_MULTIPLIER = 10, MAX_PROGRESS = 50; // the mod's crafting progress
    private static final int HIT_TICKS = 10; // the mod's HIT_DELAY
    private static final int EXTRA_RECIPES = 5; // the mod's EXTRA_RECIPE_MULTIPLIER

    private static final int FUEL = 16; // ours, as the cook: fuel it keeps on hand, asked for once below this
    private static final int ORES = 32; // ours: ores the Smeltery asks the warehouse for at a time

    /**
     * What it works at: a crafting table, furnaces, both, (the Smeltery) furnaces with every ore recipe known, or (the
     * Crusher, the Concrete Mixer) only its own recipes, never taught (the mod's custom crafting modules), or brewing stands.
     */
    public enum Kind { TABLE, FURNACE, BOTH, ORES, OWN, BREW }

    private final Predicate<Recipe> accepts;
    /** What this crafter makes, for the menus. */
    public final String makes;
    /** It learns crafting table recipes. */
    public final boolean crafts;
    /** It learns furnace recipes and smelts in its building's furnaces. */
    public final boolean smelts;
    /** It learns brewing recipes (the Alchemist). */
    public final boolean brews;
    /** The Smeltery: knows every ore recipe without being taught, and asks for and smelts any ore. */
    public final boolean oreSmelter;
    /** Players teach it recipes (not the Smeltery, Crusher or Concrete Mixer). */
    public final boolean taught;
    public final List<NamespacedKey> recipes = new ArrayList<>(); // taught; saved
    /** Requests Requests gave it to craft, refreshed each second. */
    public final List<Requests.Request> tasks = new ArrayList<>();
    private int hits;
    private List<Block> furnaces = List.of();
    private int scan;

    public Crafter(Hut hut, Colony colony, Predicate<Recipe> accepts, String makes) {
        this(hut, colony, accepts, makes, Kind.TABLE);
    }

    public Crafter(Hut hut, Colony colony, Predicate<Recipe> accepts, String makes, Kind kind) {
        super(hut, colony);
        this.accepts = accepts;
        this.makes = makes;
        this.crafts = kind == Kind.TABLE || kind == Kind.BOTH;
        this.smelts = kind == Kind.FURNACE || kind == Kind.BOTH || kind == Kind.ORES;
        this.brews = kind == Kind.BREW;
        this.oreSmelter = kind == Kind.ORES;
        this.taught = kind != Kind.ORES && kind != Kind.OWN;
        if (oreSmelter) for (Recipe r : teachable()) recipes.add(((Keyed) r).getKey());
    }

    /** Every recipe on the server it could be taught and doesn't know yet. */
    public List<Recipe> teachable() {
        List<Recipe> out = new ArrayList<>();
        for (var it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r = it.next();
            if (r instanceof Keyed k && !k.getKey().getNamespace().equals("minecp") && !recipes.contains(k.getKey()) && learns(r) && accepts.test(r)) out.add(r);
        }
        return out;
    }

    /** Whether it can learn this kind of recipe: for a crafting table, a furnace or a brewing stand. */
    private boolean learns(Recipe r) {
        return r instanceof ShapedRecipe || r instanceof ShapelessRecipe ? crafts
                : r instanceof BrewingRecipe b ? brews && Alchemist.input(b) != null : smelts && r instanceof CookingRecipe<?>;
    }

    // ---- recipes ------------------------------------------------------------------------------------------------

    /** 2^level x 5, more with research (the mod's getMaxRecipes). */
    public int maxRecipes() {
        return (int) ((1 << hut.level) * EXTRA_RECIPES * (1 + colony.research.effect(Effects.RECIPES)));
    }

    /** Teaches it a recipe. @return why not, or null when learnt. */
    public String teach(Recipe r) {
        if (!taught) return "It already knows every recipe it can use.";
        if (!learns(r) || !(r instanceof Keyed k))
            return brews ? "Only brewing recipes can be taught." : !crafts ? "Only furnace recipes can be taught." : !smelts ? "Only crafting table recipes can be taught."
                    : "Only crafting table and furnace recipes can be taught.";
        if (k.getKey().getNamespace().equals("minecp")) return "Huts, racks and goggles can't be taught: they need the Build Tool."; // the mod's blocks aren't crafter recipes either
        if (recipes.contains(k.getKey())) return "It already knows that recipe.";
        if (!accepts.test(r)) return "The " + hut.type.worker + " can't make that: " + makes + ".";
        if (recipes.size() >= maxRecipes()) return "It knows " + maxRecipes() + " recipes, the most at this hut level. Upgrade the hut or forget one.";
        recipes.add(k.getKey());
        return null;
    }

    public static ItemStack resultOf(NamespacedKey key) {
        Recipe r = Bukkit.getRecipe(key);
        return r == null ? null : r.getResult();
    }

    /**
     * Recipes it knows without being taught, each from a hut level (the mod's crafterrecipes, like the baker's bread);
     * they don't count against {@link #maxRecipes()}. A vanilla recipe, or one of the mod's own that the server never
     * registers, so players can't use it.
     */
    public final Map<Recipe, Integer> builtIn = new LinkedHashMap<>();

    /** One of the mod's own crafter recipes, never registered with the server (players can't use it): {@code count} x {@code result}. */
    public static Recipe own(String id, Material result, int count, Map<Material, Integer> ingredients) {
        return own(id, new ItemStack(result, count), ingredients);
    }

    /** The same, making {@code result} (one of Colonies' own items, like a sifter mesh). */
    public static Recipe own(String id, ItemStack result, Map<Material, Integer> ingredients) {
        var r = new ShapelessRecipe(new NamespacedKey("minecp", id), result);
        ingredients.forEach((m, n) -> r.addIngredient(n, m));
        return r;
    }

    /** The research each built-in recipe needs (the mod's research-id and not-research-id); none: always known. */
    final Map<Recipe, Predicate<Studies>> needs = new java.util.HashMap<>();

    /** Knows {@code r} without teaching from hut {@code level}, once {@code when} holds for the colony's research. */
    public void learn(Recipe r, int level, Predicate<Studies> when) {
        builtIn.put(r, level);
        needs.put(r, when);
    }

    /**
     * A taught recipe, or a built-in one its hut level allows, that makes something matching {@code want}, or null: the
     * first found, or with its recipe mode on "most in warehouse" (research) the one whose biggest ingredient the
     * warehouse has most of (the mod's getFirstFulfillableRecipe with MAX_STOCK).
     */
    public Recipe recipeFor(Predicate<ItemStack> want) {
        List<Recipe> found = new ArrayList<>();
        for (NamespacedKey key : recipes) {
            Recipe r = Bukkit.getRecipe(key);
            if (r != null && want.test(r.getResult())) found.add(r);
        }
        for (var e : builtIn.entrySet()) if (hut.level >= e.getValue() && knows(e.getKey()) && makes(e.getKey(), want)) found.add(e.getKey());
        if (found.isEmpty()) return null;
        var warehouse = colony.warehouse();
        if (found.size() > 1 && warehouse != null && Settings.has(hut.type, Settings.RECIPE_MODE)
                && hut.setting(Settings.RECIPE_MODE).equals("MAX_STOCK") && colony.research.effect(Effects.RECIPE_MODE) > 0)
            return found.stream().max(java.util.Comparator.comparingInt(r -> ingredients(r).entrySet().stream().max(Map.Entry.comparingByValue())
                    // the hut chest counts too: what a courier brings over mustn't change the choice mid-job
                    .map(e -> warehouse.count(e.getKey()::test) + inChest(e.getKey()::test)).orElse(0))).get();
        return found.getFirst();
    }

    /**
     * Whether {@code r} makes what is wanted. One of Colonies' own items (a mesh is paper with a tag) only for a want a
     * plain item of its type wouldn't do: an order for paper never gets meshes.
     */
    private static boolean makes(Recipe r, Predicate<ItemStack> want) {
        ItemStack out = r.getResult();
        return want.test(out) && (out.getPersistentDataContainer().isEmpty() || !want.test(new ItemStack(out.getType())));
    }

    // ---- what kinds of crafter change (the Crusher, the Concrete Mixer) ---------------------------------------------

    /** Whether research lets it use this built-in recipe now (the mod's research-id and not-research-id). */
    boolean knows(Recipe builtIn) {
        return needs.getOrDefault(builtIn, s -> true).test(colony.research);
    }

    /** Whether it makes this at the hut (at its table, or in its furnaces when it smelts); the mixer's concrete is made in water. */
    boolean craftsAtHut(Recipe r) {
        return !(r instanceof CookingRecipe<?>);
    }

    /** What it has made with {@code r} that isn't in the chest yet, counted as made: in its furnaces (or, for the mixer, in water). */
    int inProgress(Recipe r) {
        return inFurnaces(r);
    }

    /** What a craft gives besides the result (the crusher's flint from gravel). */
    List<ItemStack> extras(Recipe r) {
        return List.of();
    }

    /** Work of its own when no task is ready to craft (the crusher's daily crushing). @return true if it did some. */
    boolean otherWork(Colonist w, Inventory chest) {
        return false;
    }

    /** Whether the recipe uses something matching {@code test}: a crafter never makes what it would use up (a loop). */
    public static boolean uses(Recipe r, Predicate<ItemStack> test) {
        for (RecipeChoice c : ingredients(r).keySet()) if (test.test(c.getItemStack())) return true;
        return false;
    }

    /** A recipe's ingredients: each different ingredient and how many of it one craft uses. */
    static Map<RecipeChoice, Integer> ingredients(Recipe r) {
        Map<RecipeChoice, Integer> out = new LinkedHashMap<>();
        if (r instanceof ShapedRecipe s) {
            for (String row : s.getShape()) for (char ch : row.toCharArray()) {
                RecipeChoice c = s.getChoiceMap().get(ch);
                if (c != null) out.merge(c, 1, Integer::sum);
            }
        } else if (r instanceof ShapelessRecipe s) for (RecipeChoice c : s.getChoiceList()) out.merge(c, 1, Integer::sum);
        else if (r instanceof CookingRecipe<?> c) out.put(c.getInputChoice(), 1);
        else if (r instanceof BrewingRecipe b && Alchemist.input(b) instanceof ItemStack in) {
            out.put(b.getIngredient(), 1);
            out.put(new RecipeChoice.ExactChoice(in), BOTTLES);
        }
        return out;
    }

    static final double MIN_WOOD_RATIO = 0.75; // the mod's Sawmill: the wood ratio must be above this

    /** The mod's sawmill ratio (CraftRules.SAWMILL): every wood item over every ingredient that isn't planks or logs. */
    static double woodRatio(int planksAndLogs, int otherWood, int rest) {
        int wood = planksAndLogs + otherWood;
        return wood > 0 ? (double) wood / (otherWood + rest) : 0; // wood / 0 is infinity, above 0.75
    }

    static final int BOTTLES = 3; // a stand brews this many at once

    /** How many one craft makes: a brewing stand three bottles (the mod's brewing recipes too). */
    private static int made(Recipe r) {
        return r instanceof BrewingRecipe ? BOTTLES : Math.max(1, r.getResult().getAmount());
    }

    private static String label(RecipeChoice c) {
        ItemStack first = c.getItemStack();
        boolean more = c instanceof RecipeChoice.MaterialChoice mc && mc.getChoices().size() > 1;
        return PostBox.label(first) + (more ? " or similar" : "");
    }

    // ---- work ---------------------------------------------------------------------------------------------------

    /**
     * How many more times it must craft for this request: what is still wanted, less what is made and on its way.
     * {@code raw}: count everything in the chest, ingredients set aside included (working out those ingredients
     * themselves; otherwise it would ask itself in a loop). What it asks itself for is already less what the chest holds.
     */
    int craftsLeft(Requests.Request t, Recipe r, boolean raw) {
        int ready = (t.job == this ? 0 : raw ? inChest(t.test()) : ready(t.test())) + inProgress(r);
        int per = made(r);
        return Math.max(0, (owed(t) - ready + per - 1) / per);
    }

    /** What its furnaces are smelting with {@code r} or have smelted, counted as made: it is on its way to the chest. */
    private int inFurnaces(Recipe r) {
        if (!(r instanceof CookingRecipe<?> c)) return 0;
        int n = 0;
        for (Block b : furnaces) {
            if (!(b.getState(false) instanceof org.bukkit.block.Furnace f)) continue;
            ItemStack in = f.getInventory().getSmelting(), out = f.getInventory().getResult();
            if (in != null && !in.isEmpty() && c.getInputChoice().test(in)) n += in.getAmount() * r.getResult().getAmount();
            if (out != null && !out.isEmpty() && out.isSimilar(r.getResult())) n += out.getAmount();
        }
        return n;
    }

    /** What it still owes this request: its share, but no more than the hut still needs once loads on their way arrive. */
    private static int owed(Requests.Request t) {
        return Math.max(0, Math.min(t.crafterShare, t.amount() - t.inTransit));
    }

    /** The ingredients its tasks still need, so couriers or other crafters bring them. */
    @Override
    public List<Want> wants() {
        Map<RecipeChoice, Integer> need = needed();
        List<Want> wants = new ArrayList<>();
        need.forEach((c, n) -> {
            int more = n - inChest(c::test);
            if (more > 0) wants.add(new Want(label(c), c::test, more));
        });
        if (oreSmelter && hut.level >= 1 && inChest(Crafter::isOre) < ORES / 2) // the mod's smelter asks for any ore, minimum 1
            wants.add(new Want("ores to smelt", Crafter::isOre, ORES - inChest(Crafter::isOre), Math.max(1, hut.number(Settings.MIN_ORE))));
        if (smelts && !furnaces.isEmpty() && (toSmelt(true) || oreSmelter) && inChest(this::isFuel) < FUEL / 2)
            wants.add(new Want("fuel", this::isFuel, FUEL - inChest(this::isFuel), 1)); // as the mod's fuel request: minimum 1
        return wants;
    }

    /** Whether a task still needs something smelted ({@code raw}: as {@link #craftsLeft}). */
    private boolean toSmelt(boolean raw) {
        for (Requests.Request t : tasks) if (recipeFor(t.test()) instanceof CookingRecipe<?> r && craftsLeft(t, r, raw) > 0) return true;
        return false;
    }

    /** Ingredients for every craft its tasks still need, a few crafts at a time where they don't stack. */
    private Map<RecipeChoice, Integer> needed() {
        Map<RecipeChoice, Integer> need = new LinkedHashMap<>();
        for (Requests.Request t : tasks) {
            Recipe r = recipeFor(t.test());
            if (r == null) continue;
            int crafts = craftsLeft(t, r, true);
            if (crafts <= 0) continue;
            Map<RecipeChoice, Integer> one = ingredients(r);
            int single = one.entrySet().stream().mapToInt(e -> e.getKey().getItemStack().getMaxStackSize() == 1 ? e.getValue() : 0).sum();
            int batch = r instanceof BrewingRecipe ? 1 : batch(crafts, single); // ours: a chain of brews piles up bottles at each step
            one.forEach((c, n) -> need.merge(c, n * batch, Integer::sum));
        }
        return need;
    }

    private static final int MOST_SINGLES = 9; // ours: a third of the chest. shortcut: per task, so many orders at once can still fill it; share it out if that happens

    /** How many of {@code crafts} it gets ingredients for at once, when each uses {@code single} that don't stack (bottles would fill the chest). */
    static int batch(int crafts, int single) {
        return single == 0 ? crafts : Math.min(crafts, Math.max(1, MOST_SINGLES / single));
    }

    /** Ingredients for its tasks and what it made for them stay (a courier takes those straight to the hut); the rest goes. */
    @Override
    int keep(ItemStack item) {
        long keep = 0;
        for (var e : needed().entrySet()) if (e.getKey().test(item)) keep += e.getValue(); // the whole ingredient, any log it takes
        for (Requests.Request t : tasks) if (t.test().test(item)) keep += owed(t);
        if (smelts && isFuel(item) || oreSmelter && isOre(item)) return Integer.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE, keep);
    }

    /** How many items matching {@code test} wait in its chest, made and ready to go: not set aside as ingredients. */
    int ready(Predicate<ItemStack> test) {
        Inventory chest = chest();
        if (chest == null) return 0;
        int reserved = 0;
        for (var e : needed().entrySet())
            for (ItemStack i : chest.getContents())
                if (i != null && !i.isEmpty() && test.test(i) && e.getKey().test(i)) { reserved += e.getValue(); break; }
        return Math.max(0, inChest(test) - reserved);
    }

    /**
     * Gives a courier what it made for {@code r}, as many as fit in {@code maxStacks} stacks (the mod's delivery
     * request from the crafter's hut); they count against its share.
     */
    List<ItemStack> handOver(Requests.Request r, int maxStacks) {
        List<ItemStack> taken = new ArrayList<>();
        Inventory chest = chest();
        if (chest != null) r.crafterShare -= Warehouse.take(chest, r.test(), Math.min(owed(r), ready(r.test())), maxStacks, taken);
        return taken;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus(hut.type.worker + ": idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Inventory chest = chest();
        if (chest == null) return;
        if (tend(w, chest)) return;
        Recipe ready = null;
        boolean anyWork = false;
        for (Requests.Request t : tasks) {
            Recipe r = recipeFor(t.test());
            if (r == null || !craftsAtHut(r) || craftsLeft(t, r, false) == 0) continue;
            anyWork = true;
            if (hasIngredients(chest, r)) { ready = r; break; }
        }
        if (ready == null) {
            hits = 0;
            if (otherWork(w, chest)) return;
            idle(w, anyWork ? "Waiting for ingredients in the hut chest." : smeltNote(), 40, hut.block);
            return;
        }
        if (!atHut(w)) { w.setStatus(hut.type.worker + ": working"); goToHut(w); return; }
        w.setStatus(hut.type.worker + ": working");
        note = null;
        w.hold(ready.getResult());
        w.swing();
        cooldown = HIT_TICKS;
        if (++hits < hits()) return;
        hits = 0;
        craft(chest, ready);
        w.worked(); // one action per craft, as the mod's
        experience(w, ready.getResult().getAmount() / 2.0); // the mod's: half the request's count when done; ours: per craft
    }

    /** A step at what it works with besides its table: its furnaces (the Alchemist's stands). @return true if it did something there. */
    boolean tend(Colonist w, Inventory chest) {
        return smelts && smelt(w, chest);
    }

    /**
     * Fills its furnaces from the chest, keeps them fuelled, and empties what they made into the chest.
     * @return true if it did something with them this time.
     */
    private boolean smelt(Colonist w, Inventory chest) {
        if (scan-- <= 0) { furnaces = Furnaces.in(colony, hut, Material.FURNACE); scan = 200; }
        // the request smelters use at most their main skill / 10 + 1 furnaces at once; the Smeltery's ore smelter (the mod's AbstractEntityAIUsesFurnace) all
        int most = oreSmelter ? Integer.MAX_VALUE : skill(0) / 10 + 1;
        return !furnaces.isEmpty() && Furnaces.tend(this, w, furnaces, chest, () -> nextInput(chest), this::isFuel, most);
    }

    /**
     * Its worker speeds its furnaces up: its skill / 10 x 2 ticks a second, the second skill for the request smelters,
     * the main one for the Smeltery's (the mod's AbstractEntityAIUsesFurnace).
     */
    @Override
    public void everySecond() {
        if (smelts && colony.worker(this) != null) Furnaces.speedUp(furnaces, skill(oreSmelter ? 0 : 1) / 10 * Furnaces.SPEED_UP_PER_10);
    }

    /** Why its furnaces stand idle, for the hut menu, or null. */
    private String smeltNote() {
        if (!smelts) return null;
        if (furnaces.isEmpty()) return toSmelt(true) || oreSmelter ? "No furnace in the building to smelt in." : null;
        if (Furnaces.stuck(furnaces, chest())) return "The hut chest is full.";
        return toSmelt(false) ? "Waiting for something to smelt, or fuel, in the hut chest." : null;
    }

    /**
     * Fuel for this smelter: anything that burns, except what its recipes smelt or make (the Stone Smeltery's logs
     * become charcoal: burning them, or the charcoal, would undo its work).
     */
    private boolean isFuel(ItemStack i) {
        if (!Furnaces.isFuel(i)) return false;
        for (NamespacedKey key : recipes)
            if (Bukkit.getRecipe(key) instanceof CookingRecipe<?> r && (r.getInputChoice().test(i) || r.getResult().getType() == i.getType())) return false;
        return true;
    }

    /** What to put in an empty furnace: the input of a task still to make (no more than it needs), else any ore it knows. */
    private ItemStack nextInput(Inventory chest) {
        for (Requests.Request t : tasks) {
            Recipe r = recipeFor(t.test());
            int crafts = r instanceof CookingRecipe<?> ? craftsLeft(t, r, false) : 0;
            if (crafts <= 0) continue;
            for (ItemStack i : chest.getContents())
                if (i != null && !i.isEmpty() && ((CookingRecipe<?>) r).getInputChoice().test(i)) return i.asQuantity(Math.min(i.getAmount(), crafts));
        }
        if (oreSmelter) for (ItemStack i : chest.getContents()) if (i != null && !i.isEmpty() && isOre(i)) return i;
        return null;
    }

    private static boolean isOre(ItemStack i) {
        return CraftRules.isOre(i.getType());
    }

    boolean hasIngredients(Inventory chest, Recipe r) {
        for (var e : ingredients(r).entrySet()) if (inChest(e.getKey()::test) < e.getValue()) return false;
        return true;
    }

    /** Uses up one craft's ingredients from the chest and puts the result (and what's left, like empty buckets) in. */
    void craft(Inventory chest, Recipe r) {
        List<ItemStack> used = new ArrayList<>();
        ingredients(r).forEach((c, n) -> Warehouse.take(chest, c::test, n, Integer.MAX_VALUE, used));
        List<ItemStack> out = new ArrayList<>();
        out.add(r.getResult().clone());
        out.addAll(extras(r));
        for (ItemStack i : used) { // what's left of an ingredient, like an empty bucket
            Material remains = i.getType().getCraftingRemainingItem();
            if (remains != null) out.add(new ItemStack(remains, i.getAmount()));
        }
        var at = hut.block.getLocation().toCenterLocation();
        for (ItemStack over : chest.addItem(out.toArray(ItemStack[]::new)).values()) at.getWorld().dropItemNaturally(at, over);
    }

    // ---- menus and saving ---------------------------------------------------------------------------------------

    /**
     * Swings per craft (the mod's getRequiredProgressForMakingRawMaterial): 10 / (its crafting skill / 2 + 1) x 3, in
     * whole numbers: 30 at level 1, 3 from level 8, none from level 20. The crafting skill is its main one (the baker's dexterity).
     */
    private int hits() {
        int speed = hut.type == Hut.Type.BAKER ? skill(1) : skill(0);
        return PROGRESS_MULTIPLIER / Math.min(speed / 2 + 1, MAX_PROGRESS) * HITTING_TIME;
    }

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>();
        if (hut.level == 0) {
            lines.add(Component.text("The " + hut.type.worker.toLowerCase() + " starts once level 1 is built.", NamedTextColor.GRAY));
            return lines;
        }
        lines.add(Component.text(oreSmelter ? "Smelts the ores couriers bring from the warehouse, and the ingots huts ask for. Needs fuel and furnaces in its building."
                : !taught ? "Makes what the colony's huts ask for from its own recipes: " + makes + "."
                : (brews ? "Brews" : crafts && smelts ? "Crafts and smelts" : smelts ? "Smelts" : "Crafts") + " what the colony's huts ask for, from the recipes it was taught: " + recipes.size() + " of "
                + maxRecipes() + ". It makes " + makes + "." + (smelts ? " Needs fuel and furnaces in its building." : ""), NamedTextColor.GRAY));
        if (!builtIn.isEmpty()) {
            List<String> now = new ArrayList<>(), later = new ArrayList<>();
            builtIn.forEach((r, level) -> { if (knows(r)) (hut.level >= level ? now : later)
                    .add(PostBox.label(r.getResult()) + (hut.level >= level ? "" : " (level " + level + ")")); });
            if (!now.isEmpty() || !later.isEmpty()) lines.add(Component.text("Knows without teaching: " + String.join(", ", now) + (later.isEmpty() ? "" : "; later " + String.join(", ", later)) + ".",
                    NamedTextColor.GRAY));
        }
        for (Requests.Request t : tasks)
            lines.add(Component.text("Crafting " + owed(t) + " × " + t.label() + " for the " + t.job.hut.type.title + "."));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        if (!oreSmelter) s.set("recipes", recipes.stream().map(NamespacedKey::toString).toList());
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String k : s.getStringList("recipes")) {
            NamespacedKey key = NamespacedKey.fromString(k);
            if (key != null && Bukkit.getRecipe(key) != null && !recipes.contains(key)) recipes.add(key);
        }
    }
}
