package dev.minecp.build;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;

/**
 * The mod's decoration controller (BlockDecorationController): the block a build without a hut may have, which says
 * what was built there and how, so it can be upgraded and repaired (Decorations). A waxed wall sign with a tag stands
 * in for it; as in the mod it costs the builder nothing, drops nothing and is never built over.
 */
public record DecorationController(String structure, StructureRotation rotation, boolean mirror) {
    private static final NamespacedKey KEY = NamespacedKey.fromString("minecp:decoration"); // "structure|rotation|mirror"

    public static boolean is(Block b) {
        return at(b) != null;
    }

    /** The controller at {@code b}, or null. */
    public static DecorationController at(Block b) {
        if (!Tag.WALL_SIGNS.isTagged(b.getType()) || !(b.getState(false) instanceof Sign sign)) return null;
        String[] s = sign.getPersistentDataContainer().getOrDefault(KEY, PersistentDataType.STRING, "").split("\\|");
        return s.length == 3 ? new DecorationController(s[0], StructureRotation.valueOf(s[1]), Boolean.parseBoolean(s[2])) : null;
    }

    /** A build is finished: its controller, if it has one, now says so. Ours: the mod's goes up with the rest of the build. */
    public static void place(BuildOrder order) {
        if (order.controller == null) return;
        Block b = order.controller.block();
        if (!is(b)) {
            if (!b.isReplaceable()) return; // something the builder left alone stands there
            var sign = (WallSign) Material.OAK_WALL_SIGN.createBlockData();
            sign.setFacing(order.controller.facing());
            sign.setWaterlogged(b.getType() == Material.WATER);
            b.setBlockData(sign, false);
        }
        var made = new DecorationController(order.structure, order.rotation, order.mirror);
        Sign sign = (Sign) b.getState(false);
        sign.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, made.structure + "|" + made.rotation + "|" + made.mirror);
        sign.setWaxed(true);
        sign.getSide(Side.FRONT).line(1, Component.text(made.title()));
        sign.getSide(Side.FRONT).line(2, Component.text(made.level() > 0 ? "Level " + made.level() : ""));
        sign.update(true, false);
    }

    /** Its level, the digit its structure's name ends with (the mod's getBlueprintLevel); 0 if it has none. */
    int level() {
        char last = structure.charAt(structure.length() - 1);
        return last >= '0' && last <= '9' ? last - '0' : 0;
    }

    private String title() {
        return Catalog.title(level() > 0 ? structure.substring(0, structure.length() - 1) : structure);
    }

    /** "Smallwell level 2". */
    public String name() {
        return level() > 0 ? title() + " level " + level() : title();
    }

    /** The same one level higher, or null if it has no levels. */
    public DecorationController upgraded() {
        return level() == 0 ? null : new DecorationController(structure.substring(0, structure.length() - 1) + (level() + 1), rotation, mirror);
    }

    /** Its build around the controller at {@code b}, or null: no such structure, or it has no controller (the mod's checks). */
    public BuildOrder order(Block b) {
        Location at = b.getLocation();
        BuildOrder o = BuildOrder.create(structure, at, rotation, mirror, b);
        return o == null || o.controller == null ? null : o;
    }
}
