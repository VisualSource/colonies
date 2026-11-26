package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A student at the Library (the mod's BuildingLibrary, JobStudent and EntityAIStudy), the mod's numbers: two a level.
 * Once a minute it studies at a bookshelf of the building with paper or a book from its inventory, which may raise its
 * intelligence and may be used up; without either it studies anyway and asks for some.
 */
public final class Student extends Job {
    private static final int STUDY_TICKS = 20 * 60;   // STUDY_DELAY; also how long it tries to reach a shelf
    private static final int TRY_ODDS = 8;            // ONE_IN_X_CHANCE
    private static final int INTELLIGENCE_XP = 10;    // tryLevelUpIntelligence's experience
    private static final int FETCHED = 10;            // what it takes from the chest at a time
    private static final int KEPT = 64;               // the building's keepX for study items

    /** The mod's study items (data/minecolonies/study_items): how much they help, and the chance in 100 one is used up. */
    private enum Item {
        PAPER(Material.PAPER, 40, 100), BOOK(Material.BOOK, 60, 10);

        final Material material;
        final int increase, breaks;

        Item(Material material, int increase, int breaks) {
            this.material = material;
            this.increase = increase;
            this.breaks = breaks;
        }
    }

    private Block shelf;
    private int walking;
    private List<Block> shelves = List.of();
    private int shelvesLevel = -1;

    public Student(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** A bookshelf of its building, else the hut block (the mod's getRandomBookShelf). */
    private Block randomShelf() {
        if (shelvesLevel != hut.level) {
            shelvesLevel = hut.level;
            BuildOrder o = hut.built(colony.style);
            shelves = o == null ? List.of() : o.steps.stream().filter(s -> s.data().getMaterial() == Material.BOOKSHELF).map(BuildOrder.Step::block).toList();
        }
        if (shelves.isEmpty()) return hut.block;
        Block b = shelves.get(ThreadLocalRandom.current().nextInt(shelves.size()));
        return b.getType() == Material.BOOKSHELF ? b : hut.block;
    }

    private static boolean studyItem(ItemStack i) {
        if (i == null) return false;
        for (Item s : Item.values()) if (i.getType() == s.material && i.getPersistentDataContainer().isEmpty()) return true;
        return false;
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Student: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (has(Student::studyItem) == 0 && inChest(Student::studyItem) > 0) { // the mod gathers some from the building first
            if (!atHut(w)) { w.setStatus("Student: getting paper"); goToHut(w); return; }
            fetch(Student::studyItem, FETCHED);
        }
        if (shelf == null) shelf = randomShelf();
        if (!w.seesBeside(shelf) && walking < STUDY_TICKS) { // the mod studies where it is once it gave up walking
            w.setStatus("Student: going to study");
            w.goBeside(shelf);
            walking += 20;
            cooldown = 20;
            return;
        }
        walking = 0;
        note = has(Student::studyItem) == 0 ? "No paper or books: it studies without, and asks for some." : null;
        study(w);
        shelf = null;
        cooldown = STUDY_TICKS;
    }

    /** One study: with a random study item it holds, a try at intelligence with that item's odds, which may use it up. */
    private void study(Colonist w) {
        w.setStatus("Student: studying");
        var rnd = ThreadLocalRandom.current();
        List<Item> held = java.util.Arrays.stream(Item.values()).filter(i -> has(x -> x.getType() == i.material && studyItem(x)) > 0).toList();
        if (held.isEmpty()) tryIntelligence(colony, w, TRY_ODDS);
        else {
            Item chosen = held.get(rnd.nextInt(held.size()));
            w.hold(new ItemStack(chosen.material));
            tryIntelligence(colony, w, TRY_ODDS * (10.0 / chosen.increase));
            if (rnd.nextInt(100) <= chosen.breaks) use(x -> x.getType() == chosen.material && studyItem(x), 1); // never a Clipboard
        }
        w.swing();
        w.workedLittle(); // decreaseSaturationForContinuousAction
        w.location().getWorld().playSound(w.location(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 0.25f, 1.5f);
    }

    /**
     * The mod's tryLevelUpIntelligence, as written: it fails with chance 1 / {@code chance}; otherwise, while its
     * intelligence is under 9 x its happiness, 10 experience in intelligence.
     * That makes studying with nothing (87.5%) beat paper (50%) and a book (25%), which may be a bug in the mod, but no
     * issue upstream reports it (checked 2026-11-25). Decided: kept as the mod's; see docs/parity.md (Library).
     */
    public static void tryIntelligence(dev.minecp.colony.Colony colony, Colonist w, double chance) {
        if (chance > 0 && ThreadLocalRandom.current().nextDouble() * chance < 1) return;
        if (w.skills.level(Skill.INTELLIGENCE) < (int) colony.happiness.of(w) * 9) w.learn(Skill.INTELLIGENCE, INTELLIGENCE_XP);
    }

    /** The library's first student asks and keeps for the building: its 64 are the building's, not each student's (the mod's keepX). */
    private boolean first() {
        var all = colony.jobsAt(hut);
        return !all.isEmpty() && all.getFirst() == this;
    }

    /** With no paper or books on hand or in the chest: a stack of either (the mod's study items request), once for the building. */
    @Override
    public List<Want> wants() {
        if (hut.level < 1 || !first() || has(Student::studyItem) + inChest(Student::studyItem) > 0) return List.of();
        return List.of(new Want("paper or books", Student::studyItem, KEPT, 1));
    }

    @Override
    int keep(ItemStack item) {
        return studyItem(item) && first() ? KEPT : 0;
    }

    @Override
    boolean keepsOnHand(ItemStack i) {
        return studyItem(i);
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("Students start once level 1 is built.", NamedTextColor.GRAY));
        return List.of(Component.text("Up to " + 2 * hut.level + " students study here once a minute at its bookshelves, with paper or books"
                + " (put some in the chest), which may raise their intelligence: it makes all their work experience count for more.", NamedTextColor.GRAY));
    }
}
