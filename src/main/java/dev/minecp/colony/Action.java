package dev.minecp.colony;

/**
 * What a player may do in a colony, switched on or off per {@link Rank} (the mod's {@code Action}). Only the actions
 * we enforce are here; the mod's scan tool, map, visitor, teleport and explosion actions wait for those features.
 */
public enum Action {
    ACCESS_HUTS("Open hut menus"),
    PLACE_HUTS("Place huts"),
    BREAK_HUTS("Break huts"),
    MANAGE_HUTS("Use hut menu buttons and the build tool"),
    EDIT_PERMISSIONS("Change members and ranks"),
    RECEIVE_MESSAGES("Get colony messages"),
    PLACE_BLOCKS("Place blocks"),
    BREAK_BLOCKS("Break blocks"),
    FILL_BUCKET("Use buckets"),
    OPEN_CONTAINER("Open chests and other containers"),
    ACCESS_TOGGLEABLES("Use doors, gates and trapdoors"),
    RIGHTCLICK_BLOCK("Use other blocks"),
    RIGHTCLICK_ENTITY("Use animals and other entities"),
    TOSS_ITEM("Drop items"),
    PICKUP_ITEM("Pick up items"),
    THROW_POTION("Throw potions"),
    SHOOT_ARROW("Shoot arrows"),
    ATTACK_ENTITY("Attack animals and other entities"),
    ATTACK_CITIZEN("Attack citizens"),
    HURT_CITIZEN("Hit citizens for more than 1 damage"),
    HURT_VISITOR("Hit visitors for more than 1 damage"),
    RALLY_GUARDS("Rally guards with a banner");

    public final String label;

    Action(String label) {
        this.label = label;
    }
}
