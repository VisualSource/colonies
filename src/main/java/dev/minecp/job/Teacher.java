package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The School (the mod's BuildingSchool, JobTeacher, JobPupil, EntityAIWorkTeacher and EntityAIWorkPupil), the mod's
 * numbers unless noted: the colony's children are its pupils, two a level, who sit at its seats (its blueprint's sitting
 * spots, else its carpets) for one to two minutes at a time, now and then taking a recess, each sitting with paper
 * using one up for 50 experience in intelligence. Its teacher hands them paper and teaches them, for more.
 */
public final class Teacher extends Job {
    private static final int PUPILS_PER_LEVEL = 2;      // ChildrenBuildingModule: 2 a level
    private static final int RECESS_ODDS = 10;          // STUDY_TO_RECESS_RATIO
    private static final int SIT_MIN = 60, SIT_SPREAD = 60; // seconds a pupil sits: 60 + rand(60)
    private static final double PAPER_XP = 50;          // a pupil's paper, used up when it gets up
    private static final int PAPER_ASKED = 16;          // PAPER_TO_REQUEST
    private static final double LESSON_XP = 1.5, TEACHER_XP = 0.1;
    private static final double REACH = 2.5;            // ours: close enough to teach

    /** A pupil's day at school (not saved: it sits down again). */
    private static final class Pupil {
        Block seat;
        int sitLeft, wait;
        boolean recess;
    }

    private final Map<UUID, Pupil> pupils = new HashMap<>();
    private Colonist teaching;  // the pupil it is teaching
    private int teachLeft;      // seconds of the lesson left
    private List<Block> seats = List.of();
    private int seatsLevel = -1;

    public Teacher(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** The school a child goes to: the colony's children in turn fill its built schools, two places a level; null if none has room. */
    public static Teacher schoolOf(Colony colony, Colonist child) {
        if (!child.child) return null;
        List<Colonist> children = colony.colonists.stream().filter(c -> c.child).toList();
        int index = children.indexOf(child), places = 0;
        for (Job j : colony.jobs)
            if (j instanceof Teacher t && t.hut.level >= 1 && index < (places += PUPILS_PER_LEVEL * t.hut.level)) return t;
        return null;
    }

    /** Its seats: the blueprint's sitting spots, else the carpets of its building (the mod's getRandomPlaceToSit). */
    private Block randomSeat() {
        if (seatsLevel != hut.level) {
            seatsLevel = hut.level;
            BuildOrder o = hut.level < 1 ? null : BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
            seats = o == null ? List.of() : !o.seats.isEmpty() ? o.seats
                    : o.steps.stream().filter(s -> Tag.WOOL_CARPETS.isTagged(s.data().getMaterial())).map(BuildOrder.Step::block).toList();
        }
        return seats.isEmpty() ? null : seats.get(ThreadLocalRandom.current().nextInt(seats.size()));
    }

    /** A pupil's school day, once a second (the mod's EntityAIWorkPupil): a seat, a sitting, now and then a recess. */
    public void pupilTick(Colonist c) {
        Pupil p = pupils.computeIfAbsent(c.id, k -> new Pupil());
        if (--p.wait > 0) return;
        p.wait = 20;
        myPupils(); // the grown up and those gone to another school give their seats back, teacher or not
        var rnd = ThreadLocalRandom.current();
        if (p.recess) { // it runs about the building until 1 time in 10 it goes back in
            c.setStatus("Pupil: recess");
            if (!c.isMoving() && rnd.nextInt(RECESS_ODDS) == 0) p.recess = false;
            else c.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (p.seat == null) {
            if (rnd.nextInt(RECESS_ODDS) == 0) { p.recess = true; return; }
            Block seat = randomSeat();
            if (seat == null) { c.setStatus("Pupil: no seat"); note = "No seats or carpets in the school for its pupils."; return; }
            if (pupils.values().stream().anyMatch(o -> o != p && seat.equals(o.seat))) return; // taken: another try next time
            p.seat = seat;
        }
        if (c.isMoving()) return;
        if (!seat(c, p)) {
            if (!c.isBeside(p.seat)) { c.setStatus("Pupil: going to class"); c.goBeside(p.seat); return; }
            c.sitOn(p.seat);
            p.sitLeft = SIT_MIN + rnd.nextInt(SIT_SPREAD);
        }
        boolean paper = paper(c) > 0;
        c.setStatus("Pupil: studying");
        c.hold(paper ? new ItemStack(Material.PAPER) : null);
        var at = c.location().add(0, 1, 0);
        at.getWorld().spawnParticle(paper ? Particle.ENCHANT : Particle.HAPPY_VILLAGER, at, 3, 0.3, 0.3, 0.3);
        if (--p.sitLeft > 0) return;
        c.standUp();
        c.hold(null);
        if (paper) { // its paper, used up for 50 experience in intelligence (more with research)
            c.inventory(colony.inventorySlots()).removeItem(new ItemStack(Material.PAPER));
            c.learn(Skill.INTELLIGENCE, PAPER_XP * (1 + colony.research.effect(Effects.TEACHING)));
        }
        c.workedLittle();
        p.seat = null;
    }

    private static boolean seat(Colonist c, Pupil p) {
        return p.seat != null && p.seat.equals(c.seat());
    }

    private int paper(Colonist c) {
        int n = 0;
        for (ItemStack i : c.inventory(colony.inventorySlots()).getContents()) if (isPaper(i)) n += i.getAmount();
        return n;
    }

    private static boolean isPaper(ItemStack i) {
        return i != null && i.getType() == Material.PAPER && i.getPersistentDataContainer().isEmpty();
    }

    /** The children who go here now. */
    private List<Colonist> myPupils() {
        List<Colonist> out = new ArrayList<>();
        for (Colonist c : colony.colonists) if (c.child && schoolOf(colony, c) == this) out.add(c);
        pupils.keySet().removeIf(id -> out.stream().noneMatch(c -> c.id.equals(id))); // grown up or gone
        return out;
    }

    /**
     * The teacher (the mod's EntityAIWorkTeacher): it keeps paper, picks a pupil sitting in class, stays with it for a
     * while, gives it a paper if it has none, and teaches it intelligence: 1.5 x (1 + its own intelligence / 10) x
     * (1 + its knowledge / 10), more with research.
     */
    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Teacher: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        cooldown = 20;
        if (has(Teacher::isPaper) == 0 && inChest(Teacher::isPaper) > 0) {
            if (!atHut(w)) { w.setStatus("Teacher: getting paper"); goToHut(w); return; }
            fetch(Teacher::isPaper, PAPER_ASKED);
        }
        List<Colonist> in = myPupils();
        if (teaching != null && (!in.contains(teaching) || !seat(teaching, pupils.get(teaching.id)))) teaching = null;
        if (teaching == null) {
            List<Colonist> sitting = in.stream().filter(c -> pupils.containsKey(c.id) && seat(c, pupils.get(c.id))).toList();
            if (sitting.isEmpty()) {
                note = in.isEmpty() ? "No children to teach." : null;
                w.setStatus("Teacher: waiting for pupils");
                w.wander(hut.block.getLocation().toCenterLocation());
                return;
            }
            teaching = sitting.get(ThreadLocalRandom.current().nextInt(sitting.size()));
            int pace = (int) (100 / Math.max(1, skill(1) / 2.0)); // its mana slows it less
            teachLeft = ThreadLocalRandom.current().nextInt(Math.max(1, pace / 2)) + pace / 2;
        }
        note = null;
        w.setStatus("Teacher: teaching " + teaching.name);
        if (w.location().getWorld() != teaching.location().getWorld() || w.location().distance(teaching.location()) > REACH) {
            w.goBeside(teaching.location().getBlock());
            return;
        }
        if (--teachLeft > 0) return;
        Inventory theirs = teaching.inventory(colony.inventorySlots());
        if (paper(teaching) == 0 && theirs.firstEmpty() >= 0 && use(Teacher::isPaper, 1) > 0) theirs.addItem(new ItemStack(Material.PAPER)); // never into a full inventory
        double xp = LESSON_XP * (1 + w.skills.level(Skill.INTELLIGENCE) / 10.0) * (1 + colony.research.effect(Effects.TEACHING)) * (1 + skill(0) / 10.0);
        teaching.learn(Skill.INTELLIGENCE, xp);
        w.swing();
        experience(w, TEACHER_XP);
        w.workedLittle();
        teaching = null;
    }

    /** With no paper on hand or in the chest: 16 (the mod's request). */
    @Override
    public List<Want> wants() {
        if (hut.level < 1 || has(Teacher::isPaper) + inChest(Teacher::isPaper) > 0) return List.of();
        return List.of(new Want(name(Material.PAPER), Teacher::isPaper, PAPER_ASKED));
    }

    /** Its paper stays (ours: so couriers leave it). */
    @Override
    int keep(ItemStack item) {
        return isPaper(item) ? PAPER_ASKED : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return isPaper(i);
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("Lessons start once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Up to " + PUPILS_PER_LEVEL * hut.level + " of the colony's children study here, growing their intelligence;"
                + " paper (in the chest) makes their lessons count, and the teacher hands it out.", NamedTextColor.GRAY));
        List<Colonist> in = myPupils();
        lines.add(Component.text("Pupils: " + (in.isEmpty() ? "none" : String.join(", ", in.stream().map(x -> x.name).toList()))));
        return lines;
    }
}
