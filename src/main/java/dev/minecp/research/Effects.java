package dev.minecp.research;

/**
 * The research effects Colonies applies, by the mod's effect ids (read with {@link Studies#effect}). A test checks each
 * is in research.json, so a typo can't silently read as 0.
 */
public final class Effects {
    private Effects() {}

    // citizens
    public static final String HEALTH = "healthaddition";
    public static final String WALKING = "walkingmultiplier";
    public static final String REGENERATION = "regenerationmultiplier";
    public static final String HEALING_SATURATION = "healingsaturationlimitaddition";
    public static final String SATURATION = "saturationmultiplier";
    public static final String HAPPINESS = "happinessmultiplier";
    public static final String GROWTH = "growthmultiplier";
    public static final String CITIZEN_CAP = "citizencapaddition";
    public static final String WORK_LONGER = "workingdayhaddition";
    public static final String GRAVE_DECAY = "gravedecaybonus";
    public static final String RESURRECT_CHANCE = "resurrectchanceaddition";
    public static final String UNDERTAKER_RUN = "undertakerrun";

    // workers
    public static final String BREAK_SPEED = "blockbreakspeedmultiplier";
    public static final String PLACE_SPEED = "blockplacespeedmultiplier";
    public static final String FARMING = "farmingmultiplier";
    public static final String TOOL_DURABILITY = "tooldurabilitymultiplier";
    public static final String RECIPES = "recipesmultiplier";
    public static final String MINIMUM_STOCK = "minimumstockmultiplier";
    public static final String INVENTORY_SLOTS = "citizeninvslotsaddition";
    public static final String FISH_TREASURE = "fishingtreasure";
    public static final String LOOTING = "looting";
    public static final String MINER_FIRE_RESISTANCE = "minerfireresunlock";
    public static final String WORK_IN_RAIN = "workinginrainunlock";
    public static final String SOFT_SHOES = "softshoesunlock";
    public static final String MORE_ORES = "moreoresmultiplier";
    public static final String CRUSHING_ONE_TO_ONE = "crushing11unlock";
    public static final String DEPTH_KNOWLEDGE = "knowledgeofthedepthsunlock";

    // knights
    public static final String MELEE_DAMAGE = "meleedamageaddition";
    public static final String MELEE_ARMOR = "meleearmormultiplier";
    public static final String BLOCK_ATTACKS = "blockattacksmultiplier";
    public static final String GUARD_CRIT = "guardcrit";
    public static final String KNIGHT_TAUNT = "knighttauntmobsunlock";
    public static final String SHIELD_USAGE = "shieldusageunlock";
    public static final String RETREAT = "retreatunlock";
    public static final String FLEEING_DAMAGE = "fleeingdamagemultiplier";
    public static final String FLEEING_SPEED = "fleeingspeedaddition";
    public static final String WHIRLWIND = "whirlwindabilityunlock";
    public static final String SLEEP_LESS = "sleeplessmultiplier";
    public static final String ARMOR_DURABILITY = "armordurabilitymultiplier";

    /** Every id above, for the test. */
    static final String[] ALL = {HEALTH, WALKING, REGENERATION, HEALING_SATURATION, SATURATION, HAPPINESS, GROWTH, CITIZEN_CAP,
            WORK_LONGER, GRAVE_DECAY, RESURRECT_CHANCE, UNDERTAKER_RUN, BREAK_SPEED, PLACE_SPEED, FARMING, TOOL_DURABILITY, RECIPES,
            MINIMUM_STOCK, INVENTORY_SLOTS, FISH_TREASURE, LOOTING, MINER_FIRE_RESISTANCE, WORK_IN_RAIN, SOFT_SHOES, MORE_ORES, CRUSHING_ONE_TO_ONE, DEPTH_KNOWLEDGE,
            MELEE_DAMAGE, MELEE_ARMOR, BLOCK_ATTACKS, GUARD_CRIT, KNIGHT_TAUNT, SHIELD_USAGE, RETREAT, FLEEING_DAMAGE, FLEEING_SPEED, WHIRLWIND,
            SLEEP_LESS, ARMOR_DURABILITY};
}
