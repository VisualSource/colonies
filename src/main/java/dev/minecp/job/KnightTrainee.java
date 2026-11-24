package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * A knight in training at the Combat Academy (the mod's EntityAICombatTraining), the mod's numbers: it strikes the
 * building's dummies (a carved pumpkin on hay), or 1 time in 4 spars with another trainee, with a sword and a shield.
 */
public final class KnightTrainee extends Trainee {
    private static final int PARTNER_PERCENT = 25;  // PARTNER_TRAINING_CHANCE
    private static final int STRIKE_DELAY = 60;     // RANGED_ATTACK_DELAY_BASE, counted down a step at a time
    private static final int DUMMY_STEP = 20, PARTNER_STEP = 60; // ticks a step: 20, TRAININGS_DELAY
    private static final double PARTNER_REACH = 5, STEP_BACK = 4; // MIN_DISTANCE_TO_TRAIN; walkAwayFrom
    private static final double XP = 2;             // XP_BASE_RATE

    private KnightTrainee partner; // not saved
    private List<Block> dummies = List.of();
    private Block dummy;
    private int strikes, delay, tries;
    private boolean onDummies; // this session is at dummies: no more looking for a partner

    public KnightTrainee(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    boolean ready(Colonist w) {
        if (!equipped(w, Tool.SWORD)) return false;
        if (has(Guard::isShield) > 0) return true;
        if (inChest(Guard::isShield) == 0) idle(w, "Needs a shield in the hut chest to train.", REQUEST_DELAY, hut.block);
        else if (!atHut(w)) goToHut(w);
        else {
            if (bagFull()) deliver(); // as fetchTool
            return fetch(Guard::isShield, 1) > 0;
        }
        return false;
    }

    /** Its sparring partner, while both still train here. */
    private KnightTrainee partner() {
        if (partner != null && (partner.partner != this || colony.worker(partner) == null)) partner = null;
        return partner;
    }

    @Override
    boolean committed() {
        return partner() != null;
    }

    /** Another trainee of this hut with no partner, or null. */
    private KnightTrainee freePartner() {
        for (Job j : colony.jobsAt(hut))
            if (j != this && j instanceof KnightTrainee t && colony.worker(t) != null && t.partner() == null) return t;
        return null;
    }

    /** Its dummies: the hay under its carved pumpkins. */
    @Override
    void findSpots(BuildOrder o) {
        dummies = List.of();
        if (o == null) return;
        Set<Block> hay = new HashSet<>(blocks(o, Material.HAY_BLOCK));
        dummies = Stream.concat(blocks(o, Material.CARVED_PUMPKIN).stream(), blocks(o, Material.JACK_O_LANTERN).stream())
                .map(b -> b.getRelative(BlockFace.DOWN)).filter(hay::contains).toList();
    }

    private List<Block> standing() {
        return dummies.stream().filter(b -> b.getType() == Material.HAY_BLOCK).toList();
    }

    @Override
    String lacks() {
        return standing().isEmpty() && partner() == null && freePartner() == null ? "No dummies in its building and nobody free to spar with." : null;
    }

    @Override
    void reset() {
        partner = null;
        dummy = null;
        strikes = delay = tries = 0;
    }

    @Override
    void begin() {
        onDummies = false;
    }

    @Override
    void train(Colonist w) {
        if (partner() == null && dummy == null) {
            KnightTrainee other = !onDummies && ThreadLocalRandom.current().nextInt(100) < PARTNER_PERCENT ? freePartner() : null;
            if (other != null) {
                partner = other;
                other.partner = this;
            } else if (strikes >= hut.level * PER_LEVEL || (dummy = random(standing())) == null) {
                strikes = 0;
                training = false;
                return;
            } else {
                onDummies = true;
                strikes++;
                tries = 0;
            }
        }
        if (partner != null) spar(w);
        else hitDummy(w);
    }

    private void hitDummy(Colonist w) {
        if (!w.isBeside(dummy)) {
            if (++tries <= WALK_TRIES) w.goBeside(dummy);
            else { dummy = null; training = false; }
            return;
        }
        w.face(dummy.getLocation().toCenterLocation());
        cooldown = DUMMY_STEP;
        if (delay > 0) { delay--; return; }
        strike(w);
        delay = STRIKE_DELAY;
        dummy = null;
    }

    private void spar(Colonist w) {
        dummy = null;
        onDummies = false; // without this partner it decides again
        Colonist other = colony.worker(partner);
        Location at = w.location(), there = other.location();
        if (Colonist.flat(at, there) > PARTNER_REACH) { w.goBeside(there.getBlock()); return; }
        w.face(there);
        cooldown = PARTNER_STEP;
        if (delay > 0) { delay--; return; }
        if (strike(w)) other.showHurt(); // the mod's hit of no damage
        Vector away = at.toVector().subtract(there.toVector()).setY(0);
        if (away.lengthSquared() > 0) w.walk(at.clone().add(away.normalize().multiply(STEP_BACK)));
        delay = STRIKE_DELAY;
        if (++strikes > hut.level * PER_LEVEL) {
            partner.partner = null;
            partner = null;
            strikes = 0;
            training = false;
        }
    }

    /** Half the time it raises its shield, half the time it strikes. @return whether it struck. */
    private boolean strike(Colonist w) {
        experience(w, XP);
        w.worked();
        boolean block = ThreadLocalRandom.current().nextBoolean();
        w.hold(block ? new ItemStack(Material.SHIELD) : tool(Tool.SWORD)); // ours: the shield in the main hand, so nothing is left in the off hand
        w.location().getWorld().playSound(w.location(), block ? Sound.ITEM_SHIELD_BLOCK : Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.NEUTRAL, 1, 1);
        if (block) return false;
        w.swing();
        wear(Tool.SWORD, w);
        return true;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        wantTool(wants, Tool.SWORD);
        if (has(Guard::isShield) + inChest(Guard::isShield) == 0) wants.add(new Want("a shield", Guard::isShield, 1));
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        return fits(Tool.SWORD, item, null) || Guard.isShield(item) ? 1 : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return Guard.isShield(i);
    }

    @Override
    public List<Component> info(Colony c) {
        return List.of(Component.text(hut.level == 0 ? "Training starts once level 1 is built."
                : "Up to " + hut.level + " knights live and train here with a sword and a shield, growing adaptability and stamina. Guard huts hire the best of them.",
                NamedTextColor.GRAY));
    }
}
