package dev.minecp.job;

/** How huscarls' and marksmen's damage is split (the mod's MeleeCombatAI and RangeCombatAI); apart from Guard, which only loads on a server. */
final class Piercing {
    private static final double MARKSMAN = 10; // the mod's; OPEN QUESTION in docs/parity.md

    private Piercing() {}

    /** The share of the damage that goes through armor: half, and the adaptability level / 2 percent. */
    static double share(int adaptability) {
        return (50 + adaptability / 2.0) / 100;
    }

    /** What a marksman's arrow itself does of a ranger's damage. */
    static double arrow(double damage, double share) {
        return damage * (1 - share);
    }

    /** What goes through armor besides, from what the arrow itself does (not from the ranger's damage). */
    static double through(double arrow, double share) {
        return arrow * share * MARKSMAN;
    }
}
