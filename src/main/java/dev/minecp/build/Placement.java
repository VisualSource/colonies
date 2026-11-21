package dev.minecp.build;

import dev.minecp.MineCP;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.job.Job;
import dev.minecp.menu.Dialogs;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The build tool (our Build Tool item stands in for Structurize's): right-click a block and browse the colony's style
 * by its categories, as the mod's build tool does ({@link Catalog}): huts in any of their versions (from a hut item you
 * carry) and builds without a hut (decorations, infrastructure, walls). A preview of the level 1 build stays in view,
 * only for you, until you confirm or cancel; right-click again for the mod's controls: move relative to where you face,
 * up/down, rotate, mirror, confirm, cancel. A hut is placed at once; a build without a hut is queued for the builder.
 */
public final class Placement implements Listener {
    private static final float SCALE = 0.9f; // a bit smaller than real blocks, so it reads as a preview
    private static final StructureRotation[] TURNS = {StructureRotation.NONE, StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180, StructureRotation.COUNTERCLOCKWISE_90};
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static final class Session {
        final Catalog.Entry entry;
        final String style;
        Block anchor; // where the hut block goes; for a build without a hut, its corner
        int turn;     // index into TURNS
        boolean mirror;
        final List<BlockDisplay> shown = new ArrayList<>();

        Session(Catalog.Entry entry, String style, Block anchor) {
            this.entry = entry;
            this.style = style;
            this.anchor = anchor;
        }
    }

    private final MineCP plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public Placement(MineCP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !plugin.items.isBuildTool(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) controls(p, s);
        else if (e.getClickedBlock() != null) choose(p, e.getClickedBlock().getRelative(e.getBlockFace()));
        else p.sendMessage(Component.text("Right-click the ground where it should go.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        end(e.getPlayer());
    }

    public void endAll() {
        for (Session s : sessions.values()) s.shown.forEach(org.bukkit.entity.Entity::remove);
        sessions.clear();
    }

    /** The style of the colony a build here would join (else the player's own colony's, else the default). */
    private void choose(Player p, Block anchor) {
        Colony c = plugin.claims.colonyAt(anchor.getLocation());
        if (c == null) c = plugin.colonyOf(p);
        browse(p, anchor, c == null ? Colony.DEFAULT_STYLE : c.style, "");
    }

    /** One category of the style: its sub-categories, then what can be placed in it (huts with how many you carry). */
    private void browse(Player p, Block anchor, String style, String folder) {
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        List<ActionButton> buttons = new ArrayList<>();
        for (String sub : Catalog.folders(style, folder))
            buttons.add(button(Catalog.title(sub) + " ›", p, null, player -> browse(player, anchor, style, sub)));
        for (Catalog.Entry e : Catalog.entries(style, folder)) {
            String label = e.name();
            if (e.hut() != null && !creative) label += " (" + carried(p, e.hut()) + ")";
            buttons.add(button(label, p, null, player -> start(player, e, style, anchor)));
        }
        if (!folder.isEmpty()) {
            int up = folder.lastIndexOf('/');
            buttons.add(button("‹ Back", p, null, player -> browse(player, anchor, style, up < 0 ? "" : folder.substring(0, up))));
        }
        if (buttons.isEmpty()) {
            p.sendMessage(Component.text("Nothing to build in the " + style + " style: is its data pack installed?", NamedTextColor.RED));
            return;
        }
        show(p, folder.isEmpty() ? "Build tool: " + style : Catalog.title(folder),
                List.of(text(folder.isEmpty() ? "Pick a category. A hut needs its hut item; anything else is built by your builder." : "What goes here?")), buttons);
    }

    private int carried(Player p, Hut.Type type) {
        int have = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && plugin.items.hutType(it) == type) have += it.getAmount();
        return have;
    }

    private void start(Player p, Catalog.Entry entry, String style, Block anchor) {
        if (entry.hut() != null && p.getGameMode() != GameMode.CREATIVE && carried(p, entry.hut()) == 0) {
            p.sendMessage(Component.text("Carry a " + entry.hut().title + " item (crafted with the Build Tool) to place it.", NamedTextColor.RED));
            return;
        }
        end(p);
        Session s = new Session(entry, style, anchor);
        sessions.put(p.getUniqueId(), s);
        if (draw(p, s)) p.sendMessage(Component.text("Placing the " + entry.name()
                + ". Right-click with the Build Tool to move, turn or confirm it.", NamedTextColor.GREEN));
    }

    /** The building as it would stand now. Null if its structure is missing. */
    private static BuildOrder order(Session s) {
        return BuildOrder.create(s.entry.structure(s.style), s.anchor.getLocation(), TURNS[s.turn], s.mirror, s.entry.hut() != null ? s.anchor : null);
    }

    /** Replaces the preview. @return false (and ends placement) if the structure is missing. */
    private boolean draw(Player p, Session s) {
        s.shown.forEach(org.bukkit.entity.Entity::remove);
        s.shown.clear();
        BuildOrder o = order(s);
        if (o == null) {
            p.sendMessage(Component.text("No structure for the " + s.entry.name() + " in the " + s.style + " style.", NamedTextColor.RED));
            end(p);
            return false;
        }
        Set<Block> solid = new HashSet<>();
        for (BuildOrder.Step st : o.steps) if (!st.isClear() && !st.fill() && st.data().getMaterial().isOccluding()) solid.add(st.block());
        Transformation shrink = new Transformation(new Vector3f((1 - SCALE) / 2), new AxisAngle4f(), new Vector3f(SCALE), new AxisAngle4f());
        for (BuildOrder.Step st : o.steps) {
            if (st.isClear() || st.fill()) continue; // air and foundation filler: nothing to see
            Block b = st.block();
            boolean hidden = true; // walled in on all sides: skip, it can't be seen anyway
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST})
                if (!solid.contains(b.getRelative(f))) { hidden = false; break; }
            if (hidden) continue;
            BlockData data = st.data();
            s.shown.add(b.getWorld().spawn(b.getLocation(), BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setTransformation(shrink);
                d.setBrightness(new Display.Brightness(15, 15)); // readable at night
                d.setVisibleByDefault(false);
                d.setPersistent(false);
            }));
        }
        // The building's boundary, as the tape will show it once placed.
        s.shown.addAll(Tape.around(s.anchor.getWorld(), o.footprint(), s.anchor.getY() + 0.01, false));
        for (BlockDisplay d : s.shown) p.showEntity(plugin, d);
        return true;
    }

    /** The mod's build tool window, as a dialog: each button closes it, so the preview can be seen to change. */
    private void controls(Player p, Session s) {
        BlockFace facing = p.getFacing();
        List<ActionButton> buttons = List.of(
                button("Forward", p, s, x -> move(x, s, facing)),
                button("Back", p, s, x -> move(x, s, facing.getOppositeFace())),
                button("Left", p, s, x -> move(x, s, turn(facing, -1))),
                button("Right", p, s, x -> move(x, s, turn(facing, 1))),
                button("Up", p, s, x -> move(x, s, BlockFace.UP)),
                button("Down", p, s, x -> move(x, s, BlockFace.DOWN)),
                button("Rotate left", p, s, x -> { s.turn = (s.turn + 3) % 4; draw(x, s); }),
                button("Rotate right", p, s, x -> { s.turn = (s.turn + 1) % 4; draw(x, s); }),
                button("Mirror: " + (s.mirror ? "on" : "off"), p, s, x -> { s.mirror = !s.mirror; draw(x, s); }),
                button("Cancel", p, s, this::end),
                button("Confirm", p, s, x -> confirm(x, s)));
        show(p, s.entry.name(), List.of(text("Placing the " + s.entry.name() + ". Directions are from where you face.")), buttons);
    }

    private void move(Player p, Session s, BlockFace f) {
        s.anchor = s.anchor.getRelative(f);
        draw(p, s);
    }

    private static BlockFace turn(BlockFace f, int quarterTurns) {
        for (int i = 0; i < 4; i++) if (SIDES[i] == f) return SIDES[(i + quarterTurns + 4) % 4];
        return f;
    }

    /**
     * As in the mod: a hut's item is used up and its hut block put down (the builder builds it from the hut's menu); a
     * build without a hut goes to the builder as a work order, if it is wholly inside a colony and you may manage huts.
     */
    private void confirm(Player p, Session s) {
        if (s.entry.hut() == null) { queue(p, s); return; }
        Hut.Type type = s.entry.hut();
        BuildOrder o = order(s);
        String problem = s.anchor.getY() < s.anchor.getWorld().getMinHeight() ? "Too low to build."
                : o == null ? "No structure for the " + s.entry.name() + "."
                : plugin.placeProblem(p, type, s.anchor, o.footprint(), true);
        if (problem == null && plugin.colonyAt(s.anchor) != null) problem = "Another hut is in that spot.";
        if (problem == null && (plugin.graveColony(s.anchor) != null || !Job.contents(s.anchor).isEmpty()))
            problem = "Clear the block at the hut spot first.";
        int slot = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && slot < 0; i++) if (inv.getItem(i) != null && plugin.items.hutType(inv.getItem(i)) == type) slot = i;
        if (problem == null && slot < 0 && p.getGameMode() != GameMode.CREATIVE) problem = "You no longer carry a " + type.title + ".";
        // Last, so it is only asked when the block really goes: as a player break, so protection plugins can refuse it.
        var broken = s.anchor.isEmpty() ? null : new org.bukkit.event.block.BlockBreakEvent(s.anchor, p);
        if (problem == null && broken != null && !broken.callEvent()) problem = "You can't break the block at the hut spot.";
        if (problem != null) {
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return; // placement stays open to fix it
        }
        // The hut block, turned the way the building's own hut block is.
        BlockData block = type.block.createBlockData();
        for (BuildOrder.Step st : o.steps)
            if (st.block().equals(s.anchor) && st.data() instanceof Directional marker && block instanceof Directional d
                    && d.getFaces().contains(marker.getFacing())) d.setFacing(marker.getFacing());
        // A handler may have dropped something itself (a storage rack drops as a rack): then the block just goes.
        if (broken != null) { if (broken.isDropItems()) s.anchor.breakNaturally(); else s.anchor.setType(org.bukkit.Material.AIR); }
        s.anchor.setBlockData(block);
        if (slot >= 0 && p.getGameMode() != GameMode.CREATIVE) {
            ItemStack it = inv.getItem(slot);
            it.subtract();
            inv.setItem(slot, it.isEmpty() ? null : it);
        }
        end(p);
        // The version placed: its upgrades follow it (the mod's blueprint path); the usual one needs no note.
        plugin.placed(p, type, s.anchor, TURNS[s.turn], s.mirror, s.entry.path().equals(type.path) ? null : s.entry.path());
    }

    /** A build without a hut: queued for the colony's builder, which builds it like any work order. */
    private void queue(Player p, Session s) {
        BuildOrder o = order(s);
        Colony c = plugin.claims.colonyAt(s.anchor.getLocation());
        String problem = o == null ? "No structure for the " + s.entry.name() + "."
                : c == null ? "Place it inside a colony's border."
                : !c.permissions.has(p, dev.minecp.colony.Action.MANAGE_HUTS) ? "You can't order builds in " + c.name + "."
                : !plugin.claims.ownsAll(c, s.anchor.getWorld(), o.footprint()) ? "The " + s.entry.name().toLowerCase() + " would reach outside " + c.name + "'s border."
                : c.queueDecoration(new Colony.Decoration(s.entry.structure(s.style), s.entry.name(), s.anchor.getLocation(), TURNS[s.turn], s.mirror));
        if (problem != null) {
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return; // placement stays open to fix it
        }
        plugin.save(c);
        end(p);
        p.sendMessage(Component.text("The " + s.entry.name().toLowerCase() + " is queued for the builder of " + c.name + ".", NamedTextColor.GREEN));
    }

    private void end(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) s.shown.forEach(org.bukkit.entity.Entity::remove);
    }

    /** A button for {@code shownTo} only, run while that placement is still the open one. */
    private ActionButton button(String label, Player shownTo, Session s, Consumer<Player> action) {
        return Dialogs.button(plugin, label, 150, shownTo, () -> s == null || sessions.get(shownTo.getUniqueId()) == s,
                (player, view) -> action.accept(player));
    }

    private static DialogBody text(String s) {
        return Dialogs.text(Component.text(s));
    }

    private static void show(Player p, String title, List<DialogBody> body, List<ActionButton> buttons) {
        Dialogs.show(p, title, body, List.of(), buttons, 2);
    }
}
