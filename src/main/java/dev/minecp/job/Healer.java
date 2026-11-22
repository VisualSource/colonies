package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Disease;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Hospital's healer (the mod's JobHealer and EntityAIWorkHealer), the mod's numbers unless noted: it asks for the
 * cure items of its patients (the sick lying in its beds or waiting by it), brings one of each to a patient's own
 * inventory, so the patient can get well, and now and then cures one outright. It also heals hurt players in the
 * Hospital, and walks out to citizens badly hurt nearby. It never falls sick.
 */
public final class Healer extends Job {
    private static final int DECIDE_TICKS = 20;    // the mod's: it decides once a second
    private static final int REQUEST_COUNT = 16;   // the cure items it asks for at a time (minimum 1)
    private static final int FREE_CURE_ODDS = 60 * 60; // each second, a patient is cured outright on nextInt(this) <= max(1, knowledge / 20)
    private static final int FREE_CURE_STEPS = 30; // MAX_PROGRESS_TICKS, a step a second
    private static final double REMOTE_HEALTH = 10; // a citizen this hurt...
    private static final int REMOTE_RANGE = 40;    // ...within 40 blocks per hut level gets a visit
    private static final double REACH = 2.5;       // ours: close enough to hand over or heal

    private enum Act { NONE, DELIVER, FREE_CURE, REMOTE }

    private Act act = Act.NONE;
    private Colonist patient;
    private int progress;

    public Healer(Hut hut, Colony colony) {
        super(hut, colony);
    }

    @Override
    public double diseaseModifier() {
        return 0; // the mod's JobHealer: healers don't fall sick
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Healer: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        cooldown = DECIDE_TICKS;
        if (act != Act.NONE && (patient == null || !colony.colonists.contains(patient))) reset();
        switch (act) {
            case NONE -> decide(w);
            case DELIVER -> deliver(w);
            case FREE_CURE -> freeCure(w);
            case REMOTE -> remote(w);
        }
    }

    private void reset() {
        act = Act.NONE;
        patient = null;
        progress = 0;
    }

    private void decide(Colonist w) {
        var rnd = ThreadLocalRandom.current();
        for (Colonist p : colony.sickness.patients(hut)) {
            if (rnd.nextInt(FREE_CURE_ODDS) <= Math.max(1, skill(1) / 20)) { act = Act.FREE_CURE; patient = p; return; }
            if (!p.disease.curedBy(p.inventory()) && (p.disease.curedBy(bag()) || p.disease.curedBy(chest()))) { act = Act.DELIVER; patient = p; return; }
        }
        if (healPlayer(w)) return;
        List<Colonist> all = colony.colonists;
        Colonist someone = all.isEmpty() ? null : all.get(rnd.nextInt(all.size())); // the mod's random citizen
        if (someone != null && someone != w && someone.health < REMOTE_HEALTH && someone.location().getWorld() == hut.block.getWorld()
                && flat(someone.location(), hut.block.getLocation()) < hut.level * REMOTE_RANGE) {
            act = Act.REMOTE;
            patient = someone;
            return;
        }
        note = colony.sickness.patients(hut).isEmpty() ? null : "Waiting for the cure items its patients need, in the hut chest.";
        w.setStatus("Healer: waiting for patients");
        w.wander(hut.block.getLocation().toCenterLocation());
    }

    /** Gets one of each cure item (from its own inventory, else the hut chest), walks to the patient and puts them in its inventory. */
    private void deliver(Colonist w) {
        if (patient.disease == null) { reset(); return; }
        if (!patient.disease.curedBy(bag())) {
            if (!atHut(w)) { w.setStatus("Healer: working"); goToHut(w); return; }
            for (Material m : patient.disease.cures) if (has(m) < 1) fetch(m, 1);
            if (!patient.disease.curedBy(bag())) { reset(); return; } // gone meanwhile, or no room
        }
        if (!near(w, patient)) { w.setStatus("Healer: bringing a cure"); w.goBeside(patient.location().getBlock()); return; }
        w.swing();
        Inventory into = patient.inventory(slots());
        for (Material m : patient.disease.cures) {
            if (use(m, 1) == 0) continue;
            if (!into.addItem(new ItemStack(m)).isEmpty()) { // the mod's PATIENT_FULL_INVENTORY: it keeps it
                gather(List.of(new ItemStack(m)), w);
                colony.tell(patient.name + "'s inventory is full: the healer can't give them their cure.");
                break;
            }
        }
        experience(w, 2); // BASE_XP_GAIN for a treatment
        reset();
    }

    /** It keeps cure items with it for the next patient. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        for (Disease d : Disease.values()) if (d.cures.contains(i.getType())) return true;
        return false;
    }

    /** Hearts for 30 s beside the patient, then it is well (the mod's freeCure). */
    private void freeCure(Colonist w) {
        if (patient.disease == null) { reset(); return; }
        if (!near(w, patient)) { w.setStatus("Healer: working"); w.goBeside(patient.location().getBlock()); progress = 0; return; }
        w.setStatus("Healer: curing " + patient.name);
        w.swing();
        hearts(patient.location(), 2);
        if (++progress < FREE_CURE_STEPS) return;
        colony.sickness.cure(patient);
        experience(w, 2); // BASE_XP_GAIN
        reset();
    }

    /** A citizen badly hurt nearby: it walks over and heals it to 5 (and its hut level) short of full (the mod's wander). */
    private void remote(Colonist w) {
        if (!near(w, patient)) { w.setStatus("Healer: going to a hurt citizen"); w.goBeside(patient.location().getBlock()); return; }
        w.swing();
        patient.health = Math.max(patient.health, healedTo(colony.health.max(patient)));
        experience(w, 1); // the mod's, for healing on its walks
        hearts(patient.location(), 5);
        reset();
    }

    /** A player in the Hospital more than 10 (and twice its level) below full: healed to 5 (and its level) short (the mod's curePlayer). */
    private boolean healPlayer(Colonist w) {
        int[] box = colony.footprint(hut); // minX, maxX+1, minZ, maxZ+1
        if (box == null) return false;
        for (Player p : hut.block.getWorld().getPlayers()) {
            Location l = p.getLocation();
            if (l.getBlockX() < box[0] || l.getBlockX() >= box[1] || l.getBlockZ() < box[2] || l.getBlockZ() >= box[3]) continue;
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            double full = max == null ? 20 : max.getValue();
            if (p.getHealth() >= full - 10 - 2 * hut.level) continue;
            if (w.location().distance(l) > REACH) { w.setStatus("Healer: working"); w.goBeside(l.getBlock()); return true; }
            w.swing();
            p.setHealth(Math.min(full, Math.max(p.getHealth(), healedTo(full))));
            experience(w, 1); // the mod's, for healing a player
            p.sendMessage(Component.text("The healer patches you up.", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }

    /** What the healer heals someone to: 5, and its hut level, short of full (the mod's). */
    private double healedTo(double full) {
        return full - 5 - hut.level;
    }

    private static void hearts(Location at, int n) {
        at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 1, 0), n, 0.3, 0.3, 0.3);
    }

    /** Across the ground only, as the mod's getDistance2D. */
    private static double flat(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static boolean near(Colonist w, Colonist other) {
        return w.location().getWorld() == other.location().getWorld() && w.location().distance(other.location()) <= REACH;
    }

    /** One of each of its patients' cure items it lacks: 16 at a time, any amount will do (the mod's requests). */
    @Override
    public List<Want> wants() {
        Set<Material> missing = new LinkedHashSet<>();
        if (hut.level >= 1) for (Colonist p : colony.sickness.patients(hut))
            if (!p.disease.curedBy(p.inventory())) for (Material m : p.disease.cures) if (inChest(i -> i.getType() == m) + has(m) < 1) missing.add(m);
        List<Want> wants = new ArrayList<>();
        for (Material m : missing) wants.add(new Want(name(m), i -> i.getType() == m, REQUEST_COUNT, 1));
        return wants;
    }

    /** It keeps a stack of each cure item for later patients (ours). */
    @Override
    int keep(ItemStack item) {
        for (Disease d : Disease.values()) if (d.cures.contains(item.getType())) return REQUEST_COUNT;
        return 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The healer starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Sees to the sick in its beds and nearby: it asks for their cure items and brings them one of each.",
                NamedTextColor.GRAY));
        for (Colonist p : c.sickness.patients(hut))
            lines.add(Component.text(p.name + ": " + p.disease.label + " (needs " + p.disease.cureLabel() + ")"));
        return lines;
    }
}
