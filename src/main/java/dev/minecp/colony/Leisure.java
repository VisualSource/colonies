package dev.minecp.colony;

import dev.minecp.build.BuildOrder;
import dev.minecp.build.DecorationController;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free time, the mod's numbers unless noted: each citizen's leisure time (CitizenData.update) and what an idle citizen
 * does (EntityAICitizenWander): now and then it goes to a leisure site (RegisteredStructureManager.getRandomLeisureSite),
 * strolls and sits there, and at a library or university reads a book.
 */
public final class Leisure {
    static final int UPDATE = 60;               // CitizenData.update's tick rate (every 3 s)
    static final int TIME = 20 * 60 * 3;        // 3 minutes of it
    private static final int DECIDE = 100;      // the wander AI decides every 100 ticks
    private static final int CHANCE = 5;        // LEISURE_CHANCE: of 100, to go to a leisure site
    private static final int STEP = 20;         // the leisure states tick every 20 ticks
    private static final int REACH = 3;         // walkToPos's range
    private static final int SIT = 20 * 30;     // sitting or standing about: 30 s
    private static final double READ_INTELLIGENCE = 12.5; // EntityAIStudy.BASE_SKILL_INCREASE_CHANCE

    private final Colony colony;
    /** Decoration controllers whose build is a leisure site (the mod's "leisure" tag), as their builder finished them, and plantation field blocks. Saved. */
    public final Set<Block> sites = new LinkedHashSet<>();
    private final Map<Colonist, Visit> visits = new HashMap<>(); // not saved: a visit starts over
    private final Map<Block, Map.Entry<Object, java.util.Optional<BuildOrder>>> builds = new HashMap<>(); // each site's build as it stands, by level or structure

    /** A citizen at or on its way to a leisure site. */
    private static final class Visit {
        final Block site;
        Block walkTo, seat;
        boolean reading;
        int wait;
        long seen; // the colony tick it was last idle at

        Visit(Block site) { this.site = site; }
    }

    Leisure(Colony colony) {
        this.colony = colony;
    }

    /** Every 3 s: leisure time runs down, or now and then starts; a citizen in a better home gets it more often. */
    void update(Colonist c) {
        if (c.leisureTime > 0) { c.leisureTime -= UPDATE; return; }
        int home = c.home == null ? 1 : Math.max(1, c.home.level);
        if (ThreadLocalRandom.current().nextInt(20 * 60 * (int) (60 / (home / 2.0)) / UPDATE) <= 0) c.leisureTime = TIME;
    }

    /**
     * What an idle citizen does this tick: strolls around {@code around}, now and then (5% of its decisions) off to a
     * leisure site, where it stays until 1 time in 300 it has had enough. Children and guards only stroll.
     */
    void idle(Colonist c, Location around, long tick) {
        Visit v = visits.get(c);
        var rnd = ThreadLocalRandom.current();
        if (v == null) {
            if (!c.child && (tick + c.id.hashCode()) % DECIDE == 0 && !c.isMoving() && rnd.nextInt(100) < CHANCE) {
                Block site = pick();
                if (site == null) site = (c.home != null ? c.home : colony.townHall()).block;
                visits.put(c, v = new Visit(site));
            } else {
                c.wander(around);
                return;
            }
        }
        v.seen = tick;
        c.setStatus("Free time");
        if ((tick + c.id.hashCode()) % STEP != 0 || c.isMoving()) return;
        if (v.reading) { read(c, v); return; }
        if (!near(c, v.site)) { c.goBeside(v.site); return; }
        if (rnd.nextInt(60 * 5) < 1) { end(c, v); return; } // the mod's: 1 in 300 at each step
        if (v.wait > 0) { v.wait -= STEP; return; }
        if (v.walkTo != null) {
            boolean standing = !v.walkTo.equals(v.seat);
            if (standing ? !c.location().getBlock().equals(v.walkTo) && !near(c, v.walkTo, 1) : !near(c, v.walkTo)) {
                if (!standing) { c.goBeside(v.walkTo); return; } // beside a seat
                if (c.walk(v.walkTo.getLocation().add(0.5, 0, 0.5))) return; // onto a standing spot
                v.wait = SIT; // no way onto it: it stands about where it is
                v.walkTo = null;
                return;
            }
            if (v.walkTo.equals(v.seat) && free(v.seat, c)) c.sitOn(v.seat);
            v.wait = SIT;
            v.walkTo = null;
            return;
        }
        c.standUp();
        BuildOrder build = build(v.site);
        if (build == null) { c.walkRandom(v.site.getLocation().toCenterLocation(), 10); return; } // not built: around it
        Hut hut = colony.hutAt(v.site);
        if (rnd.nextInt(10) <= 0) { c.walkRandom(v.site.getLocation().toCenterLocation(), 10); v.wait = 30; return; }
        if (hut != null && (hut.type == Hut.Type.LIBRARY || hut.type == Hut.Type.UNIVERSITY) && rnd.nextInt(100) < 5) {
            List<Block> shelves = build.steps.stream().filter(s -> s.data().getMaterial() == Material.BOOKSHELF).map(BuildOrder.Step::block).toList();
            v.walkTo = shelves.isEmpty() ? v.site : shelves.get(rnd.nextInt(shelves.size()));
            v.reading = true;
            return;
        }
        // The mod's: in the rain or half the time an inside spot (a seat half of those times), else an outside one, else a plain seat.
        Map<String, List<Block>> spots = build.spots.isEmpty() && !build.seats.isEmpty() ? Map.of("sit", build.seats) : build.spots; // a pack converted before its spots were marked
        if (v.site.getWorld().hasStorm() || rnd.nextBoolean()) {
            if (spots.containsKey("sit_in") && rnd.nextBoolean()) { if (goSit(c, v, spots.get("sit_in"))) return; }
            else if (spots.containsKey("stand_in")) { goStand(v, spots.get("stand_in")); return; }
        }
        if (spots.containsKey("sit_out") && rnd.nextBoolean()) { if (goSit(c, v, spots.get("sit_out"))) return; }
        else if (spots.containsKey("stand_out")) { goStand(v, spots.get("stand_out")); return; }
        if (spots.containsKey("sit")) goSit(c, v, spots.get("sit"));
    }

    /** Off to one of these seats, if nobody sits there. @return whether it goes. */
    private boolean goSit(Colonist c, Visit v, List<Block> seats) {
        Block seat = seats.get(ThreadLocalRandom.current().nextInt(seats.size()));
        if (!free(seat, c)) return false;
        v.walkTo = v.seat = seat;
        return true;
    }

    private static void goStand(Visit v, List<Block> spots) {
        v.walkTo = spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
        v.seat = null;
    }

    /** At a bookshelf, a book in hand; 1 in 20 at each step it is done: a try at intelligence (the mod's readABook). */
    private void read(Colonist c, Visit v) {
        if (!near(c, v.walkTo)) { c.goBeside(v.walkTo); return; }
        if (ThreadLocalRandom.current().nextInt(100) < 5) {
            c.hold(null);
            dev.minecp.job.Student.tryIntelligence(colony, c, READ_INTELLIGENCE);
            end(c, v);
            return;
        }
        c.hold(new ItemStack(Material.BOOK));
    }

    private void end(Colonist c, Visit v) {
        visits.remove(c);
        if (v.seat != null && v.seat.equals(c.seat())) c.standUp();
        if (v.reading) c.hold(null);
    }

    /** Each tick: a citizen that wasn't idle this tick (back to work, to bed, to a meal) has left its leisure site. */
    void forget(long tick) {
        for (var e : List.copyOf(visits.entrySet())) if (e.getValue().seen != tick) end(e.getKey(), e.getValue());
    }

    private static boolean near(Colonist c, Block b) {
        return near(c, b, REACH);
    }

    private static boolean near(Colonist c, Block b, int within) {
        Location at = c.location();
        return at.getWorld() == b.getWorld() && at.distance(b.getLocation().toCenterLocation()) <= within + 0.5;
    }

    /** Nobody else sits there, citizen or visitor. */
    private boolean free(Block seat, Colonist me) {
        for (Colonist o : colony.colonists) if (o != me && seat.equals(o.seat())) return false;
        for (var v : colony.visitors.list) if (seat.equals(v.colonist.seat())) return false;
        return true;
    }

    /**
     * The mod's random leisure site: 1 in 4 the town hall from level 3; else 1 in 4 a mystical site (not in the rain),
     * library or university; else 1 in 4 a tavern (in the rain also when the town hall isn't built); in the rain the town
     * hall; else a leisure decoration. Null: none, it stays at home.
     */
    private Block pick() {
        var rnd = ThreadLocalRandom.current();
        boolean raining = colony.townHall().block.getWorld().hasStorm();
        Hut th = colony.townHall();
        int d = rnd.nextInt(4);
        if (d < 1 && th.level >= 3) return th.block;
        Hut h = null;
        if (d < 2) {
            if (!raining && rnd.nextBoolean()) h = random(Hut.Type.MYSTICAL_SITE);
            else h = random(rnd.nextBoolean() ? Hut.Type.LIBRARY : Hut.Type.UNIVERSITY);
        }
        if (h != null) return h.block;
        if (d < 3 || raining && th.level < 1) {
            h = random(Hut.Type.TAVERN);
            if (h != null) return h.block;
        }
        if (raining) return th.block;
        sites.removeIf(b -> b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && !DecorationController.is(b)
                && b.getType() != dev.minecp.job.Plantation.FIELD_BLOCK); // taken down
        return sites.isEmpty() ? null : List.copyOf(sites).get(rnd.nextInt(sites.size()));
    }

    private Hut random(Hut.Type type) {
        List<Hut> found = colony.huts.stream().filter(h -> h.type == type && h.level >= 1).toList();
        return found.isEmpty() ? null : found.get(ThreadLocalRandom.current().nextInt(found.size()));
    }

    /** The build standing at a site, for its seats and bookshelves: a hut's at its level, a decoration's from its controller. */
    private BuildOrder build(Block site) {
        Hut h = colony.hutAt(site);
        Object key = h != null ? (Object) h.level : DecorationController.at(site);
        if (key == null || h != null && h.level < 1) return null;
        var known = builds.get(site);
        if (known == null || !known.getKey().equals(key)) {
            BuildOrder o = h != null ? h.built(colony.style)
                    : DecorationController.at(site).order(site);
            builds.put(site, known = Map.entry(key, java.util.Optional.ofNullable(o)));
        }
        return known.getValue().orElse(null);
    }
}
