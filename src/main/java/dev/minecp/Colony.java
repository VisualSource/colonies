package dev.minecp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** A player's colony: a town hall block and its citizens. */
final class Colony {
    private static final int STARTING_CITIZENS = 4;
    private static final String[] FEMALE = {"Ada", "Cora", "Edda", "Greta", "Ivy", "Lena", "Nell", "Pia", "Rosa",
            "Tilda", "Vera", "Yara"};
    private static final String[] MALE = {"Bram", "Dell", "Finn", "Hal", "Jory", "Milo", "Otto", "Sven", "Ulric",
            "Wynn", "Kit", "Quill"};
    private static final String[] LAST = {"Ashdown", "Brook", "Carter", "Dale", "Fairweather", "Fletcher", "Hale",
            "Holt", "Miller", "Oakley", "Reed", "Thatcher", "Underhill", "Wells"};

    final UUID owner;
    final String name;
    final Location townHall; // block location
    final List<Colonist> colonists = new ArrayList<>();
    Builder builder; // null until a builder's hut is placed
    String style = "medievaloak"; // structure pack hut buildings come from

    Colony(UUID owner, String name, Location townHall) {
        this.owner = owner;
        this.name = name;
        this.townHall = townHall.toBlockLocation();
    }

    static Colony found(UUID owner, String name, Block townHall) {
        Colony colony = new Colony(owner, name, townHall.getLocation());
        var rnd = ThreadLocalRandom.current();
        Location spawn = townHall.getLocation().toCenterLocation();
        for (int i = 0; i < STARTING_CITIZENS; i++) {
            boolean female = rnd.nextBoolean();
            colony.colonists.add(new Colonist(UUID.randomUUID(), colony.uniqueName(female), Colonist.randomSkin(female), spawn));
        }
        return colony;
    }

    private String uniqueName(boolean female) {
        var rnd = ThreadLocalRandom.current();
        String[] first = female ? FEMALE : MALE;
        while (true) { // 144 combinations per list; colonies are far smaller
            String name = first[rnd.nextInt(first.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            if (colonists.stream().noneMatch(c -> c.name.equals(name))) return name;
        }
    }

    boolean isTownHall(Block block) {
        return block.getLocation().equals(townHall);
    }

    boolean isBuilderHut(Block block) {
        return builder != null && builder.hut.equals(block);
    }

    private Builder newBuilder(Block hut) {
        Builder b = new Builder(hut);
        b.onFinish(this::tell);
        b.protect = block -> block.equals(hut) || isTownHall(block);
        return b;
    }

    void addBuilderHut(Block hut) {
        builder = newBuilder(hut);
        colonists.stream().findFirst().ifPresent(c -> builder.worker = c.id); // everyone else is unemployed
    }

    void removeBuilderHut() {
        builder.dropCarried();
        builder = null;
    }

    void tell(String message) {
        Player p = Bukkit.getPlayer(owner);
        if (p != null) p.sendMessage(message);
    }

    void tick() {
        Location center = townHall.toCenterLocation();
        for (Colonist c : colonists) {
            if (!c.tick()) continue;
            if (builder != null && c.id.equals(builder.worker)) builder.tick(c);
            else {
                c.setStatus("Unemployed");
                c.wander(center);
            }
        }
    }

    void despawn() {
        colonists.forEach(Colonist::despawn);
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("style", style);
        putLocation(s, "townhall", townHall);
        if (builder != null) {
            var bs = s.createSection("builder");
            putLocation(bs, "hut", builder.hut.getLocation());
            bs.set("level", builder.level);
            bs.set("upgrading-to", builder.upgradingTo);
            if (builder.worker != null) bs.set("worker", builder.worker.toString());
            builder.carried.forEach((m, n) -> bs.set("carried." + m.name(), n));
            if (builder.order != null) {
                bs.set("order.structure", builder.order.structure);
                bs.set("order.rotation", builder.order.rotation.name());
                putLocation(bs, "order.origin", builder.order.origin);
            }
        }
        for (Colonist c : colonists) {
            var cs = s.createSection("citizens." + c.id);
            cs.set("name", c.name);
            cs.set("skin", c.skin);
            putLocation(cs, "location", c.location());
        }
    }

    /** @return null if the colony's world is not loaded. */
    static Colony load(UUID owner, ConfigurationSection s) {
        Location townHall = getLocation(s, "townhall");
        if (townHall == null) return null;
        Colony colony = new Colony(owner, s.getString("name"), townHall);
        colony.style = s.getString("style", colony.style);
        var citizens = s.getConfigurationSection("citizens");
        if (citizens != null) for (String id : citizens.getKeys(false)) {
            var cs = citizens.getConfigurationSection(id);
            Location at = getLocation(cs, "location");
            if (at == null) at = townHall.toCenterLocation();
            colony.colonists.add(new Colonist(UUID.fromString(id), cs.getString("name"), cs.getInt("skin"), at));
        }
        var bs = s.getConfigurationSection("builder");
        Location hut = bs == null ? null : getLocation(bs, "hut");
        if (hut != null) {
            colony.builder = colony.newBuilder(hut.getBlock());
            colony.builder.level = bs.getInt("level");
            String worker = bs.getString("worker");
            if (worker != null) colony.builder.worker = UUID.fromString(worker);
            var carried = bs.getConfigurationSection("carried");
            if (carried != null) for (String m : carried.getKeys(false)) {
                Material mat = Material.matchMaterial(m);
                if (mat != null) colony.builder.carried.put(mat, carried.getInt(m));
            }
            Location origin = getLocation(bs, "order.origin");
            if (origin != null) {
                String structure = bs.getString("order.structure");
                BuildOrder order = BuildOrder.create(structure, origin,
                        StructureRotation.valueOf(bs.getString("order.rotation", "NONE")), null); // origin already placed
                if (order != null) colony.builder.start(order, bs.getInt("upgrading-to"));
                else
                    Bukkit.getLogger().warning("[MineCP] Structure " + structure + " not found; build order of " + colony.name + " dropped.");
            }
        }
        return colony;
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
