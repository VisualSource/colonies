package dev.minecp.citizen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A citizen's skills (the mod's CitizenSkillHandler, Skill and ExperienceUtils), the mod's numbers: eleven skills from
 * level 1 to 99, each with its experience towards the next level. Work's experience raises a job's two skills and
 * their complementary ones, and lowers the adverse ones.
 */
public final class Skills {
    /** The mod's skills, each with the one that grows along with it and the one that suffers (Intelligence has neither). */
    public enum Skill {
        ATHLETICS, DEXTERITY, STRENGTH, AGILITY, STAMINA, MANA, ADAPTABILITY, FOCUS, CREATIVITY, KNOWLEDGE, INTELLIGENCE;

        Skill complementary() {
            return switch (this) {
                case ATHLETICS -> STRENGTH; case DEXTERITY -> AGILITY; case STRENGTH -> ATHLETICS; case AGILITY -> DEXTERITY;
                case STAMINA -> KNOWLEDGE; case MANA -> FOCUS; case ADAPTABILITY -> CREATIVITY; case FOCUS -> MANA;
                case CREATIVITY -> ADAPTABILITY; case KNOWLEDGE -> STAMINA; case INTELLIGENCE -> null;
            };
        }

        Skill adverse() {
            return switch (this) {
                case ATHLETICS -> DEXTERITY; case DEXTERITY -> ATHLETICS; case STRENGTH -> AGILITY; case AGILITY -> STRENGTH;
                case STAMINA -> MANA; case MANA -> STAMINA; case ADAPTABILITY -> FOCUS; case FOCUS -> ADAPTABILITY;
                case CREATIVITY -> KNOWLEDGE; case KNOWLEDGE -> CREATIVITY; case INTELLIGENCE -> null;
            };
        }

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    private static final int MAX_LEVEL = 99; // MAX_CITIZEN_LEVEL

    private final Map<Skill, Integer> level = new EnumMap<>(Skill.class);
    private final Map<Skill, Double> xp = new EnumMap<>(Skill.class);

    Skills() {
        for (Skill s : Skill.values()) { level.put(s, 1); xp.put(s, 0.0); }
    }

    public int level(Skill s) {
        return level.get(s);
    }

    double xp(Skill s) {
        return xp.get(s);
    }

    /** Every skill a random level from 1 to {@code cap} - 1 (all 1 for a cap of 1 or less), as the mod's init. */
    public void roll(int cap) {
        var rnd = ThreadLocalRandom.current();
        for (Skill s : Skill.values()) { level.put(s, cap <= 1 ? 1 : rnd.nextInt(cap - 1) + 1); xp.put(s, 0.0); }
    }

    /**
     * A child's: rolled up to {@code cap}, then 25 to 49 points shared out by how much of the two role models' combined
     * levels each skill makes up (the mod's init with parents; a random citizen stands in for a missing parent).
     */
    public void inherit(int cap, Skills a, Skills b) {
        roll(cap);
        int bonus = 25 + ThreadLocalRandom.current().nextInt(25), total = 0;
        for (Skill s : Skill.values()) total += a.level(s) + b.level(s);
        for (Skill s : Skill.values()) level.put(s, level(s) + (int) ((a.level(s) + b.level(s)) / (double) total * bonus));
    }

    /** Experience for the next level from {@code level}: 1 + 5 x level + 0.005 x level³ (ExperienceUtils.getXPNeededForNextLevel). */
    static double needed(int level) {
        return level <= 0 ? 1 : Math.max(1, 1 + 5 * level + 0.005 * level * level * level);
    }

    /**
     * Adds experience to a skill, levelling it up as it fills (the mod's addXpToSkill). None while the skill has
     * reached its home's limit: 10 levels per home level plus 10 (homeless: 10), no limit with a home at the top level;
     * none at 99. @return whether it gained a level.
     */
    boolean add(Skill s, double amount, int homeLevel, int homeMaxLevel) {
        int lvl = level(s);
        if ((homeLevel < homeMaxLevel || homeMaxLevel < dev.minecp.colony.Hut.MAX_LEVEL) && (homeLevel + 1) * 10 <= lvl || lvl >= MAX_LEVEL) return false;
        double left = xp(s) + amount;
        while (left > 0 && lvl < MAX_LEVEL) {
            double next = needed(lvl);
            if (next > left) break;
            left -= next;
            lvl++;
        }
        boolean up = lvl > level(s);
        level.put(s, lvl);
        xp.put(s, lvl >= MAX_LEVEL ? 0 : left);
        return up;
    }

    /** Takes experience off a skill, losing levels as it runs out (never below 1; the mod's removeXpFromSkill). */
    void remove(Skill s, double amount) {
        int lvl = level(s);
        double have = xp(s);
        while (amount > 0) {
            if (have >= amount || lvl <= 1) { have = Math.max(0, have - amount); break; }
            amount -= have;
            have = needed(lvl - 1);
            lvl--;
        }
        level.put(s, lvl);
        xp.put(s, have);
    }

    /** How far a skill is towards its next level, 0 to 1. */
    public double progress(Skill s) {
        return level(s) >= MAX_LEVEL ? 1 : xp(s) / needed(level(s));
    }

    /** All its experience towards next levels (the mod's getTotalXP, dropped as orbs when it dies). */
    double total() {
        return xp.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** "ATHLETICS:12:3.5" per skill, for saving. */
    public List<String> save() {
        List<String> out = new ArrayList<>();
        for (Skill s : Skill.values()) out.add(s.name() + ":" + level(s) + ":" + xp(s));
        return out;
    }

    public void load(List<String> saved) {
        for (String line : saved) {
            String[] p = line.split(":");
            try {
                Skill s = Skill.valueOf(p[0]);
                level.put(s, Math.clamp(Integer.parseInt(p[1]), 1, MAX_LEVEL));
                xp.put(s, Double.parseDouble(p[2]));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {}
        }
    }
}
