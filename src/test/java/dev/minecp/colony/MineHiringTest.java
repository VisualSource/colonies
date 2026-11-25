package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.job.Job;
import dev.minecp.job.Miner;
import dev.minecp.job.Quarrier;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Miner's Hut hires a miner or a quarrier, not both (the mod's). Runs without a server. */
class MineHiringTest {
    @Test
    void aMineHasOneWorkerMinerOrQuarrier() {
        Colony colony = new Colony(UUID.randomUUID(), "Test", null, 1);
        Hut mine = new Hut(Hut.Type.MINER, null, 1);
        colony.huts.add(mine);
        colony.addJob(mine);
        Job miner = colony.jobsAt(mine).stream().filter(j -> j instanceof Miner).findFirst().orElseThrow();
        Job quarrier = colony.jobsAt(mine).stream().filter(j -> j instanceof Quarrier).findFirst().orElseThrow();
        assertEquals(2, colony.jobsAt(mine).size(), "a miner's job and a quarrier's");
        assertTrue(colony.canHire(miner) && colony.canHire(quarrier), "nobody hired yet: either");

        Colonist bob = new Colonist(UUID.randomUUID(), "Bob", 0, new Location(null, 0, 0, 0));
        colony.colonists.add(bob);
        miner.worker = bob.id;
        assertFalse(colony.canHire(quarrier), "with a miner, no quarrier");
        assertTrue(colony.canHire(miner), "the miner's own job stays open to it");

        miner.worker = null;
        quarrier.worker = bob.id;
        assertFalse(colony.canHire(miner), "with a quarrier, no miner");
    }
}
