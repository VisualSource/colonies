package dev.minecp.colony;

import dev.minecp.build.BuildOrder;
import dev.minecp.build.Tape;
import dev.minecp.citizen.Colonist;
import dev.minecp.job.Builder;
import dev.minecp.job.Cook;
import dev.minecp.job.Farmer;
import dev.minecp.job.Job;
import dev.minecp.logistics.Rack;
import dev.minecp.logistics.Requests;
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
 * Rules: levels above 1 need the town hall at that level or higher; housing room is the beds of houses and taverns.
 * A new colony has nobody: the first {@link #STARTING_CITIZENS} move in one by one at the town hall, as in the mod;
 * after them the colony grows only by children born in houses with room, and by recruiting the taverns' visitors.
 */
public final class Colony {
    private static final int STARTING_CITIZENS = 4; // the mod's initialcitizenamount default
    private static final int CITIZEN_CAP = 25; // the mod's CITIZEN_LIMIT_DEFAULT: the most citizens before research
    // The mod's starting citizens (CitizenManager.onColonyTick): every colony tick (500 ticks) the countdown drops by
    // 500 plus 60 per town hall level; one moves in when it runs out, and it starts again at 60 s (first time: 30 s).
    private static final int COLONY_TICK = 500;
    private int moveIn = 30 * 20, colonyTick;
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
    /** The style packs MineCP knows of (tools/convert_blueprints.py makes their data packs). */
    private static final String[] STYLES = {"medievaloak", "medievalspruce", "medievalbirch", "medievaldarkoak", "cavern"};

    /** The styles whose data pack is installed (it has a town hall). */
    public static List<String> styles() {
        return java.util.Arrays.stream(STYLES).filter(s -> BuildOrder.exists(Hut.Type.TOWN_HALL.structure(s, 1))).toList();
    }
    // Children (the mod's EntityAICitizenChild): time towards growing up builds in a shared pool, 500 a colony tick
    // while there are children; each child takes 2000 from it at a time. From 4000 a child grows up with a 1 in 71 chance
    // each colony tick, and surely past 70000.
    private static final int CHILD_SHARE = 2000, CHILD_MIN = 4000, CHILD_MAX = 70_000, GROW_CHANCE = 71;
    int childPool; // saved
    public int day; // colony days passed (saved)
    public final Health health = new Health(this);
    final Routine routine = new Routine(this);
    public final Permissions permissions;
    public final Requests requests = new Requests(this);
    public final Happiness happiness = new Happiness(this);
    public final Visitors visitors = new Visitors(this);
    public long founded; // when, in ms: where two colonies' buildings claim the same chunk, the older one keeps it
    public Runnable onClaimsChanged = () -> {}; // the plugin re-reads every colony's claims
    /** What the colony reports as it grows, for advancements; the plugin sets it. */
    public interface Milestones {
        default void queued(Hut.Type type, int level) {}
        default void built(Hut.Type type, int level) {}
        default void population(int citizens) {}
        default void ate() {}
        default void taught(org.bukkit.Material result) {}
    }

    public Milestones milestones = new Milestones() {};
    public java.util.function.Consumer<Hut> onTakenDown = h -> {}; // a removal order finished: the plugin removes the hut
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
    public int[] footprint(Hut h) {
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

    /** How many citizens the colony has beds for: houses and taverns, at least 1, at most {@link #CITIZEN_CAP} (the mod's getMaxCitizens). */
    public int capacity() {
        return Math.max(1, Math.min(CITIZEN_CAP, huts.stream().mapToInt(h -> h.type.rooms(h.level)).sum()));
    }

    /** Whether another citizen fits: born, or recruited from a tavern. */
    public boolean hasRoom() {
        return colonists.size() < capacity();
    }

    /** A new citizen at {@code at}. As the mod: the first is either sex, then whichever is fewer (rounding towards female). */
    private Colonist newCitizen(Location at) {
        long females = colonists.stream().filter(Colonist::female).count();
        boolean female = colonists.isEmpty() ? ThreadLocalRandom.current().nextBoolean() : females < colonists.size() / 2.0;
        return new Colonist(UUID.randomUUID(), uniqueName(female), Colonist.randomSkin(female), at);
    }

    /** One of the starting citizens moves in at the town hall. */
    private void moveIn() {
        Colonist c = newCitizen(townHall().block.getLocation().toCenterLocation());
        join(c);
        tell(c.name + " moved into " + name + ".");
    }

    /** {@code c} becomes a citizen (a starting citizen, a newborn, or a recruited visitor). */
    void join(Colonist c) {
        colonists.add(c);
        c.setStatus("Unemployed");
        assignHomes();
        assignJobs();
        milestones.population(colonists.size());
    }

    String uniqueName(boolean female) {
        var rnd = ThreadLocalRandom.current();
        String[] first = female ? FEMALE : MALE;
        while (true) { // 144 combinations per list; colonies are far smaller
            String name = first[rnd.nextInt(first.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            if (colonists.stream().noneMatch(c -> c.name.equals(name)) && visitors.list.stream().noneMatch(v -> v.colonist.name.equals(name)))
                return name;
        }
    }

    /**
     * The mod's ReproductionManager: with room in the colony, at least 2 citizens, a man and a woman among citizens and
     * visitors, and a house or tavern with a spare bed, a child is born there. Tried every colony tick (the mod's timer
     * works out to that whenever there is room).
     */
    private void birth() {
        if (!hasRoom() || colonists.size() < 2) return;
        List<Colonist> all = new ArrayList<>(colonists);
        visitors.list.forEach(v -> all.add(v.colonist));
        if (all.stream().noneMatch(Colonist::female) || all.stream().allMatch(Colonist::female)) return;
        Hut house = spareBed();
        if (house == null) return;
        Colonist child = newCitizen(house.block.getLocation().toCenterLocation());
        child.setChild(true);
        child.home = house;
        join(child);
        child.setStatus("Playing");
        if (colonists.stream().filter(c -> c.child).count() == 1) tell(child.name + " was born in " + name + "!"); // as the mod: only the first child
    }

    private Hut spareBed() {
        for (Hut h : huts) if (residents(h).size() < h.type.rooms(h.level)) return h;
        return null;
    }

    /** Children take their share of growing-up time, and some grow up. */
    private void growUp() {
        var rnd = ThreadLocalRandom.current();
        if (colonists.stream().anyMatch(c -> c.child)) childPool += COLONY_TICK; // shortcut: the mod's pool also empties at 70000; ours doesn't
        for (Colonist c : colonists) {
            if (!c.child) continue;
            if (childPool >= CHILD_SHARE) { childPool -= CHILD_SHARE; c.childTime += CHILD_SHARE; }
            if (c.childTime >= CHILD_MIN && (rnd.nextInt(GROW_CHANCE) == 0 || c.childTime > CHILD_MAX)) {
                c.setChild(false);
                c.childTime = 0;
                tell(c.name + " has grown up.");
                assignJobs();
            }
        }
    }

    public List<Colonist> residents(Hut house) {
        return colonists.stream().filter(c -> c.home == house).toList();
    }

    /** Gives homeless citizens a bed in a house or tavern with room (a level N house sleeps N, a tavern 4). */
    void assignHomes() {
        for (Colonist c : colonists) {
            if (c.home != null) continue;
            for (Hut h : huts) if (residents(h).size() < h.type.rooms(h.level)) { c.home = h; break; }
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

    public Job jobOf(Colonist c) {
        for (Job j : jobs) if (c.id.equals(j.worker)) return j;
        return null;
    }

    public Colonist worker(Job j) {
        for (Colonist c : colonists) if (c.id.equals(j.worker)) return c;
        return null;
    }

    /**
     * Gives jobless citizens the open jobs of built huts. As the mod (IBuilding.canAssignCitizens): a hut takes a worker
     * once level 1 is built; only the Builder's Hut hires at level 0, so its builder can build it.
     */
    void assignJobs() {
        for (Job j : jobs) {
            if (worker(j) != null || j.hut.level < 1 && j != builder) continue;
            j.worker = colonists.stream().filter(c -> !c.child && jobOf(c) == null).map(c -> c.id).findFirst().orElse(null);
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
        if (hut.type == Hut.Type.TAVERN) visitors.tavernRemoved(hut);
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
            if (done.remove()) {
                tell("Your builder took down the " + done.hut().type.title + ".");
                onTakenDown.accept(done.hut());
                return;
            }
            done.hut().level = done.level();
            assignHomes();
            assignJobs(); // a hut finished at level 1 hires now
            claimsChanged(); // a higher level claims more
            tell("Your " + done.hut().type.title + " is now level " + done.level() + ".");
            milestones.built(done.hut().type, done.level());
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
    public String requestUpgrade(Hut hut, ClaimMap claims) {
        int next = hut.level + 1;
        if (builder == null) return "Place a Builder's Hut first: the builder builds every hut.";
        if (hut.level >= hut.type.maxLevel()) return "The " + hut.type.title + " is at the highest level.";
        if (upgradeOf(hut) != null) return "That upgrade is already queued.";
        if (hut.type != Hut.Type.TOWN_HALL && next > 1 && next > townHall().level)
            return "Upgrade the Town Hall to level " + next + " first.";
        if (!BuildOrder.exists(hut.structure(style, next))) return "Structure " + hut.structure(style, next) + " not found.";
        int[] box = hut.type.isBuilding() ? BuildOrder.footprint(hut.structure(style, next), hut.block, hut.rotation, hut.mirror) : null;
        if (box != null) for (long k : Claims.chunks(box)) { // unclaimed land is fine: the building claims it, as in the mod
            Colony owner = claims.colonyAt(hut.block.getWorld(), Claims.x(k), Claims.z(k));
            if (owner != null && owner != this) return "Level " + next + " of the " + hut.type.title + " would reach into " + owner.name + ".";
        }
        workOrders.add(new Hut.WorkOrder(hut, next));
        milestones.queued(hut.type, next);
        return null;
    }

    /**
     * Queues taking the hut's building down (the mod's removal work order, level 0 here): the builder clears what is
     * left of it, then the hut is removed and its item dropped. @return why it can't, or null when queued.
     */
    public String requestRemoval(Hut hut) {
        if (builder == null) return "Place a Builder's Hut first: the builder takes buildings down too.";
        if (!hut.type.removable()) return "Break the hut block to remove the " + hut.type.title + ".";
        if (hut.level == 0) return "Nothing is built there: break the hut block to remove it.";
        if (upgradeOf(hut) != null) return "Cancel its queued work first.";
        workOrders.add(new Hut.WorkOrder(hut, hut.level, true));
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
        if (++colonyTick % COLONY_TICK == 0) {
            if (colonists.size() < STARTING_CITIZENS && (moveIn -= COLONY_TICK + 60 * townHall().level) <= 0) { // also refills them if they die, as in the mod
                moveIn = 60 * 20;
                moveIn();
            }
            birth();
            growUp();
            visitors.colonyTick(COLONY_TICK);
        }
        long time = townHall().block.getWorld().getTime(); // 0..23999
        routine.clock(time);
        boolean raining = townHall().block.getWorld().hasStorm();
        if (colonyTick % 20 == 0) graves.removeIf(g -> g.isLoaded() && !g.decay(20)); // the mod's grave ticks only while loaded
        if (colonyTick % 20 == 0) requests.refresh(); // who fills what each hut is waiting for
        visitors.tick();
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
                c.setStatus(c.child ? "Playing" : "Unemployed");
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
        // shortcut: uses the colony's style now; if it changed since the hut was built, little matches and little is taken down
        BuildOrder o = BuildOrder.create(structure, w.hut().block.getLocation(), w.hut().rotation, w.hut().mirror, w.hut().block);
        if (o != null && w.remove()) o = o.removal(); // the building at that level, as it stands
        if (o == null) tell("Structure " + structure + " not found; upgrade skipped.");
        else builder.start(o, w);
    }

    public void despawn() {
        for (Colonist c : colonists) if (c.meal != null) { c.meal.abandon(); c.meal = null; } // food back in its chest
        jobs.forEach(Job::stow);
        colonists.forEach(Colonist::despawn);
        visitors.despawn();
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
