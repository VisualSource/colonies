package dev.minecp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The plugin: colonies by owner, their ticks and saves, and the world events on their huts, graves and citizens. */
public final class MineCP extends JavaPlugin implements Listener {
    // Every colony is saved on this timer: citizens move and get hungry every tick, so a "changed" flag would always be set.
    private static final long AUTOSAVE_TICKS = 20 * 30;
    private static final int HUT_RANGE = 64;
    private final Map<UUID, Colony> colonies = new HashMap<>(); // by owner
    private final Set<UUID> tickFailed = new HashSet<>();
    final Items items = new Items(this);
    private Saves saves;
    private final Menus menus = new Menus(this);
    private final Placement placement = new Placement(this);

    @Override
    public void onEnable() {
        items.addRecipes();
        saves = new Saves(new File(getDataFolder(), "colonies"), getLogger());
        saves.loadAll(colonies);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(items, this);
        getServer().getPluginManager().registerEvents(placement, this);
        Objects.requireNonNull(getCommand("colony")).setExecutor(new Commands(this));
        getServer().getScheduler().runTaskTimer(this, () -> colonies.values().forEach(c -> {
            try {
                c.tick();
            } catch (RuntimeException e) { // one colony's bug must not stop the others; logged once, not every tick
                if (tickFailed.add(c.owner)) getLogger().log(java.util.logging.Level.SEVERE, "Colony " + c.name + " failed to tick", e);
            }
        }), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, () -> colonies.values().forEach(Colony::tape), 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveAll, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
    }

    @Override
    public void onDisable() {
        placement.endAll();
        saveAll();
        saves.finish();
        colonies.values().forEach(Colony::despawn);
    }

    void saveAll() {
        colonies.values().forEach(this::save);
    }

    void save(Colony c) {
        if (colonies.get(c.owner) != c) return; // disbanded while one of its menus was still open: don't bring its file back
        saves.save(c);
    }

    /** The colony {@code p} owns, or null. */
    Colony colonyOf(Player p) {
        return colonies.get(p.getUniqueId());
    }

    /** The colony one of whose huts (town hall included) is this block. */
    Colony colonyAt(Block block) {
        for (Colony c : colonies.values()) if (c.hutAt(block) != null) return c;
        return null;
    }

    Colony graveColony(Block b) {
        for (Colony c : colonies.values()) if (c.graveAt(b) != null) return c;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (Rack.is(e.getItemInHand())) { Rack.tag(e.getBlockPlaced()); return; } // item data doesn't carry over to the block
        Hut.Type type = items.hutType(e.getItemInHand());
        if (type == null) return;
        Player p = e.getPlayer();
        String problem = placeProblem(p, type, e.getBlockPlaced());
        if (problem != null) {
            e.setCancelled(true);
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return;
        }
        placed(p, type, e.getBlockPlaced(), null, false);
    }

    /** Why {@code p} can't put a {@code type} hut at {@code at}, or null if it can. */
    String placeProblem(Player p, Hut.Type type, Block at) {
        Colony c = colonies.get(p.getUniqueId());
        if (type == Hut.Type.TOWN_HALL) return c != null ? "You already have a colony."
                : saves.isUnloaded(p.getUniqueId()) ? "Your colony's save couldn't be loaded; ask an admin to check the server log." : null;
        Block th = c == null ? null : c.townHall().block;
        return c == null ? "You need a colony first."
                : type.unique && c.hut(type) != null ? "Your colony already has a " + type.title + "."
                : !th.getWorld().equals(at.getWorld()) || th.getLocation().distance(at.getLocation()) > HUT_RANGE
                        ? "Place it within " + HUT_RANGE + " blocks of your town hall." : null;
    }

    /**
     * Registers a hut block just put at {@code at} (by hand, or by the build tool with the building's turn and mirror;
     * rotation null: from the block's facing). Check {@link #placeProblem} first.
     */
    void placed(Player p, Hut.Type type, Block at, StructureRotation rotation, boolean mirror) {
        Colony c = colonies.get(p.getUniqueId());
        Hut hut;
        if (type == Hut.Type.TOWN_HALL) {
            c = Colony.found(p.getUniqueId(), p.getName() + "'s Colony", at);
            colonies.put(c.owner, c);
            hut = c.townHall();
            p.sendMessage(Component.text(c.name + " founded with " + c.colonists.size() + " citizens!", NamedTextColor.GREEN));
        } else {
            hut = c.addHut(type, at);
            p.sendMessage(Component.text(type == Hut.Type.FIELD
                    ? "Field placed. Right-click it to choose the crop and the size of the field."
                    : type.title + " placed. Right-click it to build it.", NamedTextColor.GREEN));
        }
        hut.rotation = rotation;
        hut.mirror = mirror;
        save(c);
    }

    /** Colonists walk their fields; vanilla would let their jumps trample the farmland. */
    @EventHandler(ignoreCancelled = true)
    public void onTrample(org.bukkit.event.entity.EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && e.getEntity().getScoreboardTags().contains(Colonist.LEGS_TAG)) e.setCancelled(true);
    }

    /** A rack drops as a Storage Rack, not a plain chest (its contents spill as usual). */
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGH)
    public void onBreakRack(BlockBreakEvent e) {
        if (!Rack.is(e.getBlock()) || colonyAt(e.getBlock()) != null || !e.isDropItems()) return;
        e.setDropItems(false);
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE)
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().toCenterLocation(), Rack.item());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Colony c = colonyAt(e.getBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        e.setDropItems(false);
        Hut hut = c.hutAt(e.getBlock());
        var at = e.getBlock().getLocation();
        if (hut.type != Hut.Type.TOWN_HALL) {
            if (hut.block.getState(false) instanceof Container ct) { // drop contents ourselves, then empty it, so they can never drop twice
                for (ItemStack item : ct.getInventory().getContents()) if (item != null) at.getWorld().dropItemNaturally(at, item);
                ct.getInventory().clear();
            }
            c.removeHut(hut);
            save(c);
            at.getWorld().dropItemNaturally(at, items.hutItem(hut.type));
            p.sendMessage(Component.text(hut.type.title + " removed.", NamedTextColor.YELLOW));
            return;
        }
        c.despawn(); // first: serving food and meals go back into their chests
        c.jobs.forEach(Job::dropCarried); // every worker's load and tools, at its hut
        c.graves.forEach(Grave::dropAll); // grave contents only live in the colony
        colonies.remove(c.owner);
        saves.delete(c.owner);
        at.getWorld().dropItemNaturally(at, items.hutItem(Hut.Type.TOWN_HALL));
        p.sendMessage(Component.text(c.name + " was disbanded.", NamedTextColor.YELLOW));
    }

    /** Right-click opens the colony menus; sneak + right-click keeps the normal action (open chest, ring bell). */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (items.isToken(e.getItem())) return; // the build tool (Placement)
        Colony c = colonyAt(e.getClickedBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        if (p.isSneaking()) return;
        e.setCancelled(true);
        menus.hut(p, c, c.hutAt(e.getClickedBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    /** Hits on a citizen's body or legs hurt the citizen instead (Colony.hurt). */
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.LOW)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Mannequin) && !e.getEntity().getScoreboardTags().contains(Colonist.LEGS_TAG)) return;
        for (Colony c : colonies.values()) {
            Colonist w = c.colonistOf(e.getEntity());
            if (w != null) { c.health.hurt(w, e); return; }
        }
    }

    /** Right-click a grave to take what is in it; breaking it drops everything. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onGraveClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Colony c = graveColony(e.getClickedBlock());
        if (c == null) return;
        e.setCancelled(true);
        Grave g = c.graveAt(e.getClickedBlock());
        if (!e.getPlayer().getUniqueId().equals(c.owner)) {
            e.getPlayer().sendMessage(Component.text("This grave belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        e.getPlayer().openInventory(g.items);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGraveBreak(BlockBreakEvent e) {
        Colony c = graveColony(e.getBlock());
        if (c == null) return;
        if (!e.getPlayer().getUniqueId().equals(c.owner)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("This grave belongs to someone else's colony.", NamedTextColor.RED));
            return;
        }
        Grave g = c.graveAt(e.getBlock());
        e.setDropItems(false);
        g.dropAll();
        c.graves.remove(g);
    }
}
