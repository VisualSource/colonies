package dev.minecp.job;

import dev.minecp.MineCP;
import dev.minecp.Scrolls;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Enchanter's Tower (the mod's BuildingEnchanter and EntityAIWorkEnchanter), the mod's numbers unless noted. Its
 * worker's mana is its Mana skill: below 10 per hut level it studies the huts chosen as its gather targets, each once a
 * day, with a book (the book is used up, mana +1); with enough it turns an ancient tome into an enchanted book from its
 * hut level's table, which costs mana as high as the book's best enchantment. It takes each result to its hut. It also
 * makes the mod's scrolls when the colony asks for them (crafterrecipes/enchanter), before anything else.
 */
public final class Enchanter extends Crafter {
    private static final int MANA_PER_LEVEL = 10;      // MANA_REQ_PER_LEVEL
    private static final int DRAIN_STEPS = 60, DRAIN_TICKS = 10; // MAX_PROGRESS_TICKS, its drain state every 10 ticks
    private static final int ENCHANT_STEPS = 300, ENCHANT_TICKS = 20; // MAX_ENCHANTMENT_TICKS / hut level, a second each
    private static final double XP_PER_DRAIN = 10;
    private static final int TOMES_KEPT = 64;          // the hut keeps a stack of tomes

    private enum State { DECIDE, TO_TARGET, DRAIN, ENCHANT, DUMP }

    private State state = State.DECIDE;
    /** The huts it studies (the mod's gather targets; none at first), and those it has studied today. */
    public final Set<Hut> targets = new LinkedHashSet<>();
    private final Set<Hut> studied = new LinkedHashSet<>();
    private int studiedDay = -1;
    private Hut target;
    private int progress;

    public Enchanter(Hut hut, Colony colony) {
        super(hut, colony, r -> false, "teleport, area teleport, guard help and highlight scrolls", Kind.OWN);
        // The mod's recipes; ours: a plain stick for its gold scepter (our Build Tool is made from one)
        learn(scroll("enchanter_scroll_tp", Scrolls.Kind.TELEPORT, 3, Map.of(Material.PAPER, 3, Material.COMPASS, 1, Material.STICK, 1), 0), 1, s -> true);
        learn(scroll("enchanter_scroll_area_tp", Scrolls.Kind.AREA, 1, Map.of(), 3), 2, s -> true);
        learn(scroll("enchanter_scroll_guard_help", Scrolls.Kind.GUARD_HELP, 2, Map.of(Material.LAPIS_LAZULI, 5, Material.ENDER_PEARL, 1, Material.PAPER, 1), 1), 3, MORE_SCROLLS);
        learn(scroll("enchanter_scroll_highlight", Scrolls.Kind.HIGHLIGHT, 5, Map.of(Material.GLOWSTONE_DUST, 6, Material.PAPER, 2), 3), 3, MORE_SCROLLS);
    }

    private static final java.util.function.Predicate<dev.minecp.research.Studies> MORE_SCROLLS = s -> s.effect("morescrollsunlock") > 0;

    /** A scroll recipe: these plain items and {@code teleportScrolls} colony teleport scrolls (unbound). */
    private static org.bukkit.inventory.Recipe scroll(String id, Scrolls.Kind kind, int count, Map<Material, Integer> items, int teleportScrolls) {
        var r = (org.bukkit.inventory.ShapelessRecipe) own(id, Scrolls.item(kind, count), items);
        for (int i = 0; i < teleportScrolls; i++) r.addIngredient(org.bukkit.inventory.RecipeChoice.exactChoice(Scrolls.item(Scrolls.Kind.TELEPORT, 1)));
        return r;
    }

    // ---- the ancient tome -----------------------------------------------------------------------------------------

    private static final NamespacedKey TOME = new NamespacedKey(MineCP.NAMESPACE, "ancient_tome");

    /**
     * The mod's ancient tome: an enchanted book with no enchantment and a tag, stacking to 64. Raiders drop it; it can't
     * be used (an anvil or grindstone has nothing to take from it). Ours: it always glints (the mod's only on a raid night).
     */
    public static ItemStack ancientTome() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 64);
        item.editMeta(m -> {
            m.itemName(Component.text("Ancient Tome", NamedTextColor.GOLD));
            m.lore(List.of(Component.text("An enchanter turns it into an enchanted book", NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(TOME, PersistentDataType.BYTE, (byte) 1);
        });
        dev.minecp.Items.model(item, "ancient_tome");
        return item;
    }

    public static boolean isAncientTome(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(TOME);
    }

    // ---- the enchanted books (the mod's recipes/enchanter1 to 5 loot tables) ----------------------------------------

    /** The mod's 22 enchantments of its tables' tiers: tier t has each at level t, or its highest. */
    private static final String[] TIERED = {"aqua_affinity", "bane_of_arthropods", "blast_protection", "depth_strider",
            "efficiency", "feather_falling", "fire_aspect", "fire_protection", "flame", "frost_walker", "knockback", "looting",
            "power", "projectile_protection", "protection", "punch", "quick_charge", "respiration", "sharpness", "smite",
            "sweeping_edge", "unbreaking"};
    /** Weights of tiers 1 to 5 in each hut level's table: level 1 tier 1 at 50; level 2 adds tier 2 at 25; and so on. */
    private static final int[][] TIER_WEIGHTS = {{50}, {50, 25}, {50, 25, 15}, {0, 25, 15, 5}, {0, 0, 15, 5, 1}};

    private record Entry(Enchantment enchantment, int level, int weight) {}

    /**
     * The table for a hut level: the tiers' entries, plus Fortune I (level 3) and II (level 4) at 1, Infinity in tiers 4
     * and 5, and tier 5's Mending, Multishot, Silk Touch and Fortune III. Ours: each tier as "level t or the
     * enchantment's highest" for all 22 (the mod leaves a few out of some tiers), and no Raider's Bane.
     */
    static List<Entry> table(int hutLevel) {
        List<Entry> out = new ArrayList<>();
        int[] weights = TIER_WEIGHTS[Math.clamp(hutLevel, 1, 5) - 1];
        for (int t = 1; t <= weights.length; t++) {
            if (weights[t - 1] == 0) continue;
            for (String k : TIERED) add(out, k, t, weights[t - 1]);
            if (t >= 4) add(out, "infinity", 1, weights[t - 1]);
            if (t == 5) for (String k : new String[]{"mending", "multishot", "silk_touch"}) add(out, k, 1, weights[t - 1]);
            if (t == 5) add(out, "fortune", 3, weights[t - 1]);
        }
        if (hutLevel == 3) add(out, "fortune", 1, 1);
        if (hutLevel == 4) add(out, "fortune", 2, 1);
        return out;
    }

    private static void add(List<Entry> out, String key, int level, int weight) {
        Enchantment e = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(Key.key(key));
        if (e != null) out.add(new Entry(e, Math.min(level, e.getMaxLevel()), weight));
    }

    /** An enchanted book rolled from the hut level's table. */
    private ItemStack book() {
        List<Entry> table = table(hut.level);
        int roll = ThreadLocalRandom.current().nextInt(table.stream().mapToInt(Entry::weight).sum());
        Entry pick = table.getLast();
        for (Entry e : table) if ((roll -= e.weight()) < 0) { pick = e; break; }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        Entry chosen = pick;
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(chosen.enchantment(), chosen.level(), true));
        return book;
    }

    // ---- its work -------------------------------------------------------------------------------------------------

    private int required() {
        return MANA_PER_LEVEL * hut.level;
    }

    /** A hut it may study: built, with a worker's job, not an enchanter's (the mod's EnchanterStationsModule). */
    public static boolean canStudy(Hut h, Colony c) {
        return h.level >= 1 && h.type != Hut.Type.ENCHANTER && c.jobAt(h) != null && h.type.worker != null;
    }

    private static boolean plainBook(ItemStack i) {
        return i.getType() == Material.BOOK && i.getPersistentDataContainer().isEmpty();
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>(super.wants());
        if (hut.level < 1) return wants;
        if (level(Skill.MANA) < required()) {
            if (!targets.isEmpty() && has(Enchanter::plainBook) + inChest(Enchanter::plainBook) == 0)
                wants.add(new Want("book", Enchanter::plainBook, 1));
        } else if (has(Enchanter::isAncientTome) + inChest(Enchanter::isAncientTome) == 0)
            wants.add(new Want("ancient tome", Enchanter::isAncientTome, 1));
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        if (isAncientTome(item)) return TOMES_KEPT;
        return plainBook(item) ? 1 : super.keep(item);
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return isAncientTome(i) || plainBook(i) || super.keepsOnHand(i);
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level < 1) return List.of(Component.text("The enchanter starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>(super.info(c));
        lines.add(Component.text("Mana " + level(Skill.MANA) + " of " + required() + " needed to enchant. Below it, it studies "
                + (targets.isEmpty() ? "the huts you choose in Gather targets (none yet)" : targets.size() + " gather targets, each once a day, with a book")
                + "; with enough it turns an ancient tome into an enchanted book.", NamedTextColor.GRAY));
        return lines;
    }

    /** With no scroll asked for (the crafter's work comes first), it studies or enchants. */
    @Override
    boolean otherWork(Colonist w, org.bukkit.inventory.Inventory chest) {
        if (studiedDay != colony.day) { studiedDay = colony.day; studied.clear(); } // the mod's onWakeUp
        targets.removeIf(h -> !colony.huts.contains(h) || !canStudy(h, colony));
        switch (state) {
            case DECIDE -> decide(w);
            case TO_TARGET -> {
                if (!targets.contains(target)) { state = State.DECIDE; return true; }
                if (!w.isBeside(target.block)) { w.goBeside(target.block); return true; }
                progress = 0;
                state = State.DRAIN;
            }
            case DRAIN -> {
                if (!targets.contains(target) || !w.isBeside(target.block)) { state = State.TO_TARGET; return true; }
                Location at = w.location().add(0, 1, 0);
                at.getWorld().spawnParticle(Particle.ENCHANT, at, 10, 0.5, 0.5, 0.5, 0.5);
                if (progress % 6 == 0) at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, target.block.getLocation().toCenterLocation(), 3, 0.5, 0.5, 0.5);
                cooldown = DRAIN_TICKS;
                if (++progress < DRAIN_STEPS) return true;
                if (use(Enchanter::plainBook, 1) == 1) {
                    w.skills.shift(Skill.MANA, 1);
                    experience(w, XP_PER_DRAIN);
                }
                studied.add(target);
                target = null;
                state = State.DUMP;
            }
            case ENCHANT -> {
                if (!atHut(w)) { goToHut(w); return true; }
                Location at = hut.block.getLocation().toCenterLocation();
                at.getWorld().spawnParticle(Particle.ENCHANT, at, 20, 0.5, 0.8, 0.5, 1);
                if (progress % 5 == 0) at.getWorld().playSound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.BLOCKS, 0.6f, 1);
                w.swing();
                cooldown = ENCHANT_TICKS;
                if (++progress < ENCHANT_STEPS / hut.level) return true;
                if (use(Enchanter::isAncientTome, 1) == 1) {
                    ItemStack book = book();
                    int cost = ((EnchantmentStorageMeta) book.getItemMeta()).getStoredEnchants().values().stream().mapToInt(Integer::intValue).max().orElse(0);
                    w.skills.shift(Skill.MANA, -cost);
                    gather(List.of(book), w);
                    hut.block.getWorld().playSound(at, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 0.5f, 1.5f);
                }
                state = State.DUMP;
            }
            case DUMP -> {
                if (!atHut(w)) { goToHut(w); return true; }
                if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return true; }
                state = State.DECIDE;
            }
        }
        return true;
    }

    /** The mod's decide: study while short of mana, else enchant; with what it needs from its hut. */
    private void decide(Colonist w) {
        if (loaded()) { state = State.DUMP; return; }
        if (level(Skill.MANA) < required()) {
            if (targets.isEmpty()) { idle(w, "Choose huts for it to study in Gather targets.", 100, hut.block); return; }
            if (has(Enchanter::plainBook) == 0) {
                if (!atHut(w)) { goToHut(w); return; }
                if (fetch(Enchanter::plainBook, 1) == 0) { idle(w, "Needs a book to study with.", 100, hut.block); return; }
            }
            List<Hut> left = targets.stream().filter(h -> !studied.contains(h)).toList();
            if (left.isEmpty()) { idle(w, "It has studied every gather target today.", 200, hut.block); return; }
            target = left.get(ThreadLocalRandom.current().nextInt(left.size()));
            note = null;
            w.setStatus(hut.type.worker + ": studying");
            state = State.TO_TARGET;
            return;
        }
        if (has(Enchanter::isAncientTome) == 0) {
            if (!atHut(w)) { goToHut(w); return; }
            if (fetch(Enchanter::isAncientTome, 1) == 0) { idle(w, "Needs an ancient tome to enchant.", 100, hut.block); return; }
        }
        note = null;
        w.setStatus(hut.type.worker + ": enchanting");
        progress = 0;
        state = State.ENCHANT;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("targets", targets.stream().map(c.huts::indexOf).toList());
        s.set("studied", studied.stream().map(c.huts::indexOf).toList());
        s.set("studied-day", studiedDay);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (int i : s.getIntegerList("targets")) { Hut h = c.hutByIndex(i); if (h != null) targets.add(h); }
        for (int i : s.getIntegerList("studied")) { Hut h = c.hutByIndex(i); if (h != null) studied.add(h); }
        studiedDay = s.getInt("studied-day", -1);
    }
}
