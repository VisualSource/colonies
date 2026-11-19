package dev.minecp.colony;

import dev.minecp.build.BuildOrder;
import dev.minecp.build.Tape;
import dev.minecp.citizen.Colonist;
import dev.minecp.job.Builder;
import dev.minecp.job.Cook;
import dev.minecp.job.Farmer;
import dev.minecp.job.Job;
import dev.minecp.logistics.Rack;
import dev.minecp.logistics.Warehouse;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A player's colony: its huts (the town hall first), citizens, and the builder's queue of hut upgrades.
 * Rules: levels above 1 need the town hall at that level or higher; housing room is
 * {@link #STARTING_CITIZENS} plus the total level of all houses, and a citizen moves in every
 * {@link #GROWTH_TICKS} while there is room.
 */
public final class Colony {
    private static final int STARTING_CITIZENS = 4; // the mod's initialcitizenamount default
    // Ours: the mod spawns only the starting citizens on a timer; the rest come from the tavern and children.
    private static final int GROWTH_TICKS = 20 * 120;
    private static final String[] FEMALE = {"Ada", "Cora", "Edda", "Greta", "Ivy", "Lena", "Nell", "Pia", "Rosa",
            "Tilda", "Vera", "Yara"};
    private static final String[] MALE = {"Bram", "Dell", "Finn", "Hal", "Jory", "Milo", "Otto", "Sven", "Ulric",
            "Wynn", "Kit", "Quill"};
    private static final String[] LAST = {"Ashdown", "Brook", "Carter", "Dale", "Fairweather", "Fletcher", "Hale",
            "Holt", "Miller", "Oakley", "Reed", "Thatcher", "Underhill", "Wells"};

    public final UUID owner;
    public final String name;
    public final List<Hut> huts = new ArrayList<>(); // huts.getFirst() is the town hall
    public final List<Colonist> colonists = new ArrayList<>();
    public final List<Hut.WorkOrder> workOrders = new ArrayList<>(); // waiting for the builder, oldest first
    public final List<Job> jobs = new ArrayList<>(); // one per worker hut
    public final List<Grave> graves = new ArrayList<>(); // where citizens died, until dug up or decayed
    public Builder builder; // the builder's job (also in jobs); null until a Builder's Hut is placed
    public static final String DEFAULT_STYLE = "medievaloak";
    public String style = DEFAULT_STYLE; // structure pack hut buildings come from
    private int growth = 0;
    public int day; // colony days passed (saved)
    public final Health health = new Health(this);
    final Routine routine = new Routine(this);
    public final Permissions permissions;
    public long founded; // when, in ms: where two colonies' buildings claim the same chunk, the older one keeps it
    public Runnable onClaimsChanged = () -> {}; // the plugin re-reads every colony's claims
    private java.util.Set<Long> claims;

    Colony(UUID owner, String name, Block townHall, int townHallLevel) {
        this.owner = owner;
        this.name = name;
        this.permissions = new Permissions(owner);
        huts.add(new Hut(Hut.Type.TOWN_HALL, townHall, townHallLevel));
    }

    public static Colony found(UUID owner, String name, Block townHall) {
        Colony colony = new Colony(owner, name, townHall, 0);
        colony.founded = System.currentTimeMillis();
        for (int i = 0; i < STARTING_CITIZENS; i++) colony.addCitizen();
        return colony;
    }

    /** The chunks this colony claims, as {@link Claims#key} values in its town hall's world. */
    public java.util.Set<Long> claims() {
        if (claims == null) claims = Claims.of(this);
        return claims;
    }

    /** Huts or their levels changed: the claims are worked out again. */
    void claimsChanged() {
        claims = null;
        onClaimsChanged.run();
    }

    /** The box the hut's building covers as built (minX, maxX+1, minZ, maxZ+1), or null before level 1. */
    int[] footprint(Hut h) {
        if (!h.type.isBuilding() || h.level == 0) return null;
        if (h.builtBoxLevel != h.level) {
            h.builtBox = BuildOrder.footprint(h.structure(style, h.level), h.block, h.rotation, h.mirror);
            h.builtBoxLevel = h.level;
        }
        return h.builtBox;
    }

    public Hut townHall() {
        return huts.getFirst();
    }

    public Hut hutAt(Block block) {
        for (Hut h : huts) if (h.block.equals(block)) return h;
        return null;
    }

    public Hut hut(Hut.Type type) {
        for (Hut h : huts) if (h.type == type) return h;
        return null;
    }

    public int capacity() {
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

    public List<Colonist> residents(Hut house) {
        return colonists.stream().filter(c -> c.home == house).toList();
    }

    /** Gives homeless citizens a bed in a house with room (a level N house sleeps N). */
    void assignHomes() {
        for (Colonist c : colonists) {
            if (c.home != null) continue;
            for (Hut h : huts) if (h.type == Hut.Type.HOUSE && residents(h).size() < h.level) { c.home = h; break; }
        }
    }

    public Hut addHut(Hut.Type type, Block block) {
        Hut hut = new Hut(type, block, 0);
        huts.add(hut);
        addJob(hut);
        assignJobs();
        claimsChanged();
        return hut;
    }

    void addJob(Hut hut) {
        if (hut.type.job == null) return;
        Job job = hut.type.job.apply(hut, this);
        if (job instanceof Builder b) wire(builder = b);
        jobs.add(job);
    }

    /** The fields a farmer works: farmers, oldest hut first, each take the oldest fields left, one per hut level. */
    public List<Hut> fieldsOf(Job farmer) {
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
    public Job farmerOf(Hut field) {
        for (Job j : jobs) if (j instanceof Farmer && fieldsOf(j).contains(field)) return j;
        return null;
    }

    public Job jobAt(Hut hut) {
        for (Job j : jobs) if (j.hut == hut) return j;
        return null;
    }

    Job jobOf(Colonist c) {
        for (Job j : jobs) if (c.id.equals(j.worker)) return j;
        return null;
    }

    public Colonist worker(Job j) {
        for (Colonist c : colonists) if (c.id.equals(j.worker)) return c;
        return null;
    }

    /** Gives every job without a worker to an unemployed citizen, oldest jobs first. */
    void assignJobs() {
        for (Job j : jobs) {
            if (worker(j) != null) continue;
            j.worker = colonists.stream().filter(c -> jobOf(c) == null).map(c -> c.id).findFirst().orElse(null);
        }
    }

    public void removeHut(Hut hut) {
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
        claimsChanged();
    }

    /** The builder tells the colony when an order is done, and never builds over a hut block. */
    private void wire(Builder b) {
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
            claimsChanged(); // a higher level claims more
            tell("Your " + done.hut().type.title + " is now level " + done.level() + ".");
        });
        b.protect = this::isHutBlock;
    }

    private boolean isHutBlock(Block block) {
        return hutAt(block) != null;
    }

    /** The queued or in-progress upgrade of this hut, or null. */
    public Hut.WorkOrder upgradeOf(Hut hut) {
        if (builder != null && builder.work != null && builder.work.hut() == hut) return builder.work;
        for (Hut.WorkOrder w : workOrders) if (w.hut() == hut) return w;
        return null;
    }

    /**
     * The mod puts construction tape around a building waiting for the builder; we put yellow bars along its footprint
     * at the hut block's floor while the hut is unbuilt or has an upgrade queued or under way. Called once a second.
     */
    public void tape() {
        for (Hut h : huts) {
            Hut.WorkOrder up = h.type.isBuilding() ? upgradeOf(h) : null;
            if (up == null && (h.level > 0 || !h.type.isBuilding())) { removeTape(h); continue; }
            int level = up != null ? up.level() : 1;
            if (h.footprintLevel != level) { // the structure is loaded once per level, not every second
                BuildOrder o = BuildOrder.create(h.structure(style, level), h.block.getLocation(), h.rotation, h.mirror, h.block);
                if (o == null) continue;
                h.footprint = o.footprint();
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
    public String requestUpgrade(Hut hut) {
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

    public void cancelUpgrade(Hut hut) {
        workOrders.removeIf(w -> w.hut() == hut);
        if (builder != null && builder.work != null && builder.work.hut() == hut) builder.cancel();
    }

    /** A message to every online member allowed colony messages (the mod's RECEIVE_MESSAGES). */
    void tell(String message) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (permissions.has(permissions.rank(p.getUniqueId()), Action.RECEIVE_MESSAGES)) p.sendMessage(message);
    }

    public void tick() {
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
        routine.clock(time);
        boolean raining = townHall().block.getWorld().hasStorm();
        if (growth % 20 == 0) graves.removeIf(g -> g.isLoaded() && !g.decay(20)); // the mod's grave ticks only while loaded
        for (Colonist c : List.copyOf(colonists)) { // a citizen may die during its tick
            if (!c.tick()) continue;
            Job job = jobOf(c);
            routine.hunger(c, job);
            health.heal(c);
            if (c.fleeTicks > 0) { c.fleeTicks--; c.setStatus("Fleeing"); continue; }
            if (time > Routine.NIGHT - 2000 && (c.bedtime || routine.shouldGoSleep(c, job, time))) { // from late evening to dawn
                if (!c.bedtime && c.meal != null) { c.meal.abandon(); c.meal = null; }
                c.bedtime = true;
                if (job == null || job.leaveWork(c)) routine.goToBed(c);
                continue;
            }
            if (c.bedtime) { c.bedtime = false; c.wake(); }
            if (c.meal != null) { // eating comes before work
                if (c.meal.tick(this, job)) {
                    c.hungerNote = c.meal.why;
                    if (c.meal.why != null) c.foodRetry = Routine.FOOD_RETRY_TICKS;
                    c.meal = null;
                }
                continue;
            }
            if (job != null && raining && !job.worksInRain()) { // the mod's workers stay in out of the rain
                c.setStatus("Rain: off work");
                if (job.leaveWork(c)) c.wander(routine.homeOf(c).block.getLocation().toCenterLocation());
            } else if (job != null) job.tick(c);
            else {
                c.setStatus("Unemployed");
                c.wander((c.home != null ? c.home : townHall()).block.getLocation().toCenterLocation());
            }
        }
    }

    private Warehouse warehouse;

    /** The colony's built warehouse (level 1 or higher), or null. */
    public Warehouse warehouse() {
        for (Hut h : huts) if (h.type == Hut.Type.WAREHOUSE && h.level >= 1) {
            if (warehouse == null || warehouse.hut != h) warehouse = new Warehouse(h, this);
            return warehouse;
        }
        return null;
    }

    /** A Cook's Hut that is built and has a cook, where hungry citizens can be served; null if none. */
    public Cook restaurant() {
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

    public void despawn() {
        for (Colonist c : colonists) if (c.meal != null) { c.meal.abandon(); c.meal = null; } // food back in its chest
        jobs.forEach(Job::stow);
        colonists.forEach(Colonist::despawn);
        huts.forEach(Colony::removeTape);
    }

    public Hut hutByIndex(int i) {
        return i >= 0 && i < huts.size() ? huts.get(i) : null;
    }

    /** The citizen whose body or legs {@code e} is, or null. */
    public Colonist colonistOf(org.bukkit.entity.Entity e) {
        for (Colonist c : colonists) if (c.owns(e)) return c;
        return null;
    }

    public Grave graveAt(Block b) {
        for (Grave g : graves) if (g.block.equals(b)) return g;
        return null;
    }
}
