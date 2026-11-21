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

    // The mod's supply deployers (ISupplyItem, ItemSupplyCampDeployer, ItemSupplyChestDeployer): placed 10 blocks ahead,
    // at most a third of the footprint may fail its checks, the ship needs 3 layers of water and room 7 above them.
    private static final int SUPPLY_OFFSET = 10, WATER_LEVELS = 3, SCAN_HEIGHT = 7;
    private static final double SUPPLY_TOLERANCE = 1.0 / 3;

    private static final class Session {
        final Catalog.Entry entry;
        final String style;
        String supply; // "camp" or "ship" while placing a supply item, else null
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
        String supply = plugin.items.supplyKind(e.getItem());
        if (supply != null && e.getHand() == EquipmentSlot.OFF_HAND) { e.setCancelled(true); return; } // never put down as a plain chest or boat
        if (e.getHand() != EquipmentSlot.HAND || supply == null && !plugin.items.isBuildTool(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        e.setCancelled(true); // no chest or boat put down
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) controls(p, s);
        else if (supply != null) supplies(p, supply, e.getClickedBlock());
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

    /** A supply item: which style's camp or ship (the mod uses the pack the player picked), then its placement. */
    private void supplies(Player p, String kind, Block clicked) {
        if (p.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) {
            p.sendMessage(Component.text("Supplies only arrive in the overworld.", NamedTextColor.RED));
            return;
        }
        if (usedSupplies(p)) {
            p.sendMessage(Component.text("You have had your supplies already: each player gets one camp or ship.", NamedTextColor.RED));
            return;
        }
        String path = "decorations/supplies/supply" + kind;
        List<ActionButton> buttons = new ArrayList<>();
        for (String style : dev.minecp.colony.Colony.styles())
            if (BuildOrder.exists(style + "/" + path)) buttons.add(button(style, p, null, player -> startSupply(player, kind, path, style, clicked)));
        if (buttons.isEmpty()) { p.sendMessage(Component.text("No style has a supply " + kind + ": is a style's data pack installed?", NamedTextColor.RED)); return; }
        show(p, kind.equals("ship") ? "Supply Ship" : "Supply Camp", List.of(text("In which style?")), buttons);
    }

    /** Starts placing the camp or ship centred 10 blocks ahead: the camp's ground layer on the ground, the ship's keel 3 below the water's surface. */
    private void startSupply(Player p, String kind, String path, String style, Block clicked) {
        Block at = (clicked != null ? clicked : p.getLocation().getBlock()).getRelative(p.getFacing(), SUPPLY_OFFSET);
        var w = at.getWorld();
        int top = w.getHighestBlockYAt(at.getX(), at.getZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES); // water counts
        Block anchor = w.getBlockAt(at.getX(), kind.equals("ship") ? top - (WATER_LEVELS - 1) : top, at.getZ());
        var entry = new Catalog.Entry(path, kind.equals("ship") ? "Supply Ship" : "Supply Camp", null, false);
        BuildOrder o = BuildOrder.create(entry.structure(style), anchor.getLocation(), TURNS[0], false, null);
        if (o == null) return;
        int[] fp = o.footprint();
        anchor = anchor.getRelative((fp[0] - fp[1]) / 2, 0, (fp[2] - fp[3]) / 2); // the corner, so it is centred there
        end(p);
        Session s = new Session(entry, style, anchor);
        s.supply = kind;
        sessions.put(p.getUniqueId(), s);
        if (draw(p, s)) p.sendMessage(Component.text("Placing the " + entry.name() + ". Right-click with it again to move, turn or confirm it.", NamedTextColor.GREEN));
    }

    private static final String SUPPLIES_USED = "supplies_used";

    /** Whether the player has placed a camp or ship before (the mod counts uses of the item); creative may place more. */
    private boolean usedSupplies(Player p) {
        return p.getGameMode() != GameMode.CREATIVE
                && p.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, SUPPLIES_USED));
    }

    /**
     * The mod's checks (canCampBePlaced, canShipBePlaced), each allowing a third of the footprint to fail: the camp's
     * ground layer stands on solid ground with nothing solid on it; the ship's hull is in water for its 3 lowest layers,
     * with nothing solid in its way up to 7 more (the mod's loop takes layers 0 to 3 + 7, both included; ours also: those
     * 3 layers are mostly water). Every style's supply blueprints have the mod's default levels (1 ground layer, 3 water
     * layers), so they are fixed here rather than read from the blueprint's tag. Ours, stricter: no part inside a colony
     * where the player may not place blocks (the mod counts those spots among the third), and no container in the way.
     * @return why not, or null.
     */
    private String supplyProblem(Player p, Session s, BuildOrder o) {
        var w = s.anchor.getWorld();
        int[] fp = o.footprint();
        for (int cx = fp[0] >> 4; cx <= (fp[1] - 1) >> 4; cx++) for (int cz = fp[2] >> 4; cz <= (fp[3] - 1) >> 4; cz++) {
            Colony c = plugin.claims.colonyAt(w, cx, cz);
            if (c != null && !c.permissions.has(p, dev.minecp.colony.Action.PLACE_BLOCKS)) return "That is inside " + c.name + ".";
        }
        Set<Block> planned = new HashSet<>();
        int height = 0;
        for (BuildOrder.Step st : o.steps) {
            // ours: the paste replaces everything in its box, and a chest's items would be lost with it
            if (st.block().getState(false) instanceof org.bukkit.block.Container) return "A chest or other container is in the way at "
                    + st.block().getX() + " " + st.block().getY() + " " + st.block().getZ() + ".";
            planned.add(st.block());
            height = Math.max(height, st.block().getY() - s.anchor.getY() + 1);
        }
        boolean ship = "ship".equals(s.supply);
        int y0 = s.anchor.getY(), inTheWay = 0, unsupported = 0, dry = 0;
        for (int x = fp[0]; x < fp[1]; x++) for (int z = fp[2]; z < fp[3]; z++) {
            if (!ship) {
                Block ground = w.getBlockAt(x, y0, z);
                if (planned.contains(ground) && !ground.getType().isSolid()) unsupported++;
                if (ground.getRelative(BlockFace.UP).getType().isSolid()) inTheWay++;
                continue;
            }
            for (int y = 0; y <= Math.min(WATER_LEVELS + SCAN_HEIGHT, height - 1); y++) {
                Block b = w.getBlockAt(x, y0 + y, z);
                if (y < WATER_LEVELS && !water(b)) dry++;
                if (!planned.contains(b)) continue; // the structure leaves this spot as it is
                if (y < WATER_LEVELS) { if (!water(b)) unsupported++; }
                else if (b.getType().isSolid()) inTheWay++;
            }
        }
        double allowed = (fp[1] - fp[0]) * (fp[3] - fp[2]) * SUPPLY_TOLERANCE;
        // Ours: the mod only asks for water where the hull is (a few blocks in its lowest layers), so a ship could
        // land on dry ground; here two thirds of everything under it must be water too.
        if (ship && dry > allowed * WATER_LEVELS) unsupported = Integer.MAX_VALUE;
        if (unsupported > allowed) return ship ? "The ship needs open water under it, " + WATER_LEVELS + " blocks deep. Move it down or out to sea."
                : "The camp needs solid ground under it. Move it down or to flatter ground.";
        if (inTheWay > allowed) return ship ? "Too much is in the ship's way above the water." : "Too much stands on the ground there. Move it up or clear the spot.";
        return null;
    }

    private static boolean water(Block b) {
        return b.getType() == org.bukkit.Material.WATER || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    /**
     * The mod's SuppliesHandler: placed at once with everything in it, the supply item used up, once per player. The
     * barrel's Town Hall and gold scepter (converted as tagged stand-ins) become our Town Hall and Build Tool.
     */
    private void placeSupplies(Player p, Session s) {
        BuildOrder o = order(s);
        String problem = o == null ? "No structure for the " + s.entry.name() + "." : usedSupplies(p) ? "You have had your supplies already." : supplyProblem(p, s, o);
        int slot = -1;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && slot < 0; i++) if (s.supply.equals(plugin.items.supplyKind(inv.getItem(i)))) slot = i;
        if (problem == null && slot < 0) problem = "You no longer carry the " + s.entry.name() + "."; // creative too, as in the mod
        if (problem != null) {
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return; // placement stays open to fix it
        }
        end(p);
        o.paste();
        boolean townHall = false, buildTool = false;
        for (BuildOrder.Step st : o.steps)
            if (st.block().getState(false) instanceof org.bukkit.block.Container c)
                for (int i = 0; i < c.getInventory().getSize(); i++) {
                    ItemStack real = plugin.items.starter(c.getInventory().getItem(i));
                    if (real == null) continue;
                    c.getInventory().setItem(i, real);
                    townHall |= plugin.items.hutType(real) == Hut.Type.TOWN_HALL;
                    buildTool |= plugin.items.isBuildTool(real);
                }
        // Ours: the cavern camp and ship have no kit in their barrels, so whatever is missing goes to the player.
        List<ItemStack> missing = new ArrayList<>();
        if (!townHall) missing.add(plugin.items.hutItem(Hut.Type.TOWN_HALL));
        if (!buildTool) missing.add(plugin.items.buildTool());
        inv.setItem(slot, null); // used up, in creative too (the mod's)
        p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, SUPPLIES_USED), org.bukkit.persistence.PersistentDataType.BOOLEAN, true); // creative too
        dev.minecp.Advancements.award(p, "minecolonies/root"); // the mod's root advancement is placing supplies
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
        missing.forEach(it -> inv.addItem(it).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left)));
        p.sendMessage(Component.text("Your " + s.entry.name().toLowerCase() + " has arrived. " + (missing.isEmpty()
                ? "Its barrel holds a Town Hall and a Build Tool" : "You have been given a Town Hall and a Build Tool")
                + ": place the Town Hall to found your colony.", NamedTextColor.GREEN));
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
        if (s.supply != null) { placeSupplies(p, s); return; }
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
