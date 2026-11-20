package dev.minecp.colony;

import dev.minecp.citizen.Colonist;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The taverns' visitors (the mod's TavernBuildingModule, EntityAIVisitor and RecruitmentInteraction): travellers who
 * stay at a tavern for a while and join the colony if a player pays what they ask. The mod's numbers throughout.
 */
public final class Visitors {
    /** The mod's stay: saturation 60 dropping 0.02 every 50 ticks. */
    static final int STAY_TICKS = 60 * 50 * 50;
    private static final int FIRST_VISITOR = 10_000; // ticks before a new tavern's first visitor
    private static final int RUN_OFF_PERCENT = 3;    // the mod's nextInt(100) <= 2: they take the payment and leave
    /**
     * What visitors ask, by rarity 1 to 9 (the mod's recruitment_items). Its own foods are left out: rarity 7 had
     * only those, so ours there are rabbit stew, pumpkin pie and golden carrots.
     */
    private static final Material[][] COSTS = {
            {Material.DRIED_KELP, Material.BREAD, Material.PAPER, Material.APPLE, Material.BAKED_POTATO, Material.SUGAR, Material.EGG},
            {Material.MUSHROOM_STEW, Material.FEATHER, Material.FLINT, Material.COPPER_INGOT, Material.LEATHER, Material.COOKED_COD, Material.COOKED_SALMON},
            {Material.COOKED_CHICKEN, Material.COOKED_PORKCHOP, Material.COOKED_MUTTON, Material.COOKED_BEEF, Material.BOOK},
            {Material.IRON_INGOT, Material.GOLD_INGOT, Material.COOKIE, Material.REDSTONE, Material.LAPIS_LAZULI, Material.QUARTZ, Material.AMETHYST_CLUSTER},
            {Material.HONEYCOMB, Material.HONEY_BOTTLE, Material.NETHER_WART},
            {Material.INK_SAC, Material.BLAZE_POWDER, Material.SPIDER_EYE, Material.SLIME_BALL},
            {Material.RABBIT_STEW, Material.PUMPKIN_PIE, Material.GOLDEN_CARROT},
            {Material.DIAMOND, Material.EMERALD, Material.ENDER_PEARL, Material.CAKE},
            {Material.GHAST_TEAR, Material.EXPERIENCE_BOTTLE, Material.ENCHANTED_BOOK, Material.GOLDEN_APPLE},
    };

    /** A visitor: its body, the tavern it stays at, what it asks to join, and ticks left before it leaves. */
    public static final class Visitor {
        public final Colonist colonist;
        public final Hut tavern;
        public final Material item;
        public final int count;
        int stay;

        Visitor(Colonist colonist, Hut tavern, Material item, int count, int stay) {
            this.colonist = colonist;
            this.tavern = tavern;
            this.item = item;
            this.count = count;
            this.stay = stay;
        }
    }

    private final Colony colony;
    public final List<Visitor> list = new ArrayList<>();
    // shortcut: not saved, so a restart waits FIRST_VISITOR again; save it if restarts turn out to starve taverns
    private final Map<Hut, Integer> timers = new HashMap<>();

    Visitors(Colony colony) {
        this.colony = colony;
    }

    /**
     * Every colony tick (500 ticks): each built tavern's countdown drops 500; at 0 a visitor arrives if it has room
     * (3 per level), and the next one is rand(3000) + (6000 / level) × citizens / room away: a fuller colony gets fewer.
     */
    void colonyTick(int ticks) {
        var rnd = ThreadLocalRandom.current();
        for (Hut h : colony.huts) {
            if (h.type != Hut.Type.TAVERN || h.level < 1) continue;
            int t = timers.getOrDefault(h, FIRST_VISITOR) - ticks;
            if (t <= 0) {
                if (list.stream().filter(v -> v.tavern == h).count() < 3L * h.level) arrive(h);
                t = rnd.nextInt(3000) + (6000 / h.level) * colony.colonists.size() / Math.max(1, colony.capacity());
            }
            timers.put(h, t);
        }
    }

    /** The mod's spawnVisitor: a rarity around the middle of what the tavern's level allows, and an item of it. */
    private void arrive(Hut tavern) {
        var rnd = ThreadLocalRandom.current();
        int limit = 3 * tavern.level + 1;
        int rarity = Math.clamp((int) rnd.nextGaussian(limit / 2, 2), 1, limit - 1);
        Material[] items = COSTS[rarity - 1];
        Material item = items[rnd.nextInt(items.length)];
        int count = Math.min(5 * (10 - rarity) + rnd.nextInt(3), item.getMaxStackSize());
        boolean female = rnd.nextBoolean();
        var at = tavern.block.getLocation().toCenterLocation();
        Colonist c = new Colonist(UUID.randomUUID(), colony.uniqueName(female), Colonist.randomSkin(female), at);
        list.add(new Visitor(c, tavern, item, count, STAY_TICKS));
    }

    /**
     * Each tick: visitors wander around their tavern until their stay runs out (shortcut: the mod's also sit on the
     * tavern's seats, which the converted buildings don't mark; they wander within 12 blocks instead of 10).
     */
    void tick() {
        for (Visitor v : List.copyOf(list)) {
            if (--v.stay <= 0) { leave(v); continue; }
            if (!v.colonist.tick()) continue;
            v.colonist.setStatus("Visitor");
            v.colonist.wander(v.tavern.block.getLocation().toCenterLocation());
        }
    }

    private void leave(Visitor v) {
        v.colonist.despawn();
        list.remove(v);
    }

    /** The tavern is gone: its visitors leave (as in the mod). */
    void tavernRemoved(Hut tavern) {
        for (Visitor v : List.copyOf(list)) if (v.tavern == tavern) leave(v);
        timers.remove(tavern);
    }

    void despawn() {
        list.forEach(v -> v.colonist.despawn());
    }

    public Visitor of(Entity e) {
        for (Visitor v : list) if (v.colonist.owns(e)) return v;
        return null;
    }

    /**
     * {@code p} pays what the visitor asks (nothing in creative) and it becomes a citizen where it stands, homeless
     * until a house has room. Needs room in the colony's housing. 3% of the time it runs off with the payment, as in
     * the mod. @return what happened, for the player.
     */
    public String recruit(Player p, Visitor v) {
        if (!list.contains(v)) return v.colonist.name + " has left.";
        if (!colony.hasRoom()) return "No room: build or upgrade a house or tavern first.";
        if (p.getGameMode() != GameMode.CREATIVE && !pay(p, v.item, v.count))
            return "You need " + v.count + " × " + dev.minecp.job.Job.name(v.item) + ".";
        list.remove(v);
        if (ThreadLocalRandom.current().nextInt(100) < RUN_OFF_PERCENT) {
            v.colonist.despawn();
            return v.colonist.name + " took the payment and ran off!";
        }
        colony.join(v.colonist);
        return v.colonist.name + " joined " + colony.name + ".";
    }

    /**
     * Takes {@code count} of {@code item} from the player's main inventory, if it has that many. Matched by item alone, as
     * the mod: any enchanted book will do, whatever its enchantments.
     */
    private static boolean pay(Player p, Material item, int count) {
        var inv = p.getInventory();
        int have = 0;
        for (int s = 0; s < 36; s++) { // the main inventory and hotbar, not armour or off hand
            ItemStack i = inv.getItem(s);
            if (i != null && i.getType() == item) have += i.getAmount();
        }
        if (have < count) return false;
        for (int s = 0; s < 36 && count > 0; s++) {
            ItemStack i = inv.getItem(s);
            if (i == null || i.getType() != item) continue;
            int take = Math.min(count, i.getAmount());
            count -= take;
            inv.setItem(s, i.getAmount() == take ? null : i.subtract(take));
        }
        return true;
    }

    void save(ConfigurationSection s) {
        for (Visitor v : list) {
            var vs = s.createSection(v.colonist.id.toString());
            vs.set("name", v.colonist.name);
            vs.set("skin", v.colonist.skin);
            ColonyStore.putLocation(vs, "location", v.colonist.location());
            vs.set("tavern", colony.huts.indexOf(v.tavern));
            vs.set("item", v.item.name());
            vs.set("count", v.count);
            vs.set("stay", v.stay);
        }
    }

    void load(ConfigurationSection s) {
        for (String id : s.getKeys(false)) {
            var vs = s.getConfigurationSection(id);
            Hut tavern = colony.hutByIndex(vs.getInt("tavern", -1));
            Material item = Material.matchMaterial(vs.getString("item", ""));
            var at = ColonyStore.getLocation(vs, "location");
            if (tavern == null || tavern.type != Hut.Type.TAVERN || item == null || at == null) continue;
            Colonist c = new Colonist(UUID.fromString(id), vs.getString("name"), vs.getInt("skin"), at);
            list.add(new Visitor(c, tavern, item, vs.getInt("count"), vs.getInt("stay", STAY_TICKS)));
        }
    }
}
