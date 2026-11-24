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
import dev.minecp.research.Effects;

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
    private static final int CITIZEN_MAX = 250; // the mod's maxCitizenPerColony default: research can't go past it
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
    /** A build without a hut, queued with the build tool (the mod's decoration work order): which, where, turned how. */
    public record Decoration(String structure, String name, Location origin, StructureRotation rotation, boolean mirror) {}
    public final List<Decoration> decorations = new ArrayList<>(); // waiting for the builder, after the huts' work orders
    public Decoration building; // the build without a hut the builder works on; saved
    public final List<Job> jobs = new ArrayList<>(); // one per worker (a barracks tower has one a level)
    public final List<Grave> graves = new ArrayList<>(); // where citizens died, until dug up or decayed
    public Builder builder; // the builder's job (also in jobs); null until a Builder's Hut is placed
    public static final String DEFAULT_STYLE = "medievaloak";
    public String style = DEFAULT_STYLE; // structure pack hut buildings come from
    /** The style packs MineCP knows of (tools/convert_blueprints.py makes their data packs). */
    private static final String[] STYLES = {"medievaloak", "medievalspruce", "medievalbirch", "medievaldarkoak", "cavern"};

    /** The styles whose data pack is installed (it has a town hall). */
    /** The plugin's log (set when it starts), for what admins may need to look back on: founding, deaths, raids, builds. */
    public static java.util.logging.Logger log = java.util.logging.Logger.getLogger("Colonies");

    /** "Name (world x y z)" of the colony, for the log. */
    public String where() {
        Block b = townHall().block;
        return "'" + name + "' (" + b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + ")";
    }

    public static List<String> styles() {
        return java.util.Arrays.stream(STYLES).filter(s -> BuildOrder.exists(Hut.Type.TOWN_HALL.structure(s, 1))).toList();
    }
    // Children (the mod's EntityAICitizenChild): time towards growing up builds in a shared pool, 500 a colony tick
    // while there are children, emptied when there are none or it reaches 70000; each child takes 2000 from it at a time. From 4000 a child grows up with a 1 in 71 chance
    // each colony tick, and surely past 70000 (research makes both faster).
    private static final int CHILD_SHARE = 2000, CHILD_MIN = 4000, CHILD_MAX = 70_000, GROW_CHANCE = 70;
    int childPool; // saved
    public int day; // colony days passed (saved)
    public final Health health = new Health(this);
    public final Sickness sickness = new Sickness(this);

    /** A citizen's inventory slots: the mod's 27, more with research. */
    public int inventorySlots() {
        return 27 + (int) research.effect(dev.minecp.research.Effects.INVENTORY_SLOTS);
    }
    final Routine routine = new Routine(this);
    public final Permissions permissions;
    public final Requests requests = new Requests(this);
    public final Happiness happiness = new Happiness(this);
    public final Visitors visitors = new Visitors(this);
    public final Raids raids = new Raids(this);
    public final dev.minecp.research.Studies research = new dev.minecp.research.Studies(this);
    public ClaimMap claimMap; // every colony's claims, set by the plugin: raids don't spawn on another colony's land
    public long founded; // when, in ms: where two colonies' buildings claim the same chunk, the older one keeps it
    public Runnable onClaimsChanged = () -> {}; // the plugin re-reads every colony's claims
    /** What the colony reports as it grows, for advancements; the plugin sets it. */
    public interface Milestones {
        default void queued(Hut.Type type, int level) {}
        default void built(Hut.Type type, int level) {}
        default void population(int citizens) {}
        default void guards(int guards) {}
        default void deepMine() {}
        default void maxFields() {}
        default void ate() {}
        default void taught(org.bukkit.Material result) {}
        default void buried() {}
        default void resurrected() {}
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

    private static final int BEDS_BELOW = 5, BEDS_ABOVE = 12; // ours: heights around the hut block searched (the mod registers beds)

    /** The beds (their heads) standing inside a hut's building. */
    List<Block> bedsIn(Hut h) {
        List<Block> found = new ArrayList<>();
        int[] box = footprint(h); // minX, maxX+1, minZ, maxZ+1
        if (box == null) return found;
        for (int x = box[0]; x < box[1]; x++) for (int z = box[2]; z < box[3]; z++) for (int y = h.block.getY() - BEDS_BELOW; y <= h.block.getY() + BEDS_ABOVE; y++) {
            Block b = h.block.getWorld().getBlockAt(x, y, z);
            if (org.bukkit.Tag.BEDS.isTagged(b.getType()) // first: the data is a new object for every block
                    && b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed && bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) found.add(b);
        }
        return found;
    }

    public Hut townHall() {
        return huts.getFirst();
    }

    public Hut hutAt(Block block) {
        for (Hut h : huts) if (h.block.equals(block)) return h;
        return null;
    }

    /** A gate spot of a built gatehouse picked at random (the mod's "gate" tag; the hut block if it has none), or null without one. */
    Block gate() {
        List<Hut> gates = huts.stream().filter(h -> h.type == Hut.Type.GATE_HOUSE && h.level >= 1).toList();
        if (gates.isEmpty()) return null;
        Hut h = gates.get(ThreadLocalRandom.current().nextInt(gates.size()));
        List<Block> spots = h.workSpots(style, "gate");
        return spots.isEmpty() ? h.block : spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }

    /** The highest level of the colony's mystical sites, 0 without one (the mod's getMysticalSiteMaxBuildingLevel). */
    public int mysticalLevel() {
        return huts.stream().filter(h -> h.type == Hut.Type.MYSTICAL_SITE).mapToInt(h -> h.level).max().orElse(0);
    }

    public Hut hut(Hut.Type type) {
        for (Hut h : huts) if (h.type == type) return h;
        return null;
    }

    /**
     * How many citizens the colony has beds for: houses, taverns and the guards and trainees who live at work, at least 1, at most {@link #CITIZEN_CAP} or what
     * research allows, never over {@link #CITIZEN_MAX} (the mod's getMaxCitizens and maxCitizensFromResearch).
     */
    public int capacity() {
        int cap = (int) Math.min(CITIZEN_MAX, Math.max(CITIZEN_CAP, research.effect(Effects.CITIZEN_CAP)));
        // a Locked home counts only who lives there now, as does a hut that houses its workers (the mod's calculateMaxCitizens)
        return Math.max(1, Math.min(cap, huts.stream().mapToInt(h -> h.type.housesWorkers() ? residents(h).size() : h.hiring == Settings.Hiring.LOCKED
                ? Math.min(residents(h).size(), h.type.rooms(h.level)) : h.type.rooms(h.level)).sum()));
    }

    /** Whether another citizen fits: born, or recruited from a tavern. */
    public boolean hasRoom() {
        return colonists.size() < capacity();
    }

    /**
     * A new citizen at {@code at}. As the mod: the first is either sex, then whichever is fewer (rounding towards
     * female); its skills rolled up to twice the colony's happiness, at least 5 for the starting citizens (initForNewCivilian).
     */
    private Colonist newCitizen(Location at) {
        long females = colonists.stream().filter(Colonist::female).count();
        boolean female = colonists.isEmpty() ? ThreadLocalRandom.current().nextBoolean() : females < colonists.size() / 2.0;
        Colonist c = new Colonist(UUID.randomUUID(), uniqueName(female), Colonist.randomSkin(female), at);
        int cap = (int) happiness.overall() * 2;
        c.skills.roll(colonists.size() < STARTING_CITIZENS ? Math.max(5, cap) : cap);
        return c;
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
        var rnd = ThreadLocalRandom.current(); // ours: two citizens at random stand in for its parents (no families yet)
        child.skills.inherit((int) happiness.overall(), colonists.get(rnd.nextInt(colonists.size())).skills, colonists.get(rnd.nextInt(colonists.size())).skills);
        child.setChild(true);
        child.home = house;
        join(child);
        child.setStatus("Playing");
        if (colonists.stream().filter(c -> c.child).count() == 1) tell(child.name + " was born in " + name + "!"); // as the mod: only the first child
    }

    private Hut spareBed() {
        for (Hut h : huts) if (h.hiring != Settings.Hiring.LOCKED && residents(h).size() < h.type.rooms(h.level)) return h; // the mod skips Locked homes
        return null;
    }

    /** Children take their share of growing-up time, and some grow up. */
    private void growUp() {
        var rnd = ThreadLocalRandom.current();
        if (colonists.stream().anyMatch(c -> c.child) && childPool < CHILD_MAX) childPool += COLONY_TICK; // the mod's updateChildTime
        else childPool = 0;
        double growth = 1 + research.effect(Effects.GROWTH); // the mod divides both the odds and the time limit by it
        for (Colonist c : colonists) {
            if (!c.child) continue;
            if (childPool >= CHILD_SHARE) { childPool -= CHILD_SHARE; c.childTime += CHILD_SHARE; }
            if (c.childTime >= CHILD_MIN && (rnd.nextInt((int) (GROW_CHANCE / growth) + 1) == 0 || c.childTime > CHILD_MAX / growth)) {
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

    /**
     * Gives homeless citizens a bed in a house or tavern with room (a level N house sleeps N, a tavern 4), in homes
     * that take residents on their own (the mod's LivingBuildingModule: auto housing, or its hiring mode). Guards and
     * trainees live at their hut instead, and lose that home with the job (the mod's WorkAtHomeBuildingModule).
     */
    void assignHomes() {
        for (Colonist c : colonists) { // first, so the beds they leave are free below
            if (livesAtWork(c)) {
                Job job = jobOf(c);
                if (c.home != null && c.home.type.isHome() && c.home.hiring != Settings.Hiring.LOCKED && !autoAssigns(c.home, Settings.AUTO_HIRING)) // the hiring switch, as the mod
                    tell(c.name + " now lives at work (" + job.hut.type.worker + "). The " + c.home.type.title + " at "
                            + c.home.block.getX() + " " + c.home.block.getY() + " " + c.home.block.getZ() + " has a bed free.");
                c.home = job.hut;
            } else if (c.home != null && c.home.type.housesWorkers()) c.home = null;
        }
        for (Colonist c : colonists) {
            if (c.home != null) continue;
            for (Hut h : huts) if (autoAssigns(h, Settings.AUTO_HOUSING) && residents(h).size() < h.type.rooms(h.level)) { c.home = h; break; }
        }
    }

    /** The colony's owner or an officer (the mod's colony managers). */
    public boolean isManager(java.util.UUID player) {
        Rank r = permissions.rank(player);
        return r == Rank.OWNER || r == Rank.OFFICER;
    }

    /** A Town Hall setting (Settings.of(TOWN_HALL)). */
    public boolean setting(String key) {
        return townHall().on(key);
    }

    /** Whether the colony fills this hut or home itself, so players don't (the mod's canAutoHire and auto housing). */
    public boolean fillsItself(Hut h) {
        return autoAssigns(h, h.type.isHome() ? Settings.AUTO_HOUSING : Settings.AUTO_HIRING);
    }

    /** Whether the colony fills this hut itself: Automatic, or Default with the Town Hall's switch on (the mod's canAutoHire). */
    private boolean autoAssigns(Hut h, String townHallSwitch) {
        return h.hiring == Settings.Hiring.AUTO || h.hiring == Settings.Hiring.DEFAULT && setting(townHallSwitch);
    }

    /** Whether the hut can take a worker now (the mod's canAssignCitizens): built to level 1, or the Builder's Hut. */
    public boolean canHire(Job j) {
        return j.hut.type.worker != null && (j.hut.level >= 1 || j == builder);
    }

    /**
     * A player hires {@code c} for {@code j} (manual hiring): it leaves its old job, and the hut's worker before it
     * becomes jobless; then auto-hiring huts fill again.
     */
    public void hire(Job j, Colonist c) {
        Job old = jobOf(c);
        if (old != null) old.worker = null;
        j.worker = c.id;
        assignJobs();
    }

    /** A player fires the hut's worker: jobless, and free for huts that hire on their own. */
    public void fire(Job j) {
        j.worker = null;
        assignJobs();
    }

    /** Whether its job gives it its home: guards and trainees. */
    public boolean livesAtWork(Colonist c) {
        Job job = jobOf(c);
        return job != null && job.hut.type.housesWorkers();
    }

    /** A player moves {@code c} into the home {@code h} (manual housing), if it has room. */
    public boolean moveInto(Hut h, Colonist c) {
        if (c.home == h || livesAtWork(c) || residents(h).size() >= h.type.rooms(h.level)) return false;
        c.home = h;
        assignHomes();
        return true;
    }

    /** A player moves {@code c} out of its home: homeless, until a home that takes residents on its own has room. */
    public void moveOut(Colonist c) {
        c.home = null;
        assignHomes();
    }

    /** After a hiring mode or the Town Hall's switches change: the huts and homes now filled automatically fill. */
    public void hiringChanged() {
        assignHomes();
        assignJobs();
    }

    public Hut addHut(Hut.Type type, Block block) {
        Hut hut = new Hut(type, block, 0);
        huts.add(hut);
        addJob(hut);
        assignJobs();
        claimsChanged();
        return hut;
    }

    /** Gives the hut as many jobs as its level allows (at least one), keeping those it has. */
    void addJob(Hut hut) {
        if (hut.type.job == null) return;
        for (int n = jobsAt(hut).size(); n < Math.max(1, hut.type.workers(hut.level)); n++) {
            Job job = hut.type.job.apply(hut, this);
            if (job instanceof Builder b) wire(builder = b);
            jobs.add(job);
        }
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

    /** The hut's (first) job, or null. */
    public Job jobAt(Hut hut) {
        for (Job j : jobs) if (j.hut == hut) return j;
        return null;
    }

    /** Every job at the hut, in hiring order. */
    public List<Job> jobsAt(Hut hut) {
        return jobs.stream().filter(j -> j.hut == hut).toList();
    }

    public Job jobOf(Colonist c) {
        for (Job j : jobs) if (c.id.equals(j.worker)) return j;
        return null;
    }

    /** The citizens who work at the hut, in hiring order. */
    public List<Colonist> workersAt(Hut hut) {
        return jobsAt(hut).stream().map(this::worker).filter(java.util.Objects::nonNull).toList();
    }

    public Colonist worker(Job j) {
        for (Colonist c : colonists) if (c.id.equals(j.worker)) return c;
        return null;
    }

    /**
     * Gives jobless citizens the open jobs of built huts. As the mod (IBuilding.canAssignCitizens): a hut takes a worker
     * once level 1 is built; only the Builder's Hut hires at level 0, so its builder can build it. Guard huts take the
     * most skilled trainee of their kind first, if their setting says so (the mod's GuardBuildingModule).
     */
    void assignJobs() {
        boolean promoted = false;
        for (Job j : jobs) {
            if (worker(j) != null || !canHire(j) || !autoAssigns(j.hut, Settings.AUTO_HIRING)) continue;
            Job trainee = j instanceof dev.minecp.job.Guard g && g.hut.on(Settings.HIRE_TRAINEE) ? mostSkilled(g::trainedBy, g.skills()[0]) : null;
            if (trainee != null) {
                j.worker = trainee.worker;
                trainee.worker = null;
                promoted = true;
                continue;
            }
            j.worker = colonists.stream().filter(c -> !c.child && jobOf(c) == null).map(c -> c.id).findFirst().orElse(null);
        }
        if (promoted) assignJobs(); // the trainee's place is open; each pass fills a guard's, so this ends
        else assignHomes();
    }

    /** The filled job of this kind whose worker has the highest {@code skill}, or null. */
    Job mostSkilled(java.util.function.Predicate<Job> kind, dev.minecp.citizen.Skills.Skill skill) {
        return jobs.stream().filter(j -> kind.test(j) && worker(j) != null)
                .max(java.util.Comparator.comparingInt(j -> worker(j).skills.level(skill))).orElse(null);
    }

    public void removeHut(Hut hut) {
        workOrders.removeIf(w -> w.hut() == hut);
        if (builder != null && builder.work != null && builder.work.hut() == hut) builder.cancel();
        for (Job job : jobsAt(hut)) {
            job.dropCarried();
            jobs.remove(job);
            if (job == builder) {
                builder = null;
                if (building != null) { decorations.addFirst(building); building = null; } // built again by the next builder
            }
        }
        for (Hut tower : towers(hut)) { // the mod clears its towers' hut blocks
            removeHut(tower);
            if (tower.block.getState(false) instanceof org.bukkit.block.Container ct) { // ours: what is in them drops
                Location at = tower.block.getLocation().toCenterLocation();
                for (ItemStack i : ct.getInventory().getContents()) if (i != null) at.getWorld().dropItemNaturally(at, i);
                ct.getInventory().clear();
            }
            tower.block.setType(Material.AIR);
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
            if (done == null) {
                log.info(where() + ": the builder finished " + (building != null ? building.name() : order.structure) + ".");
                tell("Your builder finished " + (building != null ? "the " + building.name().toLowerCase() : "the build") + ".");
                building = null;
                return;
            }
            if (done.remove()) {
                log.info(where() + ": the builder took down the " + done.hut().type.title + ".");
                tell("Your builder took down the " + done.hut().type.title + ".");
                onTakenDown.accept(done.hut());
                return;
            }
            done.hut().level = done.level();
            addJob(done.hut()); // a barracks tower takes another guard each level
            if (done.hut().type == Hut.Type.BARRACKS) addTowers(done.hut(), order);
            assignHomes();
            assignJobs(); // a hut finished at level 1 hires now
            claimsChanged(); // a higher level claims more
            log.info(where() + ": " + done.hut().type.title + " is now level " + done.level() + ".");
            tell("Your " + done.hut().type.title + " is now level " + done.level() + ".");
            milestones.built(done.hut().type, done.level());
        });
        b.protect = this::isHutBlock;
    }

    /**
     * The towers that came with the barracks (its blueprint's barracks tower hut blocks: one more each level), as
     * huts turned and mirrored as the barracks and in its version (the mod's registerBlockPosition).
     */
    private void addTowers(Hut barracks, BuildOrder order) {
        for (var e : order.subHuts.entrySet()) {
            Block b = e.getKey();
            if (!e.getValue().equals(Hut.Type.BARRACKS_TOWER.modId()) || hutAt(b) != null || b.getType() != Hut.Type.BARRACKS_TOWER.block) continue;
            Hut tower = addHut(Hut.Type.BARRACKS_TOWER, b);
            tower.barracks = barracks;
            tower.rotation = order.rotation;
            tower.mirror = order.mirror;
            tower.blueprint = barracks.blueprint == null ? null : barracks.blueprint + "tower"; // altbarracks: altbarrackstower
            dev.minecp.ChestNames.name(b, tower.type.title);
        }
    }

    /** Chunks claimed around the hut's chunk: a barracks 3 once all its towers are level 4, else 2 (the mod's); others by type. */
    int claimRadius(Hut h) {
        if (h.type != Hut.Type.BARRACKS || h.level == 0) return h.type.claimRadius(h.level);
        return towers(h).stream().anyMatch(t -> t.level < 4) ? 2 : 3;
    }

    /** The Composter's Hut job whose building has this barrel, or null. */
    public dev.minecp.job.Composter barrelOwner(Block b) {
        if (b.getType() != Material.COMPOSTER) return null;
        for (Job j : jobs) if (j instanceof dev.minecp.job.Composter cp && cp.isBarrel(b)) return cp;
        return null;
    }

    /** A barracks' towers. */
    public List<Hut> towers(Hut barracks) {
        return huts.stream().filter(t -> t.barracks == barracks).toList();
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
        boolean on = setting(Settings.TAPE);
        for (Hut h : huts) {
            if (!on) { removeTape(h); continue; }
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
        // The mod's builder takes only work up to its hut's level, unless its hut is at the top level or it is its own hut.
        // Ours: refused here with one builder, where the mod queues it for a builder that can take it.
        if (hut != builder.hut && builder.hut.level < Hut.MAX_LEVEL && next > builder.hut.level)
            return "The builder builds only up to its own hut's level: upgrade the Builder's Hut to level " + next + " first.";
        if (hut.type == Hut.Type.BARRACKS_TOWER && (hut.barracks == null || next > hut.barracks.level)) // the mod's
            return "Upgrade the Barracks first: its towers go no higher than it.";
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
    public void tell(String message) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (permissions.has(permissions.rank(p.getUniqueId()), Action.RECEIVE_MESSAGES)) p.sendMessage(message);
    }

    public void tick() {
        if (builder != null && builder.order == null) {
            if (!workOrders.isEmpty()) startNextWorkOrder();
            else if (!decorations.isEmpty()) startNextDecoration();
        }
        if (++colonyTick % COLONY_TICK == 0) {
            boolean moving = setting(Settings.MOVE_IN); // off: no new citizens, children or visitors (the mod's MOVE_IN)
            if (moving && colonists.size() < STARTING_CITIZENS && (moveIn -= COLONY_TICK + 60 * townHall().level) <= 0) { // also refills them if they die, as in the mod
                moveIn = 60 * 20;
                moveIn();
            }
            if (moving) birth();
            growUp();
            visitors.colonyTick(COLONY_TICK);
            research.colonyTick();
            raids.hireSpies();
            for (Hut h : huts) if (h.post != null) h.post.colonyTick(this);
            // ours: counted each colony tick (the mod counts when a guard is hired)
            milestones.guards((int) jobs.stream().filter(j -> j instanceof dev.minecp.job.Guard && j.worker != null).count());
        }
        long time = townHall().block.getWorld().getTime(); // 0..23999
        routine.clock(time);
        boolean raining = townHall().block.getWorld().hasStorm() && research.effect(Effects.WORK_IN_RAIN) == 0; // research: everyone works in it
        if (colonyTick % 20 == 0) graves.removeIf(g -> g.isLoaded() && !g.decay(20)); // the mod's grave ticks only while loaded
        if (colonyTick % 20 == 0) requests.refresh(); // who fills what each hut is waiting for
        if (colonyTick % 20 == 0) for (Job j : jobs) j.everySecond();
        if (colonyTick % 20 == 0) for (Hut h : huts) if (h.post != null) h.post.checkFollow();
        visitors.tick();
        raids.tick();
        sickness.tick();
        double walking = 1 + research.effect(Effects.WALKING);
        for (Colonist c : List.copyOf(colonists)) { // a citizen may die during its tick
            if (!c.tick()) continue;
            c.pace = walking; // for its next tick's walk; working sets its job's pace below
            Job job = jobOf(c);
            if (colonyTick % 20 == 0) { // the mod's gatherXp, every 20 ticks: the jobless take orbs too, but learn nothing from them
                double xp = c.gatherOrbs();
                if (xp > 0 && job != null) job.absorb(c, xp);
            }
            routine.hunger(c, job);
            health.heal(c);
            if (c.fleeTicks > 0) { c.fleeTicks--; c.setStatus("Fleeing"); continue; }
            if (c.talkTicks > 0) { c.talkTicks--; continue; } // a player clicked it: it stands still for a moment
            if (sickness.inHospital(c) && sickness.careFor(c, job)) continue; // in a hospital bed: before a raid or the night, as the mod
            boolean hide = raids.underway() && !(job instanceof dev.minecp.job.Guard); // the mod: citizens shelter at home during a raid
            if (hide || time > Routine.NIGHT - 2000 && (c.bedtime || routine.shouldGoSleep(c, job, time))) { // from late evening to dawn
                if (!c.bedtime && c.meal != null) { c.meal.abandon(); c.meal = null; }
                c.bedtime = true;
                if (job == null || job.leaveWork(c)) routine.goToBed(c);
                continue;
            }
            if (c.bedtime) { c.bedtime = false; c.wake(); }
            if ((c.disease != null || sickness.hurt(c, job)) && sickness.careFor(c, job)) { // sick or hurt: before meals and work, as the mod
                if (c.meal != null) { c.meal.abandon(); c.meal = null; } // its food back, out of the restaurant's queue
                continue;
            }
            if (c.meal != null && job != null && !job.interruptible()) { c.meal.abandon(); c.meal = null; } // not now (the mod's canAIBeInterrupted)
            if (c.meal != null) { // eating comes before work
                if (c.meal.tick(this, job)) {
                    c.hungerNote = c.meal.why;
                    if (c.meal.why != null) c.foodRetry = Routine.FOOD_RETRY_TICKS;
                    c.meal = null;
                }
                continue;
            }
            if (c.mourning > 0) { // the mod's EntityAIMournCitizen: no work today; near the grave if the undertaker buried them
                c.setStatus("Mourning");
                if (job == null || job.leaveWork(c)) c.wander(mournPlace(c));
                continue;
            }
            if (job != null && raining && !job.worksInRain()) { // the mod's workers stay in out of the rain
                c.setStatus("Rain: off work");
                if (job.leaveWork(c)) c.wander(routine.homeOf(c).block.getLocation().toCenterLocation());
            } else if (job != null) {
                c.pace = walking * job.pace(); // only while working: an undertaker runs to a grave, not to bed or a meal
                job.tick(c);
            }
            else if (c.child && dev.minecp.job.Teacher.schoolOf(this, c) instanceof dev.minecp.job.Teacher school) school.pupilTick(c); // to school
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

    /** Queues a build without a hut for the builder. @return why it can't, or null when queued. */
    public String queueDecoration(Decoration d) {
        if (builder == null) return "Place a Builder's Hut first: the builder builds it.";
        decorations.add(d);
        return null;
    }

    /** Stops the build without a hut the builder is on, if it is on one. */
    public void cancelBuilding() {
        if (builder != null && builder.order != null && builder.work == null) builder.cancel();
        building = null;
    }

    private void startNextDecoration() {
        Decoration d = decorations.removeFirst();
        BuildOrder o = BuildOrder.create(d.structure(), d.origin(), d.rotation(), d.mirror(), null);
        if (o == null) { tell("Structure " + d.structure() + " not found; the " + d.name().toLowerCase() + " was skipped."); return; }
        builder.start(o, null);
        building = d;
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
        raids.despawn();
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

    /** A named grave of someone it mourns, else the town hall (the mod also stares at other citizens half the time). */
    private Location mournPlace(Colonist c) {
        for (Job j : jobs) if (j instanceof dev.minecp.job.Undertaker u)
            for (String dead : c.mourns) {
                Block grave = u.restingPlace(dead);
                if (grave != null) return grave.getLocation().toCenterLocation();
            }
        return townHall().block.getLocation().toCenterLocation();
    }

    /** The undertaker brought {@code g}'s citizen back: a citizen again where the grave was, mourned no more. */
    public void revive(Grave g) {
        Colonist c = new Colonist(UUID.randomUUID(), g.name, g.skin, g.block.getLocation().toCenterLocation());
        c.skills.roll((int) happiness.overall() * 2); // ours: rolled again, as a new citizen (the mod keeps them)
        for (Colonist o : colonists) { // no more mourning for them, today or tomorrow
            int i = o.mourns.indexOf(g.name);
            if (i < 0) continue;
            o.mourns.remove(i);
            if (i < o.mourning) o.mourning--;
        }
        join(c);
        tell(g.name + " was brought back to life by the undertaker!");
        milestones.resurrected();
    }

    public Grave graveAt(Block b) {
        for (Grave g : graves) if (g.block.equals(b)) return g;
        return null;
    }
}
