package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.job.Job;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A colony in its save file, and back. Each job saves its own state (Job.save). */
public final class ColonyStore {
    private ColonyStore() {}

    public static void save(Colony colony, ConfigurationSection s) {
        s.set("name", colony.name);
        s.set("style", colony.style);
        s.set("day", colony.day);
        s.set("founded", colony.founded);
        colony.permissions.save(s.createSection("permissions"));
        for (int i = 0; i < colony.graves.size(); i++) {
            Grave g = colony.graves.get(i);
            var gs = s.createSection("graves." + i);
            putLocation(gs, "block", g.block.getLocation());
            gs.set("name", g.name);
            gs.set("job", g.job);
            gs.set("timer", g.timer);
            gs.set("decayed", g.decayed);
            gs.set("items", java.util.Arrays.stream(g.items.getContents()).filter(java.util.Objects::nonNull).toList());
        }
        for (int i = 0; i < colony.huts.size(); i++) {
            Hut h = colony.huts.get(i);
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
        for (int i = 0; i < colony.workOrders.size(); i++) {
            s.set("work-orders." + i + ".hut", colony.huts.indexOf(colony.workOrders.get(i).hut()));
            s.set("work-orders." + i + ".level", colony.workOrders.get(i).level());
            if (colony.workOrders.get(i).remove()) s.set("work-orders." + i + ".remove", true);
        }
        for (Job j : colony.jobs) j.save(s.createSection("jobs." + colony.huts.indexOf(j.hut)), colony);
        colony.requests.save(s.createSection("requests")); // crafters keep the requests they took
        for (Colonist c : colony.colonists) {
            var cs = s.createSection("citizens." + c.id);
            cs.set("name", c.name);
            cs.set("skin", c.skin);
            putLocation(cs, "location", c.location());
            if (c.home != null) cs.set("home", colony.huts.indexOf(c.home));
            cs.set("saturation", c.saturation);
            cs.set("health", c.health);
            if (c.child) cs.set("child-time", c.childTime);
            cs.set("diet", c.diet.stream().map(Material::name).toList());
            cs.set("mood-days", List.of(c.homelessDays, c.joblessDays, c.sleeplessDays, c.hurtDays, c.griefDays, c.defendedDays));
        }
        s.set("child-pool", colony.childPool);
        colony.visitors.save(s.createSection("visitors"));
        colony.raids.save(s.createSection("raids"));
    }

    /** @return null if the colony's world is not loaded. */
    public static Colony load(UUID owner, ConfigurationSection s) {
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
        colony.founded = s.getLong("founded");
        if (s.isConfigurationSection("permissions")) colony.permissions.load(s.getConfigurationSection("permissions"));
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
            if (cs.contains("child-time")) {
                c.setChild(true);
                c.childTime = cs.getInt("child-time");
            }
            for (String food : cs.getStringList("diet")) {
                Material m = Material.matchMaterial(food);
                if (m != null) c.ate(m);
            }
            List<Integer> days = cs.getIntegerList("mood-days");
            if (days.size() == 6) {
                c.homelessDays = days.get(0);
                c.joblessDays = days.get(1);
                c.sleeplessDays = days.get(2);
                c.hurtDays = days.get(3);
                c.griefDays = days.get(4);
                c.defendedDays = days.get(5);
            }
            colony.colonists.add(c);
        }
        colony.childPool = s.getInt("child-pool");

        var orders = s.getConfigurationSection("work-orders");
        if (orders != null) for (String i : orders.getKeys(false)) {
            Hut h = colony.hutByIndex(orders.getInt(i + ".hut", -1));
            if (h != null) colony.workOrders.add(new Hut.WorkOrder(h, orders.getInt(i + ".level"), orders.getBoolean(i + ".remove")));
        }

        for (Hut h : colony.huts) colony.addJob(h);
        for (Job j : colony.jobs) {
            var js = s.getConfigurationSection("jobs." + colony.huts.indexOf(j.hut));
            if (js != null) j.load(js, colony);
        }
        if (s.isConfigurationSection("requests")) colony.requests.load(s.getConfigurationSection("requests"));
        if (s.isConfigurationSection("visitors")) colony.visitors.load(s.getConfigurationSection("visitors"));
        if (s.isConfigurationSection("raids")) colony.raids.load(s.getConfigurationSection("raids"));
        colony.assignHomes();
        colony.assignJobs();
        return colony;
    }


    // Plain fields, not Bukkit's Location serialization: that one drops the value at file load when the world is missing.
    public static void putLocation(ConfigurationSection s, String key, Location l) {
        var ls = s.createSection(key);
        ls.set("world", l.getWorld().getName());
        ls.set("x", l.getX());
        ls.set("y", l.getY());
        ls.set("z", l.getZ());
    }

    public static Location getLocation(ConfigurationSection s, String key) {
        var ls = s.getConfigurationSection(key);
        World world = ls == null ? null : Bukkit.getWorld(ls.getString("world", ""));
        return world == null ? null : new Location(world, ls.getDouble("x"), ls.getDouble("y"), ls.getDouble("z"));
    }
}
