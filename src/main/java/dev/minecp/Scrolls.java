package dev.minecp;

import dev.minecp.citizen.Colonist;
import dev.minecp.citizen.Food;
import dev.minecp.colony.Action;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import dev.minecp.job.Job;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mod's scrolls (AbstractItemScroll and its four kinds), the mod's numbers unless noted: tagged paper stacking to
 * 16, made by the enchanter. Sneak + right-click a colony's hut binds a teleport or guard help scroll to it; then holding
 * right-click (vanilla's consumable, with the bow's pose) uses it. Each has a 1 in 10 chance to go wrong (the buff
 * scroll 1 in 8). The buff scroll is found in vanilla structures' chests, as the mod's supply loot adds it.
 */
public final class Scrolls implements Listener {
    /** The four scrolls: their tag, name, hint, use time and whether they need binding. */
    public enum Kind {
        TELEPORT("scroll_tp", "Colony Teleport Scroll", "Takes you to the town hall of the colony it is bound to", 1.6f, true),
        AREA("scroll_area_tp", "Area Teleport Scroll", "Takes you and the players near you to the town hall", 3.2f, true),
        GUARD_HELP("scroll_guard_help", "Guard Help Scroll", "Calls the guards of the guard hut it is bound to, to follow you", 1.6f, true),
        HIGHLIGHT("scroll_highlight", "Highlight Scroll", "Sneak + right-click a hut: its workers glow and run faster", 0, false),
        BUFF("scroll_buff", "Buff Scroll", "Gives you and the citizens near you a minute of regeneration", 1.6f, false);

        final String id, title, hint;
        final float seconds; // the mod's use time: 32 ticks, 64 for the area scroll
        final boolean bound;

        Kind(String id, String title, String hint, float seconds, boolean bound) {
            this.id = id;
            this.title = title;
            this.hint = hint;
            this.seconds = seconds;
            this.bound = bound;
        }
    }

    private static final NamespacedKey KIND = new NamespacedKey(MineCP.NAMESPACE, "scroll"),
            BOUND = new NamespacedKey(MineCP.NAMESPACE, "scroll_bound"); // "owner uuid;world;x;y;z" of the hut it is bound to
    private static final int FAIL_ODDS = 10;           // nextInt(10) == 0
    private static final int SPOT_RANGE = 5;           // findAround's 5 across and 5 up and down
    private static final int MISFIRE_DISTANCE = 10;    // a failed teleport lands 10 blocks off
    private static final int NAUSEA_TICKS = 140, AREA = 10, AREA_HEIGHT = 2;
    private static final int GUARD_TICKS = 18_000;     // the guards follow for 15 minutes
    private static final int HIGHLIGHT_TICKS = 2400, SELF_GLOW_TICKS = 6000;
    private static final int BUFF_ODDS = 8, BUFF_TICKS = 1200, BUFF_RANGE = 15, BLIND_TICKS = 200;
    private static final double BUFF_HEALTH = 24; // ours: what a minute of Regeneration I heals, given to citizens at once (they keep their own health)
    /** The mod's supply loot (DefaultSupplyLootProvider): which vanilla chests get it, and whether a camp or a ship. */
    private static final java.util.Set<String> CAMP_CHESTS = java.util.Set.of("spawn_bonus_chest", "simple_dungeon", "village/village_cartographer",
            "village/village_mason", "village/village_desert_house", "abandoned_mineshaft", "stronghold_library", "stronghold_crossing",
            "stronghold_corridor", "desert_pyramid", "jungle_temple", "igloo_chest", "woodland_mansion", "pillager_outpost"),
            SHIP_CHESTS = java.util.Set.of("underwater_ruin_small", "underwater_ruin_big", "buried_treasure", "shipwreck_map", "shipwreck_supply",
            "shipwreck_treasure", "village/village_fisher", "village/village_armorer", "village/village_temple");

    private final MineCP plugin;

    Scrolls(MineCP plugin) {
        this.plugin = plugin;
    }

    // ---- the items ------------------------------------------------------------------------------------------------

    public static ItemStack item(Kind k, int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        if (k.seconds > 0) item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().consumeSeconds(k.seconds)
                .animation(ItemUseAnimation.BOW).hasConsumeParticles(false).sound(Key.key("minecraft:intentionally_empty")).build());
        item.editMeta(m -> {
            m.itemName(Component.text(k.title, NamedTextColor.GOLD));
            Component hint = Component.text(k.hint, NamedTextColor.GRAY);
            m.lore(k.bound ? List.of(hint, Component.text("Not bound: sneak + right-click a hut", NamedTextColor.DARK_GRAY)) : List.of(hint));
            m.getPersistentDataContainer().set(KIND, PersistentDataType.STRING, k.name());
        });
        Items.model(item, k.id);
        return item;
    }

    /** Which scroll this is, or null. */
    public static Kind kind(ItemStack item) {
        String k = item == null ? null : item.getPersistentDataContainer().get(KIND, PersistentDataType.STRING);
        try { return k == null ? null : Kind.valueOf(k); } catch (IllegalArgumentException e) { return null; }
    }

    public static boolean is(ItemStack item, Kind k) {
        return kind(item) == k;
    }

    // ---- binding and the highlight scroll -------------------------------------------------------------------------

    /** Sneak + right-click a hut: binds a scroll to it (no permission needed, as the mod), or uses a highlight scroll. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Kind k = kind(e.getItem());
        if (k == null || !e.getAction().isRightClick() || !k.bound && k != Kind.HIGHLIGHT) return; // a buff scroll works anywhere
        boolean onHut = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.getPlayer().isSneaking();
        Colony c = onHut ? plugin.colonyAt(e.getClickedBlock()) : null;
        Hut h = c == null ? null : c.hutAt(e.getClickedBlock());
        if (h == null) { // not on a hut: a bound scroll is used by holding right-click (vanilla), nothing else happens
            if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            return;
        }
        e.setCancelled(true); // no hut menu, no use started
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = e.getItem();
        if (k == Kind.HIGHLIGHT) { highlight(p, held, c, h); return; }
        String where = h.block.getWorld().getName() + ";" + h.block.getX() + ";" + h.block.getY() + ";" + h.block.getZ();
        held.editMeta(m -> {
            m.getPersistentDataContainer().set(BOUND, PersistentDataType.STRING, c.owner + ";" + where);
            m.lore(List.of(Component.text(k.hint, NamedTextColor.GRAY), Component.text("Bound to " + h.type.title + " of " + c.name, NamedTextColor.DARK_GRAY)));
        });
        tell(p, "Scroll bound to the " + h.type.title + " of " + c.name + ".", true);
        if (k == Kind.GUARD_HELP && h.post == null) tell(p, "That isn't a guard hut: the scroll will need one.", false);
    }

    /** The mod's highlight scroll: used up at once; the hut's workers glow and run faster for 2 minutes, or (1 in 10) you glow for 5. */
    private void highlight(Player p, ItemStack held, Colony c, Hut h) {
        held.subtract();
        if (failed()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, SELF_GLOW_TICKS, 0));
            p.playSound(p, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.PLAYERS, 0.3f, 1);
            fail(p);
            return;
        }
        for (Job j : c.jobsAt(h)) {
            Colonist w = c.worker(j);
            if (w != null) w.highlight(HIGHLIGHT_TICKS);
        }
        p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.3f, 1);
    }

    // ---- using a bound scroll -------------------------------------------------------------------------------------

    /** Holding right-click finished: the scroll works (we take it, not vanilla). */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        Kind k = kind(e.getItem());
        if (k == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItem(e.getHand());
        if (kind(held) != k) return;
        if (k == Kind.BUFF) { buff(p, held); return; }
        String[] b = held.getPersistentDataContainer().getOrDefault(BOUND, PersistentDataType.STRING, "").split(";");
        Colony c = b.length == 5 ? plugin.colonyOwnedBy(UUID.fromString(b[0])) : null;
        if (c == null) { p.sendActionBar(Component.text("Bind the scroll to a colony first: sneak + right-click one of its huts.", NamedTextColor.RED)); return; }
        if (!c.permissions.has(p, Action.RIGHTCLICK_BLOCK)) { p.sendActionBar(Component.text("You aren't allowed to use that in " + c.name + ".", NamedTextColor.RED)); return; }
        switch (k) {
            case TELEPORT -> {
                held.subtract();
                if (failed()) misfire(p);
                else teleport(p, c, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f);
            }
            case AREA -> {
                List<Player> near = p.getWorld().getNearbyPlayers(p.getLocation(), AREA, AREA_HEIGHT).stream().toList();
                if (failed()) { // the rest of the stack falls out of your hand
                    held.subtract();
                    if (!held.isEmpty()) { p.getWorld().dropItemNaturally(p.getLocation(), held.clone()); held.setAmount(0); }
                    for (Player o : near) o.playSound(o, Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 0.3f, 1);
                    fail(p);
                    return;
                }
                held.subtract();
                for (Player o : near) teleport(o, c, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.1f);
            }
            case GUARD_HELP -> {
                World w = Bukkit.getWorld(b[1]);
                Hut h = w == null ? null : c.hutAt(w.getBlockAt(Integer.parseInt(b[2]), Integer.parseInt(b[3]), Integer.parseInt(b[4])));
                if (h == null || h.post == null) { p.sendActionBar(Component.text("The scroll's guard hut is gone: bind it to another.", NamedTextColor.RED)); return; }
                held.subtract();
                if (failed() || !p.getWorld().equals(h.block.getWorld())) {
                    p.getWorld().spawn(p.getLocation(), Llama.class);
                    p.playSound(p, Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.5f, 1);
                    fail(p);
                    return;
                }
                callGuards(p, c, h);
            }
            default -> {}
        }
    }

    /** The mod's buff scroll: 7 times in 8, a minute of regeneration for you and the citizens within 15 (2 up or down); else 10 s of blindness. */
    private void buff(Player p, ItemStack held) {
        held.subtract();
        var rnd = ThreadLocalRandom.current();
        if (rnd.nextInt(BUFF_ODDS) == 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, BLIND_TICKS, 0));
            p.playSound(p, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.04f, 1);
            fail(p);
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, BUFF_TICKS, 0));
        Location at = p.getLocation();
        for (Colony c : plugin.allColonies()) for (Colonist w : c.colonists) {
            Location l = w.location();
            if (!l.getWorld().equals(at.getWorld()) || Math.abs(l.getX() - at.getX()) > BUFF_RANGE || Math.abs(l.getZ() - at.getZ()) > BUFF_RANGE
                    || Math.abs(l.getY() - at.getY()) > AREA_HEIGHT) continue;
            w.health = Math.min(c.health.max(w), w.health + BUFF_HEALTH);
            l.getWorld().spawnParticle(org.bukkit.Particle.HEART, l.clone().add(0, 2, 0), 3, 0.3, 0.3, 0.3);
        }
        p.getWorld().spawnParticle(org.bukkit.Particle.HEART, at.clone().add(0, 2, 0), 3, 0.3, 0.3, 0.3);
        p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.2f, 1);
    }

    /** The mod's supply loot in vanilla structures' chests: 1 in 100 a supply camp (or ship), 1 in 10 four buff scrolls. Ours: the camp isn't the mod's instant one. */
    @EventHandler
    public void onLoot(org.bukkit.event.world.LootGenerateEvent e) {
        var key = e.getLootTable().getKey();
        if (!key.getNamespace().equals(NamespacedKey.MINECRAFT) || !key.getKey().startsWith("chests/")) return;
        String id = key.getKey().substring("chests/".length());
        boolean camp = CAMP_CHESTS.contains(id);
        if (!camp && !SHIP_CHESTS.contains(id)) return;
        var rnd = ThreadLocalRandom.current();
        if (rnd.nextFloat() < 0.01f) e.getLoot().add(plugin.items.supply(!camp));
        if (rnd.nextFloat() < 0.1f) e.getLoot().add(item(Kind.BUFF, 4));
    }

    private static boolean failed() {
        return ThreadLocalRandom.current().nextInt(FAIL_ODDS) == 0;
    }

    private static void fail(Player p) {
        p.sendActionBar(Component.text("The scroll's magic went wrong!", NamedTextColor.GOLD)); // ours: one line for the mod's ten
    }

    /** To a free spot by the colony's town hall (the mod's colonyTeleport), in its world. Ours: a spot with ground under it. */
    private static void teleport(Player p, Colony c, Sound sound, float volume) {
        Block spot = spotAround(c.townHall().block);
        if (spot == null) return;
        p.leaveVehicle();
        p.teleport(spot.getLocation().add(0.5, 0, 0.5));
        p.playSound(p, sound, SoundCategory.PLAYERS, volume, 1);
        p.sendMessage(Component.text("Teleported to " + c.name + ".", NamedTextColor.GREEN));
    }

    /** A failed teleport: 10 blocks off in the first direction with room, and 7 seconds of nausea. */
    private static void misfire(Player p) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block spot = spotAround(p.getLocation().getBlock().getRelative(f, MISFIRE_DISTANCE));
            if (spot == null) continue;
            p.teleport(spot.getLocation().add(0.5, 0, 0.5));
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, NAUSEA_TICKS, 0));
            p.playSound(p, Sound.ENTITY_BAT_TAKEOFF, SoundCategory.PLAYERS, 0.4f, 1);
            break;
        }
        fail(p);
    }

    /** The nearest spot within 5 of {@code at} with room for a player and solid ground under it, or null. */
    static Block spotAround(Block at) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SPOT_RANGE; dx <= SPOT_RANGE; dx++) for (int dy = -SPOT_RANGE; dy <= SPOT_RANGE; dy++) for (int dz = -SPOT_RANGE; dz <= SPOT_RANGE; dz++) {
            Block b = at.getRelative(dx, dy, dz);
            if (!b.isPassable() || b.isLiquid() || !b.getRelative(BlockFace.UP).isPassable() || !b.getRelative(BlockFace.DOWN).getType().isSolid()) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }

    /**
     * The mod's guard help: every guard of the hut (not the sick) comes to you, fed, and follows you for 15 minutes; then
     * the hut goes back to patrolling. Ours: they walk back rather than vanish and reappear at the hut.
     */
    private void callGuards(Player p, Colony c, Hut h) {
        h.set(Settings.GUARD_TASK, "FOLLOW");
        h.post.followFor(p, GUARD_TICKS); // a later scroll or "Follow me" replaces the end time
        for (Job j : c.jobsAt(h)) {
            Colonist g = c.worker(j);
            if (g == null || g.disease != null) continue;
            g.teleportTo(p.getLocation().getBlock());
            g.saturation = Food.MAX_SATURATION;
        }
        p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.3f, 1);
        tell(p, "The guards of the " + h.type.title + " follow you.", true);
        plugin.save(c);
    }

    private static void tell(Player p, String msg, boolean ok) {
        p.sendMessage(Component.text(msg, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
