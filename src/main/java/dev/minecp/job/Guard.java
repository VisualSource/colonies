package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Skills.Skill;
import dev.minecp.colony.Colony;
import dev.minecp.colony.GuardPost.Task;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import dev.minecp.research.Effects;

import static dev.minecp.citizen.Skills.Skill.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Enemy;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.damage.DamageType;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A guard at a Guard Tower or a barracks tower: a knight (the mod's JobKnight, EntityAIMelee, MeleeCombatAI), a ranger
 * (JobRanger, EntityAIRange, RangeCombatAI) or a druid (JobDruid, EntityAIDruid, DruidCombatAI), by the tower's
 * setting for its slot, with the mod's AbstractEntityAIGuard: it patrols, guards a spot, follows a player or patrols a mine, attacks the
 * hostile mobs it sees with a sword, a bow or splash potions, and wears the armor its tower level allows. It doesn't sleep at night or shelter from rain, but naps on duty now and then; it doesn't run when hurt (unless research lets it
 * retreat to its tower). Research adds damage, critical hits and armor; knights also parrying, a shield, taunting and a
 * whirlwind; rangers double shots, piercing arrows and arrows from the tower.
 */
public final class Guard extends Job {
    // The mod's numbers (GuardConstants, AbstractBuildingGuards, BuildingGuardTower):
    private static final int ATTACK_DELAY = 32, ATTACK_DELAY_MIN = 16; // KNIGHT_ATTACK_DELAY_BASE less adaptability / 3, at least KNIGHT_ATTACK_DELAY_MIN
    private static final double REACH = 2;             // MAX_DISTANCE_FOR_ATTACK
    private static final double BASE_DAMAGE = 3;       // BASE_PHYSICAL_DAMAGE, on top of the sword's
    private static final int VISION_BASE = 15, VISION_PER_LEVEL = 3; // BASE_VISION_RANGE + VISION_RANGE_PER_LEVEL x level
    private static final int Y_VISION = 3;
    // How far it chases by task (MAX_PATROL_, MAX_FOLLOW_ and MAX_GUARD_DERIVATION, 20 more for a knight on guard):
    private static final int CHASE = 80, ESCORT_CHASE = 30, GUARD_CHASE = 10, GUARD_CHASE_KNIGHT = 20;
    private static final int TASK_TICKS = 100;         // GUARD_TASK_INTERVAL: how often it sees to its task
    private static final int COMBAT_TICKS = 600;       // COMBAT_TIME: this long after a fight it counts as in it
    private static final int FOLLOW_TIGHT = 6, FOLLOW_LOOSE = 15; // GUARD_FOLLOW_TIGHT_RANGE, GUARD_FOLLOW_LOSE_RANGE
    private static final int GLOW_TICKS = 600;         // GLOW_EFFECT_DURATION
    private static final int GUARD_REACH = 5, GUARD_ODDS = 10, RANGER_GUARD_ODDS = 30, DRUID_GUARD_ODDS = 3; // guardMovement: 1 time in these back to the spot
    private static final int GUARD_ROAM = 10, DRUID_ROAM = 20, DRUID_HEIGHT = 3; // EntityAIDruid: this far above or below it roams too
    private static final int RALLY_RUN = 30, RALLY_RUN_MAX = 3; // the mod's rally: a Speed level per 30 adaptability, 3 above the first at most
    private static final int GUARD_BONUS_RANGE = 10, GUARD_Y_VISION = 25; // rangers on guard shoot farther; ranged guards on guard look up and down farther
    private static final int ALERTED = 4;           // RaidManager: how many guards a raid alert sends
    private static final int CALL_RANGE_SQ = 40;       // PATROL_DEVIATION_RAID_POINT, which the mod compares with the squared distance
    private static final int HP_TOWER = 20, HP_PER_LEVEL = 2; // BONUS_HP_SINGLE_GUARD, BONUS_HEALTH_PER_LEVEL
    private static final double XP_PER_KILL = 15;      // EXP_PER_MOB_DEATH
    private static final int SEARCH_TICKS = 20;        // ours: how often it looks for a target
    // The armor each tower level allows (AbstractEntityAIFight.itemsNeeded): leather 0, gold 1, chain 2, iron 3, diamond 4, netherite 5.
    private static final int[][] ARMOR = {{0, 1}, {0, 2}, {0, 3}, {2, 4}, {3, 5}};
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    // Research (the mod's MeleeCombatAI, EntityCitizen and AbstractEntityAIGuard):
    private static final double CRIT = 1.5;            // a critical hit's damage
    private static final int SHIELD_UP = 8;            // MIN_TIME_TO_ATTACK: the shield is raised while its next swing is this far off
    private static final double SHIELD_HIT = 0.5;      // GUARD_BLOCK_DAMAGE: a blocked hit still costs this share, after armor
    private static final int WHIRLWIND_ODDS = 5, WHIRLWIND_COOLDOWN = 240; // KNOCKBACK_CHANCE, KNOCKBACK_COOLDOWN
    private static final double LOW_HEALTH = 0.2, RESTED = 0.75; // retreats below 20% of its health; rests up to 75%
    private static final int RETREAT_FROM = 20;        // only farther than this (squared distance) from its tower
    private static final double REST_ALARM = 10;       // its attacker this close ends the rest
    private static final double WHIRLWIND_REACH = 2, WHIRLWIND_HEIGHT = 0.5, WHIRLWIND_KNOCKBACK = 2;
    private static final int REST_CHECK = 40;          // GUARD_REGEN_INTERVAL
    private static final int REST_HEAL = 50;           // vanilla Regeneration I, which the mod gives it: 1 health every 50 ticks
    private static final double SPEED_PER_LEVEL = 0.2; // vanilla Speed: +20% a level
    // Rangers (the mod's GuardConstants, RangeCombatAI, CombatUtils, EntityAIRange):
    private static final int RANGED_DELAY = 60;        // RANGED_ATTACK_DELAY_BASE: ticks between shots
    private static final int RANGE_BASE = 10, RANGE_MAX = 24; // BASE_ and MAX_DISTANCE_FOR_RANGED_ATTACK, +1 a tower level
    private static final double RANGED_BASE_DAMAGE = 2; // RANGER_BASE_DMG
    private static final double ARROW_DAMAGE = 2;      // ARROW_EXTRA_DAMAGE: with arrows (research)
    private static final int FLEE_DISTANCE_SQ = 7, FLEE_ODDS = 3; // RANGED_FLEE_SQDIST: this close, 1 in 3 shots it steps back
    private static final double ARROW_SPEED = 1.4, SPEED_FOR_DISTANCE = 35, AIM_HIGHER = 0.18; // CombatUtils.shootArrow
    private static final double HIT_CHANCE_DIVIDER = 15; // the aim's wobble: HIT_CHANCE_DIVIDER / (adaptability + 1)
    private static final int ARROWS_TAKEN = 64, ARROWS_LOW = 16, ARROWS_KEPT = 128; // it takes 64, asks below 16; the tower keeps 128
    private static final int FLAME_TICKS = 100;        // a Flame bow's arrows burn 5 s
    // Druids (the mod's GuardConstants, DruidCombatAI, DruidPotionEntity, EntityAIDruid, JobDruid):
    private static final int DRUID_DELAY = 40;         // AttackMoveAI's attack delay, doubled after an instant effect
    private static final int DRUID_RANGE = 3;          // BASE_DISTANCE_FOR_POTION_ATTACK, +1 a tower level
    private static final int EFFECT_TICKS_PER_MANA = 20;   // a druid's potions last its mana level x 20 ticks
    private static final double POTION_WOBBLE = 99;        // their wobble: 99 / its focus level
    private static final double XP_PER_POTION = 0.05;      // PER_POTION_XP
    private static final double POTION_SPEED = 0.5, POTION_AIM_HIGHER = 0.2, POTION_DROP = 1.1;
    private static final int HUNTED_RANGE = 16, HUNTED_HEIGHT = 8; // ours: how near a monster after a player must be
    private static final int POTIONS_TAKEN = 32, POTIONS_LOW = 8, POTIONS_ASKED = 16, POTIONS_KEPT = 32; // ours: the kept amount
    private static final org.bukkit.potion.PotionEffectType[] ADVERSE = {org.bukkit.potion.PotionEffectType.SLOWNESS,
            org.bukkit.potion.PotionEffectType.WEAKNESS};
    private static final org.bukkit.potion.PotionEffectType[] SUPPORT = {org.bukkit.potion.PotionEffectType.STRENGTH,
            org.bukkit.potion.PotionEffectType.SATURATION, org.bukkit.potion.PotionEffectType.INSTANT_HEALTH,
            org.bukkit.potion.PotionEffectType.RESISTANCE};
    /** Tags on a druid's thrown potions: harmful ones touch only monsters, helpful ones everyone else (MineCP.onDruidPotion). */
    public static final String ADVERSE_POTION = "minecp_druid_adverse", SUPPORT_POTION = "minecp_druid_support";
    // Naps on duty (the mod's AbstractEntityAIGuard.shouldSleep, sleep and wakeUpGuard):
    private static final int NAP_CHECK = 200;          // SHOULD_SLEEP_INTERVAL: a chance to nap every 10 s with no target
    private static final int NAP_ODDS = 20;            // 1 in (adaptability / 2 + 20)
    private static final int NAP_MIN = 2500, NAP_SPREAD = 500; // it naps 2500 to 3000 ticks
    private static final int NAP_HEAL_TICKS = 30;      // PARTICLE_INTERVAL
    private static final double NAP_HEAL = 0.5;        // the mod's: health each time its sleep particles show
    private static final int WAKE_GIVE_UP = 30 * 20;   // the mod's: 30 tries a second apart before giving up waking another
    private static final double WAKE_REACH = 2.25;     // the mod's: squared distance close enough to wake it

    private final Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class); // worn; saved
    private ItemStack shield; // carried once researched; saved
    private LivingEntity target;
    private Block goingTo, minePoint;
    private Location lastQuiet;                       // where it last saw to its task without a fight; null: its hut
    private boolean rallying, gearing;                // with a rally banner's bearer; on its way to fetch gear
    private int running;                              // a rallying guard's Speed level
    private int attackIn, searchIn, taskIn, fighting, restTicks;
    private long lastWhirlwind;
    private boolean fleeing, resting; // retreating to the tower, then resting there (not saved: it simply carries on)
    private int napTicks, napCheckIn = NAP_CHECK;    // napping while napTicks > 0 (not saved)
    private Colonist toWake;                          // a napping knight it is on its way to wake
    private int wakeFor;
    private Location chasing; // where its route to the target leads

    public Guard(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** The guard types (the setting's values), with the mod's numbers: weapon, health bonus, armor research. */
    private enum Type {
        // with the mod's guard types' primary and secondary skills (ModGuardTypesInitializer)
        KNIGHT(Tool.SWORD, 15, Effects.MELEE_ARMOR, ADAPTABILITY, STAMINA), // KNIGHT_HP_BONUS
        RANGER(Tool.BOW, 0, Effects.ARCHER_ARMOR, AGILITY, ADAPTABILITY),
        DRUID(null, 12, null, MANA, FOCUS); // DRUID_HP_BONUS; it throws potions

        final Tool weapon;
        final int health;
        final String armorResearch;
        final Skill primary, secondary;

        Type(Tool weapon, int health, String armorResearch, Skill primary, Skill secondary) {
            this.weapon = weapon;
            this.health = health;
            this.armorResearch = armorResearch;
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    private int slot = -1; // which of its tower's guards it is, for its type setting

    /** Its type, from its tower's setting for its slot. */
    private Type type() {
        if (slot < 0) slot = colony.jobsAt(hut).indexOf(this); // stays -1 until it is one of the colony's jobs
        return Type.valueOf(hut.setting(Settings.guardTypeKey(Math.max(slot, 0))));
    }

    private boolean knight() {
        return type() == Type.KNIGHT;
    }

    private boolean ranger() {
        return type() == Type.RANGER;
    }

    private boolean druid() {
        return type() == Type.DRUID;
    }

    @Override
    public Skill[] skills() {
        return sk(type().primary, type().secondary);
    }

    /** "Knight", "Ranger" or "Druid". */
    @Override
    public String title() {
        String t = type().name();
        return t.charAt(0) + t.substring(1).toLowerCase();
    }

    /** Whether that job trains this kind of guard (the mod's archer and knight in training). */
    public boolean trainedBy(Job trainee) {
        return knight() && trainee instanceof KnightTrainee || ranger() && trainee instanceof ArcherTrainee;
    }

    /** Its weapon: a sword, a bow, or none (a druid throws potions). */
    private Tool weapon() {
        return type().weapon;
    }

    /**
     * Health with its type's bonus (knight 15 and its stamina level, druid 12 and half its mana level, ranger none) and
     * its tower's: 2 a level, and 20 more for a Guard Tower's single guard (the mod adds them as max health modifiers).
     */
    @Override
    public double maxHealth() {
        double tower = hut.level < 1 ? 0 : HP_PER_LEVEL * hut.level + (hut.type == Hut.Type.GUARD_TOWER ? HP_TOWER : 0);
        double skill = switch (type()) { case KNIGHT -> level(STAMINA); case DRUID -> level(MANA) / 2.0; default -> 0; };
        return Colonist.MAX_HEALTH + type().health + skill + tower;
    }

    /**
     * What reaches it, in the mod's order: research may parry the whole hit; below 20% health research softens it; a
     * raised shield blocks a hit from the mob it fights, which still costs half, after armor, and wears the shield
     * (ours: "from its target" stands in for vanilla's facing check); then its armor.
     */
    @Override
    public double reaching(double damage, DamageType type, Entity source, Colonist w) {
        double parry = colony.research.effect(Effects.BLOCK_ATTACKS);
        if (knight() && parry > 0 && ThreadLocalRandom.current().nextDouble() < parry) return 0; // the mod's knights only
        if (lowHealth(w)) damage *= 1 - colony.research.effect(Effects.FLEEING_DAMAGE);
        if (shieldUp() && source != null && source == target) {
            int wear = (int) (damage * SHIELD_HIT);
            if (wear > 0) {
                ItemStack worn = shield.damage(wear, w.living()); // Unbreaking included
                shield = worn.isEmpty() ? null : worn;
            }
            return w.health > damage * SHIELD_HIT ? Math.max(SHIELD_HIT, afterArmor(damage * SHIELD_HIT)) : 0;
        }
        double through = afterArmor(damage);
        if (!fireOrLightning(type)) wearArmor(damage, w);
        return through;
    }

    /**
     * The mod's updateArmorDamage after a hit: each piece wears by a quarter of the hit, at least 1, and breaks when worn
     * out; research skips it with chance 1 - 1 / (1 + strength). Not for fire or lightning, as the mod.
     */
    private void wearArmor(double hit, Colonist w) {
        double durability = colony.research.effect(Effects.ARMOR_DURABILITY);
        if (durability > 0 && ThreadLocalRandom.current().nextDouble() > 1 / (1 + durability)) return;
        int wear = Math.max(1, (int) (hit / 4));
        armor.replaceAll((slot, piece) -> piece.damage(wear, w.living())); // Unbreaking included
        armor.values().removeIf(ItemStack::isEmpty);
    }

    /** What reaches it through its armor (vanilla's armor formula with each piece's points and toughness; research adds points). */
    private double afterArmor(double damage) {
        double points = 0, toughness = 0;
        for (ItemStack piece : armor.values()) { points += stat(piece, Attribute.ARMOR); toughness += stat(piece, Attribute.ARMOR_TOUGHNESS); }
        String research = type().armorResearch;
        points = (int) (points * (1 + (research == null ? 0 : colony.research.effect(research))));
        double cut = Math.min(20, Math.max(points / 5, points - damage / (2 + toughness / 4)));
        return damage * (1 - cut / 25);
    }

    /** Its shield is raised: researched, carried, fighting, and its next swing a while off. */
    private boolean shieldUp() {
        return shield != null && target != null && attackIn >= SHIELD_UP && shieldResearched();
    }

    /** Running back to the tower, with Speed from research (the mod gives a Speed effect of the research's level). */
    @Override
    public double pace() {
        double speed = colony.research.effect(Effects.FLEEING_SPEED);
        return fleeing && speed > 0 ? 1 + SPEED_PER_LEVEL * ((int) speed + 1) : rallying ? 1 + SPEED_PER_LEVEL * running : 1;
    }

    /** Something hurt it: it fights back (a mob; players are left to permissions) instead of running; a ranger needs its bow. */
    @Override
    public boolean fightsBack(Entity source) {
        if (!canFight()) return false;
        if (source instanceof LivingEntity l && !(source instanceof org.bukkit.entity.Player) && colony.colonistOf(source) == null && valid(l) && l != target) {
            target = l;
            callOthers(colony.worker(this));
        }
        return true;
    }

    /** A knight fights with its fists if it must; a ranger without a bow can't attack (the mod's canAttack). */
    private boolean canFight() {
        return !ranger() || hasTool(Tool.BOW, null);
    }

    @Override
    public boolean interruptible() {
        return target == null && fighting <= 0 && !fleeing && !resting && !escorting();
    }

    @Override
    public boolean sleepsAtNight() {
        return false; // on duty (it naps now and then instead, as the mod's guards)
    }

    @Override
    public boolean worksInRain() {
        return true;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("Guards start once level 1 is built.", NamedTextColor.GRAY));
        int[] tier = ARMOR[Math.min(hut.level, Hut.MAX_LEVEL) - 1];
        return List.of(Component.text(taskInfo(c)), Component.text("Guards fight the monsters they see within " + vision() + " blocks: knights with "
                + toolLabel(Tool.SWORD) + ", rangers with " + toolLabel(Tool.BOW) + " from up to " + range() + " blocks"
                + (hut.type == Hut.Type.BARRACKS_TOWER ? ", druids with splash potions from " + druidRange()
                        + " (slowing monsters, strengthening players they fight)" : "") + ". They wear " + armorName(tier[0]) + " to "
                + armorName(tier[1]) + " armor. Pick each guard's type in Settings.", NamedTextColor.GRAY));
    }

    private String taskInfo(Colony c) {
        Block spot = hut.post.guardSpot();
        Hut mine = hut.post.mine(c);
        String followed = hut.post.followed() == null ? null : org.bukkit.Bukkit.getOfflinePlayer(hut.post.followed()).getName();
        int points = hut.post.patrolPoints().size();
        return "Task: " + switch (task()) {
            case GUARD -> "guard " + spot.getX() + " " + spot.getY() + " " + spot.getZ();
            case FOLLOW -> followed == null ? "follow (nobody yet)" : "follow " + followed;
            case PATROL_MINE -> mine == null ? "patrol a mine (assign one in a Miner's Hut's menu)" : "patrol the mine at " + mine.block.getX() + " " + mine.block.getZ();
            case PATROL -> !hut.setting(Settings.PATROL_MODE).equals("MANUAL") ? "patrol the colony"
                    : points == 0 ? "patrol the colony (no patrol points set)" : "patrol " + points + " points";
        } + ".";
    }

    // ---- gear ---------------------------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        if (weapon() != null) wantTool(wants, weapon());
        if (wantsPotions() && inChest(Alchemist::isMagicPotion) == 0)
            wants.add(new Want(PostBox.label(Alchemist.magicPotion()), Alchemist::isMagicPotion, POTIONS_ASKED, POTIONS_LOW));
        if (wantsArrows() && inChest(Guard::isArrow) == 0) wants.add(new Want("arrows", Guard::isArrow, ARROWS_TAKEN, ARROWS_LOW));
        for (EquipmentSlot slot : SLOTS) // the mod's guards ask for each missing piece; one the tower no longer allows counts as missing
            if (!fitsArmor(armor.get(slot), slot) && inChest(i -> fitsArmor(i, slot)) == 0)
                wants.add(new Want(slotName(slot) + " (" + armorName(tier()[0]) + " to " + armorName(tier()[1]) + ")", i -> fitsArmor(i, slot), 1));
        if (wantsShield() && inChest(Guard::isShield) == 0) wants.add(new Want("a shield", Guard::isShield, 1)); // the mod's "nice to have"
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        if (weapon() != null && fits(weapon(), item, null) || isShield(item)) return 1;
        if (isArrow(item) && arrowsResearched()) return ARROWS_KEPT;
        if (Alchemist.isMagicPotion(item) && druid()) return POTIONS_KEPT;
        for (EquipmentSlot slot : SLOTS) if (fitsArmor(item, slot)) return 1;
        return 0;
    }

    /** Research lets it use a shield, and it has none. */
    private boolean wantsShield() {
        return shield == null && shieldResearched();
    }

    private boolean shieldResearched() {
        return knight() && colony.research.effect(Effects.SHIELD_USAGE) > 0; // the mod's knights only
    }

    private boolean arrowsResearched() {
        return ranger() && colony.research.effect(Effects.USE_ARROWS) > 0;
    }

    /** A ranger with the arrows research and under 16 arrows (the mod's EntityAIRange). */
    private boolean wantsArrows() {
        return arrowsResearched() && has(Guard::isArrow) < ARROWS_LOW;
    }

    private static boolean isArrow(ItemStack i) {
        return i != null && i.getType() == Material.ARROW;
    }

    /** A druid that research lets use magic potions and has under 8. */
    private boolean wantsPotions() {
        return potionsResearched() && has(Alchemist::isMagicPotion) < POTIONS_LOW;
    }

    private boolean potionsResearched() {
        return druid() && colony.research.effect(Effects.DRUID_POTIONS) > 0;
    }

    /** Its arrows and potions stay with it when it hands things in. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        return isArrow(i) && arrowsResearched() || Alchemist.isMagicPotion(i) && druid();
    }

    static boolean isShield(ItemStack i) {
        return i != null && i.getType() == Material.SHIELD;
    }

    private int[] tier() {
        return ARMOR[Math.max(1, Math.min(hut.level, Hut.MAX_LEVEL)) - 1];
    }

    private boolean fitsArmor(ItemStack i, EquipmentSlot slot) {
        if (i == null || i.isEmpty() || i.getType().getEquipmentSlot() != slot || stat(i, Attribute.ARMOR) <= 0) return false;
        int t = armorTier(i.getType());
        return t >= tier()[0] && t <= tier()[1];
    }

    /**
     * Whether a trip to the tower would change its gear: a sword when it has none, a worn piece the tower level no
     * longer allows, or a better piece the tower allows in the chest.
     */
    private boolean gearWaiting() {
        if (weapon() != null && !hasTool(weapon(), null) && inChest(i -> fits(weapon(), i, null)) > 0) return true;
        if (wantsArrows() && inChest(Guard::isArrow) > 0) return true;
        if (wantsPotions() && inChest(Alchemist::isMagicPotion) > 0) return true;
        if (wantsShield() && inChest(Guard::isShield) > 0) return true;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = armor.get(slot);
            if (worn != null && !fitsArmor(worn, slot)) return true;
            if (inChest(i -> fitsArmor(i, slot) && better(i, worn)) > 0) return true;
        }
        return false;
    }

    private boolean better(ItemStack piece, ItemStack worn) {
        return worn == null || stat(piece, Attribute.ARMOR) > stat(worn, Attribute.ARMOR);
    }

    /** At the tower: takes a sword it lacks, puts back armor the tower no longer allows, and wears the best pieces allowed. */
    private void gearUp() {
        if (weapon() != null && !hasTool(weapon(), null)) fetchTool(weapon(), null);
        if (wantsArrows()) fetch(Guard::isArrow, ARROWS_TAKEN - has(Guard::isArrow));
        if (wantsPotions()) fetch(Alchemist::isMagicPotion, POTIONS_TAKEN - has(Alchemist::isMagicPotion));
        Inventory chest = chest();
        if (chest == null) return;
        if (wantsShield()) for (int i = 0; i < chest.getSize() && shield == null; i++) {
            ItemStack item = chest.getItem(i);
            if (!isShield(item)) continue;
            shield = item.asOne();
            item.subtract();
            chest.setItem(i, item.isEmpty() ? null : item);
        }
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = armor.get(slot);
            if (worn != null && !fitsArmor(worn, slot)) { putBack(chest, worn); armor.remove(slot); worn = null; } // the tower level changed
            for (int i = 0; i < chest.getSize(); i++) {
                ItemStack c = chest.getItem(i);
                if (!fitsArmor(c, slot) || !better(c, worn)) continue;
                armor.put(slot, c.asOne());
                c.subtract();
                chest.setItem(i, c.isEmpty() ? null : c);
                if (worn != null) putBack(chest, worn);
                worn = armor.get(slot);
            }
        }
    }

    /** Into the chest; what doesn't fit drops at the hut, so no armor is ever lost. */
    private void putBack(Inventory chest, ItemStack item) {
        for (ItemStack over : chest.addItem(item).values())
            hut.block.getWorld().dropItemNaturally(hut.block.getLocation().toCenterLocation(), over);
    }

    /** Shows its armor and sword on the body (it is spawned again when its chunk loads). */
    private void dress(Colonist w) {
        if (w.living() == null) return;
        EntityEquipment e = w.living().getEquipment();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack want = armor.get(slot);
            if (!java.util.Objects.equals(e.getItem(slot).isEmpty() ? null : e.getItem(slot), want)) e.setItem(slot, want);
        }
        ItemStack weapon = weapon() == null ? null : tool(weapon());
        if (!java.util.Objects.equals(e.getItemInMainHand().isEmpty() ? null : e.getItemInMainHand(), weapon)) w.hold(weapon);
        ItemStack off = shieldResearched() ? shield : null; // a ranger keeps a knight's shield but doesn't carry it
        if (!java.util.Objects.equals(e.getItemInOffHand().isEmpty() ? null : e.getItemInOffHand(), off)) e.setItemInOffHand(off);
    }

    // ---- work ---------------------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        dress(w);
        if (hut.level < 1) {
            w.setStatus(title() + ": idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (attackIn > 0) attackIn--;
        if (target != null && target.isDead()) experience(w, XP_PER_KILL); // the mod's onTargetDied: what it fought died
        if (!valid(target)) target = null;
        if (!fleeing && !resting && shouldRetreat(w)) { wakeUp(w); fleeing = true; target = null; }
        if (fleeing) { retreat(w); return; }
        if (resting && rest(w)) return;
        if (napTicks > 0 && nap(w)) return;
        if (target != null && !canFight()) target = null;
        if (target == null && --searchIn <= 0 && canFight()) {
            searchIn = SEARCH_TICKS;
            target = druid() ? druidTarget(w) : nearestEnemy(w);
            if (target != null) callOthers(w);
            else if (toWake == null) toWake = napper(w);
        }
        if (target != null && druid() && affected(target)) target = null; // the mod's druid moves on once its potion took
        if (target != null) { fighting = COMBAT_TICKS; if (ranger()) shoot(w); else if (druid()) throwPotion(w); else fight(w); return; }
        if (toWake != null && wake(w)) return;
        if (--napCheckIn <= 0) {
            napCheckIn = NAP_CHECK;
            if (w.hurtTicks == 0 && fighting <= 0 && w.disease == null && startNap(w)) return; // walking or not, not sick nor just out of a fight, as the mod's: sitting down stops it
        }
        if (gearing) {
            if (w.isMoving()) return;
            if (!atHut(w)) { w.setStatus(title() + ": getting gear"); goToHut(w); return; }
            gearUp();
            gearing = false;
        }
        if (--taskIn > 0) return;
        taskIn = TASK_TICKS;
        if (fighting > 0) fighting -= TASK_TICKS;
        if (fighting <= 0) lastQuiet = w.location();
        gearing = !escorting() && gearWaiting();
        if (gearing) return;
        note = weapon() == null || hasTool(weapon(), null) ? null : ranger() ? "No bow: it can't fight. Put " + toolLabel(Tool.BOW) + " in the tower's chest."
                : "No sword: it fights with its fists. Put " + toolLabel(Tool.SWORD) + " in the tower's chest.";
        task(w);
    }

    // ---- its task (the mod's decide, every 5 s without a target) ---------------------------------------------------

    private Task task() {
        return hut.post.task();
    }

    /** On the move with a player: rallied, or told to follow. It doesn't eat, see a healer or fetch gear meanwhile. */
    private boolean escorting() {
        return rallying || task() == Task.FOLLOW;
    }

    private void task(Colonist w) {
        org.bukkit.entity.Player leader = hut.post.rallied(colony);
        rallying = leader != null;
        if (escorting()) w.living().addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, GLOW_TICKS, 0, false, false));
        else w.living().removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
        if (leader != null) { rallyTo(w, leader); return; }
        switch (task()) {
            case GUARD -> guardSpot(w);
            case FOLLOW -> follow(w);
            case PATROL_MINE -> patrolMine(w);
            case PATROL -> patrol(w);
        }
    }

    /** To the point its hut's guards patrol now; together they go on from there. One whose chunk isn't loaded counts as reached. */
    private void patrol(Colonist w) {
        w.setStatus(title() + ": patrolling");
        Block to = hut.post.point();
        if (to.getWorld().isChunkLoaded(to.getX() >> 4, to.getZ() >> 4) && !w.isBeside(to)) { walkTo(w, to); return; }
        hut.post.arrived(w, colony);
    }

    /** Keeps near the spot it guards, the ranged guards moving about it (ours: to any spot around, the mod's picks edges). */
    private void guardSpot(Colonist w) {
        w.setStatus(title() + ": guarding");
        Block spot = hut.post.guardSpot();
        Location centre = spot.getLocation().toCenterLocation();
        double off = w.location().distance(centre);
        var rnd = ThreadLocalRandom.current();
        if (knight() || ranger()) {
            if (rnd.nextInt(knight() ? GUARD_ODDS : RANGER_GUARD_ODDS) == 0) { if (off > GUARD_REACH) walkTo(w, spot); return; }
            if (knight() || w.isMoving()) return;
        } else if (rnd.nextInt(DRUID_GUARD_ODDS) == 0) { walkTo(w, spot); return; }
        if (off <= GUARD_ROAM || druid() && Math.abs(w.location().getY() - spot.getY()) > DRUID_HEIGHT) w.walkRandom(centre, ranger() ? GUARD_ROAM : DRUID_ROAM);
        else walkTo(w, spot);
    }

    /** Keeps within 6 or 15 blocks of the player it follows; more than 30 off it is brought there. */
    private void follow(Colonist w) {
        w.setStatus(title() + ": following");
        Location at = w.location(), to = hut.post.followTo();
        if (Colonist.flat(at, to) > ESCORT_CHASE) w.teleportNear(to.getBlock());
        else if (at.distance(to) > (hut.setting(Settings.FOLLOW_MODE).equals("TIGHT") ? FOLLOW_TIGHT : FOLLOW_LOOSE)) walkTo(w, to.getBlock());
    }

    /** To a spot within 3 blocks of the banner's bearer, running the faster the more adaptable it is. */
    private void rallyTo(Colonist w, org.bukkit.entity.Player leader) {
        w.setStatus(title() + ": rallying");
        var rnd = ThreadLocalRandom.current();
        Block to = leader.getLocation().getBlock().getRelative(rnd.nextInt(FOLLOW_TIGHT) - FOLLOW_TIGHT / 2, 0, rnd.nextInt(FOLLOW_TIGHT) - FOLLOW_TIGHT / 2);
        if (w.location().distance(to.getLocation().toCenterLocation()) <= FOLLOW_TIGHT) { running = 0; return; }
        running = 1 + Math.clamp(level(ADAPTABILITY) / RALLY_RUN, 0, RALLY_RUN_MAX);
        walkTo(w, to);
    }

    /** Between the dug nodes of its mine, on its own. */
    private void patrolMine(Colonist w) {
        Hut mine = hut.post.mine(colony);
        if (mine == null) {
            if (hut.post.mineGone()) return;
            w.setStatus(title() + ": no mine");
            note = "No mine to patrol: assign this hut in a Miner's Hut's menu.";
            return;
        }
        w.setStatus(title() + ": patrolling the mine");
        if (minePoint == null || w.isBeside(minePoint)) minePoint = colony.jobAt(mine) instanceof Miner m ? m.patrolPoint() : mine.block;
        walkTo(w, minePoint);
    }

    private void walkTo(Colonist w, Block to) {
        if (w.isBeside(to) || w.isMoving() && to.equals(goingTo)) return;
        goingTo = to;
        w.goBeside(to);
    }

    /** The 4 guards nearest {@code at}, within {@code range} blocks, that are free to help have their huts patrol there next; any of those free that nap wake up (the mod's raid alert). */
    public static void alert(Colony colony, Block at, int range) {
        Location l = at.getLocation();
        record Near(Guard g, double d) {}
        List<Near> near = new ArrayList<>();
        for (Job j : colony.jobs) {
            Colonist w = colony.worker(j);
            if (!(j instanceof Guard g) || w == null || w.location().getWorld() != l.getWorld() || !g.interruptible()) continue;
            double d = w.location().distance(l);
            if (d < range && (g.task() != Task.GUARD || g.inReach(l, g.chase()))) {
                if (g.napTicks > 0) g.wakeUp(w);
                near.add(new Near(g, d));
            }
        }
        near.sort(java.util.Comparator.comparingDouble(Near::d));
        for (Near n : near.subList(0, Math.min(ALERTED, near.size()))) n.g.hut.post.alert(at);
    }

    /** How far it chases from where its task has it. */
    private int chase() {
        return rallying ? ESCORT_CHASE : switch (task()) {
            case PATROL -> CHASE;
            case FOLLOW, PATROL_MINE -> ESCORT_CHASE;
            case GUARD -> GUARD_CHASE + (knight() ? GUARD_CHASE_KNIGHT : 0);
        };
    }

    /** Within its chase and {@code attack} blocks of where its task has it: the spot it guards, the player it follows, or where it last was without a fight. */
    private boolean inReach(Location at, double attack) {
        Location from = switch (task()) {
            case GUARD -> hut.post.guardSpot().getLocation().toCenterLocation();
            case FOLLOW -> hut.post.followTo();
            case PATROL, PATROL_MINE -> lastQuiet == null ? hut.block.getLocation() : lastQuiet;
        };
        return from.getWorld() == at.getWorld() && from.distance(at) <= chase() + attack;
    }

    /** It found something to fight: its hut's others join in, and a raider draws the guard huts right by it (the mod's notifyGuardsOfTarget; not a druid's). */
    private void callOthers(Colonist w) {
        if (druid() || !(target instanceof Enemy)) return; // ours: what isn't a monster but hit it is its own fight
        for (Job j : colony.jobsAt(hut)) {
            Colonist other = colony.worker(j);
            if (j instanceof Guard g && g != this && other != null && g.target == null && other.hurtTicks == 0 && g.napTicks <= 0 && g.canFight() && g.valid(target)) g.target = target;
        }
        if (colony.raids.isRaider(target)) for (Hut h : colony.huts)
            if (h.post != null && h.block.getWorld() == w.location().getWorld() && h.block.getLocation().distanceSquared(w.location()) < CALL_RANGE_SQ) h.post.alert(target.getLocation().getBlock());
    }

    private void fight(Colonist w) {
        w.setStatus(title() + ": fighting");
        Location at = w.location(), there = target.getLocation();
        if (at.distance(there) > REACH) {
            // A new route only when it stopped or the target has moved away from where it was heading: each search costs time.
            if (!w.isMoving() || chasing == null || chasing.distance(there) > 2) { w.walk(there); chasing = there.clone(); }
            return;
        }
        chasing = null;
        w.stopWalking();
        if (attackIn > 0) return;
        attackIn = Math.max(ATTACK_DELAY - level(ADAPTABILITY) / 3, ATTACK_DELAY_MIN); // the mod's knight getAttackDelay
        w.swing();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.NEUTRAL, 1, 1);
        double hit = damage(w);
        if (ThreadLocalRandom.current().nextDouble() > 1 / (1 + colony.research.effect(Effects.GUARD_CRIT))) { // research: a critical hit
            hit *= CRIT;
            there.getWorld().spawnParticle(Particle.CRIT, there.clone().add(0, target.getHeight() / 2, 0), 12, 0.3, 0.3, 0.3, 0.2);
        }
        whirlwind(w, hit); // before the blow, as the mod
        target.damage(hit, w.living());
        if (target instanceof Mob m && colony.research.effect(Effects.KNIGHT_TAUNT) > 0) m.setTarget(w.living()); // research: it draws the mob onto itself
        if (hasTool(Tool.SWORD, null)) wear(Tool.SWORD, w);
    }

    /**
     * The sword's attack damage (as the mod's SwordItem.getDamage) plus the mod's base 3, and research's; with no sword,
     * the base alone. Doubled at 20% health or less, as the mod's getAttackDamage.
     */
    private double damage(Colonist w) {
        ItemStack sword = tool(Tool.SWORD);
        double d = BASE_DAMAGE + (sword == null ? 0 : stat(sword, Attribute.ATTACK_DAMAGE)) + colony.research.effect(Effects.MELEE_DAMAGE);
        return w.health <= colony.health.max(w) * LOW_HEALTH ? d * 2 : d;
    }

    /**
     * A ranger's fight (the mod's RangeCombatAI): it closes in until it sees the target within its range, then shoots
     * every 3 s; one shot in 3 with the target very close, it steps back half its range first.
     */
    private void shoot(Colonist w) {
        if (!readyFrom(w, range())) return;
        attackIn = RANGED_DELAY;
        var rnd = ThreadLocalRandom.current();
        w.swing();
        int shots = rnd.nextDouble() < colony.research.effect(Effects.DOUBLE_ARROWS) ? 2 : 1; // research: a second arrow
        for (int i = 0; i < shots; i++) arrow(w);
        wear(Tool.BOW, w);
    }

    /** One arrow at the target, aimed and sped up with distance as the mod's CombatUtils.shootArrow. */
    private void arrow(Colonist w) {
        LivingEntity body = w.living();
        ItemStack bow = tool(Tool.BOW);
        org.bukkit.entity.Arrow a = body.launchProjectile(org.bukkit.entity.Arrow.class);
        Location from = a.getLocation();
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        double dy = target.getBoundingBox().getMinY() + target.getHeight() / 2 - from.getY();
        double flat = Math.sqrt(dx * dx + dz * dz), dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var rnd = ThreadLocalRandom.current();
        a.setVelocity(wobble(new org.bukkit.util.Vector(dx, dy + flat * AIM_HIGHER, dz), HIT_CHANCE_DIVIDER / (level(ADAPTABILITY) + 1))
                .multiply(ARROW_SPEED + dist / SPEED_FOR_DISTANCE));
        a.setDamage(arrowDamage(w, bow));
        a.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        a.addScoreboardTag(GUARD_ARROW);
        if (bow != null) {
            a.setWeapon(bow); // Punch knocks back, as the mod's
            if (bow.containsEnchantment(org.bukkit.enchantments.Enchantment.FLAME)) a.setFireTicks(FLAME_TICKS);
        }
        if (colony.research.effect(Effects.ARROW_PIERCE) > 0) a.setPierceLevel(2);
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_SKELETON_SHOOT, SoundCategory.NEUTRAL, 1, 1 / (rnd.nextFloat() * 0.4f + 0.8f));
    }

    /**
     * A druid's fight (the mod's DruidCombatAI): it closes in to 3 blocks plus its tower level and throws a splash
     * potion every 2 s (4 after an instant one): at a monster slowness, at a player in a fight strength, both for 5 s.
     * With a magic potion used up (research), the potion is level III and may be weakness, or saturation, healing or
     * resistance.
     */
    private void throwPotion(Colonist w) {
        if (!readyFrom(w, druidRange())) return;
        var rnd = ThreadLocalRandom.current();
        boolean hostile = target instanceof Enemy, strong = potionsResearched() && use(Alchemist::isMagicPotion, 1) > 0;
        var effect = hostile ? ADVERSE[rnd.nextInt(strong ? ADVERSE.length : 1)] : SUPPORT[rnd.nextInt(strong ? SUPPORT.length : 1)];
        attackIn = effect.isInstant() ? DRUID_DELAY * 2 : DRUID_DELAY;
        experience(w, XP_PER_POTION);
        w.swing();
        ItemStack potion = new ItemStack(Material.SPLASH_POTION);
        potion.editMeta(org.bukkit.inventory.meta.PotionMeta.class, m -> {
            m.addCustomEffect(new org.bukkit.potion.PotionEffect(effect, level(MANA) * EFFECT_TICKS_PER_MANA, strong ? 2 : 0), true);
            m.setColor(effect.getColor());
        });
        LivingEntity body = w.living();
        org.bukkit.entity.ThrownPotion thrown = body.launchProjectile(org.bukkit.entity.ThrownPotion.class);
        thrown.setItem(potion);
        thrown.addScoreboardTag(hostile ? ADVERSE_POTION : SUPPORT_POTION);
        Location from = thrown.getLocation();
        org.bukkit.util.Vector moving = target.getVelocity(); // it leads a moving target, as the mod's throwPotionAt
        double dx = target.getX() + moving.getX() - from.getX(), dz = target.getZ() + moving.getZ() - from.getZ();
        double dy = target.getEyeLocation().getY() - POTION_DROP - from.getY();
        thrown.setVelocity(wobble(new org.bukkit.util.Vector(dx, dy + Math.sqrt(dx * dx + dz * dz) * POTION_AIM_HIGHER, dz), POTION_WOBBLE / level(FOCUS))
                .multiply(POTION_SPEED));
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_WITCH_THROW, SoundCategory.NEUTRAL, 1, 0.8f + rnd.nextFloat() * 0.4f);
    }

    private int druidRange() {
        return DRUID_RANGE + hut.level;
    }

    /** Already under one of a druid's effects (the mod's wasAffectedByDruid). */
    private static boolean affected(LivingEntity e) {
        for (var t : ADVERSE) if (e.hasPotionEffect(t)) return true;
        for (var t : SUPPORT) if (e.hasPotionEffect(t)) return true;
        return false;
    }

    /**
     * The nearest monster, or player a monster is after, that it sees and no druid's effect is on yet; none once more
     * than 2 a tower level around it are under one (the mod's druid searchNearbyTarget).
     */
    private LivingEntity druidTarget(Colonist w) {
        int r = vision(), under = 0;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.location().getNearbyEntities(r, yVision(), r)) {
            if (!(e instanceof LivingEntity l) || !valid(l)) continue;
            boolean fighting = e instanceof org.bukkit.entity.Player p && p.getGameMode() == org.bukkit.GameMode.SURVIVAL && hunted(p);
            if (!(e instanceof Enemy) && !fighting) continue;
            if (affected(l)) { under++; continue; }
            if (!w.living().hasLineOfSight(e)) continue;
            double d = e.getLocation().distanceSquared(w.location());
            if (d < bestD) { bestD = d; best = l; }
        }
        return under <= hut.level * 2 ? best : null;
    }

    /** A monster nearby is after this player (ours: the mod helps players a monster hurt in the last 30 s). */
    private boolean hunted(org.bukkit.entity.Player p) {
        for (Entity e : p.getNearbyEntities(HUNTED_RANGE, HUNTED_HEIGHT, HUNTED_RANGE)) if (e instanceof Mob m && e instanceof Enemy && m.getTarget() == p) return true;
        return false;
    }

    /** Tag on a guard's arrows: they pass through citizens, players and tame animals (MineCP). */
    public static final String GUARD_ARROW = "minecp_guard_arrow";

    /**
     * A ranged guard's approach (the mod's AttackMoveAI and its ranged doAttack): it walks until it sees the target
     * within {@code range} (plus how far above it it stands); then, ready to attack, one time in 3 with the target
     * very close it first steps back half its range. @return true to attack now.
     */
    private boolean readyFrom(Colonist w, int range) {
        w.setStatus(title() + ": fighting");
        Location at = w.location(), there = target.getLocation();
        if (at.distance(there) > range + (at.getY() - there.getY()) || !w.living().hasLineOfSight(target)) {
            if (!w.isMoving() || chasing == null || chasing.distance(there) > 2) { w.walk(there); chasing = there.clone(); }
            return false;
        }
        chasing = null;
        if (attackIn > 0) return false;
        if (at.distanceSquared(there) < FLEE_DISTANCE_SQ && task() != Task.GUARD && ThreadLocalRandom.current().nextInt(FLEE_ODDS) == 0) {
            org.bukkit.util.Vector away = at.toVector().subtract(there.toVector()).setY(0);
            if (away.lengthSquared() > 0) w.walk(at.clone().add(away.normalize().multiply(range / 2.0)));
        } else w.stopWalking();
        return true;
    }

    /** {@code aim} made a unit vector, with vanilla Projectile.shoot's triangular wobble per axis for this inaccuracy. */
    static org.bukkit.util.Vector wobble(org.bukkit.util.Vector aim, double inaccuracy) {
        var rnd = ThreadLocalRandom.current();
        double spread = 0.0172275 * inaccuracy;
        return aim.normalize().add(new org.bukkit.util.Vector(spread * (rnd.nextDouble() - rnd.nextDouble()),
                spread * (rnd.nextDouble() - rnd.nextDouble()), spread * (rnd.nextDouble() - rnd.nextDouble())));
    }

    /**
     * The mod's calculateDamage: Power's level, research's, and 2 with an arrow from its stock (research), doubled at
     * 20% health or less and 1.5x on a critical hit; then its base 2. Its agility level / 5 counts too.
     */
    private double arrowDamage(Colonist w, ItemStack bow) {
        double d = level(AGILITY) / 5.0 + colony.research.effect(Effects.ARCHER_DAMAGE);
        if (bow != null) d += bow.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER);
        if (arrowsResearched() && use(Guard::isArrow, 1) > 0) d += ARROW_DAMAGE; // ours: used up on the shot, the mod's on a hit
        if (w.health <= colony.health.max(w) * LOW_HEALTH) d *= 2;
        if (ThreadLocalRandom.current().nextDouble() > 1 / (1 + colony.research.effect(Effects.GUARD_CRIT))) d *= CRIT;
        return RANGED_BASE_DAMAGE + d;
    }

    /** How far a ranger shoots: 10 blocks, one a tower level and its adaptability / 50 x 15, at most 24; 10 more on guard (the mod's getAttackDistance). */
    private int range() {
        return Math.min(RANGE_BASE + hut.level + (int) (level(ADAPTABILITY) / 50.0f * 15), RANGE_MAX) + (task() == Task.GUARD ? GUARD_BONUS_RANGE : 0);
    }

    /**
     * Research's whirlwind, 1 swing in 5 and at most every 12 seconds: every enemy within 2 blocks is knocked back and
     * takes the blow's damage shared among everything alive there, itself counted (the mod's doAoeAttack). Ours: knocked
     * straight away from it.
     */
    private void whirlwind(Colonist w, double hit) {
        Location at = w.location();
        long now = at.getWorld().getGameTime();
        if (colony.research.effect(Effects.WHIRLWIND) <= 0 || now - lastWhirlwind <= WHIRLWIND_COOLDOWN
                || ThreadLocalRandom.current().nextInt(WHIRLWIND_ODDS) != 0) return;
        lastWhirlwind = now;
        List<LivingEntity> near = new ArrayList<>();
        java.util.Set<Colonist> citizens = new java.util.HashSet<>(); // a citizen is a body and legs: counted once
        int alive = 1; // itself
        for (Entity e : at.getWorld().getNearbyEntities(w.living().getBoundingBox().expand(WHIRLWIND_REACH, WHIRLWIND_HEIGHT, WHIRLWIND_REACH))) {
            if (!(e instanceof LivingEntity l)) continue;
            Colonist other = colony.colonistOf(e);
            if (other == null) { near.add(l); alive++; }
            else if (other != w && citizens.add(other)) alive++;
        }
        double share = hit / alive;
        for (LivingEntity l : near) {
            if (!(l instanceof Enemy) || !valid(l)) continue;
            l.knockback(WHIRLWIND_KNOCKBACK, at.getX() - l.getX(), at.getZ() - l.getZ());
            l.damage(share, w.living());
        }
        at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 1, 0), 2, 0.5, 0, 0.5, 0);
    }

    // ---- naps ---------------------------------------------------------------------------------------------------

    /** 1 in 20, and less with the Coffee research (1 / (1 + strength) of that): it sits down where it is. */
    private boolean startNap(Colonist w) {
        var rnd = ThreadLocalRandom.current();
        if (rnd.nextInt(level(ADAPTABILITY) / 2 + NAP_ODDS) != 1 || rnd.nextDouble() >= 1 / (1 + colony.research.effect(Effects.SLEEP_LESS))) return false;
        napTicks = NAP_MIN + rnd.nextInt(NAP_SPREAD);
        w.sitOn(w.location().getBlock());
        w.setStatus(title() + ": napping");
        return true;
    }

    /** Napping: it heals a little; a mob that hurts it, or the time running out, wakes it. @return true while it naps. */
    private boolean nap(Colonist w) {
        // a meal or a raid walked it off its seat meanwhile: that ends it too
        if (target != null || w.hurtTicks > 0 || w.seat() == null || --napTicks <= 0) { wakeUp(w); return false; }
        if (napTicks % NAP_HEAL_TICKS == 0) w.health = Math.min(colony.health.max(w), w.health + NAP_HEAL);
        return true;
    }

    private void wakeUp(Colonist w) {
        if (napTicks > 0) experience(w, 1); // the mod's stopSleeping
        napTicks = 0;
        w.standUp();
    }

    /** A napping knight it can see, which it goes to wake (the mod's skipSearch), or null. */
    private Colonist napper(Colonist w) {
        int r = vision();
        for (Entity e : w.location().getNearbyEntities(r, Y_VISION, r)) {
            Colonist other = colony.colonistOf(e);
            if (other != null && other != w && colony.jobOf(other) instanceof Guard g && g.napTicks > 0 && w.living() != null
                    && w.living().hasLineOfSight(e)) return other;
        }
        return null;
    }

    /** Walks to the napping knight and wakes it (ours: with a shake; the mod's poke takes 1 health). @return true while at it. */
    private boolean wake(Colonist w) {
        if (!(colony.jobOf(toWake) instanceof Guard g) || g.napTicks == 0 || ++wakeFor > WAKE_GIVE_UP) { toWake = null; wakeFor = 0; return false; }
        w.setStatus(title() + ": waking a guard");
        if (w.location().distanceSquared(toWake.location()) > WAKE_REACH) {
            if (!w.isMoving()) w.walk(toWake.location());
            return true;
        }
        w.swing();
        g.wakeUp(toWake);
        toWake = null;
        wakeFor = 0;
        return true;
    }

    // ---- retreating (research) ----------------------------------------------------------------------------------

    /** Below 20% of its health, rounded as the mod's (int) max * 0.2. */
    private boolean lowHealth(Colonist w) {
        return w.health < (int) colony.health.max(w) * LOW_HEALTH;
    }

    /** Below 20% health and away from the tower, with the retreat research (the mod's shouldFlee), unless the setting is off. */
    private boolean shouldRetreat(Colonist w) {
        return hut.on(Settings.RETREAT) && colony.research.effect(Effects.RETREAT) > 0 && lowHealth(w)
                && w.location().distanceSquared(hut.block.getLocation().toCenterLocation()) > RETREAT_FROM;
    }

    private void retreat(Colonist w) {
        w.setStatus(title() + ": retreating");
        if (w.isMoving()) return;
        if (!atHut(w)) { goToHut(w); return; }
        fleeing = false;
        resting = true;
        restTicks = 0;
    }

    /**
     * At the tower until 75% health, healing as with Regeneration I, unless what it fights (the mob that hurt it: the
     * mod's threat table) comes within 10 blocks, checked every 2 seconds as the mod. @return true while resting.
     */
    private boolean rest(Colonist w) {
        double max = colony.health.max(w);
        if (w.health >= (int) max * RESTED || !hut.on(Settings.RETREAT)) {
            resting = false;
            hut.post.alert(hut.block); // the mod's: back to work means its hut patrols there next
            return false;
        }
        if (++restTicks % REST_CHECK == 0 && target != null && target.getLocation().distance(w.location()) < REST_ALARM) {
            resting = false;
            return false;
        }
        w.setStatus(title() + ": resting");
        if (restTicks % REST_HEAL == 0) w.health = Math.min(max, w.health + 1);
        return true;
    }

    private int vision() {
        return VISION_BASE + VISION_PER_LEVEL * hut.level;
    }

    /** Alive, and not too far to chase (the mod's isWithinPersecutionDistance). */
    private boolean valid(LivingEntity e) {
        return e != null && e.isValid() && !e.isDead() && e.getWorld().equals(hut.block.getWorld())
                && inReach(e.getLocation(), ranger() ? range() : druid() ? druidRange() : REACH);
    }

    /** How far above and below it looks (the mod's: rangers and druids 25 more where they guard). */
    private int yVision() {
        return !knight() && task() == Task.GUARD ? Y_VISION + GUARD_Y_VISION : Y_VISION;
    }

    private LivingEntity nearestEnemy(Colonist w) {
        int r = vision();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.location().getNearbyEntities(r, yVision(), r)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity l) || !valid(l) || !w.living().hasLineOfSight(e)) continue;
            double d = e.getLocation().distanceSquared(w.location());
            if (d < bestD) { bestD = d; best = l; }
        }
        return best;
    }

    // ---- gear numbers --------------------------------------------------------------------------------------------

    /** An item's own value for an attribute when worn or held where it goes (vanilla's default modifiers). */
    private static double stat(ItemStack i, Attribute attribute) {
        if (i == null || i.isEmpty()) return 0;
        EquipmentSlot slot = i.getType().getEquipmentSlot();
        double n = 0;
        for (var m : i.getType().getDefaultAttributeModifiers(slot).get(attribute)) n += m.getAmount();
        return n;
    }

    /** The tower-level rule's armor tiers (the mod's armor levels): leather 0, gold 1, chainmail 2, iron 3, diamond 4, netherite 5. */
    private static int armorTier(Material m) {
        String n = m.name();
        return n.startsWith("LEATHER_") ? 0 : n.startsWith("GOLDEN_") || n.startsWith("COPPER_") ? 1 : n.startsWith("CHAINMAIL_") ? 2
                : n.startsWith("IRON_") ? 3 : n.startsWith("DIAMOND_") ? 4 : n.startsWith("NETHERITE_") ? 5 : -1; // copper (newer than the mod) as gold, as tools count it as stone
    }

    private static String armorName(int tier) {
        return new String[]{"leather", "gold", "chainmail", "iron", "diamond", "netherite"}[tier];
    }

    private static String slotName(EquipmentSlot s) {
        return switch (s) {
            case HEAD -> "a helmet";
            case CHEST -> "a chestplate";
            case LEGS -> "leggings";
            default -> "boots";
        };
    }

    // ---- saving and ending ---------------------------------------------------------------------------------------

    @Override
    public void save(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.save(s, c);
        armor.forEach((slot, item) -> s.set("armor." + slot.name(), Base64.getEncoder().encodeToString(item.serializeAsBytes())));
        if (shield != null) s.set("shield", Base64.getEncoder().encodeToString(shield.serializeAsBytes()));
    }

    @Override
    public void load(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.load(s, c);
        var as = s.getConfigurationSection("armor");
        if (as != null) for (String slot : as.getKeys(false))
            armor.put(EquipmentSlot.valueOf(slot), ItemStack.deserializeBytes(Base64.getDecoder().decode(as.getString(slot))));
        String saved = s.getString("shield");
        if (saved != null) shield = ItemStack.deserializeBytes(Base64.getDecoder().decode(saved));
    }

    @Override
    public void dropCarried() {
        super.dropCarried();
        Location at = hut.block.getLocation().toCenterLocation();
        armor.values().forEach(i -> at.getWorld().dropItemNaturally(at, i));
        armor.clear();
        if (shield != null) at.getWorld().dropItemNaturally(at, shield);
        shield = null;
    }

    @Override
    public List<ItemStack> takeCarried() {
        List<ItemStack> all = super.takeCarried();
        all.addAll(armor.values());
        armor.clear();
        if (shield != null) all.add(shield);
        shield = null;
        return all;
    }
}
