package dev.minecp.job;

import dev.minecp.build.BuildOrder;
import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import dev.minecp.research.Effects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Composter's Hut (the mod's BuildingComposter, EntityAIWorkComposter and TileEntityBarrel), the mod's numbers
 * unless noted: its building's barrels (vanilla composters stand in for the mod's barrel blocks) take the items on its
 * compost list until they hold 64 worth, ferment for about 9 minutes, and give 6 compost (or, with its setting, a block
 * of dirt, now and then podzol). Its worker fills and empties them; players may too.
 */
public final class Composter extends Job {
    private static final int DECIDE_TICKS = 40;     // DECIDE_DELAY
    private static final int AFTER_TASK = 5;        // AFTER_TASK_DELAY
    private static final int FERMENT_STEPS = 24000 / 20; // TIMER_END / AVERAGE_TICKS: steps of a full barrel's timer
    private static final int STEP_ODDS = 20 * 2;    // each tick a step with chance 1 in (rand(40) + 1) of the game time
    private static final int PODZOL_PERCENT = 5;    // with "make dirt": podzol 5 times in 100 (x (1 + research))
    private static final double REACH = 2.5;       // ours: close enough to work a barrel
    private static final double XP = 1;            // BASE_XP_GAIN: each barrel filled or emptied

    /** A barrel's state (TileEntityBarrel): how full, its timer, and whether its compost is ready. */
    private static final class Barrel {
        int items, timer;
        boolean done;
    }

    public final Set<Material> list = EnumSet.noneOf(Material.class); // what it composts, chosen by players (the mod's item list); saved
    private final Map<Block, Barrel> barrels = new HashMap<>(); // saved
    private List<Block> spots = List.of(); // its building's barrels at spotsLevel
    private int spotsLevel = -1;
    private boolean emptying; // compost in its inventory goes to the hut chest first

    public Composter(Hut hut, Colony colony) {
        super(hut, colony);
    }

    /** Where its building's blueprint has a barrel, at its level now. */
    private List<Block> spots() {
        if (spotsLevel != hut.level) {
            spotsLevel = hut.level;
            BuildOrder o = hut.level < 1 ? null : BuildOrder.create(hut.structure(colony.style, hut.level), hut.block.getLocation(), hut.rotation, hut.mirror, hut.block);
            spots = o == null ? List.of() : o.steps.stream().filter(s -> s.data().getMaterial() == Material.COMPOSTER).map(BuildOrder.Step::block).toList();
        }
        return spots;
    }

    /** The barrels of its building as built: where its blueprint has one and a composter still stands (loads their chunks). */
    private List<Block> barrels() {
        return spots().stream().filter(b -> b.getType() == Material.COMPOSTER).toList();
    }

    /** Whether this is one of its building's barrels. */
    public boolean isBarrel(Block b) {
        return b.getType() == Material.COMPOSTER && spots().contains(b);
    }

    private Barrel state(Block b) {
        return barrels.computeIfAbsent(b, k -> new Barrel());
    }

    /**
     * Once a second for every hut, worker or not: full barrels ferment while loaded, a step with the mod's odds each
     * tick (TileEntityBarrel.tick); ready ones sparkle.
     */
    @Override
    public void everySecond() {
        for (Block b : spots()) {
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) || b.getType() != Material.COMPOSTER) continue; // loaded first: no chunk loads
            Barrel s = state(b);
            if (s.done) {
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.5, 0.5), 1, 0.2, 0, 0.2, 0);
                continue;
            }
            if (s.items < Compost.BARREL_ITEMS) continue;
            long time = b.getWorld().getGameTime();
            var rnd = ThreadLocalRandom.current();
            for (int t = 0; t < 20; t++) if ((time + t) % (rnd.nextInt(STEP_ODDS) + 1) == 0) s.timer++;
            // its worker speeds them up: its stamina (main skill) / 10 x 2 more tries a second (the mod's accelerateBarrels)
            if (colony.worker(this) != null) for (int t = 0; t < skill(0) / 10 * Furnaces.SPEED_UP_PER_10; t++) if ((time + t) % (rnd.nextInt(STEP_ODDS) + 1) == 0) s.timer++;
            if (s.timer >= FERMENT_STEPS) { s.timer = 0; s.items = 0; s.done = true; show(b, s); }
        }
    }

    /** The composter's fill level shows the barrel: 0 to 6 as it fills, 8 when ready (7 would make vanilla's bone meal). */
    private static void show(Block b, Barrel s) {
        if (!(b.getBlockData() instanceof Levelled l)) return;
        int level = s.done ? 8 : s.items * 6 / Compost.BARREL_ITEMS;
        if (l.getLevel() == level) return;
        l.setLevel(level);
        b.setBlockData(l, false);
    }

    /**
     * Puts {@code item} into the barrel, each worth its strength, as the mod's consumeNeededItems: the barrel fills as
     * far as they reach, and only the whole ones that went in are used up (an item that only partly fits isn't).
     * @return whether it took any.
     */
    private boolean fill(Block b, ItemStack item) {
        Barrel s = state(b);
        int strength = Compost.strength(item);
        if (s.done || strength == 0 || s.items >= Compost.BARREL_ITEMS) return false;
        int added = Math.min(Compost.BARREL_ITEMS - s.items, item.getAmount() * strength);
        s.items += added;
        item.subtract(added / strength);
        show(b, s);
        return true;
    }

    /**
     * A player's right-click on a barrel (the mod's useBarrel): a ready barrel's 6 compost pop out of the side clicked;
     * else what is in hand goes in, if it composts.
     */
    public void use(Player p, Block b, org.bukkit.block.BlockFace face, ItemStack held) {
        Barrel s = state(b);
        if (s.done) {
            s.done = false;
            show(b, s);
            var drop = b.getWorld().dropItem(b.getLocation().add(0.5, 1.75, 0.5), Compost.item(Compost.PER_BARREL));
            drop.setVelocity(new org.bukkit.util.Vector(face.getModX() / 5.0, face.getModY() / 5.0 + 0.2, face.getModZ() / 5.0));
            b.getWorld().playSound(b.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, SoundCategory.BLOCKS, 1, 1);
            return;
        }
        if (held == null || held.isEmpty() || Compost.strength(held) == 0) return;
        if (s.items >= Compost.BARREL_ITEMS) { p.sendMessage(Component.text("The barrel is working: its compost isn't ready yet.", NamedTextColor.YELLOW)); return; }
        if (fill(b, held))
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_COMPOSTER_FILL, SoundCategory.BLOCKS, 1, 1);
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Composter: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (emptying) { // the mod dumps after every action
            if (!atHut(w)) { w.setStatus("Composter: working"); goToHut(w); return; }
            if (!deliver()) { idle(w, "The hut chest is full.", 100, hut.block); return; }
            emptying = false;
        }
        List<Block> all = barrels();
        if (all.isEmpty()) { idle(w, "Its building has no barrels (composters) left.", 100, hut.block); return; }
        Block ready = all.stream().filter(b -> state(b).done).findFirst().orElse(null);
        if (ready != null) { harvest(w, ready); return; }
        Block open = all.stream().filter(b -> state(b).items < Compost.BARREL_ITEMS).findFirst().orElse(null);
        if (open == null) { w.setStatus("Composter: waiting for the compost"); note = null; cooldown = DECIDE_TICKS; w.wander(hut.block.getLocation().toCenterLocation()); return; }
        if (list.isEmpty()) { idle(w, "Choose what it composts: Compost list in the hut menu.", 100, hut.block); return; }
        if (has(this::listed) == 0) { // the mod's getMaterials: a stack from the hut chest
            if (!atHut(w)) { w.setStatus("Composter: getting materials"); goToHut(w); return; }
            Inventory chest = chest();
            ItemStack first = chest == null ? null : java.util.Arrays.stream(chest.getContents()).filter(this::listed).findFirst().orElse(null);
            if (first == null || fetch(i -> i.isSimilar(first), first.getMaxStackSize()) == 0) {
                idle(w, "Waiting for things to compost (from its list) in the hut chest.", DECIDE_TICKS, hut.block);
                return;
            }
        }
        note = null;
        w.setStatus("Composter: filling a barrel");
        if (!near(w, open)) { w.goBeside(open); return; }
        Inventory bag = bag();
        for (int i = 0; i < bag.getSize(); i++) {
            ItemStack item = bag.getItem(i);
            if (!listed(item)) continue;
            fill(open, item);
            bag.setItem(i, item.isEmpty() ? null : item);
            break;
        }
        w.swing();
        open.getWorld().playSound(open.getLocation(), Sound.BLOCK_COMPOSTER_FILL, SoundCategory.BLOCKS, 1, 1);
        w.workedLittle(); // the mod's decreaseSaturationForContinuousAction
        experience(w, XP);
        cooldown = AFTER_TASK;
    }

    /**
     * The mod's getLootMultiplier: with half its athletics (second skill) as L, a dice of 100 at most 2L gives twice
     * the compost, at most 4L 1.5 times, at most 8L 1.25 times.
     */
    private double lootMultiplier() {
        int l = skill(1) / 2, dice = ThreadLocalRandom.current().nextInt(100);
        return dice <= l * 2 ? 2 : dice <= l * 4 ? 1.5 : dice <= l * 8 ? 1.25 : 1;
    }

    /** Empties a ready barrel into its inventory: 6 compost (more with skill), or with "make dirt" a block of dirt or podzol. */
    private void harvest(Colonist w, Block b) {
        w.setStatus("Composter: emptying a barrel");
        if (!near(w, b)) { w.goBeside(b); return; }
        Barrel s = state(b);
        s.done = false;
        show(b, s);
        ItemStack got;
        if (hut.on(Settings.MAKE_DIRT)) {
            double podzol = PODZOL_PERCENT * (1 + colony.research.effect(Effects.PODZOL_CHANCE));
            got = new ItemStack(ThreadLocalRandom.current().nextInt(100) + 1 <= podzol ? Material.PODZOL : Material.DIRT);
        } else got = Compost.item((int) (Compost.PER_BARREL * lootMultiplier()));
        gather(List.of(got), w);
        w.swing();
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_COMPOSTER_EMPTY, SoundCategory.BLOCKS, 1, 1);
        act(w);
        experience(w, XP);
        emptying = true;
        cooldown = AFTER_TASK;
    }

    private static boolean near(Colonist w, Block b) {
        return w.location().getWorld() == b.getWorld() && w.location().distance(b.getLocation().toCenterLocation()) <= REACH;
    }

    private boolean listed(ItemStack i) {
        return i != null && list.contains(i.getType()) && Compost.strength(i) > 0;
    }

    /** What it composts stays with it when it hands in compost. */
    @Override
    boolean keepsOnHand(ItemStack i) {
        return listed(i);
    }

    /** With nothing from its list on hand or in the chest: a stack per barrel, any amount will do (the mod's request). */
    @Override
    public List<Want> wants() {
        if (hut.level < 1 || list.isEmpty() || has(this::listed) + inChest(this::listed) > 0) return List.of();
        return List.of(new Want("things to compost", this::listed, Compost.BARREL_ITEMS * Math.max(1, spots().size()), 1, hut.number(Settings.MIN_STOCK))); // no block reads
    }

    /** Everything on its list stays; compost and dirt go to the warehouse. */
    @Override
    int keep(ItemStack item) {
        return listed(item) ? Integer.MAX_VALUE : 0;
    }

    @Override
    public List<Component> info(Colony c) {
        if (hut.level == 0) return List.of(Component.text("The composter starts once level 1 is built.", NamedTextColor.GRAY));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Fills the building's barrels (composters) with what is on its compost list; a full barrel gives "
                + (hut.on(Settings.MAKE_DIRT) ? "a block of dirt" : Compost.PER_BARREL + " compost") + " after about 9 minutes. Farmers fertilize with compost.", NamedTextColor.GRAY));
        for (Block b : barrels()) {
            Barrel s = state(b);
            lines.add(Component.text("Barrel: " + (s.done ? "ready" : s.items >= Compost.BARREL_ITEMS ? "composting, " + 100 * s.timer / FERMENT_STEPS + "%"
                    : s.items + " of " + Compost.BARREL_ITEMS + " full")));
        }
        lines.add(Component.text(list.isEmpty() ? "Compost list: empty." : "Compost list: " + String.join(", ", list.stream().map(Compost::name).toList()) + ".",
                list.isEmpty() ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("list", list.stream().map(Enum::name).toList());
        List<String> saved = new ArrayList<>();
        barrels.forEach((b, st) -> saved.add(b.getX() + "," + b.getY() + "," + b.getZ() + "," + st.items + "," + st.timer + "," + st.done));
        s.set("barrels", saved);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        for (String m : s.getStringList("list")) { Material mat = Material.matchMaterial(m); if (mat != null) list.add(mat); }
        for (String line : s.getStringList("barrels")) {
            String[] p = line.split(",");
            Barrel st = new Barrel();
            st.items = Integer.parseInt(p[3]);
            st.timer = Integer.parseInt(p[4]);
            st.done = Boolean.parseBoolean(p[5]);
            barrels.put(hut.block.getWorld().getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), st);
        }
    }
}
