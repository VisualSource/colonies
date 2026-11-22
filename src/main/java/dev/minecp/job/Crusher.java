package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.colony.Settings;
import dev.minecp.research.Effects;
import dev.minecp.research.Studies;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The Crusher (the mod's BuildingCrusher and EntityAIWorkCrusher): it knows only the mod's own crushing recipes
 * (crafterrecipes/crusher), two blocks into one until the Gilded Hammer research makes them one into one, and makes
 * what huts ask for. Besides that, its daily mode (settings) crushes one recipe up to a number a day, asking the
 * warehouse for what it crushes; the count starts again each morning, as the mod's.
 */
public final class Crusher extends Crafter {
    private static final int CRUSH_TICKS = 80;      // the mod's: 40 progress every 40 ticks, a crush once past 49 (no skills)
    private static final int ASK_PER_CRUSH = 2;     // the mod asks for twice what is left of the day, at most a stack
    private static final double FLINT_CHANCE = 0.1; // its recipes/gravel loot table: flint 10 in 100 with each sand

    private static final Predicate<Studies> TWO_TO_ONE = s -> s.effect(Effects.CRUSHING_ONE_TO_ONE) <= 0, ONE_TO_ONE = TWO_TO_ONE.negate(),
            DEPTHS = s -> s.effect(Effects.DEPTH_KNOWLEDGE) > 0, ALWAYS = s -> true;

    private int crushedToday; // saved
    private int today = -1;    // the colony day it counts (saved)

    public Crusher(Hut hut, Colony colony) {
        super(hut, colony, r -> false, "gravel, sand, clay, bone meal, cobblestone and tuff", Kind.OWN);
        know("gravel", Material.GRAVEL, 1, Material.COBBLESTONE, 2, TWO_TO_ONE);
        know("gravel_1", Material.GRAVEL, 1, Material.COBBLESTONE, 1, ONE_TO_ONE);
        know("sand", Material.SAND, 1, Material.GRAVEL, 2, TWO_TO_ONE);
        know("sand_1", Material.SAND, 1, Material.GRAVEL, 1, ONE_TO_ONE);
        know("clay", Material.CLAY, 1, Material.SAND, 2, TWO_TO_ONE);
        know("clay_1", Material.CLAY, 1, Material.SAND, 1, ONE_TO_ONE);
        know("clay_ball", Material.CLAY_BALL, 4, Material.CLAY, 1, ALWAYS);
        know("bone_meal", Material.BONE_MEAL, 3, Material.BONE, 1, TWO_TO_ONE);
        know("bone_meal_1", Material.BONE_MEAL, 5, Material.BONE, 1, ONE_TO_ONE);
        know("bone_meal_block", Material.BONE_MEAL, 9, Material.BONE_BLOCK, 1, ALWAYS);
        know("cobblestone", Material.COBBLESTONE, 1, Material.TUFF, 2, DEPTHS.and(TWO_TO_ONE));
        know("cobblestone_1", Material.COBBLESTONE, 1, Material.TUFF, 1, DEPTHS.and(ONE_TO_ONE));
        know("tuff", Material.TUFF, 1, Material.COBBLED_DEEPSLATE, 2, DEPTHS.and(TWO_TO_ONE));
        know("tuff_1", Material.TUFF, 1, Material.COBBLED_DEEPSLATE, 1, DEPTHS.and(ONE_TO_ONE));
    }

    @Override
    public double diseaseModifier() {
        return 4; // the mod's JobCrusher: (100 - skill) / 25, no skills
    }

    private void know(String id, Material result, int count, Material from, int amount, Predicate<Studies> when) {
        learn(own("crusher_" + id, result, count, Map.of(from, amount)), 1, when);
    }

    @Override
    List<ItemStack> extras(Recipe r) {
        return r.getResult().getType() == Material.SAND && ThreadLocalRandom.current().nextDouble() < FLINT_CHANCE
                ? List.of(new ItemStack(Material.FLINT)) : List.of();
    }

    // ---- the daily mode -------------------------------------------------------------------------------------------

    /** The recipe its daily mode crushes, or null if research doesn't allow it yet. */
    private Recipe daily() {
        Material out = hut.block(Settings.CRUSHER_MODE);
        return recipeFor(i -> i.getType() == out);
    }

    /** How many it may still crush today in its daily mode. */
    private int leftToday() {
        if (today != colony.day) { today = colony.day; crushedToday = 0; } // the mod's onWakeUp
        return hut.number(Settings.DAILY_LIMIT) - crushedToday;
    }

    /** What its daily mode crushes (the recipe's one ingredient), or null when it has nothing to do today. */
    private Material dailyInput() {
        Recipe r = leftToday() > 0 ? daily() : null;
        return r == null ? null : ingredients(r).keySet().iterator().next().getItemStack().getType();
    }

    private int dailyAsk() {
        return Math.min(leftToday() * ASK_PER_CRUSH, 64);
    }

    @Override
    boolean otherWork(Colonist w, Inventory chest) {
        Material input = dailyInput();
        if (input == null || !tasks.isEmpty()) return false; // the mod: only when no hut is waiting on it
        Recipe r = daily();
        if (!hasIngredients(chest, r)) { idle(w, "Waiting for " + name(input) + " to crush today.", 40, hut.block); return true; }
        if (!atHut(w)) { w.setStatus(hut.type.worker + ": working"); goToHut(w); return true; }
        note = null;
        w.setStatus(hut.type.worker + ": crushing");
        w.hold(new ItemStack(input));
        w.swing();
        w.workedLittle(); // the mod's crusher: a small action each crush
        hut.block.getWorld().playSound(hut.block.getLocation(), Sound.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.5f, 1);
        craft(chest, r);
        if (++crushedToday == hut.number(Settings.DAILY_LIMIT)) w.worked(); // the mod's full action once the day's batch is done
        cooldown = CRUSH_TICKS;
        return true;
    }

    @Override
    public List<Want> wants() {
        List<Want> wants = new ArrayList<>(super.wants());
        Material input = dailyInput();
        if (input != null) {
            int more = dailyAsk() - inChest(i -> i.getType() == input);
            if (more > 0) wants.add(new Want(name(input) + " to crush", i -> i.getType() == input, more));
        }
        return wants;
    }

    @Override
    int keep(ItemStack item) {
        int keep = super.keep(item);
        return item.getType() == dailyInput() ? Math.max(keep, dailyAsk()) : keep;
    }

    @Override
    public List<net.kyori.adventure.text.Component> info(Colony c) {
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>(super.info(c));
        int limit = hut.number(Settings.DAILY_LIMIT);
        if (hut.level >= 1 && limit > 0) lines.add(net.kyori.adventure.text.Component.text("Daily: " + name(hut.block(Settings.CRUSHER_MODE))
                + ", " + Math.max(0, limit - leftToday()) + " of " + limit + " today" + (daily() == null ? " (needs research first)" : "") + ".",
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
        return lines;
    }

    @Override
    public void save(ConfigurationSection s, Colony c) {
        super.save(s, c);
        s.set("crushed_today", crushedToday);
        s.set("today", today);
    }

    @Override
    public void load(ConfigurationSection s, Colony c) {
        super.load(s, c);
        crushedToday = s.getInt("crushed_today");
        today = s.getInt("today", -1);
    }
}
