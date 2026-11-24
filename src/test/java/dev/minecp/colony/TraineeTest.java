package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.job.ArcherTrainee;
import dev.minecp.job.Job;
import dev.minecp.job.KnightTrainee;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Trainees live at work. Runs without a server, so without guards. */
class TraineeTest {
    private final Colony colony = new Colony(UUID.randomUUID(), "Test", null, 1);

    private Hut hut(Hut.Type type, int level) {
        Hut h = new Hut(type, null, level);
        colony.huts.add(h);
        colony.addJob(h);
        return h;
    }

    private Colonist citizen(String name, int agility) {
        Colonist c = new Colonist(UUID.randomUUID(), name, 0, new Location(null, 0, 0, 0));
        c.skills.load(List.of("AGILITY:" + agility + ":0"));
        colony.colonists.add(c);
        return c;
    }

    @Test
    void traineesLiveWhereTheyTrain() {
        Hut house = hut(Hut.Type.HOUSE, 1), archery = hut(Hut.Type.ARCHERY, 2);
        Colonist low = citizen("Low", 3), best = citizen("Best", 9), idle = citizen("Idle", 50);
        colony.assignJobs();
        assertSame(archery, low.home);
        assertSame(archery, best.home);
        assertSame(house, idle.home, "the house's bed went to the jobless");
        assertEquals(3, colony.capacity(), "a house bed and two trainees");

        assertSame(best.id, colony.mostSkilled(j -> j instanceof ArcherTrainee, Skill.AGILITY).worker, "the best trainee, not the better jobless");
        assertNull(colony.mostSkilled(j -> j instanceof KnightTrainee, Skill.AGILITY), "no archer is a knight's");

        colony.moveInto(house, low);
        assertSame(archery, low.home, "a trainee can't be moved out of its hut");

        for (Job j : colony.jobsAt(archery)) j.worker = null;
        colony.assignHomes();
        assertNull(low.home);
        assertNull(best.home);
        assertSame(house, idle.home);
    }
}
