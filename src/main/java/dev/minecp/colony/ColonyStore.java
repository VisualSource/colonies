package dev.minecp.colony;

import dev.minecp.citizen.Disease;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** A colony in its save file, and back. Each job saves its own state (Job.save). */
public final class ColonyStore {
    private ColonyStore() {}

    /** A job's section: its hut's index, and which of the hut's jobs it is (a barracks tower has several). */
    private static String jobKey(Colony colony, Job j) {
        return "jobs." + colony.huts.indexOf(j.hut) + "_" + colony.jobsAt(j.hut).indexOf(j);
    }

    public static void save(Colony colony, ConfigurationSection s) {
        s.set("name", colony.name);
        s.set("style", colony.style);
        s.set("maps", List.of(colony.mapIds[0], colony.mapIds[1]));
        colony.flag.save(s.createSection("flag"));
        s.set("day", colony.day);
        s.set("founded", colony.founded);
        colony.permissions.save(s.createSection("permissions"));
        for (int i = 0; i < colony.graves.size(); i++) {
            Grave g = colony.graves.get(i);
            var gs = s.createSection("graves." + i);
            putLocation(gs, "block", g.block.getLocation());
            gs.set("name", g.name);
            gs.set("job", g.job);
            gs.set("skin", g.skin);
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
            if (h.blueprint != null) hs.set("blueprint", h.blueprint);
            if (h.barracks != null) hs.set("barracks", colony.huts.indexOf(h.barracks));
            if (h.hiring != Settings.Hiring.DEFAULT) hs.set("hiring", h.hiring.name());
            h.settings.forEach((k, v) -> hs.set("settings." + k, v));
            if (h.post != null) h.post.save(hs.createSection("post"));
            if (h.dugOut) hs.set("dug-out", true);
            if (h.type == Hut.Type.FIELD) {
                if (h.seed != null) hs.set("seed", h.seed.name());
                hs.set("extent", java.util.Arrays.stream(h.extent).boxed().toList());
                hs.set("stage", h.stage);
                hs.set("worked-day", h.workedDay);
            }
        }
        for (int i = 0; i < colony.decorations.size(); i++) putDecoration(s.createSection("decorations." + i), colony.decorations.get(i));
        if (colony.building != null) putDecoration(s.createSection("building"), colony.building);
        for (int i = 0; i < colony.workOrders.size(); i++) {
            s.set("work-orders." + i + ".hut", colony.huts.indexOf(colony.workOrders.get(i).hut()));
            s.set("work-orders." + i + ".level", colony.workOrders.get(i).level());
            if (colony.workOrders.get(i).remove()) s.set("work-orders." + i + ".remove", true);
        }
        for (Job j : colony.jobs) j.save(s.createSection(jobKey(colony, j)), colony);
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
            if (c.disease != null) cs.set("disease", c.disease.name());
            cs.set("immunity", c.immunity);
            cs.set("sick-days", c.sickDays);
            cs.set("skills", c.skills.save());
            var inv = c.inventory();
            if (inv != null) {
                cs.set("inventory-size", inv.getSize());
                List<String> items = new ArrayList<>();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack it = inv.getItem(i);
                    if (it != null && !it.isEmpty()) items.add(i + ":" + Base64.getEncoder().encodeToString(it.serializeAsBytes()));
                }
                cs.set("inventory", items);
            }
            if (!c.mourns.isEmpty()) cs.set("mourns", c.mourns);
            if (c.mourning > 0) cs.set("mourning", c.mourning);
            if (c.leisureTime > 0) cs.set("leisure", c.leisureTime);
        }
        s.set("child-pool", colony.childPool);
        colony.visitors.save(s.createSection("visitors"));
        colony.raids.save(s.createSection("raids"));
        if (colony.mercenaries.hiredAt != 0) s.set("mercenaries-hired", colony.mercenaries.hiredAt);
        s.set("free-blocks", colony.freeBlocks.stream().map(Material::name).toList());
        s.set("free-positions", colony.freePositions.stream().map(ColonyStore::spot).toList());
        s.set("waypoints", colony.waypoints.stream().map(ColonyStore::spot).toList());
        for (int i = 0; i < colony.plantFields.size(); i++) {
            Colony.PlantField f = colony.plantFields.get(i);
            s.set("plant-fields." + i + ".block", spot(f.block()));
            s.set("plant-fields." + i + ".kind", f.kind().name());
            s.set("plant-fields." + i + ".plots", f.plots().stream().map(ColonyStore::spot).toList());
        }
        if (!colony.leisure.sites.isEmpty()) s.set("leisure-sites", colony.leisure.sites.stream().map(ColonyStore::spot).toList());
        if (!colony.claimsAdded.isEmpty()) s.set("claims-added", List.copyOf(colony.claimsAdded));
        if (!colony.claimsRemoved.isEmpty()) s.set("claims-removed", List.copyOf(colony.claimsRemoved));
        colony.connections.save(s.createSection("connections"));
        colony.research.save(s.createSection("research"));
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
            h.blueprint = hs.getString("blueprint");
            h.hiring = Settings.Hiring.valueOf(hs.getString("hiring", "DEFAULT"));
            var set = hs.getConfigurationSection("settings");
            if (set != null) for (String k : set.getKeys(false)) h.settings.put(k, set.getString(k));
            if (h.post != null && hs.isConfigurationSection("post")) h.post.load(hs.getConfigurationSection("post"));
            h.seed = Material.matchMaterial(hs.getString("seed", ""));
            List<Integer> extent = hs.getIntegerList("extent");
            for (int k = 0; k < Math.min(4, extent.size()); k++) h.extent[k] = extent.get(k);
            h.stage = hs.getInt("stage");
            h.workedDay = hs.getInt("worked-day", -1);
            h.dugOut = hs.getBoolean("dug-out");
            huts.add(h);
        }
        Colony colony = new Colony(owner, s.getString("name"), huts.getFirst().block, huts.getFirst().level);
        colony.huts.addAll(huts.subList(1, huts.size()));
        for (String i : hutSections.getKeys(false)) { // a barracks tower's barracks, by its index
            int b = hutSections.getInt(i + ".barracks", -1);
            Hut tower = colony.hutByIndex(Integer.parseInt(i));
            if (b >= 0 && tower != null) tower.barracks = colony.hutByIndex(b);
        }
        colony.style = s.getString("style", colony.style);
        List<Integer> maps = s.getIntegerList("maps");
        for (int i = 0; i < Math.min(maps.size(), colony.mapIds.length); i++) colony.mapIds[i] = maps.get(i);
        if (s.isConfigurationSection("flag")) colony.flag.load(s.getConfigurationSection("flag"));
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
            Grave g = new Grave(at.getBlock(), gs.getString("name"), gs.getString("job"), gs.getInt("skin"), items);
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
            String disease = cs.getString("disease");
            if (disease != null) c.disease = Disease.valueOf(disease);
            c.immunity = cs.getInt("immunity");
            c.sickDays = cs.getInt("sick-days");
            c.skills.load(cs.getStringList("skills"));
            if (cs.contains("inventory-size")) {
                var inv = c.inventory(cs.getInt("inventory-size"));
                for (String it : cs.getStringList("inventory")) {
                    int colon = it.indexOf(':');
                    inv.setItem(Integer.parseInt(it.substring(0, colon)), ItemStack.deserializeBytes(Base64.getDecoder().decode(it.substring(colon + 1))));
                }
            }
            c.mourns.addAll(cs.getStringList("mourns"));
            c.mourning = Math.min(cs.getInt("mourning"), c.mourns.size());
            c.leisureTime = cs.getInt("leisure");
            colony.colonists.add(c);
        }
        colony.childPool = s.getInt("child-pool");

        var orders = s.getConfigurationSection("work-orders");
        if (orders != null) for (String i : orders.getKeys(false)) {
            Hut h = colony.hutByIndex(orders.getInt(i + ".hut", -1));
            if (h != null) colony.workOrders.add(new Hut.WorkOrder(h, orders.getInt(i + ".level"), orders.getBoolean(i + ".remove")));
        }
        var decos = s.getConfigurationSection("decorations");
        if (decos != null) for (String i : decos.getKeys(false)) {
            var d = getDecoration(decos.getConfigurationSection(i));
            if (d != null) colony.decorations.add(d);
        }
        if (s.isConfigurationSection("building")) colony.building = getDecoration(s.getConfigurationSection("building"));

        // Research before jobs: a job's load can read it (the builder's mode).
        if (s.isConfigurationSection("research")) colony.research.load(s.getConfigurationSection("research"));
        for (Hut h : colony.huts) colony.addJob(h);
        for (Job j : colony.jobs) {
            var js = s.getConfigurationSection(jobKey(colony, j));
            if (js != null) j.load(js, colony);
        }
        // The builder carries on with the build it saved; without one (or its order), the build waits in the queue again.
        if (colony.building != null && (colony.builder == null || colony.builder.order == null || colony.builder.work != null)) {
            colony.decorations.addFirst(colony.building);
            colony.building = null;
        }
        if (s.isConfigurationSection("requests")) colony.requests.load(s.getConfigurationSection("requests"));
        if (s.isConfigurationSection("visitors")) colony.visitors.load(s.getConfigurationSection("visitors"));
        if (s.isConfigurationSection("raids")) colony.raids.load(s.getConfigurationSection("raids"));
        colony.mercenaries.hiredAt = s.getLong("mercenaries-hired");
        for (String m : s.getStringList("free-blocks")) { Material x = Material.matchMaterial(m); if (x != null) colony.freeBlocks.add(x); }
        for (String p : s.getStringList("free-positions")) if (spotAt(p) instanceof org.bukkit.block.Block b) colony.freePositions.add(b);
        for (String p : s.getStringList("waypoints")) if (spotAt(p) instanceof org.bukkit.block.Block b) colony.waypoints.add(b);
        var pf = s.getConfigurationSection("plant-fields");
        if (pf != null) for (String i : pf.getKeys(false)) {
            List<org.bukkit.block.Block> plots = new ArrayList<>();
            for (String p : pf.getStringList(i + ".plots")) if (spotAt(p) instanceof org.bukkit.block.Block b) plots.add(b);
            if (spotAt(pf.getString(i + ".block", "")) instanceof org.bukkit.block.Block b && !plots.isEmpty())
                colony.plantFields.add(new Colony.PlantField(b, dev.minecp.job.Plantation.Kind.valueOf(pf.getString(i + ".kind")), List.copyOf(plots)));
        }
        for (String p : s.getStringList("leisure-sites")) if (spotAt(p) instanceof org.bukkit.block.Block b) colony.leisure.sites.add(b);
        colony.claimsAdded.addAll(s.getLongList("claims-added"));
        colony.claimsRemoved.addAll(s.getLongList("claims-removed"));
        if (s.isConfigurationSection("connections")) colony.connections.load(s.getConfigurationSection("connections"));
        colony.assignHomes();
        colony.assignJobs();
        return colony;
    }


    /** A block as "world;x;y;z", for saves and items. */
    public static String spot(org.bukkit.block.Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    /** The block of a {@link #spot}, or null: missing, its world not loaded, or not a spot at all. */
    public static org.bukkit.block.Block spotAt(String s) {
        String[] p = s == null ? new String[0] : s.split(";");
        World w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
        try {
            return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static void putDecoration(ConfigurationSection s, Colony.Decoration d) {
        s.set("structure", d.structure());
        s.set("name", d.name());
        putLocation(s, "origin", d.origin());
        s.set("rotation", d.rotation().name());
        s.set("mirror", d.mirror());
    }

    private static Colony.Decoration getDecoration(ConfigurationSection s) {
        var at = getLocation(s, "origin");
        return at == null ? null : new Colony.Decoration(s.getString("structure"), s.getString("name", "build"), at,
                org.bukkit.block.structure.StructureRotation.valueOf(s.getString("rotation", "NONE")), s.getBoolean("mirror"));
    }
}
