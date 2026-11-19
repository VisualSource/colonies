package dev.minecp.colony;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A colony's members, their ranks, and what each rank may do (the mod's {@code Permissions}). */
public final class Permissions {
    /** A member: the name is kept for menus, as the player may be offline. */
    public record Member(String name, Rank rank) {}

    private final UUID owner;
    private final Map<UUID, Member> members = new LinkedHashMap<>(); // everyone but the owner and neutral players
    private final Map<Rank, Set<Action>> actions = new EnumMap<>(Rank.class);

    Permissions(UUID owner) {
        this.owner = owner;
        for (Rank r : Rank.values()) actions.put(r, r.defaults());
    }

    public Rank rank(UUID player) {
        if (player.equals(owner)) return Rank.OWNER;
        Member m = members.get(player);
        return m == null ? Rank.NEUTRAL : m.rank();
    }

    public boolean has(Rank rank, Action action) {
        // The owner can always reach the menus and these settings, whatever was saved (the mod's upgradePermissions).
        if (rank == Rank.OWNER && (action == Action.ACCESS_HUTS || action == Action.MANAGE_HUTS || action == Action.EDIT_PERMISSIONS)) return true;
        if ((rank == Rank.NEUTRAL || rank == Rank.HOSTILE) && action == Action.EDIT_PERMISSIONS) return false; // as the mod
        return actions.get(rank).contains(action);
    }

    /** Whether {@code p} may do this here; an operator in creative mode gets the mod's OP actions on top of their rank. */
    public boolean has(Player p, Action action) {
        return has(rank(p.getUniqueId()), action)
                || p.hasPermission("minecp.admin") && p.getGameMode() == GameMode.CREATIVE && Rank.OP.contains(action);
    }

    public Map<UUID, Member> members() {
        return Map.copyOf(members);
    }

    /** Sets a player's rank; neutral removes them. The owner's rank can't change. @return false for the owner. */
    public boolean setRank(UUID player, String name, Rank rank) {
        if (player.equals(owner) || rank == Rank.OWNER) return false;
        if (rank == Rank.NEUTRAL) members.remove(player);
        else members.put(player, new Member(name, rank));
        return true;
    }

    public void allow(Rank rank, Action action, boolean on) {
        if (on) actions.get(rank).add(action);
        else actions.get(rank).remove(action);
    }

    void save(ConfigurationSection s) {
        members.forEach((id, m) -> {
            s.set("members." + id + ".name", m.name());
            s.set("members." + id + ".rank", m.rank().name());
        });
        actions.forEach((r, set) -> s.set("ranks." + r.name(), set.stream().map(Enum::name).toList()));
    }

    void load(ConfigurationSection s) {
        var ms = s.getConfigurationSection("members");
        if (ms != null) for (String id : ms.getKeys(false))
            members.put(UUID.fromString(id), new Member(ms.getString(id + ".name", "?"), Rank.valueOf(ms.getString(id + ".rank", "FRIEND"))));
        var rs = s.getConfigurationSection("ranks");
        if (rs != null) for (String r : rs.getKeys(false)) {
            Set<Action> set = EnumSet.noneOf(Action.class);
            for (String a : rs.getStringList(r)) set.add(Action.valueOf(a));
            actions.put(Rank.valueOf(r), set);
        }
    }
}
