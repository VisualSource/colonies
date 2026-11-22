package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Disease;
import dev.minecp.citizen.Food;
import dev.minecp.job.Job;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A colony's sickness (the mod's CitizenDiseaseHandler, EntityAISickTask and the citizen's golden apple and poison
 * interactions), the mod's numbers unless noted. Citizens fall sick now and then once the colony has more than its
 * starting citizens, catch it from the sick they bump into, and get well by using one of each cure item from their own
 * inventory: players put them there, or the Hospital's healer does.
 */
public final class Sickness {
    static final int CHECK_TICKS = 60;              // the mod ticks citizen data every 3 s
    private static final int ODDS = 5 * (100000 / 3); // diseaseModifier default 5 x DISEASE_FACTOR (ours: not a setting)
    private static final int STARTING = 4;          // initialCitizenAmount: nobody falls sick until the colony has more
    private static final int IMMUNITY = 20 * 60 * 90, VACCINE = 10; // IMMUNITY_TIME after a cure in a hospital bed, x10 with Vaccines
    private static final int COLLISION_COOLDOWN = 20 * 30; // after masks stopped it catching a disease (x2 with Vaccines)
    private static final double CATCH = 0.01;       // a bump into a sick citizen: 1 in 100 (the mod rolls each bump)
    private static final double BUMP = 0.6;         // ours: bodies this close (horizontally) count as bumping
    private static final double SEEK_DOCTOR = 6;    // SEEK_DOCTOR_HEALTH: hurt this badly, it goes to a hospital
    private static final int CURE_STEPS = 60, STEP_TICKS = 20; // REQUIRED_TIME_TO_CURE, a step a second
    private static final int SELF_CURE = 60 * 60 * 2; // waiting at a hospital, 1 in this each second: it gets well by itself
    private static final double HOSPITAL_NEAR = 10; // MINIMUM_DISTANCE_TO_HOSPITAL
    private static final int APPLE_ODDS = 3;        // a golden apple cures 1 time in 3
    private static final int INTERACT_COOLDOWN = 20 * 60 * 5;
    private static final Set<Material> POISON = Set.of(Material.POISONOUS_POTATO, Material.CHICKEN, Material.SPIDER_EYE,
            Material.ROTTEN_FLESH); // the mod's poisonous_food tag

    private final Colony colony;
    private final Map<Colonist, Block> beds = new HashMap<>(); // patients in hospital beds (not saved: they lie down again)
    private final Set<Colonist> told = new HashSet<>();        // whose "no hospital" message was sent (not saved)
    private int ticks;

    Sickness(Colony colony) {
        this.colony = colony;
    }

    // ---- falling sick ------------------------------------------------------------------------------------------

    private boolean canFallSick(Colonist c) {
        Job job = colony.jobOf(c);
        return c.disease == null && c.immunity <= 0 && colony.colonists.size() > STARTING && (job == null || job.diseaseModifier() > 0);
    }

    /** Its odds against others' 1: its job's, halved by a varied diet, up to 1.25x for a dull one (the mod's food handler). */
    private double modifier(Colonist c) {
        Job job = colony.jobOf(c);
        double base = job == null ? 1 : job.diseaseModifier();
        if (c.diet.size() < Colonist.DIET_SIZE || base == 0) return base;
        return base * 0.5 * Math.min(2.5, 5.0 / Math.max(1, new HashSet<>(c.diet).size()));
    }

    /** Each tick: immunity runs down; every 3 s a citizen may fall sick, and the sick may pass it on to those they bump into. */
    void tick() {
        for (Colonist c : colony.colonists) {
            if (c.immunity > 0) c.immunity--;
            if (c.interactCooldown > 0) c.interactCooldown--;
        }
        if (++ticks % CHECK_TICKS != 0) return;
        var rnd = ThreadLocalRandom.current();
        for (Colonist c : colony.colonists)
            if (canFallSick(c) && rnd.nextInt(ODDS) < modifier(c) * 10) fallSick(c, Disease.random());
        // ours: bumps are checked every 3 s, each with the chance of the 60 ticks of bumping the mod would roll
        double perCheck = 1 - Math.pow(1 - CATCH, CHECK_TICKS);
        for (Colonist sick : List.copyOf(colony.colonists)) {
            if (sick.disease == null || sick.living() == null) continue;
            for (Colonist other : colony.colonists) {
                if (other == sick || !canFallSick(other) || other.living() == null || !near(sick, other) || rnd.nextDouble() >= perCheck) continue;
                if (colony.research.effect(Effects.MASKS) > 0 && rnd.nextBoolean()) // masks: half the time it isn't caught
                    other.immunity = Math.max(other.immunity, COLLISION_COOLDOWN * (colony.research.effect(Effects.VACCINES) > 0 ? 2 : 1));
                else fallSick(other, sick.disease);
            }
        }
    }

    private static boolean near(Colonist a, Colonist b) {
        var x = a.location();
        var y = b.location();
        return x.getWorld() == y.getWorld() && Math.hypot(x.getX() - y.getX(), x.getZ() - y.getZ()) < BUMP && Math.abs(x.getY() - y.getY()) < 1;
    }

    private void fallSick(Colonist c, Disease d) {
        c.disease = d;
        c.cureTicks = 0;
        told.remove(c);
        Colony.log.info(colony.where() + ": " + c.name + " fell sick with " + d.label + ".");
    }

    // ---- being sick -------------------------------------------------------------------------------------------

    /** Hurt badly enough to see a healer: under 6 health, fed, not a guard (the mod's isHurt). */
    boolean hurt(Colonist c, Job job) {
        return !(job instanceof dev.minecp.job.Guard) && c.health < SEEK_DOCTOR && c.saturation > Food.LOW_SATURATION;
    }

    /** Lying in a hospital bed: that comes before a raid or the night, as in the mod. */
    boolean inHospital(Colonist c) {
        return c.disease != null && beds.containsKey(c) && c.bed() != null;
    }

    /**
     * Its tick while sick (or hurt with a hospital to go to): instead of work, as the mod's sick task. @return false if
     * it has nothing to do as a patient and gets on with its day.
     */
    boolean tend(Colonist c, Job job) {
        if (job != null && !job.interruptible()) return false;
        Hut hospital = hospital(c);
        if (c.disease == null) { // hurt: to a hospital, if there is one, where its healer heals it
            if (hospital == null || !hurt(c, job)) return false;
            c.setStatus("Hurt: at the hospital");
            if (!c.isMoving() && !near(c, hospital)) c.goBeside(hospital.block);
            return true;
        }
        c.setStatus("Sick: " + c.disease.label);
        if (hasCure(c)) { applyCure(c); return true; }
        if (hospital == null) { // it says what it needs and wanders near its work (the mod's NO_HOSPITAL interaction)
            if (told.add(c)) colony.tell(c.name + " has " + c.disease.label + " and needs " + c.disease.cureLabel()
                    + " in their inventory (right-click them) to get well. A Hospital's healer would see to it.");
            Hut near = job != null ? job.hut : c.home != null ? c.home : colony.townHall();
            c.wander(near.block.getLocation().toCenterLocation());
            return true;
        }
        Block bed = beds.get(c);
        if (bed != null && c.bed() != null) { // lying in a hospital bed, waiting for the healer
            if (c.cureTicks++ % STEP_TICKS == 0 && ThreadLocalRandom.current().nextInt(SELF_CURE) == 0) cure(c);
            return true;
        }
        if (c.isMoving()) return true;
        if (!near(c, hospital)) { c.goBeside(hospital.block); return true; }
        if (bed == null) bed = freeBed(hospital);
        if (bed == null) { c.wander(hospital.block.getLocation().toCenterLocation()); return true; } // no bed free: waits about
        beds.put(c, bed);
        if (c.isBeside(bed)) c.sleepIn(bed);
        else c.goBeside(bed);
        return true;
    }

    private static boolean near(Colonist c, Hut h) {
        return c.location().getWorld() == h.block.getWorld() && c.location().distance(h.block.getLocation()) <= HOSPITAL_NEAR;
    }

    /** The nearest built Hospital, or null. */
    public Hut hospital(Colonist c) {
        Hut best = null;
        for (Hut h : colony.huts)
            if (h.type == Hut.Type.HOSPITAL && h.level >= 1 && h.block.getWorld() == c.location().getWorld()
                    && (best == null || h.block.getLocation().distanceSquared(c.location()) < best.block.getLocation().distanceSquared(c.location())))
                best = h;
        return best;
    }

    /** A hospital's beds (their heads), inside its building. */
    private List<Block> bedsOf(Hut hospital) {
        List<Block> found = new ArrayList<>();
        int[] box = colony.footprint(hospital); // minX, maxX+1, minZ, maxZ+1
        if (box == null) return found;
        int y0 = hospital.block.getY();
        for (int x = box[0]; x < box[1]; x++) for (int z = box[2]; z < box[3]; z++) for (int y = y0 - 4; y <= y0 + 12; y++) {
            Block b = hospital.block.getWorld().getBlockAt(x, y, z);
            if (b.getBlockData() instanceof Bed bd && bd.getPart() == Bed.Part.HEAD) found.add(b);
        }
        return found;
    }

    private Block freeBed(Hut hospital) {
        for (Block b : bedsOf(hospital)) if (!beds.containsValue(b)) return b;
        return null;
    }

    /** The sick citizens at this hospital: in its beds or near it (the healer's patients). */
    public List<Colonist> patients(Hut hospital) {
        List<Colonist> out = new ArrayList<>();
        for (Colonist c : colony.colonists) if (c.disease != null && (near(c, hospital) || beds.containsKey(c))) out.add(c);
        return out;
    }

    /** One of each cure item in its own inventory. */
    public static boolean hasCure(Colonist c) {
        Inventory inv = c.inventory();
        if (inv == null || c.disease == null) return false;
        for (Material m : c.disease.cures) if (!inv.contains(m)) return false;
        return true;
    }

    /** A minute with a cure item in hand (the mod's applyCure), then it uses one of each up and is well. */
    private void applyCure(Colonist c) {
        if (++c.cureTicks % STEP_TICKS != 0) return;
        var cures = c.disease.cures;
        c.hold(new ItemStack(cures.get(ThreadLocalRandom.current().nextInt(cures.size()))));
        c.swing();
        var at = c.location();
        at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.NEUTRAL, 0.5f, 1);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 2, 0), 3, 0.3, 0.2, 0.3);
        if (c.cureTicks < CURE_STEPS * STEP_TICKS) return;
        for (Material m : c.disease.cures) c.inventory().removeItem(new ItemStack(m));
        c.hold(null);
        cure(c);
        c.health = colony.health.max(c);
    }

    /** Well again: immune for 90 minutes after a hospital bed (x10 with Vaccines), half that otherwise. */
    public void cure(Colonist c) {
        boolean inBed = beds.remove(c) != null && c.bed() != null;
        c.immunity = inBed ? IMMUNITY * (colony.research.effect(Effects.VACCINES) > 0 ? VACCINE : 1) : IMMUNITY / 2;
        if (inBed) c.wake();
        c.disease = null;
        c.cureTicks = 0;
        c.sickDays = 0;
        told.remove(c);
        colony.tell(c.name + " is well again.");
    }

    /** It died or left: its hospital bed is free. */
    void forget(Colonist c) {
        beds.remove(c);
        told.remove(c);
    }

    // ---- a player's hand -----------------------------------------------------------------------------------------

    /**
     * A player right-clicks a citizen with a golden apple (a sick one gets well 1 time in 3) or poisonous food (it falls
     * sick), as the mod's directPlayerInteraction. @return true if the item was used, so no menu opens.
     */
    public boolean interact(Player p, Colonist c, ItemStack held) {
        boolean apple = held.getType() == Material.GOLDEN_APPLE && c.disease != null, poison = POISON.contains(held.getType());
        if (!apple && !poison) return false;
        if (c.interactCooldown > 0) { p.sendMessage(Component.text(c.name + " can't take that now.", NamedTextColor.RED)); return true; }
        held.subtract();
        c.interactCooldown = INTERACT_COOLDOWN;
        var at = c.location();
        if (apple) {
            if (ThreadLocalRandom.current().nextInt(APPLE_ODDS) == 0) {
                cure(c);
                at.getWorld().playSound(at, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, 1, 1);
                at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3);
            }
        } else if (canFallSick(c)) {
            fallSick(c, Disease.random());
            at.getWorld().playSound(at, Sound.ENTITY_VILLAGER_HURT, SoundCategory.NEUTRAL, 1, 1);
            p.sendMessage(Component.text(c.name + " ate it and fell sick.", NamedTextColor.RED));
        }
        return true;
    }
}
