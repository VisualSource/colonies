package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import static dev.minecp.citizen.Skills.Skill.*;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.damage.DamageType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** A job at a worker hut: one colonist works it, using the hut's chest. */
public abstract class Job {
    public final Hut hut;
    final Colony colony; // the colony it works for
    public UUID worker; // colonist id, or null while nobody has the job
    public String note; // why the worker is stuck, in full for the hut menu (the name tag stays short)
    int cooldown; // ticks until the worker acts again

    /** A citizen's inventory slots: the mod's 27, more with research. */
    int slots() {
        return colony.inventorySlots();
    }

    /** Items fetched per trip: a full inventory. */
    int carryLimit() {
        return slots() * 64;
    }

    /** Ticks for a work step, shortened by a speed research ({@link Effects#BREAK_SPEED}, {@link Effects#PLACE_SPEED}) as the mod's delays. */
    int faster(int ticks, String speedEffect) {
        return Math.max(1, (int) (ticks * (1 - colony.research.effect(speedEffect))));
    }

    private static final int BLOCK_MINING_DELAY = 500;  // AbstractEntityAIInteract
    private static final double LEVEL_MODIFIER = 0.85;
    private static final int BUILD_BLOCK_DELAY = 15, PROGRESS_MULTIPLIER = 10; // AbstractEntityAIStructure, AbstractEntityAICrafting

    /**
     * Ticks to break {@code b} with its tool of this kind (null: by hand) at a break-speed skill {@code level} (the
     * mod's calculateWorkerMiningDelay): 500 x 0.85^(level / 2) x the block's hardness / the tool's speed on it (1 by
     * hand or with the wrong tool), less the break-speed research.
     */
    int breakTicks(Block b, Tool kind, int level) {
        if (b.isLiquid()) return 0; // flowed over, not mined: vanilla's hardness 100 would stall a build for minutes
        int slot = kind == null ? -1 : toolSlot(kind, b);
        ItemStack tool = slot < 0 ? null : bag().getItem(slot);
        double ticks = BLOCK_MINING_DELAY * Math.pow(LEVEL_MODIFIER, level / 2.0) * Math.max(0, b.getType().getHardness()) / Tool.speed(tool, b.getType());
        return (int) (ticks * (1 - colony.research.effect(Effects.BREAK_SPEED)));
    }

    /** Ticks to place a block at a place-speed skill {@code level} (the mod's): 150 / (level / 2 + 10), less the place-speed research. */
    int placeTicks(int level) {
        return (int) (BUILD_BLOCK_DELAY * PROGRESS_MULTIPLIER / (level / 2 + PROGRESS_MULTIPLIER) * (1 - colony.research.effect(Effects.PLACE_SPEED)));
    }

    // ---- what a kind of job adds: the hut menu, saving, shutting down ----------------------------------------

    /** Its worker's main and second skill (the mod's WorkerBuildingModule skills for each building); null for huts without a worker. */
    public Skill[] skills() {
        return skillsOf(hut.type);
    }

    static Skill[] skillsOf(Hut.Type t) {
        return switch (t) {
            case BUILDER -> sk(ADAPTABILITY, ATHLETICS);
            case LUMBERJACK -> sk(STRENGTH, FOCUS);
            case FARMER, COMPOSTER -> sk(STAMINA, ATHLETICS);
            case COOK -> sk(ADAPTABILITY, KNOWLEDGE);
            case COURIER -> sk(AGILITY, ADAPTABILITY);
            case MINER -> sk(STRENGTH, STAMINA);
            case SAWMILL, BAKER -> sk(KNOWLEDGE, DEXTERITY);
            case STONEMASON, DYER -> sk(CREATIVITY, DEXTERITY);
            case STONE_SMELTERY -> sk(ATHLETICS, DEXTERITY);
            case BLACKSMITH -> sk(STRENGTH, FOCUS);
            case SMELTERY -> sk(ATHLETICS, STRENGTH);
            case MECHANIC -> sk(KNOWLEDGE, AGILITY);
            case FLETCHER -> sk(DEXTERITY, CREATIVITY);
            case GLASSBLOWER -> sk(CREATIVITY, FOCUS);
            case CRUSHER -> sk(STAMINA, STRENGTH);
            case SIFTER, SHEPHERD -> sk(FOCUS, STRENGTH);
            case CONCRETE_MIXER -> sk(STAMINA, DEXTERITY);
            case FISHERMAN -> sk(FOCUS, AGILITY);
            case CHICKEN_HERDER -> sk(ADAPTABILITY, AGILITY);
            case COWBOY -> sk(ATHLETICS, STAMINA);
            case SWINEHERD -> sk(STRENGTH, ATHLETICS);
            case RABBIT_HUTCH -> sk(AGILITY, ATHLETICS);
            case GRAVEYARD -> sk(STRENGTH, MANA);
            case HOSPITAL -> sk(MANA, KNOWLEDGE);
            case UNIVERSITY, SCHOOL -> sk(KNOWLEDGE, MANA);
            case LIBRARY -> sk(INTELLIGENCE, INTELLIGENCE);
            case PLANTATION -> sk(AGILITY, DEXTERITY);
            case BEEKEEPER -> sk(DEXTERITY, ADAPTABILITY);
            case GUARD_TOWER, BARRACKS_TOWER -> sk(ADAPTABILITY, STAMINA); // a knight's; Guard picks by its type
            default -> null;
        };
    }

    static Skill[] sk(Skill primary, Skill secondary) {
        return new Skill[]{primary, secondary};
    }

    /** The mod's getEffectiveSkillLevel: (level + 1) x 2 - ((level + 1) / 10)². */
    static int effective(int level) {
        return (int) ((level + 1) * 2 - Math.pow((level + 1) / 10.0, 2));
    }

    /** Its worker's level in a skill; 1 with nobody working here. */
    int level(Skill s) {
        Colonist w = colony.worker(this);
        return w == null ? 1 : w.skills.level(s);
    }

    /** Its worker's level in its main (0) or second (1) skill; 1 with nobody working here. */
    int skill(int which) {
        Colonist w = colony.worker(this);
        var s = skills();
        return w == null || s == null ? 1 : w.skills.level(s[which]);
    }

    /**
     * Experience orbs its worker picked up: first Mending repairs (2 experience a point of durability, the mod's
     * applyMending; ours: Mending tools and other items in its inventory, the mod's what it holds and wears), the rest
     * into its skills.
     */
    public void absorb(Colonist w, double xp) {
        Inventory bag = bag();
        if (bag != null) for (int i = 0; i < bag.getSize() && xp > 0; i++) {
            ItemStack item = bag.getItem(i);
            if (item == null || !item.containsEnchantment(org.bukkit.enchantments.Enchantment.MENDING)
                    || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) || d.getDamage() <= 0) continue;
            double healed = Math.min(xp / 2, d.getDamage());
            xp -= healed * 2;
            d.setDamage(d.getDamage() - (int) Math.ceil(healed));
            item.setItemMeta(d);
        }
        if (xp > 0) experience(w, xp);
    }

    /** Work experience for its worker, into its job's skills (the mod's addExperience). */
    void experience(Colonist w, double xp) {
        var s = skills();
        if (s != null) w.gainExperience(xp, s[0], s[1], hut.level, colony.research.effect(Effects.LEVELING));
    }

    /** What its worker is called in the hut menu ("Cook"; a guard by its type). */
    public String title() {
        return hut.type.worker;
    }

    /** Lines for the hut menu, under the worker and its status (the note is added after them). */
    public List<Component> info(Colony c) {
        return List.of();
    }

    /** What the hut menu's "Materials needed" lists, or null for no such button. */
    public Map<ItemStack, Integer> materials() {
        return null;
    }

    /** Saves this job's state; kinds of job add their own. What the worker holds is in its inventory, saved with it. */
    public void save(ConfigurationSection s, Colony c) {
        if (worker != null) s.set("worker", worker.toString());
    }

    /** Loads what {@link #save} wrote. Called once every hut and job of the colony exists. */
    public void load(ConfigurationSection s, Colony c) {
        String w = s.getString("worker");
        if (w != null) worker = UUID.fromString(w);
    }

    /** The colony's entities are going away (shutdown, disband): put back what isn't saved. */
    public void stow() {}

    // ---- shared helpers --------------------------------------------------------------------------------------

    /** Waits {@code ticks}, showing why in the hut menu; walks beside {@code near} first (null: stays where it is). */
    void idle(Colonist w, String why, int ticks, Block near) {
        note = why;
        w.setStatus(hut.type.worker + ": idle");
        if (near != null && !w.isBeside(near)) w.goBeside(near);
        cooldown = ticks;
    }

    /** An item's name as players read it: "oak planks". */
    public static String name(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    /**
     * Something the hut wants brought to its chest: items matching {@code test}, {@code amount} more. {@code min}: the least the warehouse must have to take the request (the mod's minimum count); by default the
     * whole amount, as the mod's crafters ask for their ingredients.
     */
    public record Want(String label, Predicate<ItemStack> test, int amount, int min) {
        public Want(String label, Predicate<ItemStack> test, int amount) {
            this(label, test, amount, amount);
        }
    }

    /** What this hut wants delivered from the warehouse. */
    public List<Want> wants() {
        return List.of();
    }

    /** What the hut chest keeps of {@code item} for every job at the hut (a barracks tower's guards share it). */
    int keptHere(ItemStack item) {
        long n = 0;
        for (Job j : colony.jobsAt(hut)) n += j.keep(item);
        return (int) Math.min(n, Integer.MAX_VALUE); // most keep everything: MAX_VALUE
    }

    /** How many of this item the hut keeps in its chest; a courier takes the rest to the warehouse. */
    int keep(ItemStack item) {
        return Integer.MAX_VALUE;
    }

    /** How many items matching {@code test} the hut chest holds. */
    int inChest(Predicate<ItemStack> test) {
        Inventory chest = chest();
        int n = 0;
        if (chest != null) for (ItemStack i : chest.getContents()) if (i != null && test.test(i)) n += i.getAmount();
        return n;
    }

    /** A want for one tool of this kind, unless the worker holds one or the chest has one. */
    void wantTool(List<Want> wants, Tool kind) {
        if (!hasTool(kind, null) && inChest(i -> fits(kind, i, null)) == 0) wants.add(new Want(toolLabel(kind), i -> fits(kind, i, null), 1));
    }

    Job(Hut hut, Colony colony) {
        this.hut = hut;
        this.colony = colony;
    }

    /** How much faster than others this job makes a citizen hungry (the mod: farmer and lumberjack 1.2). */
    public double hungerFactor() {
        return 1.0;
    }

    /** One tick of work for the worker, whose entities are spawned. */
    public abstract void tick(Colonist w);

    /** Once a second, worker or not (the composter's barrels ferment on their own). */
    public void everySecond() {}

    /** Called each tick while work stops (night, rain). @return false while it is still getting out of its work place. */
    public boolean leaveWork(Colonist w) {
        return true;
    }

    /** Most health its worker can have (the knight has more). */
    public double maxHealth() {
        return Colonist.MAX_HEALTH;
    }

    /**
     * @return the damage of a hit of this {@code type} (already capped at 20% of max health) that reaches its worker
     * {@code w}, from {@code source} (the shooter of a projectile; null: no entity): 0 for none (a miner with Hot Boots
     * in fire); the knight's research, shield and armor take some.
     */
    public double reaching(double damage, DamageType type, Entity source, Colonist w) {
        return damage;
    }

    /** Fire (lava, magma, campfires, fireballs too) or lightning, by vanilla's damage type tags, as the mod's damage checks. */
    public static boolean fireOrLightning(DamageType type) {
        return DamageTypeTags.IS_FIRE.isTagged(type) || DamageTypeTags.IS_LIGHTNING.isTagged(type);
    }

    /** Its worker was hurt by {@code source} (null: no entity): true if it stands and fights instead of running away. */
    public boolean fightsBack(org.bukkit.entity.Entity source) {
        return false;
    }

    /** Whether its worker goes to bed at night (the knight stays on duty). */
    public boolean sleepsAtNight() {
        return true;
    }

    /** A tool for digging this hut allows (not a sword): workers that dig keep these in their chest. */
    boolean isDiggingTool(ItemStack item) {
        for (Tool t : Tool.values()) if (t.digs() && fits(t, item, null)) return true;
        return false;
    }

    /** How likely its worker is to fall sick, against others' 1 (the mod's getDiseaseModifier: miners and swineherds 2). */
    public double diseaseModifier() {
        return 1;
    }

    /** Whether its worker may leave the job now to be treated (the mod's canAIBeInterrupted: not a knight in a fight). */
    public boolean interruptible() {
        return true;
    }

    /** Walking speed multiplier for what it is doing now (1: walking; an undertaker may run to a grave). */
    public double pace() {
        return 1;
    }

    /** The mod lets only the cook and fully upgraded huts' workers work in the rain. */
    public boolean worksInRain() {
        return hut.level >= Hut.MAX_LEVEL;
    }

    Inventory chest() {
        return hut.block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    // ---- the worker's inventory (the mod's citizen inventory): its tools, what it carries, food and gifts ----------

    /** Its worker's inventory, or null while nobody works here. Players can open it and give the worker what it needs. */
    Inventory bag() {
        Colonist w = colony.worker(this);
        return w == null ? null : w.inventory(slots());
    }

    /** How many items matching {@code test} its worker holds. */
    int has(Predicate<ItemStack> test) {
        Inventory bag = bag();
        int n = 0;
        if (bag != null) for (ItemStack i : bag.getContents()) if (i != null && !i.isEmpty() && test.test(i)) n += i.getAmount();
        return n;
    }

    int has(Material m) {
        return has(i -> i.getType() == m);
    }

    /** Empty slots in its worker's inventory. */
    int freeSlots() {
        Inventory bag = bag();
        int n = 0;
        if (bag != null) for (ItemStack i : bag.getStorageContents()) if (i == null || i.isEmpty()) n++;
        return n;
    }

    /** Whether its worker's inventory has no free slot left. */
    boolean bagFull() {
        Inventory bag = bag();
        return bag == null || bag.firstEmpty() < 0;
    }

    /** Puts these in its worker's inventory; what doesn't fit drops at the worker's feet (as the mod's: it can't pick it up). */
    void gather(Collection<ItemStack> items, Colonist w) {
        Inventory bag = bag();
        Location at = w.location();
        for (ItemStack i : items) {
            if (i == null || i.isEmpty()) continue;
            for (ItemStack over : bag == null ? List.of(i) : bag.addItem(i.clone()).values()) at.getWorld().dropItemNaturally(at, over);
        }
    }

    void gather(Material m, int n, Colonist w) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = n; left > 0; left -= m.getMaxStackSize()) stacks.add(new ItemStack(m, Math.min(left, m.getMaxStackSize())));
        gather(stacks, w);
    }

    /** Uses up {@code n} items matching {@code test} from its worker's inventory. @return how many it had to use. */
    int use(Predicate<ItemStack> test, int n) {
        Inventory bag = bag();
        int used = 0;
        if (bag != null) for (int i = 0; i < bag.getSize() && used < n; i++) {
            ItemStack it = bag.getItem(i);
            if (it == null || it.isEmpty() || !test.test(it)) continue;
            int take = Math.min(n - used, it.getAmount());
            it.subtract(take);
            bag.setItem(i, it.isEmpty() ? null : it);
            used += take;
        }
        return used;
    }

    /** Uses up {@code n} of {@code m} from its worker's inventory. @return how many it had to use. */
    int use(Material m, int n) {
        Inventory bag = bag();
        if (bag == null || n <= 0) return 0;
        int missing = bag.removeItem(new ItemStack(m, n)).values().stream().mapToInt(ItemStack::getAmount).sum();
        return n - missing;
    }

    /**
     * Takes up to {@code n} items matching {@code test} from the hut chest into its worker's inventory, as many as fit
     * (the rest stay in the chest). The worker must be at the hut. @return how many it took.
     */
    int fetch(Predicate<ItemStack> test, int n) {
        Inventory chest = chest(), bag = bag();
        if (chest == null || bag == null || n <= 0) return 0;
        List<ItemStack> taken = new ArrayList<>();
        int got = dev.minecp.logistics.Warehouse.take(chest, test, n, Integer.MAX_VALUE, taken);
        for (ItemStack back : bag.addItem(taken.toArray(ItemStack[]::new)).values()) { got -= back.getAmount(); chest.addItem(back); }
        return got;
    }

    int fetch(Material m, int n) {
        return fetch(i -> i.getType() == m && i.getPersistentDataContainer().isEmpty(), n);
    }

    /** What a kind of job keeps on hand when its worker hands things in, besides its tools and a stack of food (the herder's breeding item). */
    boolean keepsOnHand(ItemStack i) {
        return false;
    }

    /** Whether its worker keeps a tool of each kind and a stack of food when it hands things in (not a courier: all it carries is for others). */
    boolean keepsOwn() {
        return true;
    }

    /**
     * The slots of its worker's inventory it would hand in: all but one tool of each kind (the first; more, from a grave
     * or caught as junk, go in), its first stack of food (to eat; ours: so a farmer's carrots still go to the hut) and
     * what its job keeps on hand.
     */
    private List<Integer> handInSlots() {
        Inventory bag = bag();
        List<Integer> out = new ArrayList<>();
        if (bag == null) return out;
        boolean food = false;
        java.util.Set<Tool> tools = java.util.EnumSet.noneOf(Tool.class);
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack it = bag.getItem(i);
            if (it == null || it.isEmpty()) continue;
            if (keepsOwn()) {
                Tool kind = Tool.kindOf(it);
                if (kind != null && tools.add(kind) || keepsOnHand(it)) continue;
                if (!food && dev.minecp.citizen.Food.isMeal(it)) { food = true; continue; }
            }
            out.add(i);
        }
        return out;
    }

    /** Takes out of its worker's inventory everything it would hand in, and returns it (a courier's load, a catch). */
    List<ItemStack> unload() {
        Inventory bag = bag();
        List<ItemStack> out = new ArrayList<>();
        for (int i : handInSlots()) { out.add(bag.getItem(i)); bag.setItem(i, null); }
        return out;
    }

    /** What its worker would hand in, by type (what it keeps aside). */
    Map<Material, Integer> holding() {
        Map<Material, Integer> out = new java.util.EnumMap<>(Material.class);
        Inventory bag = bag();
        for (int i : handInSlots()) out.merge(bag.getItem(i).getType(), bag.getItem(i).getAmount(), Integer::sum);
        return out;
    }

    /**
     * Hands {@code n} of {@code m} its worker would hand in into the hut chest: the stacks themselves, a renamed one too.
     * @return how many it didn't (they didn't fit, or it held fewer).
     */
    int handIn(Material m, int n) {
        Inventory chest = chest(), bag = bag();
        if (chest == null || bag == null) return n;
        for (int i : handInSlots()) {
            ItemStack it = bag.getItem(i);
            if (n <= 0 || it.getType() != m) continue;
            ItemStack give = it.asQuantity(Math.min(n, it.getAmount()));
            int left = chest.addItem(give.clone()).values().stream().mapToInt(ItemStack::getAmount).sum(), moved = give.getAmount() - left;
            it.subtract(moved);
            bag.setItem(i, it.isEmpty() ? null : it);
            n -= moved;
            if (left > 0) break; // the chest is full
        }
        return n;
    }

    /** Whether its worker holds anything it would hand in. */
    boolean loaded() {
        return !handInSlots().isEmpty();
    }

    /** Hands into the hut chest what its worker carries but keeps on hand; what doesn't fit stays with it. @return true if all fit. */
    boolean deliver() {
        Inventory chest = chest(), bag = bag();
        if (chest == null || bag == null) return false;
        List<ItemStack> left = new ArrayList<>(chest.addItem(unload().toArray(ItemStack[]::new)).values());
        for (ItemStack i : left) bag.addItem(i);
        return left.isEmpty();
    }

    /** The hut is gone or the job ends: its worker keeps what it holds (the mod's); kinds of job drop what is the hut's own. */
    public void dropCarried() {}

    /**
     * The best tool tier this hut's worker may use (the mod's getMaxEquipmentLevel): stone up to hut level 1, then the
     * hut's level, anything at the top level. Better tools stay in the chest: the hut must be upgraded first.
     */
    int maxToolTier() {
        return hut.level >= Hut.MAX_LEVEL ? Integer.MAX_VALUE : Math.max(hut.level, 1);
    }

    /** Whether {@code item} is a tool of this kind good enough for {@code block} (null: any) that this hut allows. */
    boolean fits(Tool kind, ItemStack item, Block block) {
        return kind.fits(item, block) && Tool.tier(item) <= maxToolTier();
    }

    /** What to ask for: the tool kind and, below the top level, the best tier this hut allows. */
    String toolLabel(Tool kind) {
        int max = maxToolTier();
        if (max == Integer.MAX_VALUE) return kind.label;
        if (!kind.digs() && kind != Tool.SWORD) // rods and shears have no material: only their enchantments count
            return kind.label + " (enchanted up to level " + (max + 1) + " until the hut is upgraded)";
        return kind.label + " (" + Tool.tierName(max) + " or lower until the hut is upgraded)";
    }

    /** What the job holds of its worker's beyond its inventory, taken off it (it died: into its grave). The inventory goes too (Health). */
    public List<ItemStack> takeCarried() {
        return new ArrayList<>();
    }

    /**
     * What a block that holds items (chest, barrel, shulker box, furnace…) has inside, as copies: its own half only
     * for a double chest. getDrops leaves contents out.
     */
    public static List<ItemStack> contents(Block b) {
        Inventory inv = inventoryOf(b);
        List<ItemStack> items = new ArrayList<>();
        if (inv != null) for (ItemStack i : inv.getContents()) if (i != null && !i.isEmpty()) items.add(i.clone());
        return items;
    }

    /** Same, and empties the block, so breaking it afterwards drops just the block. */
    public static List<ItemStack> takeContents(Block b) {
        List<ItemStack> items = contents(b);
        Inventory inv = inventoryOf(b);
        if (inv != null) inv.clear();
        return items;
    }

    private static Inventory inventoryOf(Block b) {
        if (!(b.getState(false) instanceof org.bukkit.inventory.BlockInventoryHolder holder)) return null;
        return holder instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : holder.getInventory();
    }

    /** The slot of a tool of this kind in its worker's inventory, one this hut allows and good for {@code block} (null: any), or -1. */
    private int toolSlot(Tool kind, Block block) {
        Inventory bag = bag();
        if (bag != null) for (int i = 0; i < bag.getSize(); i++) if (fits(kind, bag.getItem(i), block)) return i;
        return -1;
    }

    /** Its worker's tool of this kind (any block), or null. */
    ItemStack tool(Tool kind) {
        int slot = toolSlot(kind, null);
        return slot < 0 ? null : bag().getItem(slot);
    }

    /** Whether the worker holds a tool of this kind that can break {@code block} (null: any block). */
    boolean hasTool(Tool kind, Block block) {
        return toolSlot(kind, block) >= 0;
    }

    /**
     * Takes a fitting tool of this kind from the hut chest into its worker's inventory (the worker must be at the hut);
     * one of that kind it holds that won't do goes back. @return false if the chest has none.
     */
    boolean fetchTool(Tool kind, Block block) {
        Inventory chest = chest(), bag = bag();
        if (chest == null || bag == null) return false;
        if (bag.firstEmpty() < 0) deliver(); // it stands at the chest: make room by handing in, rather than seem to lack the tool
        int best = -1; // the mod takes the lowest tier that will do, keeping better tools for harder blocks
        for (int i = 0; i < chest.getSize(); i++)
            if (fits(kind, chest.getItem(i), block) && (best < 0 || Tool.tier(chest.getItem(i)) < Tool.tier(chest.getItem(best)))) best = i;
        if (best < 0) return false;
        for (int i = 0; i < bag.getSize(); i++) { // the one it replaces
            ItemStack old = bag.getItem(i);
            if (old != null && kind.fits(old, null) && !fits(kind, old, block)) { bag.setItem(i, null); for (ItemStack over : chest.addItem(old).values()) bag.setItem(i, over); }
        }
        ItemStack item = chest.getItem(best);
        if (!bag.addItem(item.asOne()).isEmpty()) return false; // no room for it
        item.subtract();
        chest.setItem(best, item.isEmpty() ? null : item);
        return true;
    }

    /**
     * Breaks {@code block} with its tool of this kind (null: by hand): drops as that tool would get them, the tool
     * shows in the worker's hand and wears down, and is gone when it breaks.
     */
    Collection<ItemStack> breakWith(Tool kind, Block block, Colonist w) {
        int slot = kind == null ? -1 : toolSlot(kind, block); // the one good enough for it, if it holds several
        if (kind != null && slot < 0) slot = toolSlot(kind, null);
        Collection<ItemStack> drops = drops(block, slot < 0 ? null : bag().getItem(slot));
        if (slot >= 0) wearSlot(slot, w);
        experience(w, XP_PER_BLOCK);
        return drops;
    }

    /**
     * What a worker breaking {@code b} with {@code tool} (null: by hand) gets: a storage rack comes back as a rack (its
     * plain drop would be a chest named "Storage Rack" that works as neither); anything else drops as vanilla's.
     */
    static Collection<ItemStack> drops(Block b, ItemStack tool) {
        if (dev.minecp.logistics.Rack.is(b)) return List.of(dev.minecp.logistics.Rack.item());
        return tool == null ? b.getDrops() : b.getDrops(tool);
    }

    static final double XP_PER_BLOCK = 0.05; // AbstractEntityAIInteract.mineBlock: each block a worker breaks

    /** One work action: the worker swings, and it counts towards its hunger (the mod's incrementActionsDoneAndDecSaturation). */
    void act(Colonist w) {
        w.swing();
        w.worked();
    }

    /** Uses the held tool of this kind once: it shows in the worker's hand, wears down, and is gone when it breaks. */
    void wear(Tool kind, Colonist w) {
        int slot = toolSlot(kind, null);
        if (slot >= 0) wearSlot(slot, w);
    }

    private void wearSlot(int slot, Colonist w) {
        Inventory bag = bag();
        ItemStack tool = bag.getItem(slot);
        w.hold(tool);
        double lasts = colony.research.effect(Effects.TOOL_DURABILITY); // research: skips a use with chance 1 - 1/(1+strength), as the mod
        if (lasts > 0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 1 / (1 + lasts)) return;
        ItemStack worn = tool.damage(1, w.living()); // Unbreaking and the break animation included
        bag.setItem(slot, worn.isEmpty() ? null : worn);
        if (worn.isEmpty()) w.hold(null);
    }

    /**
     * Within reach of the hut chest to take or put things: on any spot {@link Colonist#walkBeside} walks to (up to
     * 2 blocks out, 2 below to 1 above). A plain distance check missed some of those, so workers walked back and forth.
     */
    boolean atHut(Colonist w) {
        return w.isBeside(hut.block);
    }

    /** Walks to the hut chest (teleports if no way leads there). */
    void goToHut(Colonist w) {
        w.goBeside(hut.block);
    }
}
