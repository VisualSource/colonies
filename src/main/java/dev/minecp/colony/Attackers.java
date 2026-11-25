package dev.minecp.colony;

import dev.minecp.citizen.Colonist;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Players attacking a colony with guards of their own colony (the mod's PvP mode: AttackingPlayer, Colony's
 * updateAttackingPlayers, EventHandler.onEnteringChunkEntity), and whom its guards fight.
 */
public final class Attackers {
    /** The mod's pvp_mode, off by default (config.yml). */
    public static boolean pvp;
    /** The colony whose citizen's body or legs this is, or null (the plugin sets it). */
    public static Function<Entity, Colony> colonyOf = e -> null;

    private static final int AWAY = 2 * 72000; // the mod's lastContactInHours over 1: no attacks once no owner or officer was on for 2 hours

    /** The guards a player came with; the first one's colony decides whether the attack counts, as the mod's. */
    private record Party(Colony from, List<Colonist> guards) {}

    private final Colony colony;
    private final Map<UUID, Party> parties = new HashMap<>();
    int away; // ticks since an owner or officer was on; saved, as the mod's

    Attackers(Colony colony) {
        this.colony = colony;
    }

    /**
     * Whether a colony may attack another (the mod's isValidAttack): its owner is hostile there, or it holds that
     * colony's owner hostile itself and is at most twice as strong (their raid levels).
     */
    static boolean mayAttack(boolean hostileThere, boolean holdsHostile, int strength, int theirs) {
        return hostileThere || holdsHostile && strength <= theirs * 2;
    }

    private boolean enemy(Colony other) {
        return pvp && away < AWAY && mayAttack(colony.permissions.rank(other.owner) == Rank.HOSTILE,
                other.permissions.rank(colony.owner) == Rank.HOSTILE, other.raids.level(), colony.raids.level());
    }

    /** Whether {@code p} is attacking the colony now, with guards (the mod's isValidAttackingPlayer). */
    public boolean attacking(Player p) {
        Party a = parties.get(p.getUniqueId());
        return a != null && enemy(a.from());
    }

    /**
     * Whom the colony's guards fight: monsters, hostile players, attackers (ours: not during a raid, when the mod's hits
     * on them don't count) and, in PvP mode, the citizens of a colony that may attack (ours: the mod's mode isn't asked).
     */
    public boolean foe(Entity e) {
        if (e instanceof Enemy) return true;
        if (e instanceof Player p)
            return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                    && (colony.permissions.rank(p.getUniqueId()) == Rank.HOSTILE || attacking(p) && !colony.raids.underway());
        if (!pvp || !(e instanceof Mannequin) && !e.getScoreboardTags().contains(Colonist.LEGS_TAG) || colony.colonistOf(e) != null) return false;
        Colony other = colonyOf.apply(e);
        return other != null && other != colony && enemy(other) && other.attackers.away < AWAY; // ours: nor one whose own guards couldn't fight back
    }

    /** A guard of {@code from} that rallies to or follows {@code p} is on the colony's land, with the player. */
    public void join(Player p, Colonist guard, Colony from) {
        if (!onLand(p.getLocation()) || colony.isManager(p.getUniqueId()) || !enemy(from)) return;
        Party a = parties.get(p.getUniqueId());
        if (a == null) {
            parties.put(p.getUniqueId(), new Party(from, new ArrayList<>(List.of(guard))));
            colony.tell(p.getName() + " has brought guards into " + colony.name + " to attack it.");
        } else if (!a.guards().contains(guard)) {
            a.guards().add(guard);
            colony.tell("Guards with " + p.getName() + " in " + colony.name + ": " + a.guards().size() + ".");
        }
    }

    /** Once a second: who left or lost all their guards isn't attacking any more. */
    void everySecond() {
        boolean manager = false;
        if (pvp) for (Player p : Bukkit.getOnlinePlayers()) if (colony.isManager(p.getUniqueId())) { manager = true; break; }
        away = !pvp || manager ? 0 : Math.min(away + 20, AWAY);
        parties.entrySet().removeIf(e -> {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !onLand(p.getLocation())) return true;
            Party a = e.getValue();
            a.guards().removeIf(g -> g.living() == null || !a.from().colonists.contains(g) || !onLand(g.location()));
            if (!a.guards().isEmpty()) return false;
            colony.tell("The attack of " + p.getName() + " on " + colony.name + " is over: no guards are left with them.");
            return true;
        });
    }

    /** A citizen of {@code from} hurt one of the colony's: in PvP mode its owner is hostile here from now on (the mod's). */
    void hurtBy(Colony from) {
        if (!pvp || colony.permissions.rank(from.owner) == Rank.HOSTILE) return;
        String name = Bukkit.getOfflinePlayer(from.owner).getName();
        if (colony.permissions.setRank(from.owner, name == null ? "?" : name, Rank.HOSTILE))
            colony.tell(from.name + " attacked " + colony.name + ": " + (name == null ? "its owner" : name) + " is hostile now.");
    }

    private boolean onLand(Location at) {
        return colony.claimMap != null && colony.claimMap.colonyAt(at) == colony;
    }
}
