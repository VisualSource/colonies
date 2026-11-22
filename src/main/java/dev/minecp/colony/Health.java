package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.job.Job;
import dev.minecp.research.Effects;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** A colony's citizens' health and death (the mod's EntityCitizen hurt/die and checkHeal, GraveManager). */
public final class Health {
    private static final int HEAL_TICKS = 100;     // the mod's HEAL_CITIZENS_AFTER
    private static final double MAX_HIT = 0.2;     // a hit takes at most this share of max health, as in the mod

    private final Colony colony;

    Health(Colony colony) {
        this.colony = colony;
    }

    /** Every 5 seconds, unless a mob or player hurt it lately: more when well fed, as the mod's checkHeal. */
    void heal(Colonist c) {
        if (c.hurtTicks > 0) c.hurtTicks--;
        c.health = Math.min(c.health, max(c)); // a former knight loses its extra health
        if (++c.healTicks < HEAL_TICKS) return;
        c.healTicks = 0;
        if (c.health >= max(c) || c.hurtTicks > 0) return;
        // research lowers the saturation needed for the full rate (a negative strength) and speeds up both rates
        double regen = 1 + colony.research.effect(Effects.REGENERATION);
        double amount = c.saturation >= Food.MAX_SATURATION + colony.research.effect(Effects.HEALING_SATURATION) ? 2 * regen
                : c.saturation < Food.LOW_SATURATION ? c.saturation / Food.MAX_SATURATION / 2 : regen;
        // the mod's: the sick heal only while under a third of their health (the last heal can go a little past it)
        if (c.health < (c.disease != null ? max(c) / 3 : max(c))) c.health = Math.min(max(c), c.health + amount);
    }

    /** Most health it can have: its job's (a knight has more), plus research's for every citizen. */
    public double max(Colonist c) {
        Job job = colony.jobOf(c);
        return (job != null ? job.maxHealth() : Colonist.MAX_HEALTH) + colony.research.effect(Effects.HEALTH);
    }

    /** Damage to a citizen's body or legs: the hit itself is cancelled and the citizen's own health takes it. */
    public void hurt(Colonist c, EntityDamageEvent e) {
        e.setCancelled(true);
        var cause = e.getCause();
        if (cause == DamageCause.SUFFOCATION) { c.hopAside(null); return; } // the mod teleports it out
        Entity direct = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null, source = direct;
        if (source instanceof Projectile p && p.getShooter() instanceof Entity shooter) source = shooter;
        if (source != null && colony.colonistOf(source) != null) return;
        // A player's hit of more than 1 needs HURT_CITIZEN (the mod); attacking at all inside a colony: Protection.
        if (source instanceof Player p && e.getDamage() > 1 && !colony.permissions.has(p, Action.HURT_CITIZEN)) return;
        Job job = colony.jobOf(c);
        var type = e.getDamageSource().getDamageType();
        double capped = Math.min(e.getDamage(), max(c) * MAX_HIT); // the mod caps the hit first; the job's research, shield and armor act on that
        double damage = cause == DamageCause.VOID || cause == DamageCause.KILL ? c.health
                : job != null ? job.reaching(capped, type, source, c) : capped;
        if (damage <= 0) return;
        c.health -= damage;
        c.showHurt();
        if (direct != null) c.hitBy(direct);
        if (!Job.fireOrLightning(type)) colony.happiness.hurt(c); // the mod's damage modifier skips fire (lava, magma, campfires too) and lightning
        if (c.health <= 0) { die(c, cause, source); return; }
        c.wake(); // vanilla wakes any sleeper that is hurt; it flees first, then goes back to bed
        if (job != null && job.fightsBack(source)) { // a knight doesn't run
            if (source instanceof LivingEntity) c.hurtTicks = HEAL_TICKS;
            return;
        }
        if (source instanceof LivingEntity) {
            c.hurtTicks = HEAL_TICKS;
            c.flee(source.getLocation(), 15);
        } else if (cause != DamageCause.FALL && c.fleeTicks == 0) c.flee(c.location(), 5);
    }

    /** The mod's death: a message to the owner, a grave with what it carried, and its job and home freed. */
    void die(Colonist c, DamageCause cause, Entity source) {
        Job job = colony.jobOf(c);
        if (c.meal != null) { c.meal.abandon(); c.meal = null; }
        List<ItemStack> items = new java.util.ArrayList<>(job != null ? job.takeCarried() : List.of());
        items.addAll(c.takeInventory()); // its own inventory too
        colony.sickness.forget(c);
        Location at = c.location();
        c.despawn();
        colony.colonists.remove(c);
        colony.assignJobs();
        boolean guard = job instanceof dev.minecp.job.Guard;
        if (!guard) colony.happiness.died();
        colony.raids.lost(guard);
        String mourn = guard || !mourn(c) ? "" : " Close family and friends will mourn " + c.name + " tomorrow out of respect.";
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_DEATH, SoundCategory.NEUTRAL, 1, 1);
        at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        Block spot = Grave.spot(at.getBlock());
        String where = " at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        Colony.log.info(colony.where() + ": " + c.name + (job != null ? " (" + job.hut.type.worker + ")" : "") + " " + deathCause(cause, source) + where + ".");
        if (spot == null) {
            items.forEach(i -> at.getWorld().dropItemNaturally(at, i));
            colony.tell(c.name + " " + deathCause(cause, source) + where + ". No grave could be made there." + mourn);
            return;
        }
        spot.setType(Grave.BLOCK);
        Grave grave = new Grave(spot, c.name, job != null ? job.hut.type.worker : null, c.skin, items);
        grave.timer += (int) (colony.research.effect(Effects.GRAVE_DECAY) * 60 * 20); // research: minutes more before it decays
        colony.graves.add(grave);
        colony.tell(c.name + " " + deathCause(cause, source) + where + ". Their grave is at "
                + spot.getX() + " " + spot.getY() + " " + spot.getZ() + "." + mourn);
    }

    /**
     * Those who shared its home will mourn it from their next morning (the mod: family and housemates; guards and
     * undertakers don't mourn). Ours: no family is kept, so housemates only. @return whether anyone will.
     */
    private boolean mourn(Colonist dead) {
        boolean any = false;
        if (dead.home != null) for (Colonist c : colony.colonists) {
            Job job = colony.jobOf(c);
            if (c.home != dead.home || job instanceof dev.minecp.job.Guard || job instanceof dev.minecp.job.Undertaker) continue;
            c.mourns.add(dead.name);
            any = true;
        }
        return any;
    }

    private static String deathCause(DamageCause cause, Entity source) {
        if (source != null) return "was killed by " + (source instanceof Player p ? p.getName() : source.getName());
        return switch (cause) {
            case FALL -> "fell to their death";
            case DROWNING -> "drowned";
            case LAVA -> "died in lava";
            case FIRE, FIRE_TICK, CAMPFIRE, HOT_FLOOR -> "burned to death";
            case STARVATION -> "starved";
            case VOID -> "fell out of the world";
            default -> "died";
        };
    }
}
