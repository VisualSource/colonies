package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Warehouse;
import dev.minecp.research.Effects;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Concrete Mixer (the mod's BuildingConcreteMixer and EntityAIConcreteMixer): it knows the mod's own recipes
 * (crafterrecipes/concretemixer): 4 sand, 4 gravel and a dye make 8 concrete powder, at the hut; and concrete, which
 * it makes in the shallow flowing water of its building: it carries the powder there, sets each spot (the powder
 * hardens at once), then mines the concrete with a pickaxe and brings it back.
 */
public final class ConcreteMixer extends Crafter {
    private static final int WATER_DEPTH = 5;   // the mod's WATER_DEPTH_SUPPORT: flowing water of this amount or less (levels 3 to 7)
    private static final int ACTION_TICKS = 20; // the mod's: a placing or a mining step each second
    private static final int BELOW = 4, ABOVE = 12; // ours: heights around the hut block searched for its water (the footprint has none)

    private static final int RESCAN_TICKS = 200;    // ours: how often it looks again while it has found no water

    private final List<Block> spots = new ArrayList<>(); // its water, as found once the hut was built (saved, as the mod's)
    private int spotsAt = -1;                              // the hut level they were found at (saved)
    private long rescanAt;

    public ConcreteMixer(Hut hut, Colony colony) {
        super(hut, colony, r -> false, "concrete powder, and concrete set in the water of its building", Kind.OWN);
        for (DyeColor color : DyeColor.values()) {
            String c = color.name();
            Material powder = Material.valueOf(c + "_CONCRETE_POWDER");
            builtIn.put(own("mixer_" + c.toLowerCase() + "_concrete_powder", powder, 8,
                    Map.of(Material.SAND, 4, Material.GRAVEL, 4, Material.valueOf(c + "_DYE"), 1)), 1);
            builtIn.put(own("mixer_" + c.toLowerCase() + "_concrete", Material.valueOf(c + "_CONCRETE"), 1, Map.of(powder, 1)), 1);
        }
    }

    private static boolean concrete(Material m) {
        return m.name().endsWith("_CONCRETE");
    }

    private static Material powderOf(Material concrete) {
        return Material.valueOf(concrete.name() + "_POWDER");
    }

    /** Concrete only while it has water to set it in; otherwise its orders go elsewhere (the warehouse, a player). */
    @Override
    boolean knows(Recipe builtIn) {
        return !concrete(builtIn.getResult().getType()) || !spots().isEmpty();
    }

    /** Concrete is set in the water, not made at the hut. */
    @Override
    boolean craftsAtHut(Recipe r) {
        return !concrete(r.getResult().getType());
    }

    /** Concrete set in its water or carried back counts as made (the mod's outputBlockCountInWorld). */
    @Override
    int inProgress(Recipe r) {
        Material m = r.getResult().getType();
        if (!concrete(m)) return super.inProgress(r);
        int n = carried.getOrDefault(m, 0);
        for (Block b : spots()) if (b.getType() == m) n++;
        return n;
    }

    /** Its water: shallow flowing water (the mod's flowing water of amount 5 or less) in its building, found once per hut level. */
    private List<Block> spots() {
        long now = hut.block.getWorld().getGameTime();
        if (spotsAt != hut.level || spots.isEmpty() && now >= rescanAt) {
            rescanAt = now + RESCAN_TICKS;
            List<Block> setNow = spots.stream().filter(b -> concrete(b.getType())).toList(); // still to be mined: the mod never forgets a spot
            spots.clear();
            spots.addAll(Furnaces.in(colony, hut, BELOW, ABOVE,
                    b -> b.getType() == Material.WATER && b.getBlockData() instanceof Levelled l && l.getLevel() >= 8 - WATER_DEPTH && l.getLevel() < 8));
            for (Block b : setNow) if (!spots.contains(b)) spots.add(b);
            spotsAt = hut.level;
        }
        return spots;
    }

    /** A task still waiting for concrete it has powder for (in the chest or carried), or null. */
    private Material concreteWanted(Inventory chest) {
        for (var t : tasks) {
            Recipe r = recipeFor(t.test());
            if (r == null || craftsAtHut(r) || craftsLeft(t, r, false) == 0) continue;
            Material m = r.getResult().getType();
            if (carried.getOrDefault(powderOf(m), 0) > 0 || inChest(i -> i.getType() == powderOf(m)) > 0) return m;
        }
        return null;
    }

    @Override
    boolean otherWork(Colonist w, Inventory chest) {
        List<Block> water = spots();
        if (water.isEmpty()) return false;
        // Mine what is set first, as the mod's (performMixingWork).
        for (Block b : water) {
            if (!concrete(b.getType())) continue;
            if (!hasTool(Tool.PICKAXE, b)) {
                if (!atHut(w)) { goToHut(w); return true; }
                if (!fetchTool(Tool.PICKAXE, b)) { idle(w, "Needs " + toolLabel(Tool.PICKAXE) + " to mine the concrete it set.", 100, hut.block); return true; }
            }
            if (!w.isBeside(b)) { w.setStatus(hut.type.worker + ": mining concrete"); w.goBeside(b); return true; }
            for (ItemStack drop : breakWith(Tool.PICKAXE, b, w)) carried.merge(drop.getType(), drop.getAmount(), Integer::sum);
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1, 1);
            b.setType(Material.AIR); // the water flows back in
            act(w);
            cooldown = faster(ACTION_TICKS, Effects.BREAK_SPEED);
            return true;
        }
        Material want = concreteWanted(chest);
        Block free = water.stream().filter(b -> b.getType() == Material.WATER).findFirst().orElse(null);
        if (want != null && free != null) {
            Material powder = powderOf(want);
            if (carried.getOrDefault(powder, 0) == 0) { // the powder for one trip: as many as there is water for
                if (!atHut(w)) { w.setStatus(hut.type.worker + ": working"); goToHut(w); return true; }
                List<ItemStack> taken = new ArrayList<>();
                Warehouse.take(chest, i -> i.getType() == powder, (int) water.stream().filter(b -> b.getType() == Material.WATER).count(), Integer.MAX_VALUE, taken);
                taken.forEach(i -> carried.merge(i.getType(), i.getAmount(), Integer::sum));
                return true;
            }
            if (!w.isBeside(free)) { w.setStatus(hut.type.worker + ": setting concrete"); w.goBeside(free); return true; }
            w.hold(new ItemStack(powder));
            act(w);
            free.setType(want); // vanilla's powder hardens at once in water
            free.getWorld().playSound(free.getLocation(), Sound.BLOCK_SAND_PLACE, SoundCategory.BLOCKS, 1, 1);
            carried.merge(powder, -1, Integer::sum);
            carried.values().removeIf(n -> n <= 0);
            cooldown = faster(ACTION_TICKS, Effects.PLACE_SPEED);
            return true;
        }
        if (carried.isEmpty()) return false;
        if (!atHut(w)) { w.setStatus(hut.type.worker + ": working"); goToHut(w); return true; } // the concrete, and any powder left, back to the chest
        if (!deliver()) idle(w, "The hut chest is full.", 100, hut.block);
        return true;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>(super.wants());
        if (hut.level >= 1 && !spots().isEmpty()) wantTool(wants, Tool.PICKAXE); // the mod keeps a pickaxe for its concrete
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        return fits(Tool.PICKAXE, item, null) ? Math.max(1, super.keep(item)) : super.keep(item);
    }

    @Override
    public void save(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("water_level", spotsAt);
        s.set("water", spots.stream().map(b -> b.getX() + "," + b.getY() + "," + b.getZ()).toList());
    }

    @Override
    public void load(org.bukkit.configuration.ConfigurationSection s, Colony c) {
        super.load(s, c);
        spotsAt = s.getInt("water_level", -1);
        for (String at : s.getStringList("water")) {
            String[] xyz = at.split(",");
            spots.add(hut.block.getWorld().getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
        }
    }
}
