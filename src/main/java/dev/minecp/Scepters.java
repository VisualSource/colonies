package dev.minecp;

import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.job.Beekeeper;
import dev.minecp.job.Lumberjack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * The mod's Forest Tool, Hive Tool and Permission Tool (ItemScepterLumberjack, ItemScepterBeekeeper,
 * ItemScepterPermission), the mod's rules unless noted: no recipes; a hut's menu hands one out (MANAGE_HUTS), the
 * town hall's permissions page the last (EDIT_PERMISSIONS). A breeze rod with a tag stands in for each model.
 */
public final class Scepters implements Listener {
    /** The three tools: their tag, name, hint, and the pack's model. */
    public enum Kind {
        FOREST("Forest Tool", "Right-click a block: one corner of the lumberjack's zone; left-click: the other", "scepterlumberjack"),
        HIVE("Hive Tool", "Right-click a beehive or nest to link it to the beekeeper, or unlink it; anything else puts it away", "scepterbeekeeper"),
        PERMISSION("Permission Tool", "Right-click a block: free for all but the hostile to use; sneaking: no longer. Right-click the air: by block or by spot", "scepterpermission");

        final String title, hint, model;

        Kind(String title, String hint, String model) {
            this.title = title;
            this.hint = hint;
            this.model = model;
        }
    }

    private static final NamespacedKey KIND = new NamespacedKey(MineCP.NAMESPACE, "scepter"),
            TARGET = new NamespacedKey(MineCP.NAMESPACE, "scepter_target"),   // a hut "world;x;y;z", or a colony owner's id
            CORNER_A = new NamespacedKey(MineCP.NAMESPACE, "scepter_a"), CORNER_B = new NamespacedKey(MineCP.NAMESPACE, "scepter_b"),
            BY_SPOT = new NamespacedKey(MineCP.NAMESPACE, "scepter_by_spot"); // the permission tool's mode

    private final MineCP plugin;

    Scepters(MineCP plugin) {
        this.plugin = plugin;
    }

    // ---- the items ------------------------------------------------------------------------------------------------

    private static ItemStack item(Kind k, String target) {
        ItemStack item = new ItemStack(Material.BREEZE_ROD);
        item.editMeta(m -> {
            m.itemName(Component.text(k.title, NamedTextColor.GOLD));
            m.lore(List.of(Component.text(k.hint, NamedTextColor.GRAY)));
            m.setMaxStackSize(1);
            m.getPersistentDataContainer().set(KIND, PersistentDataType.STRING, k.name());
            m.getPersistentDataContainer().set(TARGET, PersistentDataType.STRING, target);
        });
        Items.model(item, k.model);
        return item;
    }

    public static Kind kind(ItemStack item) {
        String k = item == null ? null : item.getPersistentDataContainer().get(KIND, PersistentDataType.STRING);
        try { return k == null ? null : Kind.valueOf(k); } catch (IllegalArgumentException e) { return null; }
    }

    /** Hands the player a Forest or Hive Tool for this hut, or a Permission Tool for its colony, in place of any it has (the mod's GiveToolMessage). */
    public void give(Player p, Kind k, Colony c, Hut h) {
        String target = k == Kind.PERMISSION ? c.owner.toString() : spot(h.block);
        for (ItemStack i : p.getInventory().getContents()) if (kind(i) == k) p.getInventory().removeItemAnySlot(i);
        ItemStack item = item(k, target);
        if (p.getInventory().getItemInMainHand().isEmpty()) p.getInventory().setItemInMainHand(item);
        else p.getInventory().addItem(item).values().forEach(o -> p.getWorld().dropItem(p.getLocation(), o));
        p.closeDialog();
        tell(p, k.title + ": " + k.hint + ".", true);
    }

    private static String spot(Block b) {
        return dev.minecp.colony.ColonyStore.spot(b);
    }

    private static Block spotAt(String s) {
        return dev.minecp.colony.ColonyStore.spotAt(s);
    }

    private static String get(ItemStack i, NamespacedKey key) {
        return i.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ---- using them ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Kind k = kind(e.getItem());
        if (k == null) return;
        e.setCancelled(true); // none of them is placed, used on what it touches, or breaks it
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = e.getItem();
        Block at = e.getClickedBlock();
        boolean right = e.getAction().isRightClick();
        switch (k) {
            case FOREST -> { if (at != null) forest(p, held, at, right); }
            case HIVE -> { if (at != null && right) hive(p, held, at); }
            case PERMISSION -> { if (right) permission(p, held, at); }
        }
    }

    /** A Forest Tool doesn't break blocks (the mod's: its left-click sets a corner). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        if (kind(e.getPlayer().getInventory().getItemInMainHand()) == Kind.FOREST) e.setCancelled(true);
    }

    /** The hut and job a hut's tool is for, or null after telling the player why. */
    private Hut hutOf(Player p, ItemStack held, Hut.Type type) {
        Block b = spotAt(get(held, TARGET));
        Colony c = b == null ? null : plugin.colonyAt(b);
        Hut h = c == null ? null : c.hutAt(b);
        if (h == null || h.type != type) { tell(p, "That tool's " + type.title + " is gone.", false); return null; }
        if (!c.permissions.has(p, Action.MANAGE_HUTS)) { plugin.protection.deny(p, c, Action.MANAGE_HUTS); return null; }
        return h;
    }

    /**
     * The mod's Forest Tool: a right-click sets one corner, a left-click the other; whenever both are known (the other
     * from the lumberjack's zone now), the zone is set if it is at most 250,000 blocks (its corners' plain differences
     * multiplied), and its "only in the zone" setting turned on.
     */
    private void forest(Player p, ItemStack held, Block at, boolean right) {
        Hut h = hutOf(p, held, Hut.Type.LUMBERJACK);
        if (h == null) return;
        Colony c = plugin.colonyAt(h.block);
        if (!(c.jobAt(h) instanceof Lumberjack lj)) return;
        if (!onOwnLand(p, c, at)) return;
        held.editMeta(m -> m.getPersistentDataContainer().set(right ? CORNER_B : CORNER_A, PersistentDataType.STRING, spot(at)));
        tell(p, "Position " + (right ? "B" : "A") + " set. " + (right ? "Select position A with a left-click" : "Select position B with a right-click")
                + ", or put the tool away when done.", true);
        Block a = spotAt(get(held, CORNER_A)), b = spotAt(get(held, CORNER_B));
        if (a == null) a = lj.zoneA();
        if (b == null) b = lj.zoneB();
        if (a == null || b == null || a.getWorld() != b.getWorld()) return;
        long volume = Lumberjack.volume(a, b);
        if (!Lumberjack.zoneFits(a, b)) {
            tell(p, "A zone of " + Math.max(volume, Lumberjack.columns(a, b)) + " blocks is too large. The most for the lumberjack is " + Lumberjack.MAX_ZONE + " blocks.", false);
            return;
        }
        lj.zone(a, b);
        plugin.save(c);
        outline(p, a, b);
        tell(p, "Lumberjack's zone set (x " + Math.min(a.getX(), b.getX()) + " to " + Math.max(a.getX(), b.getX()) + ", y " + Math.min(a.getY(), b.getY())
                + " to " + Math.max(a.getY(), b.getY()) + ", z " + Math.min(a.getZ(), b.getZ()) + " to " + Math.max(a.getZ(), b.getZ()) + ", "
                + volume + " blocks; at most " + Lumberjack.MAX_ZONE + ").", true);
    }

    /** Not on another colony's land (ours: the tools can't reach past the protection there). */
    private boolean onOwnLand(Player p, Colony c, Block at) {
        Colony owner = plugin.claims.colonyAt(at.getLocation());
        if (owner == null || owner == c) return true;
        tell(p, "That is " + owner.name + "'s land.", false);
        return false;
    }

    /** The zone's edges in particles, for the player (ours: the mod draws its box all the while the tool is held). */
    private static void outline(Player p, Block a, Block b) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX()) + 1, y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY()) + 1,
                z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ()) + 1;
        for (int x = x0; x <= x1; x++) for (int y : new int[]{y0, y1}) for (int z : new int[]{z0, z1}) p.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 1);
        for (int z = z0; z <= z1; z++) for (int y : new int[]{y0, y1}) for (int x : new int[]{x0, x1}) p.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 1);
        for (int y = y0; y <= y1; y++) for (int x : new int[]{x0, x1}) for (int z : new int[]{z0, z1}) p.spawnParticle(Particle.HAPPY_VILLAGER, x, y, z, 1);
    }

    /** The mod's Hive Tool: a hive is linked or unlinked (up to 2^(level - 1)); any other block puts the tool away. */
    private void hive(Player p, ItemStack held, Block at) {
        if (!Beekeeper.hive(at.getType())) {
            p.getInventory().setItemInMainHand(null);
            tell(p, "Hive Tool put away.", true);
            return;
        }
        Hut h = hutOf(p, held, Hut.Type.BEEKEEPER);
        if (h == null) return;
        Colony c = plugin.colonyAt(h.block);
        if (!(c.jobAt(h) instanceof Beekeeper bk) || !onOwnLand(p, c, at)) return;
        if (bk.linked.remove(at)) {
            tell(p, "Hive removed.", true);
            p.playSound(p, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.5f, 1);
        } else if (bk.linked.size() >= bk.maxHives()) {
            p.getInventory().setItemInMainHand(null);
            tell(p, "The beekeeper has as many hives as it may (" + bk.maxHives() + "): upgrade its hut for more.", false);
            return;
        } else {
            bk.linked.add(at);
            tell(p, "Hive added (" + bk.linked.size() + " of " + bk.maxHives() + ").", true);
            p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.5f, 1);
        }
        plugin.save(c);
    }

    /**
     * The mod's Permission Tool: the air switches between by block (every block of that kind in the colony) and by
     * spot (that one block); a block is made free to use for all but the hostile, or (sneaking: ours, for the mod's
     * Ctrl) not. Needs EDIT_PERMISSIONS.
     */
    private void permission(Player p, ItemStack held, Block at) {
        boolean bySpot = held.getPersistentDataContainer().getOrDefault(BY_SPOT, PersistentDataType.BOOLEAN, false);
        if (at == null) {
            held.editMeta(m -> m.getPersistentDataContainer().set(BY_SPOT, PersistentDataType.BOOLEAN, !bySpot));
            tell(p, "Set to mode " + (bySpot ? "block" : "location") + ".", true);
            return;
        }
        Colony c;
        try { c = plugin.colonyOwnedBy(UUID.fromString(get(held, TARGET))); } catch (IllegalArgumentException | NullPointerException e) { c = null; }
        if (c == null) { tell(p, "That tool's colony is gone.", false); return; }
        if (!c.permissions.has(p, Action.EDIT_PERMISSIONS)) { plugin.protection.deny(p, c, Action.EDIT_PERMISSIONS); return; }
        boolean remove = p.isSneaking();
        if (bySpot) {
            boolean changed = remove ? c.freePositions.remove(at) : c.freePositions.add(at);
            tell(p, (remove ? "Removed location " : "Added location ") + at.getX() + " " + at.getY() + " " + at.getZ() + (changed ? "." : " (it already was)."), true);
        } else {
            Material m = at.getType();
            boolean changed = remove ? c.freeBlocks.remove(m) : c.freeBlocks.add(m);
            tell(p, (remove ? "Removed block " : "Added block type ") + m.getKey().getKey().replace('_', ' ') + (changed ? "." : " (it already was)."), true);
        }
        plugin.save(c);
    }

    private static void tell(Player p, String msg, boolean ok) {
        p.sendMessage(Component.text(msg, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
