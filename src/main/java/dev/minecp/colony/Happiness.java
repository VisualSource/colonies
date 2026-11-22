package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.job.Guard;
import dev.minecp.job.Job;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Citizens' happiness, 0 to 10 (the mod's CitizenHappinessHandler and its modifiers, with the mod's weights and
 * thresholds). Each factor is 1 when neutral, below 1 when it makes the citizen unhappy and above 1 when happy;
 * happiness is 10 × the weighted mean of the factors that aren't neutral. The counted days move on at nightfall.
 * Not here: sickness, idle-at-job, mystical site, raids and quests (no such features yet).
 */
public final class Happiness {
    private static final double MAX = 10;
    /** One factor: a name for the menus, its weight, and its value (1 neutral). */
    public record Factor(String name, double weight, double value) {}

    private final Colony colony;

    Happiness(Colony colony) {
        this.colony = colony;
    }

    public List<Factor> factors(Colonist c) {
        Job job = colony.jobOf(c);
        List<Factor> f = new ArrayList<>();
        f.add(new Factor("School", 1, c.child ? 0 : 1)); // no school yet: a child is never a pupil
        f.add(new Factor("Security", 4, security()));
        f.add(new Factor("Social life", 2, social()));
        f.add(new Factor("Food", 3, food(c)));
        f.add(new Factor("Housing", 3, timed(housing(c), c.homelessDays, 0.75, 0.5)));
        f.add(new Factor("Work", 2, timed(work(c, job), c.joblessDays, 0.75, 0.5)));
        f.add(new Factor("Sleep", 1.5, sleep(c, job)));
        f.add(new Factor("Got hurt", 2, c.hurtDays > 0 ? 0 : 1)); // neutral once it wears off, so colony averages count everyone
        f.add(new Factor("A citizen died", 3, c.griefDays > 0 ? 0 : 1));
        f.add(new Factor("Beat a raid", 1, c.defendedDays > 0 ? 2 : 1));
        return f;
    }

    /** 0 to 10. All factors neutral gives 10 (the mod divides by zero there). Research raises it by a share. */
    public double of(Colonist c) {
        return Math.min(MAX, of(factors(c)) * (1 + colony.research.effect("happinessmultiplier")));
    }

    static double of(List<Factor> factors) {
        double total = 0, weight = 0;
        for (Factor f : factors) {
            if (f.value() == 1) continue; // the mod leaves neutral factors out of the mean altogether
            total += f.value() * f.weight();
            weight += f.weight();
        }
        return weight == 0 ? MAX : Math.min(MAX, MAX * total / weight);
    }

    /** The colony's: the mean of its citizens', 5.5 with none (the mod's getOverallHappiness). */
    public double overall() {
        return colony.colonists.isEmpty() ? 5.5 : colony.colonists.stream().mapToDouble(this::of).average().orElse(5.5);
    }

    /** Nightfall: every time-based factor counts a day, and the short-lived ones run down (the mod's dayEnd). */
    void dayEnd() {
        for (Colonist c : colony.colonists) {
            Job job = colony.jobOf(c);
            c.homelessDays = housing(c) < 1 ? c.homelessDays + 1 : 0;
            c.joblessDays = work(c, job) < 1 ? c.joblessDays + 1 : 0;
            c.sleeplessDays++; // back to 0 when it lies down in bed
            c.hurtDays = Math.max(0, c.hurtDays - 1);
            c.griefDays = Math.max(0, c.griefDays - 1);
            c.defendedDays = Math.max(0, c.defendedDays - 1);
            // The mod's complaints at 7 days and demands at 14, as colony messages instead of its chat bubbles.
            if (c.home == null && (c.homelessDays == 8 || c.homelessDays == 15))
                colony.tell(c.name + (c.homelessDays == 8 ? " is unhappy without a home." : " demands a home!"));
            if (!c.child && job == null && (c.joblessDays == 8 || c.joblessDays == 15))
                colony.tell(c.name + (c.joblessDays == 8 ? " is unhappy without a job." : " demands a job!"));
        }
    }

    /** A citizen got hurt: unhappy for a day (the mod's "damage", value 0 for 1 day). */
    void hurt(Colonist c) {
        c.hurtDays = 1;
    }

    /** A citizen who wasn't a guard died: everyone is unhappy for 3 days (the mod's "death"). */
    void died() {
        for (Colonist c : colony.colonists) c.griefDays = 3;
    }

    /** A raid was beaten without losing anyone: everyone is happy for 3 days (the mod's "raidwithoutdeath", 2). */
    void defended() {
        for (Colonist c : colony.colonists) c.defendedDays = 3;
    }

    /** The mod's time-based modifier: an unhappy factor gets worse after 7 and 14 days. */
    private static double timed(double base, int days, double after7, double after14) {
        if (base >= 1) return base;
        return base * (days >= 14 ? after14 : days >= 7 ? after7 : 1);
    }

    /** Guards against the rest: min(guards / (others × 2/3), 2), both counted from 1, as the mod. */
    private double security() {
        int guards = 1, others = 1;
        for (Colonist c : colony.colonists) {
            if (colony.jobOf(c) instanceof Guard) guards++;
            else others++;
        }
        return Math.min(guards / (others * 2.0 / 3), 2);
    }

    /** (citizens − problems) / citizens: each jobless adult, homeless and hungry one is a problem (so it can go below 0, as the mod). */
    private double social() {
        int total = colony.colonists.size(), troubled = 0;
        for (Colonist c : colony.colonists) {
            if (!c.child && colony.jobOf(c) == null) troubled++;
            if (c.home == null) troubled++;
            if (c.saturation <= 1) troubled++;
        }
        return total == 0 ? 1 : (double) (total - troubled) / total;
    }

    /** 0 homeless, else the house level / 3 (the mod's homelessness). */
    private static double housing(Colonist c) {
        return c.home == null ? 0 : c.home.level / 3.0;
    }

    /** 0.5 without a job, 2 at a hut above level 3, else 1; children 1 (the mod's unemployment). */
    private static double work(Colonist c, Job job) {
        if (c.child) return 1;
        if (job == null) return 0.5;
        return job.hut.level > 3 ? 2 : 1;
    }

    /** Guards 1; others 0.5, × 2 for a night or less without a bed, 1.6 for 2, then 1 (the mod's slepttonight). */
    private static double sleep(Colonist c, Job job) {
        if (job instanceof Guard) return 1;
        int d = c.sleeplessDays;
        return 0.5 * (d >= 3 ? 1 : d >= 2 ? 1.6 : 2);
    }

    /**
     * The mod's food factor, from the last 10 foods: (min(5, kinds / house level) + min(5, good / max(1, level − 2))) / 2,
     * neutral when homeless or before 10 meals. Its "good" food is MineColonies' own dishes; ours is any food that is
     * made (cooked or crafted, like bread) rather than picked.
     */
    static double food(Colonist c) {
        if (c.home == null || c.home.level == 0 || c.diet.size() < Colonist.DIET_SIZE) return 1;
        int level = c.home.level;
        int kinds = Math.max(1, new HashSet<>(c.diet).size());
        long good = c.diet.stream().filter(Happiness::made).count();
        return (Math.min(5, (double) kinds / level) + Math.min(5, (double) good / Math.max(1, level - 2))) / 2;
    }

    private static final Map<Material, Boolean> MADE = new ConcurrentHashMap<>();

    private static boolean made(Material food) {
        return MADE.computeIfAbsent(food, m -> !Bukkit.getRecipesFor(new ItemStack(m)).isEmpty());
    }
}
