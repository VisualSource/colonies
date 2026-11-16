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
    private static final int STARTING_CITIZENS = 4;
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
    Builder builder; // the builder's job (also in jobs); null until a Builder's Hut is placed
    String style = "medievaloak"; // structure pack hut buildings come from
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

    void addHut(Hut.Type type, Block block) {
        Hut hut = new Hut(type, block, 0);
        huts.add(hut);
        addJob(hut);
        assignJobs();
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
        for (Colonist c : colonists) {
            if (!c.tick()) continue;
            Job job = jobOf(c);
            hunger(c, job);
            if (c.meal != null) { // eating comes before work
                if (c.meal.tick(this, job)) {
                    c.hungerNote = c.meal.why;
                    if (c.meal.why != null) c.foodRetry = FOOD_RETRY_TICKS;
                    c.meal = null;
                }
                continue;
            }
            if (job != null) job.tick(c);
            else {
                c.setStatus("Unemployed");
                c.wander((c.home != null ? c.home : townHall()).block.getLocation().toCenterLocation());
            }
        }
    }

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
        // A hut building is anchored on its hut block and turned to match the block's facing.
        BuildOrder o = BuildOrder.create(structure, w.hut().block.getLocation(), StructureRotation.NONE, w.hut().block);
        if (o == null) tell("Structure " + structure + " not found; upgrade skipped.");
        else builder.start(o, w);
    }

    void despawn() {
        for (Colonist c : colonists) if (c.meal != null) { c.meal.abandon(); c.meal = null; } // food back in its chest
        colonists.forEach(Colonist::despawn);
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("style", style);
        for (int i = 0; i < huts.size(); i++) {
            Hut h = huts.get(i);
            var hs = s.createSection("huts." + i);
            hs.set("type", h.type.name());
            hs.set("level", h.level);
            putLocation(hs, "block", h.block.getLocation());
            if (h.type == Hut.Type.FIELD) {
                if (h.seed != null) hs.set("seed", h.seed.name());
                hs.set("extent", java.util.Arrays.stream(h.extent).boxed().toList());
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
            h.seed = Material.matchMaterial(hs.getString("seed", ""));
            List<Integer> extent = hs.getIntegerList("extent");
            for (int k = 0; k < Math.min(4, extent.size()); k++) h.extent[k] = extent.get(k);
            huts.add(h);
        }
        Colony colony = new Colony(owner, s.getString("name"), huts.getFirst().block, huts.getFirst().level);
        colony.huts.addAll(huts.subList(1, huts.size()));
        colony.style = s.getString("style", colony.style);

        var citizens = s.getConfigurationSection("citizens");
        if (citizens != null) for (String id : citizens.getKeys(false)) {
            var cs = citizens.getConfigurationSection(id);
            Location at = getLocation(cs, "location");
            if (at == null) at = colony.townHall().block.getLocation().toCenterLocation();
            Colonist c = new Colonist(UUID.fromString(id), cs.getString("name"), cs.getInt("skin"), at);
            if (cs.isInt("home")) c.home = colony.hutByIndex(cs.getInt("home"));
            c.saturation = cs.getDouble("saturation", Food.MAX_SATURATION);
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
                        StructureRotation.valueOf(bs.getString("order.rotation", "NONE")), null); // origin already placed
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
