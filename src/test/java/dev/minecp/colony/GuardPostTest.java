package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.job.Job;
import dev.minecp.job.KnightTrainee;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A guard hut's shared patrol. Runs without a server, with stand-in blocks. */
class GuardPostTest {
    private final Colony colony = new Colony(UUID.randomUUID(), "Test", null, 1);
    private final Hut tower = new Hut(Hut.Type.GUARD_TOWER, block("tower"), 1);

    /** A citizen who works at the tower (as a trainee: a guard needs a server). */
    private Colonist guard() {
        Colonist c = new Colonist(UUID.randomUUID(), "Guard", 0, new Location(null, 0, 0, 0));
        Job j = new KnightTrainee(tower, colony);
        j.worker = c.id;
        colony.colonists.add(c);
        colony.jobs.add(j);
        return c;
    }

    private static Block block(String name) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> name.hashCode();
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void manualPatrolGoesRoundTogether() {
        GuardPost post = tower.post;
        tower.set(Settings.PATROL_MODE, "MANUAL");
        Block a = block("a"), b = block("b"), raid = block("raid");
        post.addPatrol(a, true);
        post.addPatrol(b, false);
        assertSame(tower.block, post.point(), "their hut first");
        post.alert(raid);
        Colonist one = guard();
        post.arrived(one, colony);
        assertSame(raid, post.point(), "then an alert");
        post.arrived(one, colony);
        assertSame(a, post.point(), "then the first patrol point");

        Colonist two = guard();
        post.arrived(one, colony);
        assertSame(a, post.point(), "it waits for the other");
        post.arrived(one, colony);
        assertSame(a, post.point(), "the same guard twice is still one");
        post.arrived(two, colony);
        assertSame(b, post.point());
        post.arrived(one, colony);
        post.arrived(two, colony);
        assertSame(a, post.point(), "and round again");

        Block c = block("c");
        post.addPatrol(c, true);
        post.arrived(one, colony);
        post.arrived(two, colony);
        assertSame(c, post.point(), "a new round replaces the old one");
    }

    @Test
    void patrolGoesOnWithoutTheLate() {
        GuardPost post = tower.post;
        tower.set(Settings.PATROL_MODE, "MANUAL");
        Block a = block("a"), b = block("b");
        post.addPatrol(a, true);
        post.addPatrol(b, false);
        Colonist one = guard();
        guard();
        post.arrived(one, colony);
        assertSame(tower.block, post.point(), "it waits for the other");
        post.colonyTick(colony);
        assertSame(a, post.point(), "a colony tick later it goes on alone");
        for (int i = 0; i < 4; i++) post.colonyTick(colony);
        assertSame(a, post.point(), "nobody there yet");
        post.colonyTick(colony);
        assertSame(b, post.point(), "given up after five");

        tower.set(Settings.GUARD_TASK, "GUARD");
        for (int i = 0; i < 9; i++) post.colonyTick(colony);
        assertSame(b, post.point(), "only on patrol");
    }

    @Test
    void aRemovedMineEndsOnlyItsPatrol() {
        GuardPost post = tower.post;
        Hut mine = new Hut(Hut.Type.MINER, block("mine"), 1), other = new Hut(Hut.Type.GUARD_TOWER, block("other"), 1);
        colony.huts.clear(); // its town hall has no block here
        colony.huts.addAll(List.of(tower, other, mine));
        tower.set(Settings.GUARD_TASK, "PATROL_MINE");
        other.set(Settings.GUARD_TASK, "PATROL_MINE");
        assertFalse(post.mineGone(), "none assigned yet");
        post.assignMine(mine, colony);
        other.post.assignMine(mine, colony);
        assertEquals(List.of(tower), GuardPost.mineGuards(mine, colony), "a level 1 mine takes one guard hut");

        tower.set(Settings.GUARD_TASK, "GUARD");
        colony.huts.remove(mine);
        assertNull(post.mine(colony));
        assertSame(GuardPost.Task.GUARD, post.task(), "looking at it changes nothing");

        tower.set(Settings.GUARD_TASK, "PATROL_MINE");
        assertNull(post.mine(colony));
        assertTrue(post.mineGone());
        assertSame(GuardPost.Task.PATROL, post.task());
    }

    @Test
    void ranges() {
        assertEquals(80, tower.post.range());
        assertNull(new Hut(Hut.Type.HOUSE, null, 1).post);
        assertEquals(1, GuardPost.mostMineGuards(2));
        assertEquals(2, GuardPost.mostMineGuards(3));
        assertEquals(3, GuardPost.mostMineGuards(5));
    }
}
