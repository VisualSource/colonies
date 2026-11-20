package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Requests;
import dev.minecp.logistics.Warehouse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Block;
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
 * smelts whatever ore it is brought.
 */
public final class Crafter extends Job {
    private static final int HITS = 3;       // the mod's HITTING_TIME at a typical skill (it is 30 with no skill at all)
    private static final int HIT_TICKS = 10; // the mod's HIT_DELAY
    private static final int EXTRA_RECIPES = 5; // the mod's EXTRA_RECIPE_MULTIPLIER

    private static final int FUEL = 16; // ours, as the cook: fuel it keeps on hand, asked for once below this
    private static final int ORES = 32; // ours: ores the Smeltery asks the warehouse for at a time

    private final Predicate<Recipe> accepts;
    /** What this crafter makes, for the menus. */
    public final String makes;
    /** It smelts furnace recipes rather than crafting at a table. */
    public final boolean smelts;
    /** The Smeltery: knows every ore recipe without being taught, and asks for and smelts any ore. */
    public final boolean oreSmelter;
    public final List<NamespacedKey> recipes = new ArrayList<>(); // taught; saved
    /** Requests Requests gave it to craft, refreshed each second. */
    public final List<Requests.Request> tasks = new ArrayList<>();
    private int hits;
    private List<Block> furnaces = List.of();
    private int scan;
    private final Colony colony; // smelters only: for the furnaces in their building; null for table crafters

    public Crafter(Hut hut, Predicate<Recipe> accepts, String makes) {
        this(hut, null, accepts, makes, false, false);
    }

    public Crafter(Hut hut, Colony colony, Predicate<Recipe> accepts, String makes, boolean smelts, boolean oreSmelter) {
        super(hut);
        this.colony = colony;
        this.accepts = accepts;
        this.makes = makes;
        this.smelts = smelts;
        this.oreSmelter = oreSmelter;
        if (oreSmelter) for (Recipe r : teachable()) recipes.add(((Keyed) r).getKey());
    }

    /** Every recipe on the server it could be taught and doesn't know yet. */
    public List<Recipe> teachable() {
        List<Recipe> out = new ArrayList<>();
        for (var it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe r = it.next();
            if (r instanceof Keyed k && !k.getKey().getNamespace().equals("minecp") && !recipes.contains(k.getKey()) && accepts.test(r)) out.add(r);
        }
        return out;
    }

    // ---- recipes ------------------------------------------------------------------------------------------------

    public int maxRecipes() {
        return (1 << hut.level) * EXTRA_RECIPES;
    }

    /** Teaches it a recipe. @return why not, or null when learnt. */
    public String teach(Recipe r) {
        if (oreSmelter) return "It already knows every recipe it can use.";
        if (!(smelts ? r instanceof CookingRecipe<?> : r instanceof ShapedRecipe || r instanceof ShapelessRecipe) || !(r instanceof Keyed k))
            return smelts ? "Only furnace recipes can be taught." : "Only crafting table recipes can be taught.";
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

    /** A taught recipe that makes something matching {@code want}, or null. */
    public Recipe recipeFor(Predicate<ItemStack> want) {
        for (NamespacedKey key : recipes) {
            Recipe r = Bukkit.getRecipe(key);
            if (r != null && want.test(r.getResult())) return r;
        }
        return null;
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
        return out;
    }

    static final double MIN_WOOD_RATIO = 0.75; // the mod's Sawmill: the wood ratio must be above this

    /** The mod's sawmill ratio (CraftRules.SAWMILL): every wood item over every ingredient that isn't planks or logs. */
    static double woodRatio(int planksAndLogs, int otherWood, int rest) {
        int wood = planksAndLogs + otherWood;
        return wood > 0 ? (double) wood / (otherWood + rest) : 0; // wood / 0 is infinity, above 0.75
    }

    private static String label(RecipeChoice c) {
        ItemStack first = c.getItemStack();
        boolean more = c instanceof RecipeChoice.MaterialChoice mc && mc.getChoices().size() > 1;
        return name(first.getType()) + (more ? " or similar" : "");
    }

    // ---- work ---------------------------------------------------------------------------------------------------

    /**
     * How many more times it must craft for this request: what is still wanted, less what is made and on its way.
     * {@code raw}: count everything in the chest, ingredients set aside included (working out those ingredients
     * themselves; otherwise it would ask itself in a loop).
     */
    private int craftsLeft(Requests.Request t, Recipe r, boolean raw) {
        int ready = (raw ? inChest(t.test()) : ready(t.test())) + inFurnaces(r);
        int per = Math.max(1, r.getResult().getAmount());
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
            wants.add(new Want("ores to smelt", Crafter::isOre, ORES - inChest(Crafter::isOre), 1));
        if (smelts && !furnaces.isEmpty() && (!need.isEmpty() || oreSmelter) && inChest(this::isFuel) < FUEL / 2)
            wants.add(new Want("fuel", this::isFuel, FUEL - inChest(this::isFuel), 1)); // as the mod's fuel request: minimum 1
        return wants;
    }

    /** Ingredients for every craft its tasks still need. */
    private Map<RecipeChoice, Integer> needed() {
        Map<RecipeChoice, Integer> need = new LinkedHashMap<>();
        for (Requests.Request t : tasks) {
            Recipe r = recipeFor(t.test());
            if (r == null) continue;
            int crafts = craftsLeft(t, r, true);
            if (crafts > 0) ingredients(r).forEach((c, n) -> need.merge(c, n * crafts, Integer::sum));
        }
        return need;
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
        if (smelts) { smelt(w, chest); return; }
        Recipe ready = null;
        boolean anyWork = false;
        for (Requests.Request t : tasks) {
            Recipe r = recipeFor(t.test());
            if (r == null || craftsLeft(t, r, false) == 0) continue;
            anyWork = true;
            if (hasIngredients(chest, r)) { ready = r; break; }
        }
        if (ready == null) {
            hits = 0;
            idle(w, anyWork ? "Waiting for ingredients in the hut chest." : null, 40, hut.block);
            return;
        }
        if (!atHut(w)) { w.setStatus(hut.type.worker + ": working"); goToHut(w); return; }
        w.setStatus(hut.type.worker + ": working");
        note = null;
        w.hold(ready.getResult());
        w.swing();
        cooldown = HIT_TICKS;
        if (++hits < HITS) return;
        hits = 0;
        craft(chest, ready);
    }

    /** Fills its furnaces from the chest, keeps them fuelled, and empties what they made into the chest. */
    private void smelt(Colonist w, Inventory chest) {
        if (scan-- <= 0) { furnaces = Furnaces.in(colony, hut, Material.FURNACE); scan = 200; }
        if (furnaces.isEmpty()) { idle(w, "No furnace in the building to smelt in.", 40, hut.block); return; }
        if (Furnaces.tend(this, w, furnaces, chest, () -> nextInput(chest), this::isFuel)) return;
        boolean waiting = false;
        for (Requests.Request t : tasks) { Recipe r = recipeFor(t.test()); waiting |= r != null && craftsLeft(t, r, false) > 0; }
        idle(w, waiting ? "Waiting for something to smelt, or fuel, in the hut chest." : null, 40, hut.block);
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

    private boolean hasIngredients(Inventory chest, Recipe r) {
        for (var e : ingredients(r).entrySet()) if (inChest(e.getKey()::test) < e.getValue()) return false;
        return true;
    }

    /** Uses up one craft's ingredients from the chest and puts the result (and what's left, like empty buckets) in. */
    private void craft(Inventory chest, Recipe r) {
        List<ItemStack> used = new ArrayList<>();
        ingredients(r).forEach((c, n) -> Warehouse.take(chest, c::test, n, Integer.MAX_VALUE, used));
        List<ItemStack> out = new ArrayList<>();
        out.add(r.getResult().clone());
        for (ItemStack i : used) { // what's left of an ingredient, like an empty bucket
            Material remains = i.getType().getCraftingRemainingItem();
            if (remains != null) out.add(new ItemStack(remains, i.getAmount()));
        }
        var at = hut.block.getLocation().toCenterLocation();
        for (ItemStack over : chest.addItem(out.toArray(ItemStack[]::new)).values()) at.getWorld().dropItemNaturally(at, over);
    }

    // ---- menus and saving ---------------------------------------------------------------------------------------

    @Override
    public List<Component> info(Colony c) {
        List<Component> lines = new ArrayList<>();
        if (hut.level == 0) {
            lines.add(Component.text("The " + hut.type.worker.toLowerCase() + " starts once level 1 is built.", NamedTextColor.GRAY));
            return lines;
        }
        lines.add(Component.text(oreSmelter ? "Smelts the ores couriers bring from the warehouse, and the ingots huts ask for. Needs fuel and furnaces in its building."
                : (smelts ? "Smelts" : "Crafts") + " what the colony's huts ask for, from the recipes it was taught: " + recipes.size() + " of "
                + maxRecipes() + ". It makes " + makes + "." + (smelts ? " Needs fuel and furnaces in its building." : ""), NamedTextColor.GRAY));
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
