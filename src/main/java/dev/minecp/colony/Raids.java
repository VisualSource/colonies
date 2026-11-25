package dev.minecp.colony;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raids on a colony (the mod's RaidManager, HordeRaidEvent and its ship raids), with the mod's numbers unless a comment
 * says otherwise. A raid is decided one nightfall ahead and arrives at the next; its size follows the colony's "raid
 * level" (citizens, their skills, building levels and research). From each spawn point a horde comes over land, or, on
 * water, a ship (pirates, norsemen, or a sunken ship of drowned pirates) with spawners on board. Raiders are vanilla mobs
 * in plain armor (see {@link Culture}): a land horde camps by campfires for a while, then they march on the colony's huts
 * and attack citizens and players on the way. Citizens hide at home and guards fight. A boss bar shows how it goes.
 */
public final class Raids {
    // Settings (config.yml, read by the plugin): the mod's defaults.
    public static boolean enabled = true, breakDoors = true, breakBlocks = true;
    public static int difficulty = 5, maxRaiders = 80, averageNights = 14, minimumNights = 10;

    private static final int ALERT_RANGE = 75;        // the mod's: the 4 nearest guards this near the hut raiders marched on go there
    private static final int MIN_LEVEL = 75;          // MIN_REQUIRED_RAIDLEVEL
    private static final int BIG_HORDE = 20;          // one spawn point per 20 raiders
    private static final int UPDATE = 500;            // the mod's raid events update on the colony tick
    private static final int DESPAWN_TICKS = 36_000;  // TICKS_TO_DESPAWN: 30 minutes
    private static final int SPAWN_SHIELD = 40;       // the mod's invulTime: ticks a new raider can't be hurt
    private static final int NIGHTS = 3;              // a horde gives up after 3 more nightfalls
    private static final int SIGHT = 16;              // the raiders' target search, ±16 across (ours: ±3 up and down)
    private static final int IGNORE_BIOME = 2;        // IGNORE_BIOME_CHANCE: of 100, a culture regardless of the biome
    private static final int ENV_COOLDOWN = 30;       // ENV_DAMAGE_COOLDOWN: ticks between hits from no living attacker
    private static final double MAX_SCALED = 7, HP_PER_DAMAGE = 0.03; // MAX_SCALED_DAMAGE, HP_PERCENT_PER_DMG
    private static final int THORNS_OVER = 30, THORNS_CHANCE = 5;     // MIN_THORNS_DAMAGE, THORNS_CHANCE
    public static final String TAG = "minecp_raider";

    /** What each kind of raider is in one culture: a vanilla mob, its weapon, the plain armor shown, and the mod's stat changes. */
    record Spec(Class<? extends Mob> mob, Material weapon, Material offhand, Material[] armor, double health,
                java.util.function.DoubleUnaryOperator armorPoints, double damage, Object[] drops) {}

    private static Material[] set(String prefix, int pieces) { // helmet, chestplate, leggings, boots: the first {@code pieces}
        String[] slots = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
        Material[] out = new Material[pieces];
        for (int i = 0; i < pieces; i++) out[i] = Material.valueOf(prefix + slots[i]);
        return out;
    }

    private static final java.util.function.DoubleUnaryOperator D1 = d -> d; // the mod's armor: the difficulty modifier
    private static final Object TOME = "ancient tome"; // in a drop table: the Enchanter's ancient tome
    // in a drop table: the mod's own raider items (raiderItem)
    private static final Object CHIEF_SWORD = "chief sword", PHARAOH_SCEPTER = "pharaoh scepter", SPEAR = "spear", FIRE_ARROW = "fire arrow",
            SCIMITAR = "iron scimitar";
    /** The mod's two pirate armor sets (its own items), as helmet, chestplate, leggings, boots: ours tagged leather. */
    private static final String[][] PIRATE_SETS = {{"pirate_hat", "pirate_top", "pirate_leggins", "pirate_boots"}, {"pirate_cap", "pirate_chest", "pirate_legs", "pirate_shoes"}};
    private static final Object[] PIRATE_CHIEF_DROPS = {145, PIRATE_SETS[0][0], 5, PIRATE_SETS[0][2], 5, PIRATE_SETS[0][3], 5, PIRATE_SETS[0][1], 5,
            PIRATE_SETS[1][0], 5, PIRATE_SETS[1][2], 5, PIRATE_SETS[1][3], 5, PIRATE_SETS[1][1], 5, SCIMITAR, 25, TOME, 30};

    /**
     * The mod's cultures. Ours: vanilla mobs stand in for its raider models (zombies, husks and drowned for melee,
     * skeletons, bogged and strays for archers), with plain armor; the armor is for looks (its own points don't count)
     * and the raider's armor is the mod's number. Drops are the mod's loot tables, with vanilla items for its own and the
     * Enchanter's ancient tome; the Norse chief rolls twice, as in the mod. Drowned pirates have no archers (as the mod's).
     */
    enum Culture {
        BARBARIANS("Barbarians", "barbarian_raid",
                new Spec(Zombie.class, Material.STONE_AXE, null, set("LEATHER", 2), 1, D1, 0,
                        new Object[]{97, Material.DIAMOND_AXE, 1, Material.GOLDEN_AXE, 2, Material.IRON_AXE, 5, Material.STONE_AXE, 6, TOME, 3}),
                new Spec(Skeleton.class, Material.BOW, null, set("LEATHER", 2), 1, D1, 0, new Object[]{95, Material.BOW, 10, TOME, 5}),
                new Spec(Zombie.class, Material.IRON_SWORD, null, set("CHAINMAIL", 4), 1.5, d -> 2 * d, 1, // the mod's chief sword and chainmail
                        new Object[]{121, Material.DIAMOND_SWORD, 5, Material.GOLDEN_SWORD, 5, Material.IRON_SWORD, 10, CHIEF_SWORD, 1, Material.STONE_SWORD, 20, TOME, 30})),
        MUMMIES("Mummies", "egyptian_raid",
                new Spec(Husk.class, null, null, new Material[0], 1, D1, 0, new Object[]{95, TOME, 15}),
                new Spec(Skeleton.class, Material.BOW, null, new Material[0], 1, D1, 0, new Object[]{95, Material.BOW, 10, TOME, 5}),
                new Spec(Skeleton.class, Material.BOW, null, set("GOLDEN", 2), 4.5, d -> 2 * d, 1, // the pharaoh's fire-arrow scepter: a Flame bow
                        new Object[]{113, PHARAOH_SCEPTER, 3, Material.ARROW, 20, FIRE_ARROW, 10, TOME, 30})),
        AMAZONS("Amazons", "amazon_raid",
                new Spec(Zombie.class, Material.STONE_SPEAR, null, set("LEATHER", 2), 1, D1, 0, new Object[]{100, SPEAR, 15, TOME, 5}),
                new Spec(Bogged.class, Material.BOW, null, set("LEATHER", 1), 1, D1, 0, new Object[]{100, Material.BOW, 15, TOME, 5}),
                new Spec(Bogged.class, Material.BOW, null, set("LEATHER", 4), 1.5, d -> 4 * d, 1, new Object[]{190, Material.BOW, 80, TOME, 30})),
        NORSEMEN("Norsemen", "norsemen_raid",
                new Spec(Zombie.class, Material.STONE_AXE, Material.SHIELD, set("IRON", 2), 1 / 1.25, d -> 15, 0, // the shieldmaiden
                        new Object[]{95, Material.SHIELD, 10, TOME, 5}),
                new Spec(Stray.class, Material.BOW, null, set("CHAINMAIL", 1), 1 / 1.25, D1, 0, new Object[]{95, Material.BOW, 10, TOME, 5}),
                new Spec(Zombie.class, Material.STONE_AXE, null, set("IRON", 4), 1, d -> 2 * d + 15, 1,
                        new Object[]{125, Material.LEATHER, 15, Material.DIAMOND_AXE, 10, TOME, 50})),
        PIRATES("Pirates", "pirate_raid", // a scimitar each (ours: an iron sword); the captain in a pirate set
                new Spec(Zombie.class, Material.IRON_SWORD, null, new Material[0], 1, D1, 0, new Object[]{90, SCIMITAR, 6, TOME, 4}),
                new Spec(Skeleton.class, Material.BOW, null, new Material[0], 1, D1, 0, new Object[]{95, Material.BOW, 10, TOME, 5}),
                new Spec(Zombie.class, Material.IRON_SWORD, null, set("LEATHER", 4), 1.3, d -> -1, 2, PIRATE_CHIEF_DROPS)), // the mod's armor -1: none
        DROWNED("Drowned Pirates", "drowned_pirate_raid",
                new Spec(Drowned.class, Material.IRON_SWORD, null, new Material[0], 1.5, d -> 0.25, 0, new Object[]{90, SCIMITAR, 6, TOME, 4}),
                null,
                new Spec(Drowned.class, Material.IRON_SWORD, null, set("LEATHER", 4), 2, d -> -1, 0, PIRATE_CHIEF_DROPS));

        final String title, id; // id: the mod's raid event type, for the raid command
        final Spec melee, archer, chief;
        final boolean ships; // asked for by the raid command, it may come by ship (norsemen, pirates, drowned pirates)

        Culture(String title, String id, Spec melee, Spec archer, Spec chief) {
            this.title = title;
            this.id = id;
            this.melee = melee;
            this.archer = archer;
            this.chief = chief;
            this.ships = id.equals("norsemen_raid") || id.contains("pirate");
        }
    }

    /** The mod's raid ships (ShipSize, its ship raid events): which blueprint, whose crew, how deep the water must be. */
    private enum Ship {
        NORSE("norsemen_ship", Culture.NORSEMEN, 3), SUNK("sunk_ship", Culture.DROWNED, 13), PIRATE("pirate_ship", Culture.PIRATES, 3);

        final String name;
        final Culture crew;
        final int depth; // canPlaceShipAt's neededDepth; the sunken ship's DEPTH_REQ

        Ship(String name, Culture crew, int depth) {
            this.name = name;
            this.crew = crew;
            this.depth = depth;
        }

        /** Its blueprint for so many raiders (ShipSize.getShipForRaiderAmount): small up to 5, medium under 18, else big. */
        String structure(int amount) {
            return Hut.Type.FALLBACK_STYLE + "/decorations/ships/" + (amount <= 5 ? "small_" : amount < 18 ? "medium_" : "big_") + name;
        }

        /** A landing party (ShipSize): normal, archer and boss raiders. */
        static int[] wave(int amount) {
            return amount <= 5 ? new int[]{2, 0, 0} : amount < 18 ? new int[]{1, 1, 1} : new int[]{2, 2, 1};
        }
    }

    private static final int GROUND_LEVELS = 4; // every raid ship's "groundlevel" tag: its keel is 3 below the water's surface
    // the mod's spawners: vanilla's defaults but for the player range (SPAWNER_DISTANCE)
    private static final int SPAWNER_RANGE = 30, SPAWN_COUNT = 4, MAX_NEARBY = 6, SPAWN_SPREAD = 4, MIN_DELAY = 200, MAX_DELAY = 800;

    /** A ship standing where a raid placed it, with its spawners, until it sails off (the mod's AbstractShipRaidEvent). */
    private static final class Docked {
        final Ship kind;
        final int amount;
        final Location target; // on the water's surface, where it was placed
        final org.bukkit.structure.Structure backup; // what stood there before (the mod's terrain backup)
        final Location corner;
        final Map<Block, Spec> spawners = new LinkedHashMap<>(); // a spec of null: the sunken ship's archer spawners, which spawn nothing
        final Map<Block, Integer> delays = new HashMap<>();
        int maxSpawners, deaths;
        Docked(Ship kind, int amount, Location target, org.bukkit.structure.Structure backup, Location corner) {
            this.kind = kind; this.amount = amount; this.target = target; this.backup = backup; this.corner = corner;
        }
    }

    /** One group of raiders from one spawn point (the mod's HordeRaidEvent, or a ship raid event). */
    private static final class Horde {
        final Culture culture;
        final Docked ship; // null for a land horde
        final List<Mob> raiders = new ArrayList<>();
        final Map<Mob, Spec> specs = new HashMap<>();
        final List<Block> campfires = new ArrayList<>();
        int initial, killed, camp; // killed: died, not just unloaded; camp: updates left camping by the fires
        final Location landed; // where it spawned: the mod's first waypoint
        Horde(Culture culture, Docked ship, Location landed) { this.culture = culture; this.ship = ship; this.landed = landed; }
    }

    /** A raider stuck on its march (the mod's PathingStuckHandler, simplified): where it was, for how long. */
    private static final class Stuck {
        Location last;
        int ticks;
        Stuck(Location last) { this.last = last; }
    }

    private final Colony colony;
    private final List<Horde> hordes = new ArrayList<>();
    private final List<Docked> docked = new ArrayList<>(); // ships of a raid that ended, until the next nightfall
    private final Map<Mob, Stuck> stuck = new HashMap<>();
    private final Map<Mob, Integer> envHit = new HashMap<>(); // the tick of each raider's last hit from no living attacker
    private BossBar bar;
    private int nightsLeft, lost, startedAt, ticks, picks;
    private double raidModifier; // the difficulty modifier the raiders were made with
    private boolean fewWarned, escaped; // escaped: a horde timed out or unloaded rather than being beaten

    private enum End { BEATEN, LEFT, DEFEAT }
    private Hut target; // the hut the raiders' picks give, changed after max(6, raiders / 3) picks
    private final Map<Mob, Hut> goals = new HashMap<>();       // the hut each raider marches on
    private final Map<Mob, Integer> goalSince = new HashMap<>(); // and since when
    private static final int RETARGET = 4800; // the mod's: a raider picks again every 240 s
    // Saved between raids:
    int nightsSince, raidDifficulty = 7, extraNights;
    public boolean raidTonight; // set by /colonies colony raid tonight too
    private Culture tonightType; // the raid command's type for tonight's raid, else null: by biome
    private int tonightAmount;   // and its amount, else 0: the mod's size
    boolean adaptPending;
    int lastLost, lastMax;
    /** Past raids (the mod's RaidHistory), oldest first. Saved. */
    private final List<Past> history = new ArrayList<>();

    /** One past raid: when (game time), how many raiders, their kinds and where they came from, how many died, citizens lost, difficulty. */
    record Past(long time, int amount, String spawns, int dead, int lost, double difficulty) {
        String save() { return time + ";" + amount + ";" + dead + ";" + lost + ";" + difficulty + ";" + spawns; }
        static Past load(String s) {
            String[] p = s.split(";", 6);
            if (p.length != 6) return null;
            try { return new Past(Long.parseLong(p[0]), Integer.parseInt(p[1]), p[5], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Double.parseDouble(p[4])); }
            catch (NumberFormatException e) { return null; }
        }
    }

    /** The current raid's entry, with so many raiders dead and citizens lost. */
    private void record(int dead, int lost) {
        if (history.isEmpty()) return;
        Past p = history.getLast();
        history.set(history.size() - 1, new Past(p.time, p.amount, p.spawns, dead, lost, p.difficulty));
    }

    Raids(Colony colony) {
        this.colony = colony;
    }

    boolean underway() {
        return !hordes.isEmpty();
    }

    /** Spies are out (a barracks paid for them this raid): every raider glows (the mod's areSpiesEnabled). Not saved, as the raid. */
    boolean spies;
    private static final int SPIES_GOLD = 5; // BuildingBarracks.SPIES_GOLD_COST

    /**
     * Each colony tick (the mod's BuildingBarracks.onColonyTick): during a raid, the first barracks whose chest holds 5
     * gold ingots pays them for spies; after it, they are called off.
     */
    void hireSpies() {
        if (!underway()) { spies = false; return; }
        if (spies) return;
        ItemStack gold = new ItemStack(Material.GOLD_INGOT);
        for (Hut b : colony.huts) {
            if (b.type != Hut.Type.BARRACKS || !(b.block.getState(false) instanceof org.bukkit.block.Container c)
                    || !c.getInventory().containsAtLeast(gold, SPIES_GOLD)) continue;
            c.getInventory().removeItem(gold.asQuantity(SPIES_GOLD));
            spies = true;
            colony.tell("Spies paid from the Barracks mark every raider."); // ours: the mod says nothing
            return;
        }
    }

    // ---- when -----------------------------------------------------------------------------------------------------

    /**
     * Nightfall (the mod's onNightFall): ships of a raid that ended sail off; after a raid the difficulty adapts to how
     * many citizens were lost; a raid on its way counts down its nights; a raid decided last night arrives; else tonight
     * decides about the next.
     */
    void nightfall() {
        if (adaptPending) adapt();
        if (underway()) {
            nightsSince = 0;
            if (--nightsLeft <= 0) { finish(End.LEFT); sailOff(); }
            return;
        }
        sailOff();
        nightsSince++;
        if (raidTonight) { // kept until a raid starts, as in the mod
            if (canRaid() && start(false, tonightAmount, tonightType) == null) { raidTonight = false; tonightType = null; tonightAmount = 0; }
            return;
        }
        var rnd = ThreadLocalRandom.current();
        if (nightsSince < minimumNights + extraNights) return;
        raidTonight = nightsSince > averageNights + 2 || averageNights <= minimumNights || rnd.nextInt(averageNights - minimumNights) == 0;
    }

    /** More than 15% lost makes the next raids easier and later; under 5% makes them harder (1 to 14). */
    private void adapt() {
        adaptPending = false;
        double lostPct = (double) lastLost / Math.max(1, lastMax);
        if (lostPct > 0.15) {
            raidDifficulty -= (int) (lostPct / 0.15);
            extraNights = Math.max(extraNights, (int) Math.ceil(averageNights * 0.4));
        } else if (lostPct < 0.05) raidDifficulty++;
        raidDifficulty = Math.clamp(raidDifficulty, 1, 14);
    }

    /** Raids on, not peaceful, and the owner or an officer online (the mod's canRaid). */
    private boolean canRaid() {
        if (!enabled || colony.townHall().block.getWorld().getDifficulty() == Difficulty.PEACEFUL) return false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Rank r = colony.permissions.rank(p.getUniqueId());
            if (r == Rank.OWNER || r == Rank.OFFICER) return true;
        }
        return false;
    }

    /**
     * The mod's getColonyRaidLevel: each adult citizen 5 plus its skill levels together / 100, each built hut 5 + level²
     * / 5, each finished research 3, times how full the colony's beds are.
     */
    int level() {
        int l = 0;
        for (Colonist c : colony.colonists) {
            if (c.child) continue;
            int skills = 0;
            for (Skills.Skill s : Skills.Skill.values()) skills += c.skills.level(s);
            l += 5 + skills / 100;
        }
        for (Hut h : colony.huts) if (h.type.isBuilding() && h.level > 0) l += 5 + h.level * h.level / 5;
        l += colony.research.completed() * 3;
        return (int) (l * Math.min(1.0, (double) colony.colonists.size() / colony.capacity()));
    }

    private double modifier() {
        World w = colony.townHall().block.getWorld();
        return (raidDifficulty / 10.0 + 0.2) * (difficulty / 5.0) * (w.getDifficulty().ordinal() / 2.0);
    }

    // ---- start ----------------------------------------------------------------------------------------------------

    /** The mod's raid types the raid command takes. */
    public static List<String> types() {
        return java.util.Arrays.stream(Culture.values()).map(c -> c.id).toList();
    }

    /** The raid command: a raid now, of a type ({@link #types}, null: by biome) and size (0: the mod's). @return why not, or null. */
    public String start(int amount, String type) {
        Culture culture = culture(type);
        if (type != null && culture == null) return "No raid type " + type + ". Try one of " + String.join(", ", types()) + ".";
        return start(true, amount, culture);
    }

    /** The raid command's "tonight": the next nightfall's raid, with its type and size kept until it starts, as the mod's setRaidNextNight. */
    public String raidTonight(int amount, String type) {
        Culture culture = culture(type);
        if (type != null && culture == null) return "No raid type " + type + ". Try one of " + String.join(", ", types()) + ".";
        raidTonight = true;
        tonightType = culture;
        tonightAmount = amount;
        return null;
    }

    private static Culture culture(String type) {
        for (Culture c : Culture.values()) if (c.id.equals(type)) return c;
        return null;
    }

    /**
     * Starts a raid now, of {@code culture} (null: by biome). {@code amount} 0: the mod's size, 1 + level / 60 ×
     * modifier × (1 + 5% per member online) × 0.85 to 1.15, at most the configured maximum + 1 (and at the maximum the
     * raiders are that much stronger: the mod's spawnCountAdjustedDifficulty). @return why not, or null when it started.
     */
    private String start(boolean forced, int amount, Culture culture) {
        if (underway()) return "A raid is already under way.";
        int level = level();
        if (!forced && level < MIN_LEVEL) return "The colony is too small to be raided (raid level " + level + " of " + MIN_LEVEL + ").";
        double d = modifier();
        if (amount <= 0) {
            long members = Bukkit.getOnlinePlayers().stream().filter(p -> colony.permissions.isMember(p.getUniqueId())).count();
            amount = 1 + Math.min(maxRaiders, (int) (level / 60.0 * d * (1 + 0.05 * members) * ThreadLocalRandom.current().nextDouble(0.85, 1.15)));
            if (amount >= maxRaiders) d *= (double) amount / maxRaiders;
        }
        amount = Math.min(amount, maxRaiders + 1); // ours: a typed amount is capped too, the mod's own size can be one over
        Location centre = centre();
        if (centre == null) return "None of the colony's buildings are loaded.";
        int points = Math.max(1, amount / BIG_HORDE);
        int each = (int) Math.ceil((double) amount / points); // the mod splits by the points it asked for
        List<Block> spots = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            Block at = spawnPoint(centre, each, culture == null || culture.ships);
            if (at != null) spots.add(at);
        }
        if (spots.isEmpty()) return "No place to spawn raiders was found around the colony.";
        raidModifier = d;
        List<String> where = new ArrayList<>();
        for (Block at : spots) {
            Horde h = horde(at, each, culture, centre, level, d);
            if (h == null) continue; // a sunken ship that didn't fit: the mod's NO_SPAWN_POINT
            hordes.add(h);
            where.add((h.ship != null ? h.ship.kind.name : h.culture.id) + "@" + at.getX() + "," + at.getY() + "," + at.getZ());
        }
        if (hordes.isEmpty()) return "No place to spawn raiders was found around the colony.";
        nightsLeft = NIGHTS;
        nightsSince = 0;
        extraNights = 0;
        startedAt = Bukkit.getCurrentTick();
        lost = 0;
        fewWarned = false;
        escaped = false;
        Horde first = hordes.getFirst();
        Location from = first.landed;
        String dir = direction(colony.townHall().block.getLocation(), from);
        bar = BossBar.bossBar(Component.text(first.culture.title + " - " + dir), 1, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        // the mod's screen effects: darkness for mummies and pirates on land and drowned pirates, fog for norsemen and amazons
        if (first.culture == Culture.MUMMIES || first.culture == Culture.DROWNED || first.culture == Culture.PIRATES && first.ship == null)
            bar.addFlag(BossBar.Flag.DARKEN_SCREEN);
        if (first.culture == Culture.NORSEMEN || first.culture == Culture.AMAZONS) bar.addFlag(BossBar.Flag.CREATE_WORLD_FOG);
        int count = hordes.stream().mapToInt(h -> h.raiders.size()).sum();
        history.add(new Past(from.getWorld().getGameTime(), amount, String.join(" ", where), 0, 0, Math.round(d * 100) / 100.0));
        Colony.log.info(colony.where() + ": raid started, " + count + " " + first.culture.title + (first.ship != null ? " by ship" : "")
                + " from the " + dir.toLowerCase() + " (raid level " + level + ").");
        colony.tell(first.culture.title + (first.ship != null ? "' ship has been" : " have been") + " spotted " + dir.toLowerCase() + " of " + colony.name
                + "! The citizens run for shelter.");
        if (hordes.stream().anyMatch(h -> h.ship == null)) // the illagers' raid horn (ours: the mod plays its own alert); not for ships, as the mod's
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getWorld().equals(from.getWorld()) && colony.permissions.isMember(p.getUniqueId())) {
                    var toward = from.toVector().subtract(p.getLocation().toVector()).normalize().multiply(16);
                    p.playSound(p.getLocation().add(toward), Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 64, 1);
                }
        return null;
    }

    /**
     * What comes from one spawn point (the mod's raiderEvent): on water, a drowned raid is likely (deep water, or 1 in
     * 5); then a Norse ship (taiga, or 2 in 100), a sunken ship (ocean), a pirate ship, if the water there takes one;
     * else a horde over land: mummies (desert, or 1 in 100), amazons (jungle, or 1 in 100), norsemen (taiga, or 1 in
     * 100), pirates only when asked for, else barbarians two times in three and norsemen otherwise. Null: none fits.
     */
    private Horde horde(Block at, int amount, Culture forced, Location centre, int level, double d) {
        var rnd = ThreadLocalRandom.current();
        Culture type = forced;
        if ((type == null || type == Culture.DROWNED) && water(at) && water(at.getRelative(BlockFace.DOWN))
                && (water(at.getRelative(BlockFace.UP)) || rnd.nextInt(100) <= 20)) {
            type = Culture.DROWNED;
            for (int i = 0; i < Ship.SUNK.depth && at.getRelative(BlockFace.UP).isLiquid(); i++) at = at.getRelative(BlockFace.UP);
        }
        StructureRotation rotation = StructureRotation.values()[rnd.nextInt(4)];
        String biome = centre.getWorld().getBiome(centre).getKey().getKey();
        int roll = rnd.nextInt(100);
        Ship ship = null;
        Block placed = null;
        if ((type == null && (biome.contains("taiga") || roll < IGNORE_BIOME) || type == Culture.NORSEMEN) && (placed = fits(Ship.NORSE, at, amount, rotation)) != null) ship = Ship.NORSE;
        else if ((type == null && biome.contains("ocean") || type == Culture.DROWNED) && (placed = fits(Ship.SUNK, at, amount, rotation)) != null) ship = Ship.SUNK;
        else {
            if (type == Culture.DROWNED && forced == null) type = null; // ours: rolled drowned where a sunken ship doesn't fit (the mod loses the spawn point)
            if (type == null || type == Culture.PIRATES) // ours: the other turns too, where the one rolled doesn't fit (the spawn search took any of two)
                for (int turn = 0; turn < 4 && ship == null; turn++) {
                    StructureRotation r = StructureRotation.values()[(rotation.ordinal() + turn) % 4];
                    if ((placed = fits(Ship.PIRATE, at, amount, r)) != null) { ship = Ship.PIRATE; rotation = r; }
                }
        }
        if (ship != null) return sail(ship, placed, amount, rotation, centre, level, d);
        Culture land;
        if (type == null && (biome.contains("desert") || roll == 3) || type == Culture.MUMMIES) land = Culture.MUMMIES;
        else if (type == null && (biome.contains("jungle") || roll == 5) || type == Culture.AMAZONS) land = Culture.AMAZONS;
        else if (type == null && (biome.contains("taiga") || roll == 7) || type == Culture.NORSEMEN) land = Culture.NORSEMEN;
        else if (type == Culture.PIRATES) land = Culture.PIRATES; // the mod's pirate ground raid
        else if (type == null) land = rnd.nextDouble() > 0.33 ? Culture.BARBARIANS : Culture.NORSEMEN;
        else if (type == Culture.BARBARIANS) land = Culture.BARBARIANS;
        else return null;
        Block ground = at.getWorld().getHighestBlockAt(at.getX(), at.getZ());
        if (ground.isLiquid()) return null; // ours: no horde in the water
        Location l = ground.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
        Horde h = new Horde(land, null, l);
        spawn(h, l, amount, level, d);
        camp(h, l, centre);
        return h;
    }

    /** Water a raider can swim through (the mod's isWater): water, or a waterlogged block that doesn't block movement. */
    private static boolean water(Block b) {
        return b.getType() == Material.WATER || !b.getType().isSolid() && b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    private static final Map<String, BlockVector> ANCHORS = new HashMap<>();
    private static final Map<String, int[]> FOOTPRINTS = new HashMap<>(); // by structure and turn: x and z from its anchor, as BuildOrder.footprint

    /** Where the ship's anchor goes so that its keel is 3 below {@code surface}, or null. */
    private static Block anchorFor(String structure, Block surface) {
        BlockVector a = ANCHORS.computeIfAbsent(structure, BuildOrder::anchorOf);
        return a == null ? null : surface.getRelative(0, a.getBlockY() - (GROUND_LEVELS - 1), 0);
    }

    /** The ship's footprint with its anchor at {@code anchor} (minX, maxX + 1, minZ, maxZ + 1), or null; worked out once per ship and turn. */
    private static int[] footprint(String structure, StructureRotation rotation, Block anchor) {
        int[] rel = FOOTPRINTS.computeIfAbsent(structure + "/" + rotation, k -> {
            BuildOrder o = BuildOrder.create(structure, anchor.getLocation(), rotation, false, anchor);
            if (o == null) return null;
            int[] fp = o.footprint();
            return new int[]{fp[0] - anchor.getX(), fp[1] - anchor.getX(), fp[2] - anchor.getZ(), fp[3] - anchor.getZ()};
        });
        return rel == null ? null : new int[]{rel[0] + anchor.getX(), rel[1] + anchor.getX(), rel[2] + anchor.getZ(), rel[3] + anchor.getZ()};
    }

    /**
     * Whether the ship fits on the water at {@code at}, or one lower (the mod's canSpawnShipAt): of its footprint at that
     * height, 85% liquid, ice or water with nothing solid in the 5 blocks above, and liquid for its depth straight down.
     * @return the surface it fits at, or null.
     */
    private Block fits(Ship ship, Block at, int amount, StructureRotation rotation) {
        for (Block surface : List.of(at, at.getRelative(BlockFace.DOWN))) {
            Block anchor = anchorFor(ship.structure(amount), surface);
            int[] fp = anchor == null ? null : footprint(ship.structure(amount), rotation, anchor);
            if (fp == null) return null; // the original style's pack isn't installed
            int ok = 0, all = 0;
            for (int x = fp[0]; x < fp[1]; x++) for (int z = fp[2]; z < fp[3]; z++) {
                all++;
                Block b = surface.getWorld().getBlockAt(x, surface.getY(), z);
                if (!(b.isLiquid() || Tag.ICE.isTagged(b.getType()) || water(b))) continue;
                boolean open = true;
                for (int up = 1; up <= 5 && open; up++) open = !b.getRelative(0, up, 0).getType().isSolid();
                if (open) ok++;
            }
            if (ok < 0.85 * all) continue;
            boolean deep = true;
            for (int i = 0; i < ship.depth && deep; i++) deep = surface.getRelative(0, -i, 0).isLiquid();
            if (deep) return surface;
        }
        return null;
    }

    /**
     * Places the ship (the mod's spawnTemporaryStructure: what stood there is kept, and comes back when it sails off) and
     * turns its tagged gold blocks into spawners (the mod's CreativeRaiderStructureHandler); a first landing party
     * comes at once (ours: the mod's comes on its first update).
     */
    private Horde sail(Ship ship, Block surface, int amount, StructureRotation rotation, Location centre, int level, double d) {
        String structure = ship.structure(amount);
        if (ship == Ship.SUNK) while (surface.isLiquid() && surface.getY() > surface.getWorld().getMinHeight()) surface = surface.getRelative(BlockFace.DOWN); // to the sea floor
        Block anchor = anchorFor(structure, surface);
        BuildOrder o = BuildOrder.create(structure, anchor.getLocation(), rotation, false, anchor);
        int[] fp = o.footprint();
        int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (BuildOrder.Step s : o.steps) { y0 = Math.min(y0, s.block().getY()); y1 = Math.max(y1, s.block().getY()); }
        World w = surface.getWorld();
        Location corner = new Location(w, fp[0], y0, fp[2]);
        var backup = Bukkit.getStructureManager().createStructure();
        backup.fill(corner, new BlockVector(fp[1] - fp[0], y1 - y0 + 1, fp[3] - fp[2]), false);
        o.paste();
        Docked dock = new Docked(ship, amount, surface.getLocation().add(0.5, 1, 0.5), backup, corner);
        keep(dock);
        o.workSpots.forEach((b, tag) -> {
            if (b.getType() != Material.GOLD_BLOCK || !List.of("normal", "archer", "boss").contains(tag)) return;
            // the archer spawners of drowned pirates, who have no archers, spawn nothing, as the mod's
            Spec s = tag.equals("normal") ? ship.crew.melee : tag.equals("archer") ? ship.crew.archer : ship.crew.chief;
            b.setType(Material.SPAWNER);
            if (b.getState() instanceof CreatureSpawner cs) { cs.setRequiredPlayerRange(0); cs.update(); } // ours spawn them (tick), not vanilla
            dock.spawners.put(b, s);
            dock.delays.put(b, 20); // vanilla's first spawn delay
        });
        dock.maxSpawners = dock.spawners.size();
        Horde h = new Horde(ship.crew, dock, dock.target);
        land(h, centre, level, d);
        h.initial = Math.max(1, h.raiders.size());
        return h;
    }

    /** The middle of the colony's loaded built huts, or null. */
    private Location centre() {
        double x = 0, y = 0, z = 0;
        int n = 0;
        World w = colony.townHall().block.getWorld();
        for (Hut h : colony.huts) {
            Block b = h.block;
            if (h.level < 1 || !w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            x += b.getX(); y += b.getY(); z += b.getZ(); n++;
        }
        return n == 0 ? null : new Location(w, x / n, y / n, z / n);
    }

    /**
     * The mod's spawn search: a random direction, from the hut farthest out that way, 48 blocks a step (up to 10, only
     * into loaded chunks), each step valid when no other colony owns it and every built hut is 35 + a bonus per level away
     * (guard tower 7, town hall 8, houses and taverns 4, others 2); the third valid step, else the last. Up to 8 directions.
     * On land, the block above the ground; on water, its surface. Ours: water counts only where a pirate ship fits and the
     * raid may come by ship, lava never.
     */
    private Block spawnPoint(Location centre, int amount, boolean ships) {
        var rnd = ThreadLocalRandom.current();
        World w = centre.getWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = rnd.nextDouble(Math.PI * 2), dx = Math.cos(angle), dz = Math.sin(angle);
            Hut from = null;
            double best = Double.MAX_VALUE;
            for (Hut h : colony.huts) {
                if (h.level < 1) continue;
                double along = (h.block.getX() - centre.getX()) * dx + (h.block.getZ() - centre.getZ()) * dz;
                if (-along < best) { best = -along; from = h; }
            }
            if (from == null) return null;
            Block found = null;
            int valid = 0;
            for (int step = 1; step <= 10; step++) {
                int x = (int) (from.block.getX() + dx * 48 * step), z = (int) (from.block.getZ() + dz * 48 * step);
                if (!w.isChunkLoaded(x >> 4, z >> 4) || !w.getWorldBorder().isInside(new Location(w, x, 0, z))) break;
                if (!clear(w, x, z)) continue;
                Block floor = w.getHighestBlockAt(x, z);
                if (floor.getType() == Material.LAVA) continue;
                if (floor.isLiquid() && (!ships || fits(Ship.PIRATE, floor, amount, StructureRotation.NONE) == null
                        && fits(Ship.PIRATE, floor, amount, StructureRotation.CLOCKWISE_90) == null)) continue;
                found = floor.isLiquid() ? floor : floor.getRelative(BlockFace.UP);
                if (++valid >= 3) break;
            }
            if (found != null) return found;
        }
        return null;
    }

    private boolean clear(World w, int x, int z) {
        Colony owner = colony.claimMap == null ? null : colony.claimMap.colonyAt(w, x >> 4, z >> 4); // the plugin sets it
        if (owner != null && owner != colony) return false;
        for (Hut h : colony.huts) {
            if (h.level < 1) continue;
            int bonus = switch (h.type) {
                case GUARD_TOWER -> 7;
                case TOWN_HALL -> 8;
                case HOUSE, TAVERN -> 4;
                default -> 2;
            };
            if (Math.abs(h.block.getX() - x) + Math.abs(h.block.getZ() - z) < 35 + bonus * h.level) return false;
        }
        return true;
    }

    /** "North", "South-west"...: which way {@code to} lies from {@code from}. */
    private static String direction(Location from, Location to) {
        String[] names = {"East", "South-east", "South", "South-west", "West", "North-west", "North", "North-east"};
        double angle = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX())); // +z is south
        return names[Math.floorMod(Math.round(angle / 45), 8)];
    }

    /** A tenth chiefs and three tenths archers (at least one of each), the rest melee, in a 6-wide grid. */
    private void spawn(Horde h, Location at, int n, int level, double d) {
        int chiefs = Math.max(1, (int) (0.1 * n)), archers = Math.max(1, (int) (0.3 * n)), melee = Math.max(0, n - chiefs - archers);
        List<Spec> kinds = new ArrayList<>();
        for (int i = 0; i < chiefs; i++) kinds.add(h.culture.chief);
        for (int i = 0; i < archers; i++) kinds.add(h.culture.archer != null ? h.culture.archer : h.culture.melee);
        for (int i = 0; i < melee; i++) kinds.add(h.culture.melee);
        for (int i = 0; i < kinds.size(); i++) {
            Block floor = at.getWorld().getHighestBlockAt(at.getBlockX() + i % 6 * 2 - 5, at.getBlockZ() + i / 6 * 2);
            add(h, kinds.get(i), floor.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5), level, d);
        }
        h.initial = h.raiders.size();
    }

    private void add(Horde h, Spec s, Location at, int level, double d) {
        Mob m = raider(s, at, level, d);
        h.raiders.add(m);
        h.specs.put(m, s);
    }

    /** One raider with the mod's stats (RaiderMobUtils.setMobAttributes and each raider's own changes). */
    private static Mob raider(Spec s, Location at, int level, double d) {
        return at.getWorld().spawn(at, s.mob(), m -> {
            m.addScoreboardTag(TAG);
            m.setInvulnerable(true); // the mod's 2 s after spawning, against suffocating and crowding (tick)
            m.setPersistent(false); // shortcut: not saved with the world, so a restart or unloaded chunk ends them; the mod respawns them
            m.setPersistenceRequired(true); // no natural despawning far from players (the mod: 30 minutes)
            m.setCanPickupItems(false);
            if (m instanceof Zombie z) {
                z.setAdult();
                z.setShouldBurnInDay(false);
                z.setCanBreakDoors(breakDoors && at.getWorld().getDifficulty().ordinal() >= Difficulty.NORMAL.ordinal()); // the mod: Normal and up
            }
            if (m instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
            m.setRemainingAir(m.getMaximumAir());
            set(m, Attribute.MAX_HEALTH, Math.max(10, 10 + 0.025 * level) * d * s.health());
            m.setHealth(m.getAttribute(Attribute.MAX_HEALTH).getValue());
            set(m, Attribute.ATTACK_DAMAGE, 2 + d * Math.min(level / 400, 3) + s.damage());
            set(m, Attribute.ARMOR, Math.max(0, s.armorPoints().applyAsDouble(d)));
            set(m, Attribute.MOVEMENT_SPEED, d >= 2.4 ? 0.30 : 0.25);
            set(m, Attribute.FOLLOW_RANGE, 70);
            EntityEquipment e = m.getEquipment();
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            String[] pirate = PIRATE_SETS[ThreadLocalRandom.current().nextInt(2)]; // a captain's set, 1 or 2 at random
            for (int i = 0; i < s.armor().length; i++) { // the mod's barbarian chief wears real chainmail, which adds its armor; the rest is for looks
                ItemStack piece = captain(s) ? raiderItem(s.armor()[i], pirate[i], title(pirate[i])) : new ItemStack(s.armor()[i]);
                e.setItem(slots[i], s == Culture.BARBARIANS.chief ? piece : looks(piece));
            }
            if (s.weapon() != null) {
                ItemStack weapon = weaponOf(s);
                e.setItemInMainHand(weapon.getType() == Material.BOW ? weapon : looks(weapon)); // a bow's damage is its arrows'
            }
            if (s.offhand() != null) e.setItemInOffHand(looks(new ItemStack(s.offhand())));
            for (EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET)) e.setDropChance(slot, 0); // drops: Raids.died
        });
    }

    /** Worn or held for looks only: its own armor and damage don't count (the raider's stats are the mod's). */
    private static ItemStack looks(ItemStack item) {
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes().build());
        return item;
    }

    private static void set(LivingEntity e, Attribute a, double value) {
        var attr = e.getAttribute(a);
        if (attr != null) attr.setBaseValue(value);
    }

    /** Campfires, one per 5 raiders, 3 to 7 blocks out; it camps 3 updates, 6 within 100 blocks of the colony's centre. */
    private void camp(Horde h, Location at, Location centre) {
        var rnd = ThreadLocalRandom.current();
        for (int i = 0; i < Math.max(1, h.initial / 5); i++) {
            int dx = rnd.nextInt(3, 8) * (rnd.nextBoolean() ? 1 : -1), dz = rnd.nextInt(3, 8) * (rnd.nextBoolean() ? 1 : -1);
            Block floor = at.getWorld().getHighestBlockAt(at.getBlockX() + dx, at.getBlockZ() + dz);
            Block spot = floor.getRelative(BlockFace.UP);
            if (!floor.getType().isSolid() || !spot.isEmpty()) continue;
            spot.setType(Material.CAMPFIRE);
            h.campfires.add(spot);
        }
        h.camp = at.distance(centre) <= 100 ? 6 : 3;
    }

    // ---- ships ----------------------------------------------------------------------------------------------------

    /**
     * A landing party from the ship (the mod's onUpdate: when fewer than two raiders a spawner are about): its ShipSize's
     * normal, archer and boss raiders, ashore toward the colony (ours: the first dry ground on the way, else the deck).
     */
    private void land(Horde h, Location centre, int level, double d) {
        Docked s = h.ship;
        Location at = s.target.clone();
        boolean ashore = false;
        Vector toward = centre.toVector().subtract(at.toVector()).setY(0);
        if (toward.lengthSquared() > 0) {
            toward.normalize();
            for (int i = 4; i <= 200; i += 4) { // ours: up to 200 blocks out, a look every 4
                Location l = s.target.clone().add(toward.clone().multiply(i));
                if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) break;
                Block floor = l.getWorld().getHighestBlockAt(l);
                if (floor.isLiquid() || floor.getType() == Material.GOLD_BLOCK || floor.getType() == Material.SPAWNER) continue;
                if (Math.abs(floor.getX() - s.target.getX()) + Math.abs(floor.getZ() - s.target.getZ()) < 24) continue; // ours: still the ship (the big one is 42 long)
                at = floor.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
                ashore = true;
                break;
            }
        }
        if (!ashore && !s.spawners.isEmpty()) at = s.spawners.keySet().iterator().next().getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
        int[] wave = Ship.wave(s.amount);
        Culture c = h.culture;
        int cap = s.spawners.size() * 2 + 10; // the mod's: never more raiders than this
        for (int i = 0; i < wave[0] && h.raiders.size() < cap; i++) add(h, c.melee, at, level, d);
        if (c.archer != null) for (int i = 0; i < wave[1] && h.raiders.size() < cap; i++) add(h, c.archer, at, level, d);
        for (int i = 0; i < wave[2] && h.raiders.size() < cap; i++) add(h, c.chief, at, level, d);
    }

    /**
     * The ship's spawners, as vanilla's with the mod's player range: each, with a player within 30 blocks, spawns up to 4
     * of its raiders within 4 blocks every 10 to 40 s while fewer than 6 of them are near; never more raiders than two a
     * spawner plus 10 (the mod's cap). A spawner broken by a player is gone.
     */
    private void spawners(Horde h) {
        Docked s = h.ship;
        var rnd = ThreadLocalRandom.current();
        int level = level();
        for (var it = s.spawners.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Block b = e.getKey();
            if (b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && b.getType() != Material.SPAWNER) { it.remove(); continue; }
            int delay = s.delays.merge(b, -20, Integer::sum);
            if (delay > 0 || e.getValue() == null) continue;
            Location c = b.getLocation().toCenterLocation();
            if (c.getNearbyPlayers(SPAWNER_RANGE).isEmpty()) continue;
            s.delays.put(b, rnd.nextInt(MIN_DELAY, MAX_DELAY));
            long near = h.raiders.stream().filter(m -> m.getLocation().distanceSquared(c) < 81).count(); // ours: within 9, for vanilla's box around it
            for (int i = 0; i < SPAWN_COUNT && near < MAX_NEARBY && h.raiders.size() < s.spawners.size() * 2 + 10; i++) {
                Block at = b.getRelative(rnd.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1), rnd.nextInt(-1, 2), rnd.nextInt(-SPAWN_SPREAD, SPAWN_SPREAD + 1));
                if (!at.isPassable() || !at.getRelative(BlockFace.UP).isPassable() || at.getRelative(BlockFace.DOWN).isPassable() && !at.isLiquid()) continue;
                add(h, e.getValue(), at.getLocation().add(0.5, 0, 0.5), level, raidModifier);
                near++;
            }
        }
    }

    /** What stood where the ship lies comes back (the mod's loadBackupForEvent): the ship sails off. */
    private void restore(Docked s) {
        for (Block b : s.spawners.keySet()) if (b.getType() == Material.SPAWNER) b.setType(Material.AIR);
        s.backup.place(s.corner, false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new java.util.Random());
        backupFile(s.corner).delete();
    }

    /**
     * The backup of what a ship covers, also on disk (the mod's structure backups): after a crash it is put back when the
     * colony loads. Named for where it goes: plugins/Colonies/ships/(owner)/(world);(x);(y);(z).nbt.
     */
    private java.io.File backupFile(Location corner) {
        java.io.File dir = new java.io.File(org.bukkit.plugin.java.JavaPlugin.getPlugin(dev.minecp.MineCP.class).getDataFolder(), "ships/" + colony.owner);
        return new java.io.File(dir, corner.getWorld().getName() + ";" + corner.getBlockX() + ";" + corner.getBlockY() + ";" + corner.getBlockZ() + ".nbt");
    }

    private void keep(Docked s) {
        java.io.File f = backupFile(s.corner);
        try {
            f.getParentFile().mkdirs();
            Bukkit.getStructureManager().saveStructure(f, s.backup);
        } catch (java.io.IOException e) {
            Colony.log.warning(colony.where() + ": couldn't keep what the raid ship covers on disk: " + e.getMessage());
        }
    }

    /** Ships left in the world by a crash sail off: what they cover comes back from the backups on disk. */
    private void restoreLeftovers() {
        java.io.File[] files = new java.io.File(org.bukkit.plugin.java.JavaPlugin.getPlugin(dev.minecp.MineCP.class).getDataFolder(), "ships/" + colony.owner).listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            String[] p = f.getName().replace(".nbt", "").split(";");
            World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
            if (w == null) continue;
            try {
                Bukkit.getStructureManager().loadStructure(f).place(new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])),
                        false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new java.util.Random());
                f.delete();
                Colony.log.info(colony.where() + ": a raid ship left by a crash sailed off.");
            } catch (java.io.IOException | NumberFormatException e) {
                Colony.log.warning(colony.where() + ": couldn't put back what a raid ship covered (" + f.getName() + "): " + e.getMessage());
            }
        }
    }

    /** At nightfall, ships of a raid that ended sail off. */
    private void sailOff() {
        if (docked.isEmpty()) return;
        for (Docked s : docked) restore(s);
        docked.clear();
        colony.tell("The raiders' ship sails off.");
    }

    // ---- during ---------------------------------------------------------------------------------------------------

    /** Every tick from the colony: steering, spawners and the boss bar once a second; camping, landings and glowing every update. */
    void tick() {
        if (hordes.isEmpty()) return;
        ticks++;
        for (Horde h : hordes) for (Mob m : h.raiders) if (m.isInvulnerable() && m.getTicksLived() >= SPAWN_SHIELD) m.setInvulnerable(false);
        if (ticks % UPDATE == 0) {
            Location centre = centre();
            for (Horde h : hordes) {
                if (h.camp > 0) h.camp--;
                if (h.ship != null && centre != null && !h.ship.spawners.isEmpty() && h.raiders.size() < h.ship.spawners.size() * 2) land(h, centre, level(), raidModifier);
                boolean glow = spies || (h.ship != null ? h.ship.spawners.isEmpty() : h.raiders.size() < Math.round(0.15 * h.initial));
                if (glow) h.raiders.forEach(m -> m.setGlowing(true));
            }
        }
        if (ticks % 20 != 0) return;
        boolean timedOut = Bukkit.getCurrentTick() - startedAt > DESPAWN_TICKS;
        float progress = 0;
        int alive = 0;
        for (Horde h : List.copyOf(hordes)) {
            h.raiders.removeIf(m -> !m.isValid() || m.isDead());
            if (h.ship != null) spawners(h);
            int standing = h.initial - h.killed; // unloaded raiders still count: walking away isn't a win
            boolean beaten = h.ship != null ? h.ship.spawners.isEmpty() && h.raiders.isEmpty() // the mod's: every spawner and raider gone
                    : standing < Math.floor(0.1 * h.initial) || standing <= 0; // the mod's win: under a tenth left
            if (timedOut || beaten || h.ship == null && h.raiders.isEmpty()) {
                if (!beaten) escaped = true;
                end(h);
                continue;
            }
            for (Mob m : h.raiders) steer(h, m);
            alive += h.raiders.size();
            progress += h.ship != null ? (float) h.ship.spawners.size() / Math.max(1, h.ship.maxSpawners) : (float) h.raiders.size() / Math.max(1, h.initial);
        }
        if (hordes.isEmpty()) { finish(escaped ? End.LEFT : End.BEATEN); return; }
        bar.progress(Math.clamp(progress / hordes.size(), 0, 1));
        for (Player p : Bukkit.getOnlinePlayers()) { // shown while in the colony's land
            Location l = p.getLocation();
            if (l.getWorld().equals(colony.townHall().block.getWorld()) && colony.claims().contains(Claims.key(l.getBlockX() >> 4, l.getBlockZ() >> 4)))
                p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
        if (alive <= 5 && !fewWarned && hordes.stream().allMatch(h -> h.ship == null)) {
            fewWarned = true;
            colony.tell("Only " + alive + " raiders left!");
        }
    }

    /** A horde is done: its raiders and campfires go; its ship stays until the next nightfall, as the mod's. */
    private void end(Horde h) {
        h.raiders.forEach(Entity::remove);
        h.raiders.clear();
        removeCamp(h);
        if (h.ship != null) docked.add(h.ship);
        hordes.remove(h);
    }

    /**
     * A raider without a target looks for a citizen or player within sight; else, done camping, it marches on the shared
     * target hut in steps of up to 32 blocks (vanilla paths reach about that far; the mod follows waypoints along a path
     * it found). As in the mod, a step that isn't the hut and lies over 50 blocks (Manhattan) from where the horde landed
     * is walked at 1.8, else 1.1. A barbarian chief speeds up those around it now and then; one stuck on its march breaks
     * its way through.
     */
    private void steer(Horde h, Mob m) {
        dev.minecp.citizen.Nms.keepActive(m, 40);
        if (h.specs.get(m) == Culture.BARBARIANS.chief) aura(m);
        LivingEntity t = m.getTarget();
        if (t == null || !t.isValid() || t.isDead()) {
            t = nearestTarget(m);
            if (t != null) m.setTarget(t);
        }
        Hut goal = t != null || h.camp > 0 ? null : goal(m);
        if (goal != null && breakGate(m)) return;
        unstick(m, goal);
        if (goal == null || m.getPathfinder().hasPath()) return;
        Location at = m.getLocation(), there = goal.block.getLocation().toCenterLocation();
        double dist = at.distance(there);
        if (dist < 5) { goals.remove(m); return; } // reached: another hut next (the mod roams its bounds for 30 s first)
        Location step = dist <= 32 ? there : at.clone().add(there.toVector().subtract(at.toVector()).normalize().multiply(32));
        if (dist > 32) step = step.getWorld().getHighestBlockAt(step).getLocation().add(0.5, 1, 0.5);
        double fromLanding = Math.abs(step.getX() - h.landed.getX()) + Math.abs(step.getY() - h.landed.getY()) + Math.abs(step.getZ() - h.landed.getZ());
        m.getPathfinder().moveTo(step, dist > 32 && fromLanding > 50 ? 1.8 : 1.1);
    }

    /**
     * The barbarian chief's aura (the mod's chief sword): with the raid's difficulty modifier over 2, about every 30 s
     * (the mod: every 120 of its steps, each 1 to 20 ticks at random, about 5.6 on average), Speed II for 3 s on every raider within 7 blocks across and
     * 3 up or down that hasn't Speed. Ours: the box is even all round (the mod's grows one way only).
     */
    private void aura(Mob chief) {
        if (raidModifier <= 2.0 || ThreadLocalRandom.current().nextInt(30) != 0) return;
        for (Entity e : chief.getNearbyEntities(7, 3, 7))
            if (e instanceof Mob m && isRaider(m) && !m.hasPotionEffect(PotionEffectType.SPEED))
                m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1));
    }

    /**
     * A raider on its march that hasn't moved for 5 s breaks a block in its way every 3 s (the mod's PathingStuckHandler,
     * raidersbreakblocks): the first of the block it stands in, the one at its head, above that, then ahead of it at its
     * head and its feet, toward its goal; it takes max(0.5, health / 20) damage for each while over a third of its health.
     * After 2 minutes stuck it takes 40% of its health (with blocks off too) and breaks the first block toward its goal within 6. Never a hut
     * block, a decoration controller, a container (ours), or what can't be broken. No drops, as the mod's.
     * Ours: no ladders or leaf bridges.
     */
    private void unstick(Mob m, Hut goal) {
        Stuck st = stuck.computeIfAbsent(m, k -> new Stuck(m.getLocation()));
        Location here = m.getLocation();
        if (goal == null || here.getWorld() != st.last.getWorld() || here.distanceSquared(st.last) >= 1) { st.last = here; st.ticks = 0; return; }
        st.ticks += 20;
        Vector toward = goal.block.getLocation().toCenterLocation().toVector().subtract(here.toVector()).setY(0);
        BlockFace ahead = toward.lengthSquared() == 0 ? BlockFace.NORTH
                : Math.abs(toward.getX()) > Math.abs(toward.getZ()) ? (toward.getX() > 0 ? BlockFace.EAST : BlockFace.WEST) : (toward.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        Block feet = here.getBlock();
        if (st.ticks >= 2400) { // the mod's last resort (completeStuckBlockBreak 6, the damage whether or not blocks break)
            m.damage(m.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.4);
            for (int i = 1; i <= 6 && breakBlocks; i++) {
                Block b = feet.getRelative(ahead, i);
                if (breakable(b)) { b.setType(Material.AIR); break; }
                if (breakable(b.getRelative(BlockFace.UP))) { b.getRelative(BlockFace.UP).setType(Material.AIR); break; }
            }
            st.ticks = 0;
            return;
        }
        if (!breakBlocks || st.ticks < 100 || (st.ticks - 100) % 60 != 0) return;
        for (Block b : List.of(feet, feet.getRelative(BlockFace.UP), feet.getRelative(0, 2, 0), feet.getRelative(BlockFace.UP).getRelative(ahead), feet.getRelative(ahead))) {
            if (!breakable(b)) continue;
            b.setType(Material.AIR);
            double hp = m.getHealth(), max = m.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (hp >= max / 3) m.damage(Math.max(0.5, hp / 20));
            return;
        }
    }

    private boolean breakable(Block b) {
        Material t = b.getType();
        return !t.isAir() && !b.isLiquid() && t.isSolid() && t.getHardness() >= 0 && t != Material.BEDROCK && dev.minecp.Gates.kind(b) == null
                && !(b.getState(false) instanceof org.bukkit.block.Container) && colony.hutAt(b) == null && !dev.minecp.build.DecorationController.is(b);
    }

    private final Map<Mob, Double> breaking = new HashMap<>(); // each raider's progress on the gate before it, in ticks
    private static final int GATE_BREAK = 240; // vanilla's door break time, which the mod's gates keep

    /**
     * A marching raider at a closed gate breaks it (the mod's EntityAIBreakDoor, raidersbreakdoors, Normal and up): the
     * gate's hardness is 1 + each of its blocks' (iron 10, wood 7) together; it gets on with it one tick in max(1,
     * hardness / (1 + raiders within 5 / ((5 + Sturdy Gates) / 2))), and the whole gate goes after 240 such ticks. Each
     * of those ticks, as often, up to the 4 nearest guards within 100 are called to 3 blocks inside it. Ours: a raider
     * within a block of a gate block is at it; once a second it gets on by 20 ticks' worth on average.
     * @return whether it is at a gate (it doesn't walk on meanwhile).
     */
    private boolean breakGate(Mob m) {
        Block feet = m.getLocation().getBlock(), gateBlock = null;
        for (int x = -1; x <= 1 && gateBlock == null; x++) for (int y = 0; y <= 1 && gateBlock == null; y++) for (int z = -1; z <= 1 && gateBlock == null; z++)
            if (dev.minecp.Gates.closed(feet.getRelative(x, y, z))) gateBlock = feet.getRelative(x, y, z);
        if (gateBlock == null) { breaking.remove(m); return false; }
        if (!breakDoors || m.getWorld().getDifficulty().ordinal() < Difficulty.NORMAL.ordinal()) return false;
        m.getPathfinder().stopPathfinding();
        m.lookAt(gateBlock.getLocation().toCenterLocation());
        double hardness = 1 + dev.minecp.Gates.hardness(dev.minecp.Gates.gate(gateBlock));
        long near = m.getNearbyEntities(5, 5, 5).stream().filter(this::isRaider).count() + 1;
        double perNear = (5 + colony.research.effect(dev.minecp.research.Effects.ENHANCED_GATES)) / 2;
        int chance = (int) Math.max(1, hardness / (1 + near / perNear));
        double done = breaking.merge(m, 20.0 / chance, Double::sum);
        m.swingMainHand();
        if (ThreadLocalRandom.current().nextDouble() < 20.0 / chance) { // the guards nearest are called to just inside it
            Location c = centre(), centre = c != null ? c : colony.townHall().block.getLocation();
            Vector in = centre.toVector().subtract(gateBlock.getLocation().toCenterLocation().toVector()).setY(0);
            Location to = gateBlock.getLocation().toCenterLocation().add(in.lengthSquared() > 0 ? in.normalize().multiply(3) : in);
            dev.minecp.job.Guard.alert(colony, to.getBlock(), 100);
        }
        for (Player p : gateBlock.getLocation().getNearbyPlayers(32)) p.sendBlockDamage(gateBlock.getLocation(), (float) Math.min(1, done / GATE_BREAK), m.getEntityId());
        if (done >= GATE_BREAK) {
            for (Player p : gateBlock.getLocation().getNearbyPlayers(32)) p.sendBlockDamage(gateBlock.getLocation(), 0, m.getEntityId());
            gateBlock.getWorld().playSound(gateBlock.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 1, 1);
            dev.minecp.Gates.remove(gateBlock);
            breaking.remove(m);
        }
        return true;
    }

    public boolean isRaider(Entity e) {
        for (Horde h : hordes) if (h.raiders.contains(e)) return true;
        return false;
    }

    /** On to another hut: guards are sent to this one (the mod alerts them whenever it picks the next). */
    private void leaveTarget() {
        if (target != null) dev.minecp.job.Guard.alert(colony, target.block, ALERT_RANGE);
        target = null;
    }

    /** The hut a raider marches on: picked when it has none (or that hut is gone) and again every 240 s. */
    private Hut goal(Mob m) {
        Hut g = goals.get(m);
        int now = Bukkit.getCurrentTick();
        if (g == null || !colony.huts.contains(g) || now - goalSince.getOrDefault(m, now) > RETARGET) {
            g = target();
            if (g == null) goals.remove(m); else goals.put(m, g);
            goalSince.put(m, now);
        }
        return g;
    }

    /** A pick (the mod's getRandomBuilding): the same hut for max(6, raiders / 3) picks, then another. */
    private Hut target() {
        int raiders = hordes.stream().mapToInt(h -> h.raiders.size()).sum();
        if (target == null || !colony.huts.contains(target) || ++picks > Math.max(6, raiders / 3)) {
            leaveTarget();
            List<Hut> built = colony.huts.stream().filter(x -> x.level >= 1).toList();
            target = built.isEmpty() ? null : built.get(ThreadLocalRandom.current().nextInt(built.size()));
            picks = 0;
        }
        return target;
    }

    private LivingEntity nearestTarget(Mob m) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : m.getNearbyEntities(SIGHT, 3, SIGHT)) {
            LivingEntity l = null;
            if (e instanceof Player p && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) l = p;
            else if (e instanceof org.bukkit.entity.Mannequin && colony.colonistOf(e) != null) l = (LivingEntity) e;
            if (l == null || !m.hasLineOfSight(l)) continue;
            double d = l.getLocation().distanceSquared(m.getLocation());
            if (d < bestD) { bestD = d; best = l; }
        }
        return best;
    }

    private static Enchantment bane;

    /** Raider's Bane (the mod's raider_damage_enchant), from the original style's data pack; null without it. */
    private static Enchantment bane() {
        if (bane == null) bane = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(Key.key(dev.minecp.MineCP.NAMESPACE, "raiders_bane"));
        return bane;
    }

    /**
     * A raider is hurt (the mod's AbstractEntityMinecoloniesRaider.hurt). With no living attacker (falling, fire,
     * drowning...): nothing while its horde camps, then at most a hit every 1.5 s, and none that would leave it under
     * 20% to 60% of its health (the difficulty modifier / 10 + 0.2). A living attacker ends its horde's camp; a player's
     * hit over 30 comes back half as thorns 1 time in 5, and up to 7 of a player's damage counts as 3% of the raider's
     * health each (20% more a level of Raider's Bane on the weapon).
     */
    public void hurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Mob m)) return;
        Horde h = null;
        for (Horde x : hordes) if (x.specs.containsKey(m)) h = x;
        if (h == null) return;
        Entity attacker = e instanceof EntityDamageByEntityEvent by
                ? (by.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity s ? s : by.getDamager()) : null;
        if (!(attacker instanceof LivingEntity)) {
            if (e.getCause() == EntityDamageEvent.DamageCause.KILL || e.getCause() == EntityDamageEvent.DamageCause.VOID) return; // /kill and the void always do
            int now = Bukkit.getCurrentTick();
            double max = m.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (h.camp > 0 || now - envHit.getOrDefault(m, -ENV_COOLDOWN) < ENV_COOLDOWN
                    || (m.getHealth() - e.getDamage()) / max < Math.min(raidModifier / 10 + 0.2, 0.6)) { e.setCancelled(true); return; }
            envHit.put(m, now);
            return;
        }
        h.camp = 0;
        if (!(attacker instanceof Player p)) return;
        double damage = e.getDamage();
        if (damage > THORNS_OVER && ThreadLocalRandom.current().nextInt(THORNS_CHANCE) == 0)
            p.damage(damage * 0.5, DamageSource.builder(DamageType.THORNS).withCausingEntity(m).withDirectEntity(m).build());
        Enchantment b = bane();
        int level = b == null ? 0 : p.getInventory().getItemInMainHand().getEnchantmentLevel(b);
        double scaled = Math.min(damage, MAX_SCALED);
        e.setDamage(Math.max(damage, damage - scaled + scaled * HP_PER_DAMAGE * m.getAttribute(Attribute.MAX_HEALTH).getValue() * (1 + level / 5.0)));
    }

    /**
     * A raider died: the mod's loot table instead of vanilla's, and 5 experience. Loot-table weapons come undamaged, as
     * the mod's (its tables have no damage function). The pharaoh's arrows come 1 to 16, plus 1 to 32 a Looting level.
     * On a ship raid, every so many deaths (twice its raiders / its spawners) take a spawner with them, as the mod's.
     */
    public void died(org.bukkit.event.entity.EntityDeathEvent e) {
        for (Horde h : hordes) {
            Spec s = h.specs.get(e.getEntity());
            if (s == null) continue;
            h.killed++;
            if (!history.isEmpty()) record(history.getLast().dead() + 1, lost);
            if (h.ship != null && h.ship.maxSpawners > 0 && ++h.ship.deaths > h.ship.amount * 2 / h.ship.maxSpawners && !h.ship.spawners.isEmpty()) {
                Block first = h.ship.spawners.keySet().iterator().next();
                h.ship.spawners.remove(first);
                if (first.getType() == Material.SPAWNER) first.setType(Material.AIR);
                h.ship.deaths = 0;
            }
            e.getDrops().clear();
            e.setDroppedExp(5);
            var rnd = ThreadLocalRandom.current();
            Player killer = e.getEntity().getKiller();
            int looting = killer == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
            Object[] d = s.drops();
            for (int rolls = s == Culture.NORSEMEN.chief ? 2 : 1; rolls > 0; rolls--) {
                int roll = rnd.nextInt((int) d[0]);
                for (int i = 1; i < d.length; i += 2) {
                    if ((roll -= (int) d[i + 1]) >= 0) continue;
                    ItemStack drop = drop(d[i]);
                    if (drop.getType() == Material.ARROW) drop.setAmount(Math.min(64, rnd.nextInt(1, 17) + looting * rnd.nextInt(1, 33)));
                    e.getDrops().add(drop);
                    break;
                }
            }
            if (killer != null) for (ItemStack m : equipped(s)) // vanilla: only when a player killed it
                if (rnd.nextFloat() - looting * 0.01f < EQUIPMENT_DROP_CHANCE) e.getDrops().add(worn(m));
            return;
        }
    }

    private static final float EQUIPMENT_DROP_CHANCE = 0.085f; // vanilla's for equipment a mob is given; the mod doesn't change it

    /**
     * What the mod equips a raider with, which drops as a vanilla mob's equipment does: the weapon (the chief's sword,
     * the spearman's spear, the pirates' scimitar; not the pharaoh's scepter, which the mod's own loot gives), and the
     * barbarian chief's chainmail. The rest of what ours wear is only for looks and never drops.
     */
    private static List<ItemStack> equipped(Spec s) {
        List<ItemStack> out = new ArrayList<>();
        if (s.weapon() != null && s != Culture.MUMMIES.chief) out.add(weaponOf(s));
        if (s == Culture.BARBARIANS.chief) for (Material m : s.armor()) out.add(new ItemStack(m));
        return out;
    }

    // ---- the mod's raider items: vanilla ones with a tag (the optional resource pack shows the mod's models) -------

    private static final org.bukkit.NamespacedKey RAIDER_ITEM = new org.bukkit.NamespacedKey(dev.minecp.MineCP.NAMESPACE, "raider_item");

    /** A loot table entry as an item: a vanilla item, the ancient tome, or one of the mod's raider items. */
    private static ItemStack drop(Object o) {
        if (o == TOME) return dev.minecp.job.Enchanter.ancientTome();
        if (o == CHIEF_SWORD) return raiderItem(Material.IRON_SWORD, "chief_sword", "Chief Sword");
        if (o == PHARAOH_SCEPTER) return pharaohScepter();
        if (o == SPEAR) return raiderItem(Material.STONE_SPEAR, "spear", "Spear");
        if (o == FIRE_ARROW) return raiderItem(Material.ARROW, "fire_arrow", "Fire Arrow");
        if (o == SCIMITAR) return raiderItem(Material.IRON_SWORD, "iron_scimitar", "Iron Scimitar");
        if (o instanceof String piece) return raiderItem(pirateMaterial(piece), piece, title(piece)); // a piece of pirate armor
        return new ItemStack((Material) o);
    }

    /** A pirate armor piece's stand-in: leather of its slot. */
    private static Material pirateMaterial(String piece) {
        for (String[] set : PIRATE_SETS) for (int i = 0; i < 4; i++) if (set[i].equals(piece)) return Culture.PIRATES.chief.armor()[i];
        return Material.LEATHER_HELMET;
    }

    /** "pirate_leggins" -> "Pirate Leggins". */
    private static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.split("_")) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return out.toString();
    }

    /** What a raider holds: the mod's own weapon where it has one (ours: a vanilla item with its model), else the vanilla one. */
    private static ItemStack weaponOf(Spec s) {
        if (s == Culture.BARBARIANS.chief) return drop(CHIEF_SWORD);
        if (s == Culture.MUMMIES.chief) return pharaohScepter();
        if (s == Culture.AMAZONS.melee) return drop(SPEAR);
        if (s == Culture.PIRATES.melee || s == Culture.DROWNED.melee || captain(s)) return drop(SCIMITAR);
        return new ItemStack(s.weapon());
    }

    /** A pirate or drowned pirate captain: a scimitar and a pirate set. */
    private static boolean captain(Spec s) {
        return s == Culture.PIRATES.chief || s == Culture.DROWNED.chief;
    }

    /** The pharaoh's scepter: a bow with Flame (its shots set fire, as the mod's fire-arrow scepter). */
    private static ItemStack pharaohScepter() {
        ItemStack bow = raiderItem(Material.BOW, "pharaoh_scepter", "Pharaoh Scepter");
        bow.addEnchantment(Enchantment.FLAME, 1);
        return bow;
    }

    private static ItemStack raiderItem(Material m, String id, String name) {
        ItemStack item = new ItemStack(m);
        item.editMeta(meta -> {
            meta.itemName(Component.text(name));
            meta.getPersistentDataContainer().set(RAIDER_ITEM, org.bukkit.persistence.PersistentDataType.STRING, id);
        });
        dev.minecp.Items.model(item, id);
        return item;
    }

    /** Whether it is the mod's fire arrow. */
    public static boolean isFireArrow(ItemStack item) {
        return item != null && "fire_arrow".equals(item.getPersistentDataContainer().get(RAIDER_ITEM, org.bukkit.persistence.PersistentDataType.STRING));
    }

    /** A dropped piece of equipment, worn down as vanilla's: max durability less a random 1 to (max - 3) left. */
    private static ItemStack worn(ItemStack item) {
        Material m = item.getType();
        int max = m.getMaxDurability();
        if (max > 0) {
            var rnd = ThreadLocalRandom.current();
            item.setData(DataComponentTypes.DAMAGE, max - rnd.nextInt(1 + rnd.nextInt(Math.max(max - 3, 1))));
        }
        return item;
    }

    /** A citizen died during a raid: a guard counts 1, anyone else 2; over half the colony's beds is a defeat. */
    void lost(boolean guard) {
        if (!underway()) return;
        lost += guard ? 1 : 2;
        if (!history.isEmpty()) record(history.getLast().dead(), lost);
        if ((double) lost / colony.capacity() > 0.5) finish(End.DEFEAT);
    }

    // ---- end ------------------------------------------------------------------------------------------------------

    /**
     * Every horde is beaten, the colony lost, or the raiders left (timed out, gave up after their nights, or unloaded):
     * what is left of them goes and the colony hears how it went. Ours: a raid the raiders left gives no reward and
     * doesn't change the difficulty.
     */
    private void finish(End end) {
        Colony.log.info(colony.where() + ": raid ended, " + end.name().toLowerCase() + "; " + lost + " citizens lost.");
        for (Horde h : List.copyOf(hordes)) end(h);
        forgetRaiders();
        if (bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        bar = null;
        target = null;
        if (end == End.LEFT) {
            colony.tell("The raiders around " + colony.name + " have left.");
            return;
        }
        lastLost = lost;
        lastMax = colony.capacity();
        adaptPending = true;
        if (end == End.DEFEAT) {
            extraNights = averageNights * 2;
            colony.tell("Defeat! The raiders did what they came for, and will be back another day.");
            return;
        }
        colony.tell("The raid on " + colony.name + " is over: the raiders are beaten!");
        if (lost == 0) colony.happiness.defended();
        for (Player p : Bukkit.getOnlinePlayers()) // ours: the mod plays its own victory sound
            if (colony.permissions.isMember(p.getUniqueId())) p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.HOSTILE, 1, 1);
    }

    /** What was kept about each raider goes with the raid (only one raid runs at a time). */
    private void forgetRaiders() {
        stuck.clear();
        envHit.clear();
        goals.clear();
        goalSince.clear();
        breaking.clear();
    }

    private static void removeCamp(Horde h) {
        for (Block b : h.campfires) if (b.getType() == Material.CAMPFIRE) b.setType(Material.AIR);
        h.campfires.clear();
    }

    /** The plugin stops: raiders and campfires go, and ships sail off at once (neither is saved). */
    void despawn() {
        for (Horde h : List.copyOf(hordes)) end(h);
        forgetRaiders();
        for (Docked s : docked) restore(s);
        docked.clear();
        if (bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        bar = null;
    }

    // ---- history (the mod's RaidHistory) --------------------------------------------------------------------------

    /** The last raid as "N hours ago: ...", or null if there was none. */
    public String lastRaid() {
        return history.isEmpty() ? null : describe(history.getLast());
    }

    /** Every past raid, newest first. */
    public List<String> history() {
        List<String> out = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) out.add(describe(history.get(i)));
        return out;
    }

    private String describe(Past p) {
        long hours = (colony.townHall().block.getWorld().getGameTime() - p.time) / 72_000; // the mod's: an hour is 72000 ticks
        StringBuilder from = new StringBuilder();
        for (String s : p.spawns.split(" ")) {
            if (s.isEmpty()) continue;
            String[] kind = s.split("@");
            from.append(from.isEmpty() ? "" : ", ").append(kind[0].replace("_raid", "").replace('_', ' ')).append(" at ").append(kind.length > 1 ? kind[1].replace(',', ' ') : "?");
        }
        return hours + " hours ago: " + p.amount + " raiders (" + from + "), " + p.dead + " killed, " + p.lost + " citizen losses, difficulty " + p.difficulty;
    }

    void save(ConfigurationSection s) {
        s.set("nights-since", nightsSince);
        s.set("difficulty", raidDifficulty);
        s.set("extra-nights", extraNights);
        s.set("tonight", raidTonight);
        if (tonightType != null) s.set("tonight-type", tonightType.id);
        if (tonightAmount > 0) s.set("tonight-amount", tonightAmount);
        if (adaptPending) s.set("last-lost", List.of(lastLost, lastMax));
        if (!history.isEmpty()) s.set("history", history.stream().map(Past::save).toList());
    }

    void load(ConfigurationSection s) {
        nightsSince = s.getInt("nights-since");
        raidDifficulty = s.getInt("difficulty", 7);
        extraNights = s.getInt("extra-nights");
        raidTonight = s.getBoolean("tonight");
        tonightType = culture(s.getString("tonight-type"));
        tonightAmount = s.getInt("tonight-amount");
        List<Integer> last = s.getIntegerList("last-lost");
        if (last.size() == 2) { adaptPending = true; lastLost = last.get(0); lastMax = last.get(1); }
        for (String p : s.getStringList("history")) { Past x = Past.load(p); if (x != null) history.add(x); }
        restoreLeftovers();
    }
}
