package dev.minecp;

import dev.minecp.build.BuildOrder;
import dev.minecp.build.Placement;
import dev.minecp.citizen.Colonist;
import dev.minecp.build.Border;
import dev.minecp.colony.Action;
import dev.minecp.colony.ClaimMap;
import dev.minecp.colony.Claims;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Grave;
import dev.minecp.colony.Hut;
import dev.minecp.job.Job;
import dev.minecp.logistics.Rack;
import dev.minecp.menu.Menus;
import dev.minecp.menu.Teaching;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The plugin: colonies by owner, their ticks and saves, and the world events on their huts, graves and citizens. */
public final class MineCP extends JavaPlugin implements Listener {
    // Every colony is saved on this timer: citizens move and get hungry every tick, so a "changed" flag would always be set.
    private static final long AUTOSAVE_TICKS = 20 * 30;
    private final Map<UUID, Colony> colonies = new HashMap<>(); // by owner
    public final ClaimMap claims = new ClaimMap();
    private final Set<UUID> tickFailed = new HashSet<>();
    public final Items items = new Items(this);
    private Saves saves;
    private final Menus menus = new Menus(this);
    public final Teaching teaching = new Teaching(this);
    private final Placement placement = new Placement(this);
    private final Border border = new Border(this);
    final Protection protection = new Protection(this);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cfg = getConfig();
        Claims.initialSize = cfg.getInt("claims.initial-size", Claims.initialSize);
        Claims.minDistance = cfg.getInt("claims.min-distance", Claims.minDistance);
        Claims.maxSize = cfg.getInt("claims.max-size", Claims.maxSize);
        protection.enabled = cfg.getBoolean("protection.enabled", true);
        dev.minecp.colony.Raids.enabled = cfg.getBoolean("raids.enabled", true);
        dev.minecp.colony.Raids.breakDoors = cfg.getBoolean("raids.break-doors", true);
        dev.minecp.colony.Raids.difficulty = Math.clamp(cfg.getInt("raids.difficulty", 5), 0, 10);
        dev.minecp.colony.Raids.maxRaiders = Math.clamp(cfg.getInt("raids.max-raiders", 80), 6, 400);
        dev.minecp.colony.Raids.averageNights = Math.clamp(cfg.getInt("raids.average-nights", 14), 1, 50);
        dev.minecp.colony.Raids.minimumNights = Math.clamp(cfg.getInt("raids.minimum-nights", 10), 1, 30);
        try {
            protection.explosions = Protection.Explosions.valueOf(cfg.getString("protection.explosions", "everything").toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("protection.explosions must be everything, entities, players or nothing; using everything.");
        }
        items.addRecipes();
        saves = new Saves(new File(getDataFolder(), "colonies"), getLogger());
        saves.loadAll(colonies);
        colonies.values().forEach(this::hook);
        Advancements.load(getLogger());
        reindex();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(protection, this);
        getServer().getPluginManager().registerEvents(items, this);
        getServer().getPluginManager().registerEvents(new ChestNames(this), this);
        getServer().getPluginManager().registerEvents(teaching, this);
        getServer().getPluginManager().registerEvents(placement, this);
        getServer().getPluginManager().registerEvents(border, this);
        getServer().getScheduler().runTaskTimer(this, border::tick, 10L, 10L);
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
        border.endAll();
        saveAll();
        saves.finish();
        colonies.values().forEach(Colony::despawn);
    }

    void saveAll() {
        colonies.values().forEach(this::save);
    }

    public void save(Colony c) {
        if (colonies.get(c.owner) != c) return; // disbanded while one of its menus was still open: don't bring its file back
        saves.save(c);
    }

    /** The colony {@code p} owns, or null. */
    public Colony colonyOf(Player p) {
        return colonies.get(p.getUniqueId());
    }

    /** Any colony's claims changed: work out again which colony owns each chunk. */
    private void reindex() {
        claims.update(colonies.values());
    }

    /** The colony one of whose huts (town hall included) is this block. */
    public Colony colonyAt(Block block) {
        for (Colony c : colonies.values()) if (c.hutAt(block) != null) return c;
        return null;
    }

    /** What the plugin hears from a colony: claims changes and milestones. */
    private void hook(Colony c) {
        c.onClaimsChanged = this::reindex;
        c.claimMap = claims;
        c.milestones = Advancements.forColony(c);
        // A removal order finished, maybe with nobody near: its chest's contents and the hut item go to the warehouse,
        // then the Builder's Hut chest; only what fits in neither drops, at the Builder's Hut, where someone will look.
        c.onTakenDown = hut -> {
            List<ItemStack> left = new ArrayList<>(Job.takeContents(hut.block));
            left.add(items.hutItem(hut.type));
            hut.block.setType(Material.AIR);
            c.removeHut(hut);
            var wh = c.warehouse(); // the hut just removed is no longer the colony's warehouse
            if (wh != null) left = wh.store(left);
            Block builderHut = c.builder != null ? c.builder.hut.block : hut.block;
            if (builderHut.getState(false) instanceof Container ct)
                left = new ArrayList<>(ct.getInventory().addItem(left.toArray(ItemStack[]::new)).values());
            var at = builderHut.getLocation().toCenterLocation();
            left.forEach(i -> at.getWorld().dropItemNaturally(at, i));
            save(c);
        };
    }

    public Colony graveColony(Block b) {
        for (Colony c : colonies.values()) if (c.graveAt(b) != null) return c;
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (Rack.is(e.getItemInHand())) { Rack.tag(e.getBlockPlaced()); return; } // item data doesn't carry over to the block
        Hut.Type type = items.hutType(e.getItemInHand());
        if (type == null) return;
        Player p = e.getPlayer();
        Block at = e.getBlockPlaced();
        Colony c = type == Hut.Type.TOWN_HALL ? null : claims.colonyAt(at.getLocation());
        String style = c == null ? Colony.DEFAULT_STYLE : c.style;
        int[] box = c != null && type.isBuilding() ? BuildOrder.footprint(type.structure(style, 1), at, null, false) : null; // a town hall's isn't checked
        String problem = placeProblem(p, type, at, box, false);
        if (problem != null) {
            e.setCancelled(true);
            p.sendMessage(Component.text(problem, NamedTextColor.RED));
            return;
        }
        placed(p, type, e.getBlockPlaced(), null, false);
    }

    /**
     * Why {@code p} can't put a {@code type} hut at {@code at}, or null if it can (the mod's rules). A town hall founds a
     * colony away from others; any other hut goes inside a colony's claimed chunks, its whole building too ({@code box}:
     * minX, maxX+1, minZ, maxZ+1, or null), and joins that colony. {@code tool}: placed with the build tool (MANAGE_HUTS),
     * else by hand (PLACE_HUTS).
     */
    public String placeProblem(Player p, Hut.Type type, Block at, int[] box, boolean tool) {
        if (type == Hut.Type.TOWN_HALL) {
            if (colonies.containsKey(p.getUniqueId())) return "You already have a colony.";
            if (saves.isUnloaded(p.getUniqueId())) return "Your colony's save couldn't be loaded; ask an admin to check the server log.";
            return claims.foundingProblem(at, colonies.values());
        }
        Colony c = claims.colonyAt(at.getLocation());
        if (c == null) return "Place it inside a colony's border: its claimed chunks (wear Build Goggles to see it).";
        if (!c.permissions.has(p, tool ? Action.MANAGE_HUTS : Action.PLACE_HUTS))
            return "You can't place huts in " + c.name + ".";
        if (type.unique && c.hut(type) != null) return c.name + " already has a " + type.title + ".";
        if (box != null && !claims.ownsAll(c, at.getWorld(), box)) return "The " + type.title + "'s building would reach outside " + c.name + "'s border.";
        return null;
    }

    /**
     * Registers a hut block just put at {@code at} (by hand, or by the build tool with the building's turn and mirror;
     * rotation null: from the block's facing). Check {@link #placeProblem} first.
     */
    public void placed(Player p, Hut.Type type, Block at, StructureRotation rotation, boolean mirror) {
        Colony c;
        Hut hut;
        if (type == Hut.Type.TOWN_HALL) {
            c = Colony.found(p.getUniqueId(), p.getName() + "'s Colony", at);
            colonies.put(c.owner, c);
            hook(c);
            Advancements.award(p, "minecolonies/root|minecolonies/place_townhall");
            reindex();
            hut = c.townHall();
            p.sendMessage(Component.text(c.name + " founded! The first citizens will move in at the town hall over the next few minutes.", NamedTextColor.GREEN));
        } else {
            c = claims.colonyAt(at.getLocation()); // the colony whose land it is on, which may not be the placer's own
            hut = c.addHut(type, at);
            p.sendMessage(Component.text(type == Hut.Type.FIELD
                    ? "Field placed. Right-click it to choose the crop and the size of the field."
                    : type.title + " placed. Right-click it to build it.", NamedTextColor.GREEN));
        }
        ChestNames.name(at, type.title); // only chests take it: not the town hall's bell or a field's hay bale
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
        Hut hut = c.hutAt(e.getBlock());
        // Breaking the town hall disbands the colony: only its owner may (ours; huts follow BREAK_HUTS, as in the mod).
        boolean may = hut.type == Hut.Type.TOWN_HALL ? p.getUniqueId().equals(c.owner) : c.permissions.has(p, Action.BREAK_HUTS);
        if (!may) {
            e.setCancelled(true);
            protection.deny(p, c, Action.BREAK_HUTS);
            return;
        }
        e.setDropItems(false);
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
        reindex(); // its land is free
        at.getWorld().dropItemNaturally(at, items.hutItem(Hut.Type.TOWN_HALL));
        p.sendMessage(Component.text(c.name + " was disbanded.", NamedTextColor.YELLOW));
    }

    /** Right-click opens the colony menus; sneak + right-click keeps the normal action (open chest, ring bell). */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (items.isBuildTool(e.getItem())) return; // the build tool (Placement)
        Colony c = colonyAt(e.getClickedBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        ItemStack held = e.getItem();
        if (items.isClipboard(held) || items.isResourceScroll(held)) { // binds it to this colony (the mod: any hut / the Builder's Hut)
            e.setCancelled(true);
            if (!mayAccess(p, c)) return;
            Hut hut = c.hutAt(e.getClickedBlock());
            if (items.isResourceScroll(held) && hut.type != Hut.Type.BUILDER) {
                p.sendMessage(Component.text("A resource scroll links only to a Builder's Hut.", NamedTextColor.RED));
                return;
            }
            items.bind(held, c);
            p.sendMessage(Component.text(items.isClipboard(held) ? "Noting the requests of " + c.name + " on the clipboard."
                    : "Linked to the Builder's Hut of " + c.name + ".", NamedTextColor.GREEN));
            return;
        }
        if (p.isSneaking()) return; // the block's own use (a chest): Protection checks it
        e.setCancelled(true);
        if (!mayAccess(p, c)) return;
        menus.hut(p, c, c.hutAt(e.getClickedBlock()));
    }

    /** Hut menus, citizens and the clipboard and scroll need ACCESS_HUTS; says so if not. */
    private boolean mayAccess(Player p, Colony c) {
        if (c.permissions.has(p, Action.ACCESS_HUTS)) return true;
        protection.deny(p, c, Action.ACCESS_HUTS);
        return false;
    }

    /** A clipboard or resource scroll used anywhere but on a hut shows its list (on a hut it binds: onInteract). */
    @EventHandler
    public void onUseNote(PlayerInteractEvent e) {
        var a = e.getAction();
        if (e.getHand() != EquipmentSlot.HAND || !(a == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || a == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && colonyAt(e.getClickedBlock()) == null)) return;
        ItemStack held = e.getItem();
        boolean clipboard = items.isClipboard(held);
        if (!clipboard && !items.isResourceScroll(held)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        UUID owner = items.boundTo(held);
        Colony c = owner == null ? null : colonies.get(owner);
        if (c == null) {
            p.sendMessage(Component.text(clipboard ? "Right-click a hut of a colony to note its requests first."
                    : "Right-click a Builder's Hut to link the scroll first.", NamedTextColor.RED));
            return;
        }
        if (!mayAccess(p, c)) return;
        if (clipboard) menus.clipboard(p, c);
        else menus.resourceScroll(p, c);
    }

    /** Right-click a citizen for its menu (the mod's citizen window), or a visitor to recruit it. */
    @EventHandler
    public void onClickCitizen(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        for (Colony c : colonies.values()) {
            Colonist w = c.colonistOf(e.getRightClicked());
            var v = w == null ? c.visitors.of(e.getRightClicked()) : null;
            if (w == null && v == null) continue;
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND) return;
            Player p = e.getPlayer();
            if (!mayAccess(p, c)) return;
            if (w != null) menus.citizen(p, c, w);
            else menus.visitor(p, c, v);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> colonyAt(b) != null || graveColony(b) != null);
    }

    /** Raiders: the mod's drops; a hurt one ends its horde's camp; they never hurt each other, and zombies among them don't turn into drowned. */
    @EventHandler(ignoreCancelled = true)
    public void onRaiderDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(dev.minecp.colony.Raids.TAG)) colonies.values().forEach(c -> c.raids.died(e));
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.LOW)
    public void onRaiderHurt(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(dev.minecp.colony.Raids.TAG)) return;
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by) {
            var from = by.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof org.bukkit.entity.Entity s ? s : by.getDamager();
            if (from.getScoreboardTags().contains(dev.minecp.colony.Raids.TAG)) { e.setCancelled(true); return; }
        }
        colonies.values().forEach(c -> c.raids.hurt(e.getEntity()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRaiderTransform(org.bukkit.event.entity.EntityTransformEvent e) {
        if (e.getEntity().getScoreboardTags().contains(dev.minecp.colony.Raids.TAG)) e.setCancelled(true);
    }

    /** Hits on a citizen's body or legs hurt the citizen instead (Colony.hurt). */
    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.LOW)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Mannequin) && !e.getEntity().getScoreboardTags().contains(Colonist.LEGS_TAG)) return;
        for (Colony c : colonies.values()) {
            Colonist w = c.colonistOf(e.getEntity());
            if (w != null) { c.health.hurt(w, e); return; }
            if (c.visitors.of(e.getEntity()) != null) { e.setCancelled(true); return; } // ours: visitors can't be hurt
        }
    }

    /** Right-click a grave to take what is in it; breaking it drops everything. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    public void onGraveClick(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Colony c = graveColony(e.getClickedBlock());
        if (c == null) return;
        e.setCancelled(true);
        Grave g = c.graveAt(e.getClickedBlock());
        if (!c.permissions.has(e.getPlayer(), Action.OPEN_CONTAINER)) {
            protection.deny(e.getPlayer(), c, Action.OPEN_CONTAINER);
            return;
        }
        e.getPlayer().openInventory(g.items);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGraveBreak(BlockBreakEvent e) {
        Colony c = graveColony(e.getBlock());
        if (c == null) return;
        if (!c.permissions.has(e.getPlayer(), Action.BREAK_BLOCKS)) {
            e.setCancelled(true);
            protection.deny(e.getPlayer(), c, Action.BREAK_BLOCKS);
            return;
        }
        Grave g = c.graveAt(e.getBlock());
        e.setDropItems(false);
        g.dropAll();
        c.graves.remove(g);
    }
}
