package dev.minecp.research;

import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Warehouse;
import dev.minecp.research.ResearchTree.Research;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A colony's research (the mod's LocalResearchTree and ResearchManager): what was started, how far along it is, and the
 * effects of what is finished. Its University's researchers move it on each colony tick (500 ticks): each university
 * moves on as many researches as it has researchers, the oldest first (the mod's BuildingUniversity.onColonyTick).
 */
public final class Studies {
    /** One started research: its progress in steps, until {@link ResearchTree#steps}. */
    static final class Entry {
        int progress;
        boolean done;
    }

    /** Names of the mod's huts that Colonies doesn't have yet, for requirements and unlocks. */
    private static final Map<String, String> MISSING = Map.ofEntries(
            Map.entry("stable", "Stable"));
    /** Colonies' own items that research costs ask for; set by the plugin (Items isn't static). */
    public static Predicate<ItemStack> clipboard = i -> false;
    /** What undoing a finished research costs (config): the mod's 1 ancient tome (null here), or a vanilla item. */
    public static Material resetItem = null;
    public static int resetCount = 1;
    /** "1 × ancient tome". */
    public static String resetCost() {
        return resetCount + " × " + (resetItem == null ? "ancient tome" : resetItem.name().toLowerCase().replace('_', ' '));
    }

    /** Creative players' researches finish at once (the mod's researchCreativeCompletion). */
    public static boolean creativeCompletion = true;

    private final Colony colony;
    private final Map<String, Entry> started = new LinkedHashMap<>(); // in the order started: the oldest moves on first

    public Studies(Colony colony) {
        this.colony = colony;
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    public boolean isDone(String id) {
        Entry e = started.get(id);
        return e != null && e.done;
    }

    public boolean isStarted(String id) {
        return started.containsKey(id);
    }

    /** 0..1 of the way through, for a started research. */
    public double progress(Research r) {
        Entry e = started.get(r.id());
        return e == null ? 0 : e.done ? 1 : Math.min(1, (double) e.progress / ResearchTree.steps(r));
    }

    /** The researches under way, oldest first. */
    public List<Research> inProgress() {
        List<Research> out = new ArrayList<>();
        started.forEach((id, e) -> { if (!e.done) out.add(ResearchTree.get(id)); }); // load and start keep only ids in the tree
        return out;
    }

    /** An effect's strength: the strongest of the finished researches that give it (the mod's overrides), 0 if none. */
    public double effect(String id) {
        double best = 0;
        for (var en : started.entrySet()) {
            if (!en.getValue().done) continue;
            for (var e : ResearchTree.get(en.getKey()).effects()) if (e.id().equals(id) && Math.abs(e.strength()) > Math.abs(best)) best = e.strength();
        }
        return best;
    }

    /** The research that unlocks this hut, if one does (the mod's blockhut effects); null if it needs none. */
    static Research unlockOf(Hut.Type t) {
        if (t.modId() == null) return null;
        String effect = "blockhut" + t.modId();
        for (Research r : ResearchTree.all()) for (var e : r.effects()) if (e.id().equals(effect)) return r;
        return null;
    }

    /** Why a hut of this type can't be placed yet, or null: its unlocking research isn't finished. */
    public String lockReason(Hut.Type t) {
        Research r = unlockOf(t);
        if (r == null || effect("blockhut" + t.modId()) > 0) return null;
        return "The " + t.title + " needs research first: " + r.name() + " (" + ResearchTree.branch(r.branch()).name() + "), at a University.";
    }

    /** The hut type with this mod building id (Hut.Type.modId), or null if Colonies hasn't got it. */
    private static Hut.Type hut(String modId) {
        for (Hut.Type t : Hut.Type.values()) if (modId.equals(t.modId())) return t;
        return null;
    }

    /** A hut by the mod's building id, as players read it. */
    private static String hutName(String modId) {
        Hut.Type t = hut(modId);
        return t != null ? t.title : MISSING.getOrDefault(modId, modId) + " (not in Colonies yet)";
    }

    /** What an effect does, for the menus: hut unlocks by name; the rest by the mod's effect id until Colonies has them. */
    public static String describe(ResearchTree.Effect e) {
        if (e.id().startsWith("blockhut")) return "Unlocks the " + hutName(e.id().substring("blockhut".length()));
        double v = e.strength();
        String n = num(v), pct = Math.round(v * 100) + "%";
        return switch (e.id()) {
            case Effects.HEALTH -> "Citizens have " + n + " more health";
            case Effects.WALKING -> "Citizens walk " + pct + " faster";
            case Effects.REGENERATION -> "Citizens heal " + pct + " faster";
            case Effects.HEALING_SATURATION -> "Citizens heal at the full rate with " + num(-v) + " less food in them";
            case Effects.SATURATION -> "Meals fill citizens " + pct + " more";
            case Effects.HAPPINESS -> "Citizens are " + pct + " happier";
            case Effects.GROWTH -> "Children grow up " + pct + " faster";
            case Effects.CITIZEN_CAP -> "Room for up to " + n + " citizens";
            case Effects.WORK_LONGER -> "Citizens work " + n + (v == 1 ? " hour" : " hours") + " later into the evening";
            case Effects.GRAVE_DECAY -> "Graves last " + n + " minutes longer";
            case Effects.RESURRECT_CHANCE -> "The undertaker is " + pct + " likelier to bring someone back (at most 2.5% in all)";
            case Effects.TEACHING -> "Pupils learn " + pct + " more from their lessons and their paper";
            case Effects.LEVELING -> "Citizens learn " + pct + " faster from their work";
            case Effects.UNDERTAKER_RUN -> "The undertaker runs to graves";
            case Effects.BREAK_SPEED -> v >= 1 ? "Workers break blocks with no wait" : "Workers break blocks in " + pct + " less time";
            case Effects.PLACE_SPEED -> v >= 1 ? "Workers place blocks with no wait" : "Workers place blocks in " + pct + " less time";
            case Effects.FARMING -> "Each crop a farmer harvests has a " + pct + " chance to give twice as much";
            case Effects.TOOL_DURABILITY -> "Workers' tools last " + pct + " longer";
            case Effects.RECIPES -> "Crafters can learn " + pct + " more recipes";
            case Effects.MINIMUM_STOCK -> "Postboxes keep " + pct + " more items in stock";
            case Effects.INVENTORY_SLOTS -> "Citizens carry " + n + " more stacks";
            case Effects.FISH_TREASURE -> "Fishermen find treasure anywhere, not only at sea";
            case Effects.LOOTING -> "Herders butcher as if with Looting I";
            case Effects.MINER_FIRE_RESISTANCE -> "Miners and nether miners aren't hurt by fire or lava";
            case Effects.NETHER_LOG -> "The Nether Mine keeps a log of its last trip";
            case Effects.WORK_IN_RAIN -> "Every worker works in the rain";
            case Effects.MELEE_DAMAGE -> "Knights deal " + n + " more damage";
            case Effects.MELEE_ARMOR -> "Knights' armor protects " + pct + " more";
            case Effects.BLOCK_ATTACKS -> "Knights parry " + pct + " of hits entirely";
            case Effects.GUARD_CRIT -> "Guards land critical hits (1.5x damage) on " + Math.round(100 - 100 / (1 + v)) + "% of blows";
            case Effects.KNIGHT_TAUNT -> "A mob a knight hits turns on the knight";
            case Effects.SHIELD_USAGE -> "Knights ask for a shield and raise it between blows";
            case Effects.RETREAT -> "Guards below 20% health retreat to their tower and rest there";
            case Effects.FLEEING_DAMAGE -> "Guards below 20% health take " + pct + " less damage";
            case Effects.FLEEING_SPEED -> v > 0 ? "Retreating guards run with Speed " + ((int) v + 1) : "Prepares retreating guards to run faster";
            case Effects.WHIRLWIND -> "Knights sometimes spin, striking and knocking back every enemy around them";
            case Effects.SLEEP_LESS -> "Guards nap on duty " + Math.round(100 - 100 / (1 + v)) + "% less often";
            case Effects.ARMOR_DURABILITY -> "Guards' armor lasts " + pct + " longer";
            case Effects.ARCHER_DAMAGE -> "Rangers' arrows deal " + n + " more damage";
            case Effects.ARCHER_ARMOR -> "Rangers' armor protects " + pct + " more";
            case Effects.DOUBLE_ARROWS -> "Rangers shoot a second arrow " + pct + " of the time";
            case Effects.ARROW_PIERCE -> "Rangers' arrows pierce through two more mobs";
            case Effects.USE_ARROWS -> "Rangers ask for arrows, which add 2 damage to each shot";
            case Effects.DRUID_POTIONS -> "The Alchemist makes magic potions; druids use them up for stronger potions of more kinds";
            case Effects.TELESCOPE -> "Guards rally to a banner up to 500 blocks from the town hall, outside the colony too";
            case Effects.SIFTER_STRING -> "The Fletcher makes string sifter meshes";
            case Effects.SIFTER_FLINT -> "The Stonemason makes flint sifter meshes";
            case Effects.SIFTER_IRON -> "The Blacksmith makes iron sifter meshes";
            case Effects.SIFTER_DIAMOND -> "The Mechanic makes diamond sifter meshes";
            case Effects.MASKS -> "Citizens catch diseases from the sick half as often";
            case Effects.VACCINES -> "Citizens cured in a hospital bed stay immune ten times as long";
            case Effects.MORE_ORES -> "Miners find ore in the stone they mine " + pct + " more often";
            case Effects.CRUSHING_ONE_TO_ONE -> "The crusher crushes one block into one, not two into one (bones give 5 bone meal)";
            case Effects.DEPTH_KNOWLEDGE -> "The crusher crushes cobbled deepslate into tuff, and tuff into cobblestone";
            case Effects.PLANTATION_JUNGLE -> "The plantation grows bamboo";
            case Effects.PLANTATION_LARGE -> "A plantation can work one more field (Colonies' plantations work only their own plots)";
            case Effects.PODZOL_CHANCE -> "A composter making dirt makes podzol " + pct + " more often";
            case Effects.SOFT_SHOES -> "Farmers don't trample farmland (Colonies' citizens never do)";
            default -> e.id() + " " + n + " (not in Colonies yet)";
        };
    }

    /** "2", "0.5": whole numbers without ".0". */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** A University at this level shows researches this deep: any at its top level (the mod's canDisplay). */
    public static boolean shows(Research r, int universityLevel) {
        return universityLevel >= Hut.Type.UNIVERSITY.maxLevel() || r.depth() <= universityLevel;
    }

    public boolean met(ResearchTree.Requirement q) {
        for (String b : q.buildings()) {
            Hut.Type t = hut(b);
            if (t == null) continue;
            int sum = 0;
            for (Hut h : colony.huts) {
                if (h.type != t) continue;
                if (q.single() ? h.level >= q.level() : (sum += h.level) >= q.level()) return true;
            }
        }
        return false;
    }

    /** "Lumberjack's Hut levels adding up to 3", "a Town Hall at level 2"; alternatives joined with "or". */
    public static String describe(ResearchTree.Requirement q) {
        List<String> names = q.buildings().stream().map(Studies::hutName).toList();
        return q.single() ? "a " + String.join(" or ", names) + " at level " + q.level()
                : String.join(" or ", names) + " levels adding up to " + q.level();
    }

    /** What a cost takes, or null if Colonies has no such item (the mod's gates). */
    static Predicate<ItemStack> matcher(ResearchTree.Cost c) {
        Predicate<ItemStack> any = i -> false;
        if (c.tag() != null) {
            NamespacedKey key = NamespacedKey.fromString(c.tag());
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) return null;
            any = i -> tag.isTagged(i.getType()) && plain(i);
        } else for (String k : c.items()) {
            Predicate<ItemStack> one;
            if (k.equals("minecp:rack")) one = dev.minecp.logistics.Rack::is;
            else if (k.equals("minecp:clipboard")) one = clipboard;
            else if (k.equals("minecolonies:mistletoe")) one = dev.minecp.job.Alchemist::isMistletoe;
            else if (k.equals("minecolonies:ancienttome")) one = dev.minecp.job.Enchanter::isAncientTome;
            else if (k.equals("minecolonies:compost")) one = dev.minecp.job.Compost::is;
            else {
                Material m = k.startsWith("minecraft:") ? Material.matchMaterial(k) : null;
                if (m == null) return null;
                one = i -> i.getType() == m && plain(i);
            }
            any = any.or(one);
        }
        return any;
    }

    /** Not one of Colonies' items (a hut is a chest, a supply item is paper): costs never take those by mistake. */
    private static boolean plain(ItemStack i) {
        return i.getPersistentDataContainer().getKeys().stream().noneMatch(k -> k.getNamespace().equals(dev.minecp.MineCP.NAMESPACE));
    }

    /** "8 × hay block", or the mod's item's name if Colonies hasn't got it. */
    public static String describe(ResearchTree.Cost c) {
        String what = c.tag() != null ? c.tag().replaceFirst("^minecraft:", "").replace('_', ' ') + " (any)"
                : c.items().size() > 2 ? c.items().getFirst().replaceAll(".*_", "") + " (any)" // a converted tag: "concrete (any)"
                : String.join(" or ", c.items().stream().map(k -> k.equals("minecp:rack") ? "storage rack" : k.equals("minecp:clipboard") ? "clipboard"
                : k.replaceFirst("^[a-z_]+:", "").replace('_', ' ')).toList());
        return c.count() + " × " + what + (matcher(c) == null ? " (not in Colonies yet)" : "");
    }

    /**
     * Why {@code r} can't be started at the University {@code uni}, or null (the mod's canResearch, its requirements and
     * costs). Costs count what is in the university's chest and the player's inventory.
     */
    public String problem(Player p, Hut uni, Research r) {
        if (started.containsKey(r.id())) return r.name() + " is already started.";
        if (!shows(r, uni.level)) return "A University at level " + r.depth() + " is needed for research this deep.";
        if (r.parent() != null && !isDone(r.parent())) return "Finish " + ResearchTree.parent(r).name() + " first.";
        if (r.parent() != null && ResearchTree.parent(r).onlyChild()
                && ResearchTree.children(r.parent()).stream().anyMatch(ch -> started.containsKey(ch.id())))
            return "Only one research after " + ResearchTree.parent(r).name() + " can be chosen.";
        if (r.depth() == ResearchTree.MAX_DEPTH && started.keySet().stream().map(ResearchTree::get)
                .anyMatch(o -> o.branch().equals(r.branch()) && o.depth() == ResearchTree.MAX_DEPTH))
            return "Only one research at the deepest level of each branch.";
        for (var q : r.requirements()) if (!met(q)) return "Needs " + describe(q) + ".";
        Inventory chest = chestOf(uni);
        for (var c : r.costs()) { // shortcut: each cost counts the same items alone; no research has two costs that overlap
            var m = matcher(c);
            if (m == null) return "Needs " + describe(c) + ".";
            if (count(chest, m) + count(p.getInventory(), m) < c.count()) return "Needs " + describe(c) + " in your inventory or the University's chest.";
        }
        return null;
    }

    /** Starts it, taking its costs from the University's chest first, then the player (the mod's order). @return why not, or null. */
    public String start(Player p, Hut uni, Research r) {
        boolean creative = p.getGameMode() == GameMode.CREATIVE; // the mod: no costs or checks, done at once
        Entry under = started.get(r.id());
        if (under != null) {
            if (!creative || !creativeCompletion || under.done) return r.name() + " is already started.";
            finish(r, under); // the mod: a creative player finishes one under way
            return null;
        }
        if (!creative) {
            String problem = problem(p, uni, r);
            if (problem != null) return problem;
            Inventory chest = chestOf(uni);
            for (var c : r.costs()) {
                var m = matcher(c);
                take(p.getInventory(), m, take(chest, m, c.count()));
            }
        }
        Entry e = new Entry();
        started.put(r.id(), e);
        if (creative && creativeCompletion) finish(r, e);
        else Colony.log.info(colony.where() + ": " + p.getName() + " started research " + r.id() + ".");
        return null;
    }

    /** Stops a research under way, or undoes a finished one (no started child; costs the reset item). @return why not, or null. */
    public String reset(Player p, Research r) {
        Entry e = started.get(r.id());
        if (e == null) return null;
        if (e.done) {
            if (r.noReset()) return r.name() + " can't be undone.";
            for (Research ch : ResearchTree.children(r.id()))
                if (started.containsKey(ch.id())) return "Undo what came after " + r.name() + " first.";
            if (p.getGameMode() != GameMode.CREATIVE) {
                Predicate<ItemStack> m = resetItem == null ? dev.minecp.job.Enchanter::isAncientTome : i -> i.getType() == resetItem && plain(i);
                if (count(p.getInventory(), m) < resetCount)
                    return "Undoing a research costs " + resetCost() + ".";
                take(p.getInventory(), m, resetCount);
            }
        }
        started.remove(r.id());
        Colony.log.info(colony.where() + ": " + p.getName() + (e.done ? " undid" : " stopped") + " research " + r.id() + ".");
        return null;
    }

    /** One colony tick: each built University with its researcher moves on as many researches as its level. */
    public void colonyTick() {
        unlockables();
        for (Hut u : List.copyOf(colony.huts)) {
            if (u.type != Hut.Type.UNIVERSITY || u.level < 1) continue;
            var job = colony.jobAt(u);
            if (job == null || colony.worker(job) == null) continue;
            int n = u.level; // shortcut: the mod's university has one researcher per level; ours has one doing the work of them all
            for (Research r : inProgress()) {
                if (n-- <= 0) break;
                Entry e = started.get(r.id());
                if (++e.progress >= ResearchTree.steps(r)) finish(r, e);
            }
        }
    }

    /**
     * The hidden Unlockables branch (the sifter's meshes): each research there starts and finishes by itself once its
     * parent is done and its huts are built (the mod's autostart and instant). Ours: checked each colony tick, where the
     * mod checks when a building is upgraded.
     */
    private void unlockables() {
        for (Research r : ResearchTree.all()) {
            if (!ResearchTree.branch(r.branch()).hidden() || isStarted(r.id())) continue;
            if (r.parent() != null && !isDone(r.parent()) || !r.requirements().stream().allMatch(this::met)) continue;
            Entry e = new Entry();
            e.done = true; // silent, as the mod's hidden branch: the unlocked recipes show in the crafters' menus
            started.put(r.id(), e);
            Colony.log.info(colony.where() + ": research " + r.id() + " finished.");
        }
    }

    private void finish(Research r, Entry e) {
        e.done = true;
        e.progress = ResearchTree.steps(r);
        Colony.log.info(colony.where() + ": research " + r.id() + " finished.");
        colony.tell("Research finished: " + r.name() + ".");
    }

    private static int count(Inventory inv, Predicate<ItemStack> m) {
        int n = 0;
        if (inv != null) for (ItemStack i : inv.getContents()) if (i != null && m.test(i)) n += i.getAmount();
        return n;
    }

    /** Takes up to {@code n}; @return how many it couldn't. */
    private static int take(Inventory inv, Predicate<ItemStack> m, int n) {
        return inv == null ? n : n - Warehouse.take(inv, m, n, Integer.MAX_VALUE, new ArrayList<>());
    }

    private static Inventory chestOf(Hut h) {
        return h.block.getState(false) instanceof Container ct ? ct.getInventory() : null;
    }

    public void save(ConfigurationSection s) {
        started.forEach((id, e) -> s.set(id.replace('/', '|'), e.done ? -1 : e.progress)); // -1: finished
    }

    public void load(ConfigurationSection s) {
        for (String k : s.getKeys(false)) {
            String id = k.replace('|', '/');
            if (ResearchTree.get(id) == null) continue; // gone from the tree
            Entry e = new Entry();
            int v = s.getInt(k);
            e.done = v < 0;
            e.progress = e.done ? ResearchTree.steps(ResearchTree.get(id)) : v;
            started.put(id, e);
        }
    }
}
