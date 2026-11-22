package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Enemy;
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
 * A knight at a Guard Tower (the mod's JobKnight, EntityAIMelee, MeleeCombatAI and AbstractEntityAIGuard): it patrols
 * between the colony's built huts, attacks the hostile mobs it sees with a sword, and wears the armor its tower level
 * allows. It doesn't sleep at night or shelter from rain, but naps on duty now and then; it doesn't run when hurt (unless research lets it
 * retreat to its tower). Research adds damage, critical hits, armor, parrying, a shield, taunting and a whirlwind.
 */
public final class Guard extends Job {
    // The mod's numbers (GuardConstants, AbstractBuildingGuards, BuildingGuardTower):
    private static final int ATTACK_DELAY = 32;        // KNIGHT_ATTACK_DELAY_BASE: ticks between hits (no skills: no faster)
    private static final double REACH = 2;             // MAX_DISTANCE_FOR_ATTACK
    private static final double BASE_DAMAGE = 3;       // BASE_PHYSICAL_DAMAGE, on top of the sword's
    private static final int VISION_BASE = 15, VISION_PER_LEVEL = 3; // BASE_VISION_RANGE + VISION_RANGE_PER_LEVEL x level
    private static final int Y_VISION = 3;
    private static final int CHASE = 80;               // MAX_PATROL_DERIVATION: how far from the tower it chases
    private static final int HP_KNIGHT = 15, HP_TOWER = 20, HP_PER_LEVEL = 2; // KNIGHT_HP_BONUS, BONUS_HP_SINGLE_GUARD, per level
    private static final int PATROL_WAIT = 100;        // GUARD_TASK_INTERVAL: ticks at a patrol point
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
    // Naps on duty (the mod's AbstractEntityAIGuard.shouldSleep, sleep and wakeUpGuard):
    private static final int NAP_CHECK = 200;          // SHOULD_SLEEP_INTERVAL: a chance to nap every 10 s with no target
    private static final int NAP_ODDS = 20;            // 1 in (adaptability / 2 + 20): no skills, 1 in 20
    private static final int NAP_MIN = 2500, NAP_SPREAD = 500; // it naps 2500 to 3000 ticks
    private static final int NAP_HEAL_TICKS = 30;      // PARTICLE_INTERVAL
    private static final double NAP_HEAL = 0.5;        // the mod's: health each time its sleep particles show
    private static final int WAKE_GIVE_UP = 30 * 20;   // the mod's: 30 tries a second apart before giving up waking another
    private static final double WAKE_REACH = 2.25;     // the mod's: squared distance close enough to wake it

    private final Map<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class); // worn; saved
    private ItemStack shield; // carried once researched; saved
    private LivingEntity target;
    private Hut patrolTo;
    private int attackIn, searchIn, waitAt, restTicks;
    private long lastWhirlwind;
    private boolean fleeing, resting; // retreating to the tower, then resting there (not saved: it simply carries on)
    private int napTicks, napCheckIn = NAP_CHECK;    // napping while napTicks > 0 (not saved)
    private Colonist toWake;                          // a napping knight it is on its way to wake
    private int wakeFor;
    private Location chasing; // where its route to the target leads

    public Guard(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Health with the knight's and the tower's bonuses (the mod adds them as max health modifiers). */
    @Override
    public double maxHealth() {
        return Colonist.MAX_HEALTH + HP_KNIGHT + (hut.level >= 1 ? HP_TOWER + HP_PER_LEVEL * hut.level : 0);
    }

    /**
     * What reaches it, in the mod's order: research may parry the whole hit; below 20% health research softens it; a
     * raised shield blocks a hit from the mob it fights, which still costs half, after armor, and wears the shield
     * (ours: "from its target" stands in for vanilla's facing check); then its armor.
     */
    @Override
    public double reaching(double damage, DamageType type, Entity source, Colonist w) {
        double parry = colony.research.effect(Effects.BLOCK_ATTACKS);
        if (parry > 0 && ThreadLocalRandom.current().nextDouble() < parry) return 0;
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
        points = (int) (points * (1 + colony.research.effect(Effects.MELEE_ARMOR)));
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
        return fleeing && speed > 0 ? 1 + SPEED_PER_LEVEL * ((int) speed + 1) : 1;
    }

    /** Something hurt it: it fights back (a mob; players are left to permissions) instead of running. */
    @Override
    public boolean fightsBack(Entity source) {
        if (source instanceof LivingEntity l && !(source instanceof org.bukkit.entity.Player) && colony.colonistOf(source) == null && valid(l)) target = l;
        return true;
    }

    @Override
    public boolean interruptible() {
        return target == null && !fleeing && !resting;
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
        if (hut.level == 0) return List.of(Component.text("The knight starts once level 1 is built.", NamedTextColor.GRAY));
        int[] tier = ARMOR[Math.min(hut.level, Hut.MAX_LEVEL) - 1];
        return List.of(Component.text("Patrols the colony and fights the monsters it sees within " + vision() + " blocks. Wears "
                + armorName(tier[0]) + " to " + armorName(tier[1]) + " armor and " + toolLabel(Tool.SWORD) + ".", NamedTextColor.GRAY));
    }

    // ---- gear ---------------------------------------------------------------------------------------------------

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>();
        if (hut.level < 1) return wants;
        wantTool(wants, Tool.SWORD);
        for (EquipmentSlot slot : SLOTS) // the mod's guards ask for each missing piece; one the tower no longer allows counts as missing
            if (!fitsArmor(armor.get(slot), slot) && inChest(i -> fitsArmor(i, slot)) == 0)
                wants.add(new Want(slotName(slot) + " (" + armorName(tier()[0]) + " to " + armorName(tier()[1]) + ")", i -> fitsArmor(i, slot), 1));
        if (wantsShield() && inChest(Guard::isShield) == 0) wants.add(new Want("a shield", Guard::isShield, 1)); // the mod's "nice to have"
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        if (fits(Tool.SWORD, item, null) || isShield(item)) return 1;
        for (EquipmentSlot slot : SLOTS) if (fitsArmor(item, slot)) return 1;
        return 0;
    }

    /** Research lets it use a shield, and it has none. */
    private boolean wantsShield() {
        return shield == null && shieldResearched();
    }

    private boolean shieldResearched() {
        return colony.research.effect(Effects.SHIELD_USAGE) > 0;
    }

    private static boolean isShield(ItemStack i) {
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
        if (!hasTool(Tool.SWORD, null) && inChest(i -> fits(Tool.SWORD, i, null)) > 0) return true;
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
        if (!hasTool(Tool.SWORD, null)) fetchTool(Tool.SWORD, null);
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
        ItemStack sword = tool(Tool.SWORD);
        if (!java.util.Objects.equals(e.getItemInMainHand().isEmpty() ? null : e.getItemInMainHand(), sword)) w.hold(sword);
        if (!java.util.Objects.equals(e.getItemInOffHand().isEmpty() ? null : e.getItemInOffHand(), shield)) e.setItemInOffHand(shield);
    }

    // ---- work ---------------------------------------------------------------------------------------------------

    @Override
    public void tick(Colonist w) {
        dress(w);
        if (hut.level < 1) {
            w.setStatus("Knight: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (attackIn > 0) attackIn--;
        if (!valid(target)) target = null;
        if (!fleeing && !resting && shouldRetreat(w)) { wakeUp(w); fleeing = true; target = null; }
        if (fleeing) { retreat(w); return; }
        if (resting && rest(w)) return;
        if (napTicks > 0 && nap(w)) return;
        if (target == null && --searchIn <= 0) {
            searchIn = SEARCH_TICKS;
            target = nearestEnemy(w);
            if (target == null && toWake == null) toWake = napper(w);
        }
        if (target != null) { fight(w); return; }
        if (toWake != null && wake(w)) return;
        if (--napCheckIn <= 0) {
            napCheckIn = NAP_CHECK;
            if (w.hurtTicks == 0 && w.disease == null && startNap(w)) return; // walking or not, not sick, as the mod's: sitting down stops it
        }
        if (w.isMoving() || --cooldown > 0) return;

        if (gearWaiting()) {
            if (!atHut(w)) { w.setStatus("Knight: getting gear"); goToHut(w); return; }
            gearUp();
        }
        note = hasTool(Tool.SWORD, null) ? null : "No sword: it fights with its fists. Put " + toolLabel(Tool.SWORD) + " in the tower's chest.";
        patrol(w);
    }

    /** Walks between the colony's built huts at random (the mod's automatic patrol), waiting a while at each. */
    private void patrol(Colonist w) {
        w.setStatus("Knight: patrolling");
        if (patrolTo == null || !colony.huts.contains(patrolTo)) patrolTo = nextPatrolPoint();
        if (patrolTo == null) { w.wander(hut.block.getLocation().toCenterLocation()); return; }
        if (!w.isBeside(patrolTo.block)) { w.goBeside(patrolTo.block); return; }
        if (++waitAt < PATROL_WAIT / 20) { cooldown = 20; return; } // tick() counts down cooldown once per tick
        waitAt = 0;
        patrolTo = nextPatrolPoint();
    }

    private Hut nextPatrolPoint() {
        List<Hut> built = colony.huts.stream().filter(h -> h.level >= 1).toList();
        return built.isEmpty() ? null : built.get(ThreadLocalRandom.current().nextInt(built.size()));
    }

    private void fight(Colonist w) {
        w.setStatus("Knight: fighting");
        Location at = w.location(), there = target.getLocation();
        if (at.distance(there) > REACH) {
            // A new route only when it stopped or the target has moved away from where it was heading: each search costs time.
            if (!w.isMoving() || chasing == null || chasing.distance(there) > 2) { w.walk(there); chasing = there.clone(); }
            return;
        }
        chasing = null;
        w.stopWalking();
        if (attackIn > 0) return;
        attackIn = ATTACK_DELAY;
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
        if (rnd.nextInt(NAP_ODDS) != 1 || rnd.nextDouble() >= 1 / (1 + colony.research.effect(Effects.SLEEP_LESS))) return false;
        napTicks = NAP_MIN + rnd.nextInt(NAP_SPREAD);
        w.sitOn(w.location().getBlock());
        w.setStatus("Knight: napping");
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
        w.setStatus("Knight: waking a guard");
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

    /** Below 20% health and away from the tower, with the retreat research (the mod's shouldFlee; its retreat setting is on by default). */
    private boolean shouldRetreat(Colonist w) {
        return colony.research.effect(Effects.RETREAT) > 0 && lowHealth(w)
                && w.location().distanceSquared(hut.block.getLocation().toCenterLocation()) > RETREAT_FROM;
    }

    private void retreat(Colonist w) {
        w.setStatus("Knight: retreating");
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
        if (w.health >= (int) max * RESTED) { resting = false; return false; }
        if (++restTicks % REST_CHECK == 0 && target != null && target.getLocation().distance(w.location()) < REST_ALARM) {
            resting = false;
            return false;
        }
        w.setStatus("Knight: resting");
        if (restTicks % REST_HEAL == 0) w.health = Math.min(max, w.health + 1);
        return true;
    }

    private int vision() {
        return VISION_BASE + VISION_PER_LEVEL * hut.level;
    }

    /** Alive, on the colony's land, and not too far from the tower to chase (the mod: only the distance). */
    private boolean valid(LivingEntity e) {
        if (e == null || !e.isValid() || e.isDead() || !e.getWorld().equals(hut.block.getWorld())) return false;
        Location l = e.getLocation();
        return l.distance(hut.block.getLocation()) <= CHASE && colony.claims().contains(dev.minecp.colony.Claims.key(l.getBlockX() >> 4, l.getBlockZ() >> 4));
    }

    private LivingEntity nearestEnemy(Colonist w) {
        int r = vision();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.location().getNearbyEntities(r, Y_VISION, r)) {
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity l) || !valid(l)) continue;
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
