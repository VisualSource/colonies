package dev.minecp.colony;

import java.util.EnumSet;
import java.util.Set;

import static dev.minecp.colony.Action.*;

/**
 * The mod's five built-in ranks with its default actions (its {@code Permissions.loadRanks}): each rank above neutral
 * has everything the one below it has. Neutral is anyone who isn't a member. Colonies can change each rank's actions.
 */
public enum Rank {
    OWNER, OFFICER, FRIEND, NEUTRAL, HOSTILE;

    /** The mod's default actions for this rank. */
    Set<Action> defaults() {
        Set<Action> a = EnumSet.noneOf(Action.class);
        switch (this) {
            case HOSTILE -> a.addAll(EnumSet.of(HURT_CITIZEN, HURT_VISITOR)); // the mod lets hostile players hurt citizens and visitors properly
            case NEUTRAL -> a.add(ACCESS_TOGGLEABLES);
            case FRIEND, OFFICER, OWNER -> {
                a.addAll(NEUTRAL.defaults());
                a.addAll(EnumSet.of(ACCESS_HUTS, TOSS_ITEM, PICKUP_ITEM, RIGHTCLICK_BLOCK, RIGHTCLICK_ENTITY, THROW_POTION,
                        SHOOT_ARROW, ATTACK_CITIZEN, ATTACK_ENTITY));
                if (this != FRIEND) a.addAll(EnumSet.of(PLACE_HUTS, BREAK_HUTS, MANAGE_HUTS, RECEIVE_MESSAGES, PLACE_BLOCKS,
                        BREAK_BLOCKS, FILL_BUCKET, OPEN_CONTAINER));
                if (this == OWNER) a.add(EDIT_PERMISSIONS);
            }
        }
        return a;
    }

    /** The mod's actions for a server operator in creative mode, whatever their rank. */
    static final Set<Action> OP = EnumSet.of(ACCESS_HUTS, TOSS_ITEM, PICKUP_ITEM, RIGHTCLICK_BLOCK, RIGHTCLICK_ENTITY,
            THROW_POTION, SHOOT_ARROW, ATTACK_CITIZEN, ATTACK_ENTITY, ACCESS_TOGGLEABLES, PLACE_HUTS, BREAK_HUTS, MANAGE_HUTS,
            PLACE_BLOCKS, BREAK_BLOCKS, FILL_BUCKET, OPEN_CONTAINER);

    /** "Officer". */
    public String title() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
