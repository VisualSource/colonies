package dev.minecp;

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
final class Health {
    private static final int HEAL_TICKS = 100;     // the mod's HEAL_CITIZENS_AFTER
    private static final double MAX_HIT = 0.2;     // a hit takes at most this share of max health, as in the mod

    private final Colony colony;

    Health(Colony colony) {
        this.colony = colony;
    }

    /** Every 5 seconds, unless a mob or player hurt it lately: more when well fed, as the mod's checkHeal. */
    void heal(Colonist c) {
        if (c.hurtTicks > 0) c.hurtTicks--;
        if (++c.healTicks < HEAL_TICKS) return;
        c.healTicks = 0;
        if (c.health >= Colonist.MAX_HEALTH || c.hurtTicks > 0) return;
        double amount = c.saturation >= Food.MAX_SATURATION ? 2
                : c.saturation < Food.LOW_SATURATION ? c.saturation / Food.MAX_SATURATION / 2 : 1;
        c.health = Math.min(Colonist.MAX_HEALTH, c.health + amount);
    }

    /** Damage to a citizen's body or legs: the hit itself is cancelled and the citizen's own health takes it. */
    void hurt(Colonist c, EntityDamageEvent e) {
        e.setCancelled(true);
        var cause = e.getCause();
        if (cause == DamageCause.SUFFOCATION) { c.hopAside(null); return; } // the mod teleports it out
        Entity source = e instanceof EntityDamageByEntityEvent by ? by.getDamager() : null;
        if (source instanceof Projectile p && p.getShooter() instanceof Entity shooter) source = shooter;
        if (source != null && colony.colonistOf(source) != null) return;
        // Only the owner hurts its citizens properly (the mod: players need the HURT_CITIZEN permission for more than 1).
        if (source instanceof Player p && !p.getUniqueId().equals(colony.owner) && e.getDamage() > 1) return;
        double damage = cause == DamageCause.VOID || cause == DamageCause.KILL
                ? c.health : Math.min(e.getDamage(), Colonist.MAX_HEALTH * MAX_HIT);
        if (damage <= 0) return;
        c.health -= damage;
        c.showHurt();
        if (c.health <= 0) { die(c, cause, source); return; }
        c.wake(); // vanilla wakes any sleeper that is hurt; it flees first, then goes back to bed
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
