package dev.minecp.colony;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.job.Job;

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
        double amount = c.saturation >= Food.MAX_SATURATION ? 2
                : c.saturation < Food.LOW_SATURATION ? c.saturation / Food.MAX_SATURATION / 2 : 1;
        c.health = Math.min(max(c), c.health + amount);
    }

    /** Most health it can have: its job's (a knight has more). */
    public double max(Colonist c) {
        Job job = colony.jobOf(c);
        return job != null ? job.maxHealth() : Colonist.MAX_HEALTH;
    }

    /** Damage to a citizen's body or legs: the hit itself is cancelled and the citizen's own health takes it. */
    public void hurt(Colonist c, EntityDamageEvent e) {
        e.setCancelled(true);
        var cause = e.getCause();
        if (cause == DamageCause.SUFFOCATION) { c.hopAside(null); return; } // the mod teleports it out
        Entity source = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        if (source instanceof Projectile p && p.getShooter() instanceof Entity shooter) source = shooter;
        if (source != null && colony.colonistOf(source) != null) return;
        // A player's hit of more than 1 needs HURT_CITIZEN (the mod); attacking at all inside a colony: Protection.
        if (source instanceof Player p && e.getDamage() > 1 && !colony.permissions.has(p, Action.HURT_CITIZEN)) return;
        Job job = colony.jobOf(c);
        double hit = job != null ? job.afterArmor(e.getDamage()) : e.getDamage();
        double damage = cause == DamageCause.VOID || cause == DamageCause.KILL ? c.health : Math.min(hit, max(c) * MAX_HIT);
        if (damage <= 0) return;
        c.health -= damage;
        c.showHurt();
        var type = e.getDamageSource().getDamageType(); // the mod's damage modifier skips fire (lava, magma, campfires too) and lightning
        if (!org.bukkit.tag.DamageTypeTags.IS_FIRE.isTagged(type) && !org.bukkit.tag.DamageTypeTags.IS_LIGHTNING.isTagged(type))
            colony.happiness.hurt(c); // the mod's damage modifier skips fire and lightning
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
        List<ItemStack> items = job != null ? job.takeCarried() : List.of();
        Location at = c.location();
        c.despawn();
        colony.colonists.remove(c);
        colony.assignJobs();
        if (!(job instanceof dev.minecp.job.Guard)) colony.happiness.died();
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_DEATH, SoundCategory.NEUTRAL, 1, 1);
        at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        Block spot = Grave.spot(at.getBlock());
        String where = " at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        if (spot == null) {
            items.forEach(i -> at.getWorld().dropItemNaturally(at, i));
            colony.tell(c.name + " " + deathCause(cause, source) + where + ". No grave could be made there.");
            return;
        }
        spot.setType(Grave.BLOCK);
        colony.graves.add(new Grave(spot, c.name, job != null ? job.hut.type.worker : null, items));
        colony.tell(c.name + " " + deathCause(cause, source) + where + ". Their grave is at "
                + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ".");
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
