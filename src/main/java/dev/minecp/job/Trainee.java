package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A guard in training, who lives where it trains (the mod's AbstractEntityAITraining and WorkAtHomeBuildingModule): as
 * many as the hut's level. With its gear on hand it trains 3 times in 10, else wanders for 10 s. Guard huts hire the
 * best trainee of their kind first (Colony.assignJobs).
 */
abstract class Trainee extends Job {
    private static final int DECIDE_TICKS = 20, WANDER_TICKS = 200, TRAIN_PERCENT = 30; // TARGET_SEARCH_CHANCE
    static final int PER_LEVEL = 5; // BUILDING_LEVEL_TARGET_MULTIPLIER, ACTIONS_PER_BUILDING_LEVEL: a session's length a hut level
    static final int REQUEST_DELAY = 60; // ticks before it looks for missing gear again
    static final int WALK_TRIES = 5; // ours: a stand or dummy it can't get to is given up

    boolean training; // in a session
    private int wandering, spotsLevel = -1;
    private UUID trainee; // whose session it is

    Trainee(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Whether it holds its training gear, fetching what its chest has. */
    abstract boolean ready(Colonist w);

    /** One step of the session; ends it by clearing {@link #training}. */
    abstract void train(Colonist w);

    /** Forgets the session: another citizen has the job now. */
    abstract void reset();

    /** A session starts. */
    void begin() {}

    /** Remembers where one trains in this building (null: it has none). */
    abstract void findSpots(BuildOrder o);

    /** What its building lacks to train, for the hut menu, or null. */
    abstract String lacks();

    /** Whether it must train rather than wander (a knight with a partner). */
    boolean committed() {
        return false;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus(title() + ": idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (!w.id.equals(trainee)) {
            trainee = w.id;
            training = false;
            wandering = 0;
            reset();
        }
        if (wandering > 0) {
            wandering--;
            w.setStatus(title() + ": resting");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        cooldown = DECIDE_TICKS;
        if (!ready(w)) { training = false; return; }
        if (spotsLevel != hut.level) {
            spotsLevel = hut.level;
            findSpots(hut.built(colony.style));
        }
        note = lacks();
        if (!training) {
            if (!committed() && ThreadLocalRandom.current().nextInt(100) >= TRAIN_PERCENT) { wandering = WANDER_TICKS; return; }
            training = true;
            begin();
        }
        w.setStatus(title() + ": training");
        train(w);
    }

    static List<Block> blocks(BuildOrder o, Material m) {
        return o.steps.stream().filter(s -> s.data().getMaterial() == m).map(BuildOrder.Step::block).toList();
    }

    static Block random(List<Block> spots) {
        return spots.isEmpty() ? null : spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
    }

    /** Whether it holds a tool of this kind, fetching it from the hut chest. */
    boolean equipped(Colonist w, Tool kind) {
        if (hasTool(kind, null)) return true;
        if (inChest(i -> fits(kind, i, null)) == 0) { idle(w, "Needs " + toolLabel(kind) + " in the hut chest to train.", REQUEST_DELAY, hut.block); return false; }
        if (!atHut(w)) { goToHut(w); return false; }
        return fetchTool(kind, null);
    }
}
