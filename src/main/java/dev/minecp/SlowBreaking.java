package dev.minecp;

import dev.minecp.colony.Attackers;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In PvP mode hut blocks break slowly, whatever the tool, for everyone (the mod's getDestroyProgress of AbstractColonyBlock
 * and BlockHutTownHall). A break-speed modifier shows it to the player; the ticks counted here decide.
 */
final class SlowBreaking implements Listener {
    private static final int HUT_TICKS = 1200;        // 30 x HARDNESS 10 x HARDNESS_PVP_FACTOR 4
    private static final int TOWN_HALL_TICKS = 14400; // the town hall's: 12 times that
    private static final int SMALL_TICKS = 30;        // the postbox's and stash's, always
    private static final int[] WARN_AT = {25, 50, 75, 90};
    private static final int START_PAUSE = 1200;      // ours: a minute between the messages that someone starts on a town hall
    private static final double REACH_SQ = 8 * 8;     // ours: who walks off has stopped
    private static final NamespacedKey KEY = new NamespacedKey(MineCP.NAMESPACE, "slow_breaking");

    private static final class Dig {
        final Block block;
        final int needed;
        final Colony attacked; // its members are told how far the attacker got; null: not an attack on a town hall
        int ticks;

        Dig(Block block, int needed, Colony attacked) {
            this.block = block;
            this.needed = needed;
            this.attacked = attacked;
        }
    }

    private final MineCP plugin;
    private final Map<UUID, Dig> digs = new HashMap<>();
    private final Map<UUID, Integer> toldStart = new HashMap<>(); // by colony owner: the server tick of its last start message

    SlowBreaking(MineCP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onStart(BlockDamageEvent e) {
        Player p = e.getPlayer();
        stop(p);
        Hut hut = slow(e.getBlock());
        if (hut == null || p.getGameMode() == GameMode.CREATIVE) return;
        Colony c = plugin.colonyAt(e.getBlock());
        boolean hall = hut.type == Hut.Type.TOWN_HALL;
        boolean attack = hall && c.attackers.attacking(p);
        Dig d = new Dig(e.getBlock(), needed(hut.type), attack ? c : null);
        digs.put(p.getUniqueId(), d);
        slowDown(p, d);
        if (!attack || Bukkit.getCurrentTick() - toldStart.getOrDefault(c.owner, -START_PAUSE) < START_PAUSE) return;
        toldStart.put(c.owner, Bukkit.getCurrentTick());
        c.tell(p.getName() + " is breaking the Town Hall of " + c.name + "."); // ours: the mod has a message for it but never sends it
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent e) {
        stop(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
    }

    /** Before anything else handles it: not broken yet. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (slow(e.getBlock()) == null || p.getGameMode() == GameMode.CREATIVE) return;
        Dig d = digs.get(p.getUniqueId());
        if (d == null || !d.block.equals(e.getBlock()) || !done(d.ticks, d.needed)) e.setCancelled(true);
        else stop(p);
    }

    /** Each tick, while anyone digs. */
    void tick() {
        for (UUID id : digs.keySet().toArray(UUID[]::new)) {
            Player p = Bukkit.getPlayer(id);
            Dig d = digs.get(id);
            if (p == null) { digs.remove(id); continue; }
            if (p.isDead() || p.getWorld() != d.block.getWorld() || p.getLocation().distanceSquared(d.block.getLocation()) > REACH_SQ
                    || slow(d.block) == null) { stop(p); continue; }
            d.ticks++;
            slowDown(p, d); // another tool or an effect doesn't make it faster
            int at = d.attacked == null ? 0 : warning(d.ticks, d.needed);
            if (at > 0) d.attacked.tell("The Town Hall of " + d.attacked.name + " is " + at + "% broken by " + p.getName() + ". Once it breaks, the colony is gone.");
        }
    }

    /** Ticks of digging a hut block of this kind takes. */
    static int needed(Hut.Type t) {
        return t == Hut.Type.TOWN_HALL ? TOWN_HALL_TICKS : t == Hut.Type.POST_BOX || t == Hut.Type.STASH ? SMALL_TICKS : HUT_TICKS;
    }

    /** Whether a block dug for {@code ticks} may break: vanilla's 70%, as the player's game may be ahead of the server's. */
    static boolean done(int ticks, int needed) {
        return ticks >= needed * 0.7;
    }

    /** The share broken to warn of at just this tick, in percent, or 0. Ours: of the real time; the mod's messages come within its first 14 s. */
    static int warning(int ticks, int needed) {
        for (int at : WARN_AT) if (ticks == needed * at / 100) return at;
        return 0;
    }

    private Hut slow(Block b) {
        Colony c = Attackers.pvp ? plugin.colonyAt(b) : null;
        Hut hut = c == null ? null : c.hutAt(b);
        return hut == null || hut.type == Hut.Type.FIELD ? null : hut;
    }

    private static void slowDown(Player p, Dig d) {
        var speed = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        double now = d.block.getBreakSpeed(p), wanted = 1.0 / d.needed;
        if (now <= 0 || Math.abs(now / wanted - 1) < 0.01) return; // as it should be: nothing to send
        var old = speed.getModifier(KEY);
        double factor = wanted / now * (old == null ? 1 : old.getAmount() + 1);
        speed.removeModifier(KEY);
        speed.addTransientModifier(new AttributeModifier(KEY, factor - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void stop(Player p) {
        if (digs.remove(p.getUniqueId()) != null) p.getAttribute(Attribute.BLOCK_BREAK_SPEED).removeModifier(KEY);
    }
}
