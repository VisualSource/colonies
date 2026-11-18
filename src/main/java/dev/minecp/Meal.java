package dev.minecp;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A hungry citizen getting a meal, as in the mod: food from its work hut's chest, else its house chest, else it
 * waits at the restaurant (Cook's Hut) for the cook to serve it. Then it eats until full.
 */
final class Meal {
    private static final int WAIT_TICKS = 20 * 60 * 2; // the mod's citizens wait 2 minutes to be served
    private static final int BITE_TICKS = 8;            // ours
    private static final int BITES = 5;                 // the mod's time to eat one item

    private enum State { FIND, TO_CHEST, TO_RESTAURANT, WAITING, EAT }

    final Colonist colonist;
    private State state = State.FIND;
    private Block source;        // chest it takes food from
    private Cook restaurant;     // cook it waits for
    private final List<ItemStack> food = new ArrayList<>();
    private int timer;
    String why;                  // set when it found no food

    Meal(Colonist colonist) {
        this.colonist = colonist;
    }

    boolean isWaiting() {
        return state == State.WAITING;
    }

    /** The cook hands over food. */
    void serve(List<ItemStack> items) {
        food.addAll(items);
        state = State.EAT;
        timer = 0;
    }

    double hunger() {
        return Food.MAX_SATURATION - colonist.saturation;
    }

    /** @return true when the meal is over: eaten, or no food could be had ({@link #why} says why). */
    boolean tick(Colony colony, Job job) {
        Colonist c = colonist;
        switch (state) {
            case FIND -> {
                for (Block chest : new Block[]{job == null ? null : job.hut.block, c.home == null ? null : c.home.block}) {
                    if (chest != null && Food.hasMeal(inventory(chest))) {
                        source = chest;
                        c.setStatus("Eating");
                        c.goBeside(chest);
                        state = State.TO_CHEST;
                        return false;
                    }
                }
                restaurant = colony.restaurant();
                if (restaurant == null) {
                    why = "No food in its hut or house chest, and no restaurant with a cook.";
                    return true;
                }
                c.setStatus("Hungry: going to eat");
                c.goBeside(restaurant.hut.block);
                state = State.TO_RESTAURANT;
            }
            case TO_CHEST -> {
                if (c.isMoving()) return false;
                if (!c.isBeside(source)) { c.goBeside(source); return false; }
                Inventory inv = inventory(source);
                if (inv != null) food.addAll(Food.take(inv, hunger()));
                if (food.isEmpty()) { state = State.FIND; return false; } // someone took it first
                state = State.EAT;
                timer = 0;
            }
            case TO_RESTAURANT -> {
                if (c.isMoving()) return false;
                if (!c.isBeside(restaurant.hut.block)) { c.goBeside(restaurant.hut.block); return false; }
                c.setStatus("Hungry: waiting for food");
                restaurant.waiting.add(this);
                state = State.WAITING;
                timer = 0;
            }
            case WAITING -> {
                if (++timer > WAIT_TICKS || !colony.jobs.contains(restaurant)) {
                    restaurant.waiting.remove(this);
                    why = "Waited at the restaurant, but the cook had no food to serve.";
                    return true;
                }
            }
            case EAT -> {
                if (food.isEmpty() || c.saturation >= Food.MAX_SATURATION) return finish();
                ItemStack next = food.getFirst();
                c.hold(next);
                if (++timer % BITE_TICKS != 0) return false;
                c.swing();
                c.location().getWorld().playSound(c.location(), Sound.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 0.6f, 1);
                if (timer < BITE_TICKS * BITES) return false;
                timer = 0;
                c.saturation = Math.min(Food.MAX_SATURATION, c.saturation + Food.nutrition(next));
                if (next.getAmount() > 1) next.subtract();
                else food.removeFirst();
            }
        }
        return false;
    }

    /** Done eating: leftovers go back where the food came from (or the restaurant). */
    private boolean finish() {
        colonist.hold(null);
        Block back = source != null ? source : restaurant != null ? restaurant.hut.block : null;
        Inventory inv = back == null ? null : inventory(back);
        if (inv != null) for (ItemStack over : inv.addItem(food.toArray(ItemStack[]::new)).values())
            back.getWorld().dropItemNaturally(back.getLocation().toCenterLocation(), over);
        food.clear();
        return true;
    }

    /** A meal cut short (the citizen despawned or the colony stops): leftovers are put back. */
    void abandon() {
        if (restaurant != null) restaurant.waiting.remove(this);
        finish();
    }

    private static Inventory inventory(Block b) {
        return b.getState(false) instanceof Container ct ? ct.getInventory() : null;
    }
}
