package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.citizen.Meal;
import dev.minecp.job.Job;
import dev.minecp.job.Miner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A colony's day and night and its citizens' hunger (the mod's CitizenAI, EntityAISleep and CitizenSleepHandler). */
final class Routine {
    static final int NIGHT = 12600;                  // day time from which beds can be used
    static final int FOOD_RETRY_TICKS = 20 * 60 * 5; // after finding no food, look again in 5 minutes (the mod's wait)
    private static final double TICKS_PER_BLOCK = 6; // the mod's estimate of walking time home
    private static final double Y_WEIGHT = 1.5;      // climbing counts for more
    private static final int BED_RANGE = 8; // shortcut: beds are looked for near the hut block, not by the building's own outline
    private static final int BED_RESCAN_TICKS = 200;
    private static final int HUNGER_TICKS = 20 * 60; // saturation drops once a minute (as in the mod)

    private final Colony colony;
    private boolean isDay = true, started;

    Routine(Colony colony) {
        this.colony = colony;
    }

    /** Counts a new colony day each morning, and a happiness day at nightfall, as in the mod. Called every tick with the world's day time. */
    void clock(long time) {
        boolean dayTime = time <= NIGHT;
        if (!started) { started = true; isDay = dayTime; } // a server started at night hasn't just seen nightfall
        if (dayTime && !isDay) colony.day++;
        if (!dayTime && isDay) colony.happiness.dayEnd();
        isDay = dayTime;
    }

    /** The house it sleeps in, or for the homeless the town hall (the mod: a tavern, else the town hall). */
    Hut homeOf(Colonist c) {
        return c.home != null ? c.home : colony.townHall();
    }

    /** Whether it must leave now to be home by nightfall, judging by how far it is (the mod's rule). */
    boolean shouldGoSleep(Colonist c, Job job, long time) {
        if (job != null && !job.sleepsAtNight()) return false;
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
    void goToBed(Colonist c) {
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
                if (b.getBlockData() instanceof Bed bd && bd.getPart() == Bed.Part.HEAD) beds.add(b);
            }
            beds.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(hut.getLocation())));
            house.beds = beds;
            house.bedsAt = now;
        }
        int index = colony.residents(house).indexOf(c);
        Block bed = index < house.beds.size() ? house.beds.get(index) : null;
        return bed != null && bed.getBlockData() instanceof Bed ? bed : null; // broken since the scan
    }

    /**
     * Saturation drops once a minute in daytime, at the mod's rate: more for citizens of better houses, times the
     * job's factor, plus up to half again for the work they do. Hungry citizens go to eat; starving ones are slow.
     */
    void hunger(Colonist c, Job job) {
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
}
