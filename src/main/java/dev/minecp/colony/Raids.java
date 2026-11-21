package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.job.Guard;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raids on a colony (the mod's RaidManager and HordeRaidEvent, land hordes only), with the mod's numbers unless a
 * comment says otherwise. A raid is decided one nightfall ahead and arrives at the next; its size follows the colony's
 * "raid level" (citizens and building levels). Raiders are vanilla mobs in plain armor (see {@link Culture}): they camp
 * by campfires for a while, then march on the colony's huts and attack citizens and players on the way. Citizens hide
 * at home and guards fight. A boss bar shows how many are left.
 */
public final class Raids {
    // Settings (config.yml, read by the plugin): the mod's defaults.
    public static boolean enabled = true, breakDoors = true;
    public static int difficulty = 5, maxRaiders = 80, averageNights = 14, minimumNights = 10;

    private static final int MIN_LEVEL = 75;          // MIN_REQUIRED_RAIDLEVEL
    private static final int BIG_HORDE = 20;          // one spawn point per 20 raiders
    private static final int UPDATE = 500;            // the mod's raid events update on the colony tick
    private static final int DESPAWN_TICKS = 36_000;  // TICKS_TO_DESPAWN: 30 minutes
    private static final int NIGHTS = 3;              // a horde gives up after 3 more nightfalls
    private static final int SIGHT = 16;              // the raiders' target search, ±16 across (ours: ±3 up and down)
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
    private static final Object[] NO_DROPS = {};

    /**
     * The mod's land cultures. Ours: vanilla mobs stand in for its raider models (zombies and husks for melee, skeletons,
     * bogged and strays for archers), with plain armor; the armor is for looks (its own points don't count) and the
     * raider's armor is the mod's number. Drops are the mod's loot tables with vanilla items for its own (the ancient tome
     * is left out, so its share drops nothing).
     */
    enum Culture {
        BARBARIANS("Barbarians",
                new Spec(Zombie.class, Material.STONE_AXE, null, set("LEATHER", 2), 1, D1, 0,
                        new Object[]{97, Material.DIAMOND_AXE, 1, Material.GOLDEN_AXE, 2, Material.IRON_AXE, 5, Material.STONE_AXE, 6}),
                new Spec(Skeleton.class, Material.BOW, null, set("LEATHER", 2), 1, D1, 0, new Object[]{95, Material.BOW, 10}),
                new Spec(Zombie.class, Material.IRON_SWORD, null, set("CHAINMAIL", 4), 1.5, d -> 2 * d, 1, // the mod's chief sword and chainmail
                        new Object[]{121, Material.DIAMOND_SWORD, 5, Material.GOLDEN_SWORD, 5, Material.IRON_SWORD, 11, Material.STONE_SWORD, 20})),
        MUMMIES("Mummies",
                new Spec(Husk.class, null, null, new Material[0], 1, D1, 0, NO_DROPS),
                new Spec(Skeleton.class, Material.BOW, null, new Material[0], 1, D1, 0, new Object[]{95, Material.BOW, 10}),
                new Spec(Skeleton.class, Material.BOW, null, set("GOLDEN", 2), 4.5, d -> 2 * d, 1, // the pharaoh's fire-arrow scepter: a Flame bow
                        new Object[]{113, Material.BOW, 3, Material.ARROW, 30})),
        AMAZONS("Amazons",
                new Spec(Zombie.class, Material.STONE_SPEAR, null, set("LEATHER", 2), 1, D1, 0, new Object[]{100, Material.STONE_SPEAR, 15}),
                new Spec(Bogged.class, Material.BOW, null, set("LEATHER", 1), 1, D1, 0, new Object[]{100, Material.BOW, 15}),
                new Spec(Bogged.class, Material.BOW, null, set("LEATHER", 4), 1.5, d -> 4 * d, 1, new Object[]{190, Material.BOW, 80})),
        NORSEMEN("Norsemen",
                new Spec(Zombie.class, Material.STONE_AXE, Material.SHIELD, set("IRON", 2), 1 / 1.25, d -> 15, 0, // the shieldmaiden
                        new Object[]{95, Material.SHIELD, 10}),
                new Spec(Stray.class, Material.BOW, null, set("CHAINMAIL", 1), 1 / 1.25, D1, 0, new Object[]{95, Material.BOW, 10}),
                new Spec(Zombie.class, Material.STONE_AXE, null, set("IRON", 4), 1, d -> 2 * d + 15, 1,
                        new Object[]{125, Material.LEATHER, 15, Material.DIAMOND_AXE, 10}));

        final String title;
        final Spec melee, archer, chief;

        Culture(String title, Spec melee, Spec archer, Spec chief) {
            this.title = title;
            this.melee = melee;
            this.archer = archer;
            this.chief = chief;
        }

        /**
         * By the biome at the colony's centre (the mod's raiderEvent, without its ships): desert mummies, jungle amazons,
         * taiga norsemen, else barbarians two times in three and norsemen otherwise.
         */
        static Culture at(Location centre) {
            String biome = centre.getWorld().getBiome(centre).getKey().getKey();
            if (biome.contains("desert")) return MUMMIES;
            if (biome.contains("jungle")) return AMAZONS;
            if (biome.contains("taiga")) return NORSEMEN;
            return ThreadLocalRandom.current().nextInt(3) < 2 ? BARBARIANS : NORSEMEN;
        }
    }

    /** One group of raiders from one spawn point (the mod's HordeRaidEvent). */
    private static final class Horde {
        final Culture culture;
        final List<Mob> raiders = new ArrayList<>();
        final java.util.Map<Mob, Spec> specs = new java.util.HashMap<>();
        final List<Block> campfires = new ArrayList<>();
        int initial, killed, camp; // killed: died, not just unloaded; camp: updates left camping by the fires
        Horde(Culture culture) { this.culture = culture; }
    }

    private final Colony colony;
    private final List<Horde> hordes = new ArrayList<>();
    private BossBar bar;
    private int nightsLeft, lost, startedAt, ticks, picks;
    private boolean fewWarned, escaped; // escaped: a horde timed out or unloaded rather than being beaten

    private enum End { BEATEN, LEFT, DEFEAT }
    private Hut target; // the hut the raiders march on, shared, changed after max(6, raiders / 3) picks
    // Saved between raids:
    int nightsSince, raidDifficulty = 7, extraNights;
    public boolean raidTonight; // set by /colony raid tonight too
    boolean adaptPending;
    int lastLost, lastMax;

    Raids(Colony colony) {
        this.colony = colony;
    }

    boolean underway() {
        return !hordes.isEmpty();
    }

    // ---- when -----------------------------------------------------------------------------------------------------

    /**
     * Nightfall (the mod's onNightFall): after a raid the difficulty adapts to how many citizens were lost; a raid on
     * its way counts down its nights; a raid decided last night arrives; else tonight decides about the next.
     */
    void nightfall() {
        if (adaptPending) adapt();
        if (underway()) {
            nightsSince = 0;
            if (--nightsLeft <= 0) finish(End.LEFT);
            return;
        }
        nightsSince++;
        if (raidTonight) { // kept until a raid starts, as in the mod
            if (canRaid() && start(false, 0) == null) raidTonight = false;
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

    /** Adult citizens 5 each, each built hut 5 + level² / 5, times how full the colony is (no skills or research here). */
    int level() {
        int l = 0;
        for (Colonist c : colony.colonists) if (!c.child) l += 5;
        for (Hut h : colony.huts) if (h.type.isBuilding() && h.level > 0) l += 5 + h.level * h.level / 5;
        return (int) (l * Math.min(1.0, (double) colony.colonists.size() / colony.capacity()));
    }

    private double modifier() {
        World w = colony.townHall().block.getWorld();
        return (raidDifficulty / 10.0 + 0.2) * (difficulty / 5.0) * (w.getDifficulty().ordinal() / 2.0);
    }

    // ---- start ----------------------------------------------------------------------------------------------------

    /**
     * Starts a raid now. {@code amount} 0: the mod's size, 1 + level / 60 × modifier × (1 + 5% per member online) ×
     * 0.85 to 1.15, at most the configured maximum. @return why not, or null when it started.
     */
    public String start(boolean forced, int amount) {
        if (underway()) return "A raid is already under way.";
        int level = level();
        if (!forced && level < MIN_LEVEL) return "The colony is too small to be raided (raid level " + level + " of " + MIN_LEVEL + ").";
        double d = modifier();
        if (amount <= 0) {
            long members = Bukkit.getOnlinePlayers().stream().filter(p -> colony.permissions.isMember(p.getUniqueId())).count();
            amount = 1 + Math.min(maxRaiders, (int) (level / 60.0 * d * (1 + 0.05 * members) * ThreadLocalRandom.current().nextDouble(0.85, 1.15)));
        }
        amount = Math.min(amount, maxRaiders + 1); // ours: a typed amount is capped too, the mod's own size can be one over
        Location centre = centre();
        if (centre == null) return "None of the colony's buildings are loaded.";
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < Math.max(1, amount / BIG_HORDE); i++) {
            Location at = spawnPoint(centre);
            if (at != null) spots.add(at);
        }
        if (spots.isEmpty()) return "No place to spawn raiders was found around the colony.";
        for (Location at : spots) { // split among the points found, as in the mod
            Horde h = new Horde(Culture.at(centre));
            spawn(h, at, (int) Math.ceil((double) amount / spots.size()), level, d);
            camp(h, at, centre);
            hordes.add(h);
        }
        nightsLeft = NIGHTS;
        nightsSince = 0;
        extraNights = 0;
        startedAt = Bukkit.getCurrentTick();
        lost = 0;
        fewWarned = false;
        escaped = false;
        Horde first = hordes.getFirst();
        Location from = first.raiders.isEmpty() ? centre : first.raiders.getFirst().getLocation();
        String dir = direction(colony.townHall().block.getLocation(), from);
        bar = BossBar.bossBar(Component.text(first.culture.title + " - " + dir), 1, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        Colony.log.info(colony.where() + ": raid started, " + hordes.stream().mapToInt(h -> h.raiders.size()).sum() + " " + first.culture.title
                + " from the " + dir.toLowerCase() + " (raid level " + level() + ").");
        colony.tell(first.culture.title + " have been spotted " + dir.toLowerCase() + " of " + colony.name + "! The citizens run for shelter.");
        for (Player p : Bukkit.getOnlinePlayers()) // the illagers' raid horn, from the raiders' side (ours: the mod plays its own alert)
            if (p.getWorld().equals(from.getWorld()) && colony.permissions.isMember(p.getUniqueId())) {
                var toward = from.toVector().subtract(p.getLocation().toVector()).normalize().multiply(16);
                p.playSound(p.getLocation().add(toward), Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 64, 1);
            }
        return null;
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
     * Ours: steps on water or lava are skipped (the mod sends ships there).
     */
    private Location spawnPoint(Location centre) {
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
            Location found = null;
            int valid = 0;
            for (int step = 1; step <= 10; step++) {
                int x = (int) (from.block.getX() + dx * 48 * step), z = (int) (from.block.getZ() + dz * 48 * step);
                if (!w.isChunkLoaded(x >> 4, z >> 4) || !w.getWorldBorder().isInside(new Location(w, x, 0, z))) break;
                if (!clear(w, x, z)) continue;
                Block floor = w.getHighestBlockAt(x, z);
                if (floor.isLiquid()) continue;
                found = floor.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
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
            if (Math.hypot(h.block.getX() - x, h.block.getZ() - z) < 35 + bonus * h.level) return false;
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
        for (int i = 0; i < archers; i++) kinds.add(h.culture.archer);
        for (int i = 0; i < melee; i++) kinds.add(h.culture.melee);
        for (int i = 0; i < kinds.size(); i++) {
            Block floor = at.getWorld().getHighestBlockAt(at.getBlockX() + i % 6 * 2 - 5, at.getBlockZ() + i / 6 * 2);
            Mob m = raider(kinds.get(i), floor.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5), level, d);
            h.raiders.add(m);
            h.specs.put(m, kinds.get(i));
        }
        h.initial = h.raiders.size();
    }

    /** One raider with the mod's stats (RaiderMobUtils.setMobAttributes and each raider's own changes). */
    private static Mob raider(Spec s, Location at, int level, double d) {
        return at.getWorld().spawn(at, s.mob(), m -> {
            m.addScoreboardTag(TAG);
            m.setPersistent(false); // shortcut: not saved with the world, so a restart or unloaded chunk ends them; the mod respawns them
            m.setPersistenceRequired(true); // no natural despawning far from players (the mod: 30 minutes)
            m.setCanPickupItems(false);
            if (m instanceof Zombie z) {
                z.setAdult();
                z.setShouldBurnInDay(false);
                z.setCanBreakDoors(breakDoors && at.getWorld().getDifficulty().ordinal() >= Difficulty.NORMAL.ordinal()); // the mod: Normal and up
            }
            if (m instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
            set(m, Attribute.MAX_HEALTH, Math.max(10, 10 + 0.025 * level) * d * s.health());
            m.setHealth(m.getAttribute(Attribute.MAX_HEALTH).getValue());
            set(m, Attribute.ATTACK_DAMAGE, 2 + d * Math.min(level / 400, 3) + s.damage());
            set(m, Attribute.ARMOR, s.armorPoints().applyAsDouble(d));
            set(m, Attribute.MOVEMENT_SPEED, d >= 2.4 ? 0.30 : 0.25);
            set(m, Attribute.FOLLOW_RANGE, 70);
            EntityEquipment e = m.getEquipment();
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (int i = 0; i < s.armor().length; i++) e.setItem(slots[i], looks(new ItemStack(s.armor()[i])));
            if (s.weapon() != null) {
                ItemStack weapon = new ItemStack(s.weapon());
                if (s == Culture.MUMMIES.chief) weapon.addEnchantment(Enchantment.FLAME, 1);
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

    // ---- during ---------------------------------------------------------------------------------------------------

    /** Every tick from the colony: steering and the boss bar once a second, camping and glowing every update. */
    void tick() {
        if (hordes.isEmpty()) return;
        ticks++;
        if (ticks % UPDATE == 0) for (Horde h : hordes) {
            if (h.camp > 0) h.camp--;
            if (h.raiders.size() < Math.round(0.15 * h.initial)) h.raiders.forEach(m -> m.setGlowing(true));
        }
        if (ticks % 20 != 0) return;
        boolean timedOut = Bukkit.getCurrentTick() - startedAt > DESPAWN_TICKS;
        int alive = 0, initial = 0;
        for (Horde h : List.copyOf(hordes)) {
            h.raiders.removeIf(m -> !m.isValid() || m.isDead());
            int standing = h.initial - h.killed; // unloaded raiders still count: walking away isn't a win
            boolean beaten = standing < Math.floor(0.1 * h.initial) || standing <= 0; // the mod's win: under a tenth left
            if (timedOut || beaten || h.raiders.isEmpty()) {
                if (!beaten) escaped = true;
                h.raiders.forEach(Entity::remove);
                h.raiders.clear();
                removeCamp(h);
                hordes.remove(h);
                continue;
            }
            for (Mob m : h.raiders) steer(h, m);
            alive += h.raiders.size();
            initial += h.initial;
        }
        if (hordes.isEmpty()) { finish(escaped ? End.LEFT : End.BEATEN); return; }
        bar.progress(Math.clamp((float) alive / Math.max(1, initial), 0, 1));
        for (Player p : Bukkit.getOnlinePlayers()) { // shown while in the colony's land
            Location l = p.getLocation();
            if (l.getWorld().equals(colony.townHall().block.getWorld()) && colony.claims().contains(Claims.key(l.getBlockX() >> 4, l.getBlockZ() >> 4)))
                p.showBossBar(bar);
            else p.hideBossBar(bar);
        }
        if (alive <= 5 && !fewWarned) {
            fewWarned = true;
            colony.tell("Only " + alive + " raiders left!");
        }
    }

    /**
     * A raider without a target looks for a citizen or player within sight; else, done camping, it marches on the shared
     * target hut in steps of up to 32 blocks (vanilla paths reach about that far), fast while far (the mod's 1.8 and 1.1).
     */
    private void steer(Horde h, Mob m) {
        LivingEntity t = m.getTarget();
        if (t == null || !t.isValid() || t.isDead()) {
            t = nearestTarget(m);
            if (t != null) m.setTarget(t);
        }
        if (t != null || h.camp > 0 || m.getPathfinder().hasPath()) return;
        Hut goal = target();
        if (goal == null) return;
        Location at = m.getLocation(), there = goal.block.getLocation().toCenterLocation();
        double dist = at.distance(there);
        if (dist < 5) { target = null; return; } // reached: another hut next (the mod roams its bounds for 30 s first)
        Location step = dist <= 32 ? there : at.clone().add(there.toVector().subtract(at.toVector()).normalize().multiply(32));
        if (dist > 32) step = step.getWorld().getHighestBlockAt(step).getLocation().add(0.5, 1, 0.5);
        m.getPathfinder().moveTo(step, dist > 50 ? 1.8 : 1.1);
    }

    private Hut target() {
        int raiders = hordes.stream().mapToInt(h -> h.raiders.size()).sum();
        if (target == null || !colony.huts.contains(target) || ++picks > Math.max(6, raiders / 3)) {
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

    /** A raider was hurt: its horde stops camping (the mod's setCampFireTime(0)). */
    public void hurt(Entity e) {
        for (Horde h : hordes) if (h.specs.containsKey(e)) h.camp = 0;
    }

    /** A raider died: the mod's loot table instead of vanilla's, and 5 experience. */
    public void died(org.bukkit.event.entity.EntityDeathEvent e) {
        for (Horde h : hordes) {
            Spec s = h.specs.get(e.getEntity());
            if (s == null) continue;
            h.killed++;
            e.getDrops().clear();
            e.setDroppedExp(5);
            Object[] d = s.drops();
            if (d.length == 0) return;
            int roll = ThreadLocalRandom.current().nextInt((int) d[0]);
            for (int i = 1; i < d.length; i += 2) {
                roll -= (int) d[i + 1];
                if (roll < 0) { e.getDrops().add(new ItemStack((Material) d[i])); return; }
            }
            return;
        }
    }

    /** A citizen died during a raid: a guard counts 1, anyone else 2; over half the colony's beds is a defeat. */
    void lost(boolean guard) {
        if (!underway()) return;
        lost += guard ? 1 : 2;
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
        despawn();
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

    private static void removeCamp(Horde h) {
        for (Block b : h.campfires) if (b.getType() == Material.CAMPFIRE) b.setType(Material.AIR);
        h.campfires.clear();
    }

    /** The plugin stops: raiders and campfires go (raiders aren't saved). */
    void despawn() {
        for (Horde h : hordes) {
            h.raiders.forEach(Entity::remove);
            removeCamp(h);
        }
        hordes.clear();
        if (bar != null) Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(bar));
        bar = null;
    }

    void save(ConfigurationSection s) {
        s.set("nights-since", nightsSince);
        s.set("difficulty", raidDifficulty);
        s.set("extra-nights", extraNights);
        s.set("tonight", raidTonight);
        if (adaptPending) s.set("last-lost", List.of(lastLost, lastMax));
    }

    void load(ConfigurationSection s) {
        nightsSince = s.getInt("nights-since");
        raidDifficulty = s.getInt("difficulty", 7);
        extraNights = s.getInt("extra-nights");
        raidTonight = s.getBoolean("tonight");
        List<Integer> last = s.getIntegerList("last-lost");
        if (last.size() == 2) { adaptPending = true; lastLost = last.get(0); lastMax = last.get(1); }
    }
}
