package dev.minecp;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The build tool (the Colony Token stands in for Structurize's): right-click a block to pick a hut from your inventory,
 * then a preview of its level 1 building stays in view, only for you, until you confirm or cancel. Right-click again
 * for the mod's controls: move relative to where you face, up/down, rotate, mirror, confirm, cancel.
 */
final class Placement implements Listener {
    private static final float SCALE = 0.9f; // a bit smaller than real blocks, so it reads as a preview
    private static final StructureRotation[] TURNS = {StructureRotation.NONE, StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180, StructureRotation.COUNTERCLOCKWISE_90};
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private static final class Session {
        final Hut.Type type;
        final String style;
        Block anchor; // where the hut block goes
        int turn;     // index into TURNS
        boolean mirror;
        final List<BlockDisplay> shown = new ArrayList<>();

        Session(Hut.Type type, String style, Block anchor) {
            this.type = type;
            this.style = style;
            this.anchor = anchor;
        }
    }

    private final MineCP plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    Placement(MineCP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !plugin.isToken(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) controls(p, s);
        else if (e.getClickedBlock() != null) choose(p, e.getClickedBlock().getRelative(e.getBlockFace()));
        else p.sendMessage(Component.text("Right-click the ground where the hut block should go.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        end(e.getPlayer());
    }

    void endAll() {
        for (Session s : sessions.values()) s.shown.forEach(org.bukkit.entity.Entity::remove);
        sessions.clear();
    }

    /** Lists the buildings whose hut items the player carries (in creative: all of them). */
    private void choose(Player p, Block anchor) {
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        List<ActionButton> buttons = new ArrayList<>();
        for (Hut.Type type : Hut.Type.values()) {
            if (!type.isBuilding()) continue;
            int have = 0;
            for (ItemStack it : p.getInventory().getContents())
                if (it != null && plugin.hutType(it) == type) have += it.getAmount();
            if (have == 0 && !creative) continue;
            buttons.add(button(type.title + (creative ? "" : " (" + have + ")"), p, null, player -> start(player, type, anchor)));
        }
        if (buttons.isEmpty()) {
            p.sendMessage(Component.text("Carry a hut item (crafted with the Colony Token) to place its building.", NamedTextColor.RED));
            return;
        }
        show(p, "Build tool", List.of(text("Which building goes here?")), buttons);
    }

    private void start(Player p, Hut.Type type, Block anchor) {
        Colony c = plugin.colonyOf(p);
        end(p);
        Session s = new Session(type, c == null ? Colony.DEFAULT_STYLE : c.style, anchor);
        sessions.put(p.getUniqueId(), s);
        if (draw(p, s)) p.sendMessage(Component.text("Placing the " + type.title
                + ". Right-click with the Colony Token to move, turn or confirm it.", NamedTextColor.GREEN));
    }

    /** The building as it would stand now. Null if its structure is missing. */
    private static BuildOrder order(Session s) {
        return BuildOrder.create(s.style + "/" + s.type.path + 1, s.anchor.getLocation(), TURNS[s.turn], s.mirror, s.anchor);
    }

    /** Replaces the preview. @return false (and ends placement) if the structure is missing. */
    private boolean draw(Player p, Session s) {
        s.shown.forEach(org.bukkit.entity.Entity::remove);
        s.shown.clear();
        BuildOrder o = order(s);
        if (o == null) {
            p.sendMessage(Component.text("No structure for the " + s.type.title + " in the " + s.style + " style.", NamedTextColor.RED));
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
        s.shown.addAll(Tape.around(s.anchor.getWorld(), new int[]{o.minX, o.maxX + 1, o.minZ, o.maxZ + 1}, s.anchor.getY() + 0.01, false));
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
        show(p, s.type.title, List.of(text("Placing the " + s.type.title + ". Directions are from where you face.")), buttons);
    }

    private void move(Player p, Session s, BlockFace f) {
        s.anchor = s.anchor.getRelative(f);
        draw(p, s);
    }

    private static BlockFace turn(BlockFace f, int quarterTurns) {
        for (int i = 0; i < 4; i++) if (SIDES[i] == f) return SIDES[(i + quarterTurns + 4) % 4];
        return f;
    }

    /** As in the mod: the hut item is used up and the hut block put down; the builder builds it from the hut's menu. */
    private void confirm(Player p, Session s) {
        String problem = s.anchor.getY() < s.anchor.getWorld().getMinHeight() ? "Too low to build." : plugin.placeProblem(p, s.type, s.anchor);
        if (problem == null && plugin.colonyAt(s.anchor) != null) problem = "Another hut is in that spot.";
        if (problem == null && (plugin.graveColony(s.anchor) != null || !Job.contents(s.anchor, false).isEmpty()))
            problem = "Clear the block at the hut spot first.";
        int slot = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && slot < 0; i++) if (inv.getItem(i) != null && plugin.hutType(inv.getItem(i)) == s.type) slot = i;
        if (problem == null && slot < 0 && p.getGameMode() != GameMode.CREATIVE) problem = "You no longer carry a " + s.type.title + ".";
        BuildOrder o = problem == null ? order(s) : null;
        if (problem == null && o == null) problem = "No structure for the " + s.type.title + ".";
        // Last, so it is only asked when the block really goes: as a player break, so protection plugins can refuse it.
        if (problem == null && !s.anchor.isEmpty() && !new org.bukkit.event.block.BlockBreakEvent(s.anchor, p).callEvent())
            problem = "You can't break the block at the hut spot.";
        if (problem != null) {
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return; // placement stays open to fix it
        }
        // The hut block, turned the way the building's own hut block is.
        BlockData block = s.type.block.createBlockData();
        for (BuildOrder.Step st : o.steps)
            if (st.block().equals(s.anchor) && st.data() instanceof Directional marker && block instanceof Directional d
                    && d.getFaces().contains(marker.getFacing())) d.setFacing(marker.getFacing());
        if (!s.anchor.isEmpty()) s.anchor.breakNaturally();
        s.anchor.setBlockData(block);
        if (slot >= 0 && p.getGameMode() != GameMode.CREATIVE) {
            ItemStack it = inv.getItem(slot);
            it.subtract();
            inv.setItem(slot, it.isEmpty() ? null : it);
        }
        end(p);
        plugin.placed(p, s.type, s.anchor, TURNS[s.turn], s.mirror);
    }

    private void end(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) s.shown.forEach(org.bukkit.entity.Entity::remove);
    }

    /** A button for {@code shownTo} only, run on the main thread while that placement is still the open one. */
    private ActionButton button(String label, Player shownTo, Session s, Consumer<Player> action) {
        return ActionButton.builder(Component.text(label)).width(150).action(DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player) || !player.getUniqueId().equals(shownTo.getUniqueId())) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (s == null || sessions.get(player.getUniqueId()) == s) action.accept(player);
            });
        }, ONCE)).build();
    }

    private static DialogBody text(String s) {
        return DialogBody.plainMessage(Component.text(s), 300);
    }

    private static void show(Player p, String title, List<DialogBody> body, List<ActionButton> buttons) {
        p.showDialog(Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(DialogType.multiAction(buttons).columns(2)
                        .exitAction(ActionButton.builder(Component.text("Close")).build()).build())));
    }
}
