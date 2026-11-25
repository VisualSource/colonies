package dev.minecp.job;

import dev.minecp.MineCP;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * A cavalry horse (the mod's CavalryHorseEntity), the mod's numbers unless noted: a vanilla horse the stablemaster
 * trained, tagged with its stable. It has a combat cooldown: it rises with damage, falls as the stablemaster readies it,
 * and above two thirds of its health the horse isn't fit to fight and throws its rider. A cavalry guard reserves one
 * before walking to it, for 10 seconds.
 */
public final class CavalryHorse {
    private static final NamespacedKey HOME = new NamespacedKey(MineCP.NAMESPACE, "cavalry_home"),   // "world;x;y;z" of its stable
            COOLDOWN = new NamespacedKey(MineCP.NAMESPACE, "cavalry_cooldown"),
            RESERVED = new NamespacedKey(MineCP.NAMESPACE, "cavalry_reserved"),                       // "guard uuid;world time"
            DISMOUNTED = new NamespacedKey(MineCP.NAMESPACE, "cavalry_dismounted"),                   // world time
            HEALTH_RESEARCH = new NamespacedKey(MineCP.NAMESPACE, "mount_health_research");
    static final double READY = 0.66;                // COMBAT_READINESS_THRESHOLD: fit while its cooldown is at most 66% of its max health
    static final double STAT_BOOST = 1.25;            // its health and speed as a cavalry horse
    static final double JUMP = 0.7, STEP = 1.1;
    static final double COOLDOWN_PER_DAMAGE = 0.40;
    static final double CREEPER = 0.30, PROJECTILE = 1.20; // damage taken from a creeper's blast, and from projectiles
    static final int RESERVATION_TICKS = 200;         // RESERVATION_EXPIRATION_LIMIT

    private CavalryHorse() {}

    public static boolean is(Entity e) {
        return e instanceof AbstractHorse && e.getPersistentDataContainer().has(HOME);
    }

    /** The stablemaster trains a vanilla horse (the mod's createFromVanilla): stronger and faster, not ready to fight yet. */
    static void train(Horse h, Hut stable) {
        scale(h, Attribute.MAX_HEALTH, STAT_BOOST);
        scale(h, Attribute.MOVEMENT_SPEED, STAT_BOOST);
        set(h, Attribute.JUMP_STRENGTH, JUMP);
        set(h, Attribute.STEP_HEIGHT, STEP);
        h.setHealth(h.getAttribute(Attribute.MAX_HEALTH).getValue());
        h.setTamed(true); // ours: a tame horse doesn't buck its rider (the mod's never does)
        h.setPersistent(true);
        h.setRemoveWhenFarAway(false);
        PersistentDataContainer d = h.getPersistentDataContainer();
        d.set(HOME, PersistentDataType.STRING, h.getWorld().getName() + ";" + stable.block.getX() + ";" + stable.block.getY() + ";" + stable.block.getZ());
        d.set(COOLDOWN, PersistentDataType.DOUBLE, h.getAttribute(Attribute.MAX_HEALTH).getValue()); // the mod's: it starts unready
        quiet(h);
    }

    /** No wandering of its own: unridden it strolls about its stable and comes home (Stablemaster), ridden it goes where its rider does. */
    static void quiet(AbstractHorse h) {
        for (var goal : Bukkit.getMobGoals().getAllGoals(h)) {
            String k = goal.getKey().getNamespacedKey().getKey();
            if (k.contains("stroll") || k.contains("crazy")) Bukkit.getMobGoals().removeGoal(h, goal);
        }
    }

    private static void scale(AbstractHorse h, Attribute a, double by) {
        var attr = h.getAttribute(a);
        if (attr != null) attr.setBaseValue(attr.getBaseValue() * by);
    }

    private static void set(AbstractHorse h, Attribute a, double value) {
        var attr = h.getAttribute(a);
        if (attr != null) attr.setBaseValue(value);
    }

    /** Its stable's hut block, or null. */
    public static Block home(AbstractHorse h) {
        String[] p = h.getPersistentDataContainer().getOrDefault(HOME, PersistentDataType.STRING, "").split(";");
        var w = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
        return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    static double cooldown(AbstractHorse h) {
        return h.getPersistentDataContainer().getOrDefault(COOLDOWN, PersistentDataType.DOUBLE, 0.0);
    }

    static void cooldown(AbstractHorse h, double value) {
        h.getPersistentDataContainer().set(COOLDOWN, PersistentDataType.DOUBLE, Math.max(0, value));
    }

    static double maxHealth(AbstractHorse h) {
        return h.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    /** Fit to fight: its combat cooldown at most two thirds of its health. */
    static boolean ready(AbstractHorse h) {
        return fit(cooldown(h), maxHealth(h));
    }

    static boolean fit(double cooldown, double maxHealth) {
        return cooldown <= maxHealth * READY;
    }

    /** The guard that reserved it, if that was under 10 seconds ago. */
    static UUID reservedBy(AbstractHorse h) {
        String[] p = h.getPersistentDataContainer().getOrDefault(RESERVED, PersistentDataType.STRING, "").split(";");
        if (p.length != 2 || h.getWorld().getGameTime() - Long.parseLong(p[1]) > RESERVATION_TICKS) return null;
        return UUID.fromString(p[0]);
    }

    static void reserve(AbstractHorse h, UUID guard) {
        h.getPersistentDataContainer().set(RESERVED, PersistentDataType.STRING, guard + ";" + h.getWorld().getGameTime());
    }

    static void unreserve(AbstractHorse h) {
        h.getPersistentDataContainer().remove(RESERVED);
    }

    /** A rider got off (or was thrown): it lingers 5 minutes before going home. */
    public static void dismounted(AbstractHorse h) {
        h.getPersistentDataContainer().set(DISMOUNTED, PersistentDataType.LONG, h.getWorld().getGameTime());
    }

    static long sinceDismount(AbstractHorse h) {
        return h.getWorld().getGameTime() - h.getPersistentDataContainer().getOrDefault(DISMOUNTED, PersistentDataType.LONG, 0L);
    }

    /** Free for {@code guard} to ride: alive, riderless, grown, fit, and not reserved by another guard (the mod's isAvailableTo). */
    static boolean availableTo(AbstractHorse h, UUID guard) {
        if (!h.isValid() || h.isDead() || !h.getPassengers().isEmpty() || !h.isAdult() || !ready(h)) return false;
        UUID by = reservedBy(h);
        return by == null || by.equals(guard);
    }

    /**
     * A hit on a cavalry horse (the mod's hurt): a creeper's blast does 30%, projectiles 120%, the colony's barding
     * research takes off its share; its cooldown rises by 40% of what it took, and a horse no longer fit throws its rider.
     */
    public static void hurt(EntityDamageEvent e, Colony colony) {
        if (!(e.getEntity() instanceof AbstractHorse h)) return;
        double damage = e.getDamage();
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by
                && by.getDamager() instanceof org.bukkit.entity.Creeper) damage *= CREEPER;
        if (e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) damage *= PROJECTILE;
        if (colony != null) damage *= 1 - colony.research.effect("mountarmor");
        e.setDamage(damage);
        cooldown(h, cooldown(h) + COOLDOWN_PER_DAMAGE * damage);
        if (!ready(h)) h.eject();
    }

    /** Whether its health doesn't have the colony's horse health research yet (or has an old level of it). */
    static boolean healthResearchDue(AbstractHorse h, Colony colony) {
        double want = colony.research.effect("mounthealth");
        AttributeModifier had = h.getAttribute(Attribute.MAX_HEALTH).getModifier(HEALTH_RESEARCH);
        return had == null ? want > 0 : had.getAmount() != want;
    }

    /** The colony's horse health research as a max health bonus (the mod's, applied when the stablemaster readies it). @return whether it changed. */
    static boolean healthResearch(AbstractHorse h, Colony colony) {
        if (!healthResearchDue(h, colony)) return false;
        var attr = h.getAttribute(Attribute.MAX_HEALTH);
        double want = colony.research.effect("mounthealth");
        AttributeModifier had = attr.getModifier(HEALTH_RESEARCH);
        if (had != null) attr.removeModifier(HEALTH_RESEARCH);
        if (want > 0) attr.addModifier(new AttributeModifier(HEALTH_RESEARCH, want, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        h.setHealth(Math.min(h.getHealth(), attr.getValue()));
        return true;
    }
}
