package dev.minecp;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inside a colony's claimed chunks, other players do what their rank there allows (the mod's
 * ColonyPermissionEventHandler). Hut blocks, the build tool, citizens and graves are checked where they are handled.
 */
final class Protection implements Listener {
    /** What explosions may damage inside a colony (the mod's turnoffexplosionsincolonies); players are always hurt. */
    enum Explosions { EVERYTHING, ENTITIES, PLAYERS, NOTHING }

    private final MineCP plugin;
    boolean enabled = true;              // the mod's enablecolonyprotection
    Explosions explosions = Explosions.EVERYTHING; // vanilla; the mod defaults to ENTITIES
    private final Map<UUID, Long> told = new HashMap<>(); // when each player was last told no, so it isn't every tick

    Protection(MineCP plugin) {
        this.plugin = plugin;
    }

    /** Whether {@code p} may do this at {@code at}; tells them why not. Outside every colony: yes. */
    boolean allowed(Player p, Location at, Action action) {
        Colony c = enabled ? plugin.claims.colonyAt(at) : null;
        if (c == null || c.permissions.has(p, action)) return true;
        deny(p, c, action);
        return false;
    }

    void deny(Player p, Colony c, Action action) {
        long now = System.currentTimeMillis();
        Long last = told.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        told.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text("Not allowed in " + c.name + " (" + c.permissions.rank(p.getUniqueId()).title()
                + "): " + action.label.toLowerCase(), NamedTextColor.RED));
    }

    private static boolean isCitizen(Entity e) {
        return e instanceof Mannequin || e.getScoreboardTags().contains(Colonist.LEGS_TAG);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.items.hutType(e.getItemInHand()) != null) return; // PLACE_HUTS: MineCP.placeProblem
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), Action.PLACE_BLOCKS)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (plugin.colonyAt(b) != null || plugin.graveColony(b) != null) return; // MineCP checks huts and graves
        if (!allowed(e.getPlayer(), b.getLocation(), Action.BREAK_BLOCKS)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null ? !allowed(e.getPlayer(), e.getBlock().getLocation(), Action.PLACE_BLOCKS)
                : e.getIgnitingBlock() != null && crosses(e.getIgnitingBlock(), e.getBlock())) e.setCancelled(true); // fire or lava next door
    }

    /** Fire just outside the border burning a block just inside. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBurn(BlockBurnEvent e) {
        if (e.getIgnitingBlock() != null && crosses(e.getIgnitingBlock(), e.getBlock())) e.setCancelled(true);
    }

    /**
     * Using a block: containers (a barrel is also openable, so they come first), doors and gates, then anything else
     * that does something when clicked. Both hands: when the main hand's use is refused, the off hand gets a turn.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || b == null) return;
        if (e.useInteractedBlock() == Event.Result.DENY || plugin.items.isBuildTool(e.getItem()) || plugin.graveColony(b) != null) return;
        if (plugin.colonyAt(b) != null && !e.getPlayer().isSneaking()) { // a hut's menu (main hand): ACCESS_HUTS in MineCP
            if (e.getHand() == EquipmentSlot.OFF_HAND) e.setUseInteractedBlock(Event.Result.DENY); // not the chest behind it
            return;
        }
        Action action = b.getState(false) instanceof BlockInventoryHolder ? Action.OPEN_CONTAINER
                : b.getBlockData() instanceof Openable ? Action.ACCESS_TOGGLEABLES
                : b.getType().isInteractable() ? Action.RIGHTCLICK_BLOCK : null;
        if (action != null && !allowed(e.getPlayer(), b.getLocation(), action)) e.setUseInteractedBlock(Event.Result.DENY); // items in hand still work
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEmpty(PlayerBucketEmptyEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), Action.FILL_BUCKET)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onFill(PlayerBucketFillEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation(), Action.FILL_BUCKET)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        if (!allowed(e.getPlayer(), e.getPlayer().getLocation(), Action.TOSS_ITEM)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !allowed(p, e.getItem().getLocation(), Action.PICKUP_ITEM)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onLaunch(ProjectileLaunchEvent e) {
        Projectile pr = e.getEntity();
        Action action = pr instanceof ThrownPotion ? Action.THROW_POTION : pr instanceof AbstractArrow ? Action.SHOOT_ARROW : null;
        if (action != null && pr.getShooter() instanceof Player p && !allowed(p, p.getLocation(), action)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        if (isCitizen(e.getRightClicked())) return;
        if (!allowed(e.getPlayer(), e.getRightClicked().getLocation(), Action.RIGHTCLICK_ENTITY)) e.setCancelled(true);
    }

    /** Taking or putting armor on an armor stand: its own event, not the entity click above. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!allowed(e.getPlayer(), e.getRightClicked().getLocation(), Action.RIGHTCLICK_ENTITY)) e.setCancelled(true);
    }

    /**
     * Attacking a citizen or anything but a hostile mob, judged where the player stands, as the mod does; a citizen
     * also where it stands, so nobody can hit one from just outside the border. Lowest priority: before MineCP hands a
     * citizen's hit to its health. (Hostile players may attack guards, once there are any.)
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : e.getDamager();
        if (!(damager instanceof Player p) || e.getEntity() instanceof Enemy) return;
        boolean citizen = isCitizen(e.getEntity());
        if (!allowed(p, p.getLocation(), citizen ? Action.ATTACK_CITIZEN : Action.ATTACK_ENTITY)
                || citizen && !allowed(p, e.getEntity().getLocation(), Action.ATTACK_CITIZEN)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (e.getRemover() instanceof Player p && !allowed(p, e.getEntity().getLocation(), Action.BREAK_BLOCKS)) e.setCancelled(true);
    }

    // ---- things that cross the border by themselves -----------------------------------------------------------

    /** Whether something starting at {@code from} would change a block at {@code to} that another colony claims. */
    private boolean crosses(Block from, Block to) {
        Colony c = enabled ? plugin.claims.colonyAt(to.getLocation()) : null;
        return c != null && c != plugin.claims.colonyAt(from.getLocation());
    }

    /** A piston outside a colony can't push blocks in, pull them out, or reach in with its head. */
    private boolean pistonCrosses(Block piston, List<Block> moved, org.bukkit.block.BlockFace dir) {
        if (crosses(piston, piston.getRelative(dir))) return true;
        for (Block b : moved) // both neighbours: what the retract event calls its direction differs between versions
            if (crosses(piston, b) || crosses(piston, b.getRelative(dir)) || crosses(piston, b.getRelative(dir.getOppositeFace()))) return true;
        return false;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonCrosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    /** Water and lava flowing in. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onFlow(BlockFromToEvent e) {
        if (crosses(e.getBlock(), e.getToBlock())) e.setCancelled(true);
    }

    /** Fire spreading in (and grass, vines and the like, which also spread). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onSpread(BlockSpreadEvent e) {
        if (crosses(e.getSource(), e.getBlock())) e.setCancelled(true);
    }

    // ---- explosions -------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        keepColonyBlocks(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        keepColonyBlocks(e.blockList());
    }

    private void keepColonyBlocks(List<Block> blocks) {
        if (explosions != Explosions.EVERYTHING) blocks.removeIf(b -> plugin.claims.colonyAt(b.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBlast(EntityDamageEvent e) {
        var cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        Entity hit = e.getEntity();
        boolean spare = switch (explosions) {
            case EVERYTHING, ENTITIES -> false;
            case PLAYERS -> !(hit instanceof Enemy) || hit instanceof Llama; // the mod's test, llamas included
            case NOTHING -> true;
        };
        if (spare && !(hit instanceof Player) && plugin.claims.colonyAt(hit.getLocation()) != null) e.setCancelled(true);
    }
}
