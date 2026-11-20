package dev.minecp.citizen;

import net.minecraft.core.BlockPos;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Server internals (Mojang names via paperweight-userdev), for what the API can't do. The only other use is
 * {@code dev.minecp.ChestNames#send} (a block entity update with just a chest's name). Re-check both against each new
 * Minecraft/Paper release.
 */
final class Nms {
    private Nms() {}

    /** Vanilla sleep: the client lays the body out along the bed only if it knows the bed. Marks the bed occupied. */
    static void sleep(LivingEntity e, Block head) {
        ((CraftLivingEntity) e).getHandle().startSleeping(new BlockPos(head.getX(), head.getY(), head.getZ()));
    }

    /**
     * Steers the mob toward a point for the next tick, without vanilla's path search. Call every tick.
     * setWait first: MoveControl ignores new targets while it thinks it is mid-jump, which on a ladder is forever.
     */
    static void drive(Mob m, double x, double y, double z, double speed) {
        var move = ((CraftMob) m).getHandle().getMoveControl();
        move.setWait();
        move.setWantedPosition(x, y, z, speed);
    }

    /** Stops steering: no forward push and no jump (MoveControl jumps a mob standing in a ladder block). */
    static void halt(Mob m) {
        ((CraftMob) m).getHandle().getMoveControl().setWait();
    }

    /** Jumps this tick (on a ladder: climbs). The API's setJumping is overwritten by the jump control each tick. */
    static void jump(Mob m) {
        ((CraftMob) m).getHandle().getJumpControl().jump();
    }

    /** Ends vanilla sleep and frees the bed. */
    static void wake(LivingEntity e) {
        ((CraftLivingEntity) e).getHandle().stopSleeping();
    }
}
