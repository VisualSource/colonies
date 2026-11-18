package dev.minecp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A player's colony: its huts (the town hall first), citizens, and the builder's queue of hut upgrades.
 * Rules: levels above 1 need the town hall at that level or higher; housing room is
 * {@link #STARTING_CITIZENS} plus the total level of all houses, and a citizen moves in every
 * {@link #GROWTH_TICKS} while there is room.
 */
final class Colony {
    private static final int STARTING_CITIZENS = 4; // the mod's initialcitizenamount default
    // Ours: the mod spawns only the starting citizens on a timer; the rest come from the tavern and children.
    private static final int GROWTH_TICKS = 20 * 120;
    private static final String[] FEMALE = {"Ada", "Cora", "Edda", "Greta", "Ivy", "Lena", "Nell", "Pia", "Rosa",
            "Tilda", "Vera", "Yara"};
    private static final String[] MALE = {"Bram", "Dell", "Finn", "Hal", "Jory", "Milo", "Otto", "Sven", "Ulric",
            "Wynn", "Kit", "Quill"};
    private static final String[] LAST = {"Ashdown", "Brook", "Carter", "Dale", "Fairweather", "Fletcher", "Hale",
            "Holt", "Miller", "Oakley", "Reed", "Thatcher", "Underhill", "Wells"};

    final UUID owner;
    final String name;
    final List<Hut> huts = new ArrayList<>(); // huts.getFirst() is the town hall
    final List<Colonist> colonists = new ArrayList<>();
    final List<Hut.WorkOrder> workOrders = new ArrayList<>(); // waiting for the builder, oldest first
    final List<Job> jobs = new ArrayList<>(); // one per worker hut
    final List<Grave> graves = new ArrayList<>(); // where citizens died, until dug up or decayed
    Builder builder; // the builder's job (also in jobs); null until a Builder's Hut is placed
    static final String DEFAULT_STYLE = "medievaloak";
    String style = DEFAULT_STYLE; // structure pack hut buildings come from
    private int growth = 0;

    Colony(UUID owner, String name, Block townHall, int townHallLevel) {
        this.owner = owner;
        this.name = name;
        huts.add(new Hut(Hut.Type.TOWN_HALL, townHall, townHallLevel));
    }

    static Colony found(UUID owner, String name, Block townHall) {
        Colony colony = new Colony(owner, name, townHall, 0);
        for (int i = 0; i < STARTING_CITIZENS; i++) colony.addCitizen();
        return colony;
    }

    Hut townHall() {
        return huts.getFirst();
    }

    Hut hutAt(Block block) {
        for (Hut h : huts) if (h.block.equals(block)) return h;
        return null;
    }

    Hut hut(Hut.Type type) {
        for (Hut h : huts) if (h.type == type) return h;
        return null;
    }

    int capacity() {
        return STARTING_CITIZENS + huts.stream().filter(h -> h.type == Hut.Type.HOUSE).mapToInt(h -> h.level).sum();
    }

    private void addCitizen() {
        var rnd = ThreadLocalRandom.current();
        boolean female = rnd.nextBoolean();
        colonists.add(new Colonist(UUID.randomUUID(), uniqueName(female), Colonist.randomSkin(female),
                townHall().block.getLocation().toCenterLocation()));
    }

    private String uniqueName(boolean female) {
        var rnd = ThreadLocalRandom.current();
        String[] first = female ? FEMALE : MALE;
        while (true) { // 144 combinations per list; colonies are far smaller
            String name = first[rnd.nextInt(first.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            if (colonists.stream().noneMatch(c -> c.name.equals(name))) return name;
        }
    }

    List<Colonist> residents(Hut house) {
        return colonists.stream().filter(c -> c.home == house).toList();
    }

    /** Gives homeless citizens a bed in a house with room (a level N house sleeps N). */
    private void assignHomes() {
        for (Colonist c : colonists) {
            if (c.home != null) continue;
            for (Hut h : huts) if (h.type == Hut.Type.HOUSE && residents(h).size() < h.level) { c.home = h; break; }
        }
    }

    Hut addHut(Hut.Type type, Block block) {
        Hut hut = new Hut(type, block, 0);
        huts.add(hut);
        addJob(hut);
        assignJobs();
        return hut;
    }

    private void addJob(Hut hut) {
        switch (hut.type) {
            case BUILDER -> jobs.add(builder = newBuilder(hut));
            case LUMBERJACK -> jobs.add(new Lumberjack(hut));
            case FARMER -> jobs.add(new Farmer(hut, this));
            case COOK -> jobs.add(new Cook(hut));
            case COURIER -> jobs.add(new Courier(hut, this));
            case MINER -> jobs.add(new Miner(hut, this));
            default -> {}
        }
    }

    /** The fields a farmer works: farmers, oldest hut first, each take the oldest fields left, one per hut level. */
    List<Hut> fieldsOf(Job farmer) {
        List<Hut> fields = huts.stream().filter(h -> h.type == Hut.Type.FIELD).toList();
        int taken = 0;
        for (Job j : jobs) {
            if (!(j instanceof Farmer)) continue;
            int n = Math.min(j.hut.level, fields.size() - taken);
            if (j == farmer) return fields.subList(taken, taken + n);
            taken += n;
        }
        return List.of();
    }

    /** The farmer job working this field, or null if no farmer has room for it. */
    Job farmerOf(Hut field) {
        for (Job j : jobs) if (j instanceof Farmer && fieldsOf(j).contains(field)) return j;
        return null;
    }

    Job jobAt(Hut hut) {
        for (Job j : jobs) if (j.hut == hut) return j;
        return null;
    }

    private Job jobOf(Colonist c) {
        for (Job j : jobs) if (c.id.equals(j.worker)) return j;
        return null;
    }

    Colonist worker(Job j) {
        for (Colonist c : colonists) if (c.id.equals(j.worker)) return c;
        return null;
    }

    /** Gives every job without a worker to an unemployed citizen, oldest jobs first. */
    private void assignJobs() {
        for (Job j : jobs) {
            if (worker(j) != null) continue;
            j.worker = colonists.stream().filter(c -> jobOf(c) == null).map(c -> c.id).findFirst().orElse(null);
        }
    }

    void removeHut(Hut hut) {
        workOrders.removeIf(w -> w.hut() == hut);
        if (builder != null && builder.work != null && builder.work.hut() == hut) builder.cancel();
        Job job = jobAt(hut);
        if (job != null) {
            job.dropCarried();
            jobs.remove(job);
            if (job == builder) builder = null;
        }
        for (Colonist c : colonists) if (c.home == hut) c.home = null;
        removeTape(hut);
        huts.remove(hut);
        assignHomes();
    }

    private Builder newBuilder(Hut hut) {
        Builder b = new Builder(hut);
        b.onFinish((done, order) -> {
            // A chest that already stood in a rack spot (kept rather than broken, so nothing in it is lost) becomes a rack.
            for (BuildOrder.Step s : order.steps) if (s.rack() && s.block().getType() == s.data().getMaterial()) Rack.tag(s.block());
            for (Block f : order.fields) // a field that came with the building (the farmer's scarecrow)
                if (hutAt(f) == null && f.getType() == Hut.Type.FIELD.block) {
                    addHut(Hut.Type.FIELD, f);
                    tell("A field came with the building: right-click its hay bale to choose a crop.");
                }
            if (done == null) { tell("Your builder finished the build."); return; }
            done.hut().level = done.level();
            assignHomes();
            tell("Your " + done.hut().type.title + " is now level " + done.level() + ".");
        });
        b.protect = this::isHutBlock;
        return b;
    }

    private boolean isHutBlock(Block block) {
        return hutAt(block) != null;
    }

    /** The queued or in-progress upgrade of this hut, or null. */
    Hut.WorkOrder upgradeOf(Hut hut) {
        if (builder != null && builder.work != null && builder.work.hut() == hut) return builder.work;
        for (Hut.WorkOrder w : workOrders) if (w.hut() == hut) return w;
        return null;
    }

    /**
     * The mod puts construction tape around a building waiting for the builder; we put yellow bars along its footprint
     * at the hut block's floor while the hut is unbuilt or has an upgrade queued or under way. Called once a second.
     */
    void tape() {
        for (Hut h : huts) {
            Hut.WorkOrder up = h.type.isBuilding() ? upgradeOf(h) : null;
            if (up == null && (h.level > 0 || !h.type.isBuilding())) { removeTape(h); continue; }
            int level = up != null ? up.level() : 1;
            if (h.footprintLevel != level) { // the structure is loaded once per level, not every second
                BuildOrder o = BuildOrder.create(h.structure(style, level), h.block.getLocation(), h.rotation, h.mirror, h.block);
                if (o == null) continue;
                h.footprint = new int[]{o.minX, o.maxX + 1, o.minZ, o.maxZ + 1};
                h.footprintLevel = level;
                removeTape(h); // the next level may be bigger
            }
            boolean ok = !h.tape.isEmpty();
            for (var d : h.tape) ok &= d.isValid(); // gone when its chunk unloaded
            var w = h.block.getWorld();
            int[] f = h.footprint; // spawning into an unloaded chunk would load it: wait until the corners are loaded
            if (ok || !w.isChunkLoaded(f[0] >> 4, f[2] >> 4) || !w.isChunkLoaded(f[0] >> 4, f[3] >> 4)
                    || !w.isChunkLoaded(f[1] >> 4, f[2] >> 4)) continue;
            removeTape(h);
            h.tape.addAll(Tape.around(w, f, h.block.getY() + 0.01, true));
        }
    }

    private static void removeTape(Hut h) {
        h.tape.forEach(org.bukkit.entity.Entity::remove);
        h.tape.clear();
    }

    /** Queues the hut's next level for the builder. @return why it can't, or null when queued. */
    String requestUpgrade(Hut hut) {
        int next = hut.level + 1;
        if (builder == null) return "Place a Builder's Hut first: the builder builds every hut.";
        if (hut.level >= Hut.MAX_LEVEL) return "The " + hut.type.title + " is at the highest level.";
        if (upgradeOf(hut) != null) return "That upgrade is already queued.";
        if (hut.type != Hut.Type.TOWN_HALL && next > 1 && next > townHall().level)
            return "Upgrade the Town Hall to level " + next + " first.";
        if (!BuildOrder.exists(hut.structure(style, next))) return "Structure " + hut.structure(style, next) + " not found.";
        workOrders.add(new Hut.WorkOrder(hut, next));
        return null;
    }

    void cancelUpgrade(Hut hut) {
        workOrders.removeIf(w -> w.hut() == hut);
        if (builder != null && builder.work != null && builder.work.hut() == hut) builder.cancel();
    }

    void tell(String message) {
        Player p = Bukkit.getPlayer(owner);
        if (p != null) p.sendMessage(message);
    }

    void tick() {
        if (builder != null && builder.order == null && !workOrders.isEmpty()) startNextWorkOrder();
        if (++growth >= GROWTH_TICKS) {
            growth = 0;
            if (colonists.size() < capacity()) {
                addCitizen();
                assignHomes();
                assignJobs();
                tell(colonists.getLast().name + " moved into " + name + ".");
            }
        }
        long time = townHall().block.getWorld().getTime(); // 0..23999
        boolean dayTime = time <= NIGHT;
        if (dayTime && !isDay) day++; // a new colony day each morning, as in the mod
        isDay = dayTime;
        boolean raining = townHall().block.getWorld().hasStorm();
        if (growth % 20 == 0) graves.removeIf(g -> g.isLoaded() && !g.decay(20)); // the mod's grave ticks only while loaded
        for (Colonist c : List.copyOf(colonists)) { // a citizen may die during its tick
            if (!c.tick()) continue;
            Job job = jobOf(c);
            hunger(c, job);
            heal(c);
            if (c.fleeTicks > 0) { c.fleeTicks--; c.setStatus("Fleeing"); continue; }
            if (time > NIGHT - 2000 && (c.bedtime || shouldGoSleep(c, job, time))) { // from late evening to dawn
                if (!c.bedtime && c.meal != null) { c.meal.abandon(); c.meal = null; }
                c.bedtime = true;
                if (job == null || job.leaveWork(c)) goToBed(c);
                continue;
            }
            if (c.bedtime) { c.bedtime = false; c.wake(); }
            if (c.meal != null) { // eating comes before work
                if (c.meal.tick(this, job)) {
                    c.hungerNote = c.meal.why;
                    if (c.meal.why != null) c.foodRetry = FOOD_RETRY_TICKS;
                    c.meal = null;
                }
                continue;
            }
            if (job != null && raining && !job.worksInRain()) { // the mod's workers stay in out of the rain
                c.setStatus("Rain: off work");
                if (job.leaveWork(c)) c.wander(homeOf(c).block.getLocation().toCenterLocation());
            } else if (job != null) job.tick(c);
            else {
                c.setStatus("Unemployed");
                c.wander((c.home != null ? c.home : townHall()).block.getLocation().toCenterLocation());
            }
        }
    }

    // ---- health and death (the mod's EntityCitizen hurt/die and checkHeal, GraveManager) -----------------------

    private static final int HEAL_TICKS = 100;     // the mod's HEAL_CITIZENS_AFTER
    private static final double MAX_HIT = 0.2;     // a hit takes at most this share of max health, as in the mod

    /** Every 5 seconds, unless a mob or player hurt it lately: more when well fed, as the mod's checkHeal. */
    private void heal(Colonist c) {
        if (c.hurtTicks > 0) c.hurtTicks--;
        if (++c.healTicks < HEAL_TICKS) return;
        c.healTicks = 0;
        if (c.health >= Colonist.MAX_HEALTH || c.hurtTicks > 0) return;
        double amount = c.saturation >= Food.MAX_SATURATION ? 2
                : c.saturation < Food.LOW_SATURATION ? c.saturation / Food.MAX_SATURATION / 2 : 1;
        c.health = Math.min(Colonist.MAX_HEALTH, c.health + amount);
    }

    /** The citizen whose body or legs {@code e} is, or null. */
    Colonist colonistOf(org.bukkit.entity.Entity e) {
        for (Colonist c : colonists) if (c.owns(e)) return c;
        return null;
    }

    Grave graveAt(Block b) {
        for (Grave g : graves) if (g.block.equals(b)) return g;
        return null;
    }

    /** Damage to a citizen's body or legs: the hit itself is cancelled and the citizen's own health takes it. */
    void hurt(Colonist c, org.bukkit.event.entity.EntityDamageEvent e) {
        e.setCancelled(true);
        var cause = e.getCause();
        if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION) { c.hopAside(null); return; } // the mod teleports it out
        org.bukkit.entity.Entity source = e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by ? by.getDamager() : null;
        if (source instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof org.bukkit.entity.Entity shooter) source = shooter;
        if (source != null && colonistOf(source) != null) return;
        // Only the owner hurts its citizens properly (the mod: players need the HURT_CITIZEN permission for more than 1).
        if (source instanceof Player p && !p.getUniqueId().equals(owner) && e.getDamage() > 1) return;
        double damage = cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.KILL
                ? c.health : Math.min(e.getDamage(), Colonist.MAX_HEALTH * MAX_HIT);
        if (damage <= 0) return;
        c.health -= damage;
        c.showHurt();
        if (c.health <= 0) { die(c, cause, source); return; }
        c.wake(); // vanilla wakes any sleeper that is hurt; it flees first, then goes back to bed
        if (source instanceof org.bukkit.entity.LivingEntity) {
            c.hurtTicks = HEAL_TICKS;
            c.flee(source.getLocation(), 15);
        } else if (cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL && c.fleeTicks == 0) c.flee(c.location(), 5);
    }

    /** The mod's death: a message to the owner, a grave with what it carried, and its job and home freed. */
    void die(Colonist c, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause, org.bukkit.entity.Entity source) {
        Job job = jobOf(c);
        if (c.meal != null) { c.meal.abandon(); c.meal = null; }
        List<ItemStack> items = job != null ? job.takeCarried() : List.of();
        Location at = c.location();
        c.despawn();
        colonists.remove(c);
        assignJobs();
        at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_PLAYER_DEATH, org.bukkit.SoundCategory.NEUTRAL, 1, 1);
        at.getWorld().spawnParticle(org.bukkit.Particle.POOF, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        Block spot = Grave.spot(at.getBlock());
        String where = " at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        if (spot == null) {
            items.forEach(i -> at.getWorld().dropItemNaturally(at, i));
            tell(c.name + " " + deathCause(cause, source) + where + ". No grave could be made there.");
            return;
        }
        spot.setType(Grave.BLOCK);
        graves.add(new Grave(spot, c.name, job != null ? job.hut.type.title.replace("'s Hut", "").replace(" Hut", "") : null, items));
        tell(c.name + " " + deathCause(cause, source) + where + ". Their grave is at "
                + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ".");
    }

    private static String deathCause(org.bukkit.event.entity.EntityDamageEvent.DamageCause cause, org.bukkit.entity.Entity source) {
        if (source != null) return "was killed by " + (source instanceof Player p ? p.getName() : source.getName());
        return switch (cause) {
            case FALL -> "fell to their death";
            case DROWNING -> "drowned";
            case LAVA -> "died in lava";
            case FIRE, FIRE_TICK, CAMPFIRE, HOT_FLOOR -> "burned to death";
            case STARVATION -> "starved";
            case VOID -> "fell out of the world";
            default -> "died";
        };
    }

    // ---- day and night (the mod's CitizenAI, EntityAISleep and CitizenSleepHandler) ----------------------------

    private static final int NIGHT = 12600;          // day time from which beds can be used
    private static final double TICKS_PER_BLOCK = 6; // the mod's estimate of walking time home
    private static final double Y_WEIGHT = 1.5;      // climbing counts for more
    int day;                  // colony days passed (saved)
    private boolean isDay = true;

    /** The house it sleeps in, or for the homeless the town hall (the mod: a tavern, else the town hall). */
    private Hut homeOf(Colonist c) {
        return c.home != null ? c.home : townHall();
    }

    /** Whether it must leave now to be home by nightfall, judging by how far it is (the mod's rule). */
    private boolean shouldGoSleep(Colonist c, Job job, long time) {
        Location at = c.location(), home = homeOf(c).block.getLocation();
        double extra = 0;
        if (job instanceof Miner && job.hut.block.getY() - 20 > at.getBlockY()) { // down the mine: first the way up
            Location hut = job.hut.block.getLocation();
            extra = Math.hypot(at.getX() - hut.getX(), at.getZ() - hut.getZ()) + Math.abs(at.getY() - hut.getY()) * 3;
            at = hut;
        }
        double dx = home.getX() - at.getX(), dz = home.getZ() - at.getZ(), dy = (home.getY() - at.getY()) * Y_WEIGHT;
        double needed = (Math.sqrt(dx * dx + dy * dy + dz * dz) + extra) * TICKS_PER_BLOCK;
        return NIGHT - time - needed <= 0;
    }

    /** Walks home and lies down in its bed; the homeless just wait at the town hall. */
    private void goToBed(Colonist c) {
        c.setStatus("Sleeping");
        if (c.bed() != null || c.isMoving()) return;
        Block bed = bedOf(c);
        if (bed == null) {
            Block hall = homeOf(c).block;
            if (c.location().distance(hall.getLocation()) > 16) c.goBeside(hall); // the mod's "home" range
            return;
        }
        if (c.isBeside(bed)) c.sleepIn(bed);
        else c.goBeside(bed);
    }

    /**
     * The bed of a house resident: the house's beds nearest its hut block first, one per resident in the order they
     * moved in (the mod assigns registered beds the same way). Only a free head half counts.
     */
    private Block bedOf(Colonist c) {
        if (c.home == null) return null;
        Hut house = c.home;
        int now = Bukkit.getCurrentTick();
        if (house.beds == null || now - house.bedsAt >= BED_RESCAN_TICKS) { // ~4,300 blocks: not every tick all night
            List<Block> beds = new ArrayList<>();
            Block hut = house.block;
            for (int dx = -BED_RANGE; dx <= BED_RANGE; dx++) for (int dz = -BED_RANGE; dz <= BED_RANGE; dz++) for (int dy = -4; dy <= 10; dy++) {
                Block b = hut.getRelative(dx, dy, dz);
                if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed bd && bd.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) beds.add(b);
            }
            beds.sort(java.util.Comparator.comparingDouble(b -> b.getLocation().distanceSquared(hut.getLocation())));
            house.beds = beds;
            house.bedsAt = now;
        }
        int index = residents(house).indexOf(c);
        Block bed = index < house.beds.size() ? house.beds.get(index) : null;
        return bed != null && bed.getBlockData() instanceof org.bukkit.block.data.type.Bed ? bed : null; // broken since the scan
    }

    private static final int BED_RANGE = 8; // shortcut: beds are looked for near the hut block, not by the building's own outline
    private static final int BED_RESCAN_TICKS = 200;

    private static final int HUNGER_TICKS = 20 * 60;          // saturation drops once a minute (as in the mod)
    private static final int FOOD_RETRY_TICKS = 20 * 60 * 5;  // after finding no food, look again in 5 minutes (the mod's wait)

    /**
     * Saturation drops once a minute in daytime, at the mod's rate: more for citizens of better houses, times the
     * job's factor, plus up to half again for the work they do. Hungry citizens go to eat; starving ones are slow.
     */
    private void hunger(Colonist c, Job job) {
        if (++c.hungerTicks >= HUNGER_TICKS) {
            c.hungerTicks = 0;
            if (c.location().getWorld().isDayTime()) {
                double drop = switch (c.home == null ? 0 : c.home.level) {
                    case 1 -> 0.6; case 2 -> 0.725; case 3 -> 1.0; case 4 -> 1.2; case 5 -> 1.5; default -> 0.3;
                };
                if (job != null) drop *= job.hungerFactor() * 1.5; // shortcut: assumes a busy worker; the mod counts actual actions
                c.saturation = Math.max(0, c.saturation - drop);
            }
        }
        c.setStarving(c.saturation <= 0);
        if (c.meal == null && c.saturation < Food.LOW_SATURATION && --c.foodRetry <= 0) c.meal = new Meal(c);
    }

    private Warehouse warehouse;

    /** The colony's built warehouse (level 1 or higher), or null. */
    Warehouse warehouse() {
        for (Hut h : huts) if (h.type == Hut.Type.WAREHOUSE && h.level >= 1) {
            if (warehouse == null || warehouse.hut != h) warehouse = new Warehouse(h, this);
            return warehouse;
        }
        return null;
    }

    /** A Cook's Hut that is built and has a cook, where hungry citizens can be served; null if none. */
    Cook restaurant() {
        for (Job j : jobs) if (j instanceof Cook cook && cook.hut.level >= 1 && worker(cook) != null) return cook;
        return null;
    }

    private void startNextWorkOrder() {
        Hut.WorkOrder w = workOrders.removeFirst();
        String structure = w.hut().structure(style, w.level());
        // A hut building is anchored on its hut block, turned as chosen with the build tool (by hand: the block's facing).
        BuildOrder o = BuildOrder.create(structure, w.hut().block.getLocation(), w.hut().rotation, w.hut().mirror, w.hut().block);
        if (o == null) tell("Structure " + structure + " not found; upgrade skipped.");
        else builder.start(o, w);
    }

    void despawn() {
        for (Colonist c : colonists) if (c.meal != null) { c.meal.abandon(); c.meal = null; } // food back in its chest
        for (Job j : jobs) if (j instanceof Cook cook) cook.putBackServing();
        colonists.forEach(Colonist::despawn);
        huts.forEach(Colony::removeTape);
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("style", style);
        s.set("day", day);
        for (int i = 0; i < graves.size(); i++) {
            Grave g = graves.get(i);
            var gs = s.createSection("graves." + i);
            putLocation(gs, "block", g.block.getLocation());
            gs.set("name", g.name);
            gs.set("job", g.job);
            gs.set("timer", g.timer);
            gs.set("decayed", g.decayed);
            gs.set("items", java.util.Arrays.stream(g.items.getContents()).filter(java.util.Objects::nonNull).toList());
        }
        for (int i = 0; i < huts.size(); i++) {
            Hut h = huts.get(i);
            var hs = s.createSection("huts." + i);
            hs.set("type", h.type.name());
            hs.set("level", h.level);
            putLocation(hs, "block", h.block.getLocation());
            if (h.rotation != null) hs.set("rotation", h.rotation.name());
            hs.set("mirror", h.mirror);
            if (h.type == Hut.Type.FIELD) {
                if (h.seed != null) hs.set("seed", h.seed.name());
                hs.set("extent", java.util.Arrays.stream(h.extent).boxed().toList());
                hs.set("stage", h.stage);
                hs.set("worked-day", h.workedDay);
            }
        }
        for (int i = 0; i < workOrders.size(); i++) {
            s.set("work-orders." + i + ".hut", huts.indexOf(workOrders.get(i).hut()));
            s.set("work-orders." + i + ".level", workOrders.get(i).level());
        }
        if (builder != null) {
            var bs = s.createSection("builder");
            if (builder.worker != null) bs.set("worker", builder.worker.toString());
            builder.carried.forEach((m, n) -> bs.set("carried." + m.name(), n));
            if (builder.racks > 0) bs.set("racks", builder.racks);
            saveTools(bs, builder);
            if (builder.order != null) {
                bs.set("order.structure", builder.order.structure);
                bs.set("order.rotation", builder.order.rotation.name());
                bs.set("order.mirror", builder.order.mirror);
                putLocation(bs, "order.origin", builder.order.origin);
                if (builder.work != null) {
                    bs.set("order.hut", huts.indexOf(builder.work.hut()));
                    bs.set("order.level", builder.work.level());
                }
            }
        }
        for (Job j : jobs) {
            if (j == builder) continue; // saved in its own section above
            var js = s.createSection("jobs." + huts.indexOf(j.hut));
            if (j.worker != null) js.set("worker", j.worker.toString());
            j.carried.forEach((m, n) -> js.set("carried." + m.name(), n));
            saveTools(js, j);
            if (j instanceof Miner miner) miner.save(js.createSection("mine"));
        }
        for (Colonist c : colonists) {
            var cs = s.createSection("citizens." + c.id);
            cs.set("name", c.name);
            cs.set("skin", c.skin);
            putLocation(cs, "location", c.location());
            if (c.home != null) cs.set("home", huts.indexOf(c.home));
            cs.set("saturation", c.saturation);
            cs.set("health", c.health);
        }
    }

    /** @return null if the colony's world is not loaded. */
    static Colony load(UUID owner, ConfigurationSection s) {
        var hutSections = s.getConfigurationSection("huts");
        if (hutSections == null) return null;
        List<Hut> huts = new ArrayList<>();
        for (String i : hutSections.getKeys(false)) {
            var hs = hutSections.getConfigurationSection(i);
            Location at = getLocation(hs, "block");
            if (at == null) return null;
            Hut h = new Hut(Hut.Type.valueOf(hs.getString("type")), at.getBlock(), hs.getInt("level"));
            if (hs.contains("rotation")) h.rotation = StructureRotation.valueOf(hs.getString("rotation"));
            h.mirror = hs.getBoolean("mirror");
            h.seed = Material.matchMaterial(hs.getString("seed", ""));
            List<Integer> extent = hs.getIntegerList("extent");
            for (int k = 0; k < Math.min(4, extent.size()); k++) h.extent[k] = extent.get(k);
            h.stage = hs.getInt("stage");
            h.workedDay = hs.getInt("worked-day", -1);
            huts.add(h);
        }
        Colony colony = new Colony(owner, s.getString("name"), huts.getFirst().block, huts.getFirst().level);
        colony.huts.addAll(huts.subList(1, huts.size()));
        colony.style = s.getString("style", colony.style);
        colony.day = s.getInt("day");
        var graveSections = s.getConfigurationSection("graves");
        if (graveSections != null) for (String i : graveSections.getKeys(false)) {
            var gs = graveSections.getConfigurationSection(i);
            Location at = getLocation(gs, "block");
            if (at == null) continue;
            List<ItemStack> items = new ArrayList<>();
            for (Object o : gs.getList("items", List.of())) if (o instanceof ItemStack it) items.add(it);
            Grave g = new Grave(at.getBlock(), gs.getString("name"), gs.getString("job"), items);
            g.timer = gs.getInt("timer", Grave.DECAY_TICKS);
            g.decayed = gs.getBoolean("decayed");
            colony.graves.add(g);
        }

        var citizens = s.getConfigurationSection("citizens");
        if (citizens != null) for (String id : citizens.getKeys(false)) {
            var cs = citizens.getConfigurationSection(id);
            Location at = getLocation(cs, "location");
            if (at == null) at = colony.townHall().block.getLocation().toCenterLocation();
            Colonist c = new Colonist(UUID.fromString(id), cs.getString("name"), cs.getInt("skin"), at);
            if (cs.isInt("home")) c.home = colony.hutByIndex(cs.getInt("home"));
            c.saturation = cs.getDouble("saturation", Food.MAX_SATURATION);
            c.health = cs.getDouble("health", Colonist.MAX_HEALTH);
            colony.colonists.add(c);
        }

        var orders = s.getConfigurationSection("work-orders");
        if (orders != null) for (String i : orders.getKeys(false)) {
            Hut h = colony.hutByIndex(orders.getInt(i + ".hut", -1));
            if (h != null) colony.workOrders.add(new Hut.WorkOrder(h, orders.getInt(i + ".level")));
        }

        for (Hut h : colony.huts) colony.addJob(h);
        for (Job j : colony.jobs) {
            var js = s.getConfigurationSection("jobs." + colony.huts.indexOf(j.hut));
            if (js == null || j == colony.builder) continue;
            String worker = js.getString("worker");
            if (worker != null) j.worker = UUID.fromString(worker);
            loadCarried(js, j);
            if (j instanceof Miner miner && js.isConfigurationSection("mine")) miner.load(js.getConfigurationSection("mine"));
        }
        var bs = s.getConfigurationSection("builder");
        if (colony.builder != null && bs != null) {
            String worker = bs.getString("worker");
            if (worker != null) colony.builder.worker = UUID.fromString(worker);
            loadCarried(bs, colony.builder);
            colony.builder.racks = bs.getInt("racks");
            Location origin = getLocation(bs, "order.origin");
            if (origin != null) {
                String structure = bs.getString("order.structure");
                BuildOrder order = BuildOrder.create(structure, origin,
                        StructureRotation.valueOf(bs.getString("order.rotation", "NONE")), bs.getBoolean("order.mirror"), null); // origin already placed
                Hut h = colony.hutByIndex(bs.getInt("order.hut", -1));
                Hut.WorkOrder work = h != null ? new Hut.WorkOrder(h, bs.getInt("order.level")) : null;
                if (order != null) colony.builder.start(order, work);
                else Bukkit.getLogger().warning("[MineCP] Structure " + structure + " not found; build order of "
                        + colony.name + " dropped.");
            }
        }
        colony.assignHomes();
        colony.assignJobs();
        return colony;
    }

    /** Tools keep durability and enchantments, so they are stored as whole items. */
    private static void saveTools(ConfigurationSection s, Job j) {
        j.tools.forEach((kind, item) -> s.set("tools." + kind.name(), Base64.getEncoder().encodeToString(item.serializeAsBytes())));
        if (!j.load.isEmpty()) s.set("load", j.load.stream().map(i -> Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList());
    }

    private static void loadCarried(ConfigurationSection s, Job j) {
        var tools = s.getConfigurationSection("tools");
        if (tools != null) for (String kind : tools.getKeys(false))
            j.tools.put(Tool.valueOf(kind), ItemStack.deserializeBytes(Base64.getDecoder().decode(tools.getString(kind))));
        for (String item : s.getStringList("load")) j.load.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(item)));
        var carried = s.getConfigurationSection("carried");
        if (carried != null) for (String m : carried.getKeys(false)) {
            Material mat = Material.matchMaterial(m);
            if (mat != null) j.carried.put(mat, carried.getInt(m));
        }
    }

    private Hut hutByIndex(int i) {
        return i >= 0 && i < huts.size() ? huts.get(i) : null;
    }

    // Plain fields, not Bukkit's Location serialization: that one drops the value at file load when the world is missing.
    private static void putLocation(ConfigurationSection s, String key, Location l) {
        var ls = s.createSection(key);
        ls.set("world", l.getWorld().getName());
        ls.set("x", l.getX());
        ls.set("y", l.getY());
        ls.set("z", l.getZ());
    }

    private static Location getLocation(ConfigurationSection s, String key) {
        var ls = s.getConfigurationSection(key);
        World world = ls == null ? null : Bukkit.getWorld(ls.getString("world", ""));
        return world == null ? null : new Location(world, ls.getDouble("x"), ls.getDouble("y"), ls.getDouble("z"));
    }
}
