package dev.minecp;

import dev.minecp.colony.Action;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's gates (AbstractBlockGate: the iron gate up to 6 wide and 8 high, the wooden one 6 by 5), the mod's rules
 * unless noted. A gate is a flat grid of gate blocks that open and close together: a right-click on any of them, or a
 * redstone signal reaching one, opens the whole grid; open, all but its top row (the mod's spikes) are invisible and
 * can be walked through. Breaking one block takes the whole gate down, dropping a gate item for each block. Placing
 * one fills the grid out to its sides and up, as far as the air and the items in hand allow; not next to another gate,
 * and only on solid ground. Ours: iron bars and oak fences stand in for the closed blocks, structure void for the open
 * ones; which blocks are gates is kept in the chunk's data. Raiders break them (Raids), and the Mechanic makes them.
 */
public final class Gates implements Listener {
    /** A kind of gate: its stand-in block, the mod's size limits and per-block hardness, its name and item id. */
    public enum Kind {
        IRON(Material.IRON_BARS, 6, 8, 10, "Iron Gate", "gate_iron"),
        WOOD(Material.OAK_FENCE, 6, 5, 7, "Wooden Gate", "gate_wood");

        public final Material block;
        final int width, height;
        final float hardness;
        final String title, id;

        Kind(Material block, int width, int height, float hardness, String title, String id) {
            this.block = block;
            this.width = width;
            this.height = height;
            this.hardness = hardness;
            this.title = title;
            this.id = id;
        }

        /** Its item: the stand-in block, named and tagged (the optional resource pack shows the mod's model). */
        public ItemStack item() {
            ItemStack item = new ItemStack(block);
            item.editMeta(m -> {
                m.itemName(Component.text(title));
                m.getPersistentDataContainer().set(ITEM, PersistentDataType.STRING, name());
            });
            Items.model(item, id);
            return item;
        }

        public static Kind ofItem(ItemStack item) {
            if (item == null || !item.hasItemMeta()) return null;
            String k = item.getPersistentDataContainer().get(ITEM, PersistentDataType.STRING);
            for (Kind kind : values()) if (kind.name().equals(k)) return kind;
            return null;
        }

        /** The mod's item id ("gate_iron"), or null. */
        public static Kind ofId(String id) {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            return null;
        }
    }

    private static final NamespacedKey ITEM = new NamespacedKey(MineCP.NAMESPACE, "gate");
    private static final NamespacedKey CELLS = new NamespacedKey(MineCP.NAMESPACE, "gates"); // in a chunk: "x,y,z,KIND;..."
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final MineCP plugin;

    Gates(MineCP plugin) {
        this.plugin = plugin;
    }

    // ---- which blocks are gates -------------------------------------------------------------------------------------

    private static Map<String, Kind> cells(Chunk c) {
        Map<String, Kind> out = new LinkedHashMap<>();
        String s = c.getPersistentDataContainer().get(CELLS, PersistentDataType.STRING);
        if (s == null) return out;
        for (String cell : s.split(";")) {
            int k = cell.lastIndexOf(',');
            if (k < 0) continue;
            try { out.put(cell.substring(0, k), Kind.valueOf(cell.substring(k + 1))); } catch (IllegalArgumentException ignored) {} // an unknown kind: left out
        }
        return out;
    }

    private static void save(Chunk c, Map<String, Kind> cells) {
        if (cells.isEmpty()) { c.getPersistentDataContainer().remove(CELLS); return; }
        StringBuilder s = new StringBuilder();
        cells.forEach((pos, k) -> s.append(s.isEmpty() ? "" : ";").append(pos).append(',').append(k.name()));
        c.getPersistentDataContainer().set(CELLS, PersistentDataType.STRING, s.toString());
    }

    private static String pos(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** What kind of gate block this is, or null: a registered spot that still holds its block (closed) or structure void (open). */
    public static Kind kind(Block b) {
        Material t = b.getType();
        if (t != Material.STRUCTURE_VOID && t != Kind.IRON.block && t != Kind.WOOD.block) return null;
        if (!b.getChunk().getPersistentDataContainer().has(CELLS)) return null;
        Kind k = cells(b.getChunk()).get(pos(b));
        return k != null && (t == k.block || t == Material.STRUCTURE_VOID) ? k : null;
    }

    /** Whether it is a closed gate block (raiders break those). */
    public static boolean closed(Block b) {
        Kind k = kind(b);
        return k != null && b.getType() == k.block;
    }

    /** Marks a block as a gate block of this kind (a built blueprint's gates, or one placed). */
    public static void register(Block b, Kind k) {
        Map<String, Kind> cells = cells(b.getChunk());
        cells.put(pos(b), k);
        save(b.getChunk(), cells);
    }

    private static void unregister(Block b) {
        Map<String, Kind> cells = cells(b.getChunk());
        if (cells.remove(pos(b)) != null) save(b.getChunk(), cells);
    }

    // ---- the grid (the mod's findLowerLeftCorner, and its loops along the gate and up) ---------------------------

    /** Along which side the gate runs: east if it has a neighbour there or west, else south. */
    private static BlockFace along(Block b, Kind k) {
        return kind(b.getRelative(BlockFace.EAST)) == k || kind(b.getRelative(BlockFace.WEST)) == k ? BlockFace.EAST : BlockFace.SOUTH;
    }

    /** Every block of the gate {@code b} belongs to, column by column from its lower left corner, each bottom to top. */
    public static List<Block> gate(Block b) {
        Kind k = kind(b);
        List<Block> out = new ArrayList<>();
        if (k == null) return out;
        BlockFace right = along(b, k), left = right.getOppositeFace();
        Block corner = b;
        for (int v = 0; v < k.height && kind(corner.getRelative(BlockFace.DOWN)) == k; v++) corner = corner.getRelative(BlockFace.DOWN);
        for (int h = 0; h < k.width && kind(corner.getRelative(left)) == k; h++) corner = corner.getRelative(left);
        for (int h = 0; h < k.width; h++) {
            Block column = corner.getRelative(right, h);
            if (kind(column) != k) break;
            for (int v = 0; v < k.height && kind(column.getRelative(0, v, 0)) == k; v++) out.add(column.getRelative(0, v, 0));
        }
        return out;
    }

    /** The mod's combined hardness: each block's, together (raiders take longer over a bigger gate). */
    public static float hardness(List<Block> gate) {
        return gate.isEmpty() ? 0 : gate.size() * kind(gate.getFirst()).hardness;
    }

    /** Opens or closes the whole gate, as its lower left corner is now (the mod's toggleGate). */
    private void toggle(Block b) {
        List<Block> gate = gate(b);
        if (gate.isEmpty()) return;
        Kind k = kind(gate.getFirst());
        boolean opening = gate.getFirst().getType() != Material.STRUCTURE_VOID;
        boolean any = false;
        for (Block cell : gate) { // set without physics, so no redstone events of our own
            if (kind(cell.getRelative(BlockFace.UP)) != k) continue; // the top row stays: the mod's spikes
            cell.setType(opening ? Material.STRUCTURE_VOID : k.block, false);
            any = true;
        }
        if (!any) return; // one block high: all spikes, nothing opens
        if (!opening) for (Block cell : gate) connect(cell);
        b.getWorld().playSound(b.getLocation(), opening ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1, 1);
    }

    /** A closed block joins its neighbours again (set without physics, it wouldn't): bars and fences to solid sides and each other. */
    private static void connect(Block cell) {
        if (!(cell.getBlockData() instanceof MultipleFacing m)) return;
        for (BlockFace f : SIDES) {
            if (!m.getAllowedFaces().contains(f)) continue;
            Block n = cell.getRelative(f);
            m.setFace(f, n.getType() == cell.getType() || n.getBlockData().isFaceSturdy(f.getOppositeFace(), BlockSupport.FULL));
        }
        cell.setBlockData(m, false);
    }

    /** Takes the whole gate down (the mod's removeGate). @return how many blocks it had. */
    public static int remove(Block b) {
        List<Block> gate = gate(b);
        for (Block cell : gate) {
            unregister(cell);
            cell.setType(Material.AIR);
        }
        return gate.size();
    }

    // ---- players ----------------------------------------------------------------------------------------------------

    /** A right-click on a gate block opens or closes it (ACCESS_TOGGLEABLES in a colony, as doors). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || b == null || e.getHand() != EquipmentSlot.HAND || kind(b) == null) return;
        if (e.getPlayer().isSneaking() && e.getItem() != null && !e.getItem().isEmpty()) return; // placing against it
        e.setCancelled(true);
        if (!plugin.protection.allowed(e.getPlayer(), b.getLocation(), Action.ACCESS_TOGGLEABLES)) return;
        toggle(b);
    }

    /** A redstone signal reaching a gate block opens it, losing it closes it (the mod's neighborChanged). */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        Block b = e.getBlock();
        if (kind(b) == null) return;
        boolean open = gate(b).getFirst().getType() == Material.STRUCTURE_VOID; // as its lower left corner is
        if (b.isBlockIndirectlyPowered() != open) toggle(b);
    }

    /** Breaking one block takes the whole gate down, a gate item dropping for each (the mod's playerWillDestroy). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Kind k = kind(b);
        if (k == null) return;
        e.setDropItems(false);
        int n = remove(b);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE && n > 0)
            b.getWorld().dropItemNaturally(b.getLocation().toCenterLocation(), k.item().asQuantity(n));
    }

    /**
     * Placing a gate item (the mod's canSurvive and setPlacedBy): not with another gate block within 1 block, and on
     * solid ground. It then fills up, then out to the left and the right (as the player faces), as far as the air goes
     * and the items in hand last, within the gate's size.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Kind k = Kind.ofItem(e.getItemInHand());
        if (k == null) return;
        Block b = e.getBlockPlaced();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if ((x != 0 || y != 0 || z != 0) && kind(b.getRelative(x, y, z)) != null) { e.setCancelled(true); return; }
        Block below = b.getRelative(BlockFace.DOWN);
        if (!below.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL)) { e.setCancelled(true); return; }
        register(b, k);
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand()); // the stack itself, not the event's copy
        int[] spare = {e.getPlayer().getGameMode() == GameMode.CREATIVE ? Integer.MAX_VALUE : hand.getAmount() - 1}; // less the one placed
        java.util.function.Predicate<Block> may = at -> plugin.protection.allowed(e.getPlayer(), at.getLocation(), Action.PLACE_BLOCKS); // ours: not into a colony it may not build in
        BlockFace right = SIDES[(java.util.Arrays.asList(SIDES).indexOf(e.getPlayer().getFacing()) + 1) % 4], left = right.getOppositeFace();
        fillUp(b, k, spare, may);
        int room = k.width - 1;
        for (BlockFace side : List.of(left, right))
            for (int h = 1; h < k.width && room > 0; h++) {
                Block at = b.getRelative(side, h);
                if (!at.getType().isAir() || kind(at.getRelative(side)) == k || !may.test(at)) break;
                if (spare[0] > 0) { place(at, k); spare[0]--; }
                fillUp(at, k, spare, may);
                room--;
            }
        // Paper takes the placed item only from a stack left as it was: changed, the count is set to what is really left
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE && spare[0] != hand.getAmount() - 1) hand.setAmount(spare[0]);
        for (Block cell : gate(b)) connect(cell);
    }

    private static void fillUp(Block base, Kind k, int[] spare, java.util.function.Predicate<Block> may) {
        for (int v = 1; v < k.height; v++) {
            Block at = base.getRelative(0, v, 0);
            if (!at.getType().isAir() || kind(at.getRelative(BlockFace.UP)) == k || !may.test(at)) break;
            if (spare[0] > 0) { place(at, k); spare[0]--; }
        }
    }

    private static void place(Block at, Kind k) {
        at.setType(k.block, false);
        register(at, k);
    }
}
