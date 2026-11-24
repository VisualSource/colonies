package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** An archer in training at the Archery (the mod's EntityAIArcherTraining), the mod's numbers: it shoots at the building's target blocks from its stands. */
public final class ArcherTrainee extends Trainee {
    private static final int DRAW_TICKS = 10 * 5, CHECK_TICKS = 60;   // RANGED_ATTACK_DELAY_BASE steps of STANDARD_DELAY; CHECK_SHOT_DELAY
    private static final double HIT_XP = 1, MISS_XP = 0.2;            // XP_PER_SUCCESSFUL_SHOT, XP_BASE_RATE
    private static final double HIT_DISTANCE_SQ = 2;                  // MIN_DISTANCE_FOR_SUCCESS (compared with the square)
    private static final double AT_STAND_SQ = 2;                      // ours: this near (squared) it stands on it
    private static final double SPEED = 1.6, AIM_HIGHER = 0.2, HIT_CHANCE_DIVIDER = 15; // GuardConstants

    private List<Block> stands = List.of(), targets = List.of();
    private Block stand, target;
    private Arrow arrow;
    private int shots, tries;

    public ArcherTrainee(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    boolean ready(Colonist w) {
        return equipped(w, Tool.BOW);
    }

    /** Its stands: the blueprint's work spots, else its glowstone (the mod's getRandomShootingStandPosition); and its targets. */
    @Override
    void findSpots(BuildOrder o) {
        stands = targets = List.of();
        if (o == null) return;
        stands = o.workSpots.entrySet().stream().filter(e -> e.getValue().equals(BuildOrder.WORK)).map(java.util.Map.Entry::getKey).toList();
        if (stands.isEmpty()) stands = blocks(o, Material.GLOWSTONE);
        targets = blocks(o, Material.TARGET);
    }

    @Override
    String lacks() {
        return stands.isEmpty() ? "Its building has no stands to shoot from." : targets.isEmpty() ? "Its building has no target blocks." : null;
    }

    @Override
    void reset() {
        stand = target = null;
        arrow = null;
        shots = tries = 0;
    }

    @Override
    void train(Colonist w) {
        if (arrow != null) { // the shot has landed (or flown off)
            experience(w, arrow.isValid() && arrow.getLocation().distanceSquared(target.getLocation()) < HIT_DISTANCE_SQ ? HIT_XP : MISS_XP);
            arrow = null;
            target = null;
        }
        if (stand == null) {
            tries = 0;
            if ((stand = random(stands)) == null) { end(); return; }
        }
        Location at = stand.getLocation().add(0.5, 1, 0.5);
        // beside it will do (the mod's reach is 7): carpet under a 2 high roof is too low for the legs
        if (w.location().distanceSquared(at) > AT_STAND_SQ && (tries == 0 || !w.isBeside(stand))) {
            if (++tries > WALK_TRIES || !w.walk(at) && w.walkBeside(stand) == null) end();
            return;
        }
        w.hold(tool(Tool.BOW));
        if (target == null) {
            if (shots >= hut.level * PER_LEVEL) { shots = 0; end(); return; }
            target = random(targets);
            if (target == null || !sees(w, target)) { end(); return; }
            shots++;
            w.face(target.getLocation().toCenterLocation());
            cooldown = DRAW_TICKS;
            return;
        }
        shoot(w);
        cooldown = CHECK_TICKS;
    }

    private void end() {
        training = false;
        stand = target = null;
    }

    private static boolean sees(Colonist w, Block b) {
        Location eye = w.living().getEyeLocation();
        Vector to = b.getLocation().toCenterLocation().toVector().subtract(eye.toVector());
        var hit = eye.getWorld().rayTraceBlocks(eye, to, to.length(), FluidCollisionMode.NEVER, true);
        return hit != null && b.equals(hit.getHitBlock());
    }

    /** The mod's aim: at the target block's corner, higher with distance, wobbling by 15 / (agility / 2 + 1). */
    private void shoot(Colonist w) {
        Location from = w.location().add(0, 1, 0);
        double dx = target.getX() - from.getX(), dy = target.getY() - from.getY(), dz = target.getZ() - from.getZ();
        Vector aim = new Vector(dx, dy + Math.sqrt(dx * dx + dz * dz) * AIM_HIGHER, dz);
        arrow = from.getWorld().spawn(from, Arrow.class, a -> {
            a.setShooter(w.living());
            a.setDamage(0);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.addScoreboardTag(Guard.GUARD_ARROW);
            a.setVelocity(Guard.wobble(aim, HIT_CHANCE_DIVIDER / (skill(0) / 2.0 + 1)).multiply(SPEED));
        });
        from.getWorld().playSound(from, Sound.ENTITY_SKELETON_SHOOT, SoundCategory.NEUTRAL, 1, 1);
        if (ThreadLocalRandom.current().nextBoolean()) wear(Tool.BOW, w);
        act(w);
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new java.util.ArrayList<>();
        if (hut.level >= 1) wantTool(wants, Tool.BOW);
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        return fits(Tool.BOW, item, null) ? 1 : 0;
    }

    @Override
    public List<Component> info(Colony c) {
        return List.of(Component.text(hut.level == 0 ? "Training starts once level 1 is built."
                : "Up to " + hut.level + " archers live and train here with bows, growing agility and adaptability. Guard huts hire the best of them as rangers.",
                NamedTextColor.GRAY));
    }
}
