package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Warehouse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries items between the warehouse and the worker huts, as the mod's couriers do: first what huts want
 * (materials for the builder, seeds and tools, the cook's food and fuel), then the surplus in huts
 * (logs, harvest, what the builder dug up) to the warehouse.
 */
public final class Courier extends Job {

    private final Colony colony;
    Job serving;          // the hut it is bringing to or fetching from, so couriers don't double up
    private Want want;    // what it fetches from the warehouse for {@link #serving}; null: it collects surplus
    private boolean toWarehouse; // with a load: heading to the warehouse (surplus, or a delivery that no longer fit)

    public Courier(Hut hut, Colony colony) {
        super(hut);
        this.colony = colony;
    }

    /** Stacks it can carry per trip, as in the mod: 1, 2, 4, 8 for hut levels 1-4, any amount at level 5. */
    int maxStacks() {
        return hut.level >= Hut.MAX_LEVEL ? Integer.MAX_VALUE : 1 << Math.max(0, hut.level - 1);
    }

    @Override
    public List<Component> info(Colony c) {
        int m = maxStacks();
        return List.of(Component.text(hut.level == 0 ? "The courier starts once level 1 is built."
                : "Carries " + (m == Integer.MAX_VALUE ? "any amount" : "up to " + m + (m == 1 ? " stack" : " stacks"))
                + " per trip between the warehouse and the huts (more with each level).", NamedTextColor.GRAY));
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Courier: idle"); // the hut menu says it starts at level 1
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        Warehouse wh = colony.warehouse();
        if (wh == null) { idle(w, "No Warehouse: build one (level 1 or higher) for couriers to work from.", 60, hut.block); return; }
        if (serving != null && !colony.jobs.contains(serving)) { serving = null; want = null; }

        if (!load.isEmpty()) { // deliver what it carries
            Block to = toWarehouse || serving == null ? wh.hut.block : serving.hut.block;
            if (!w.isBeside(to)) { w.setStatus("Courier: working"); w.goBeside(to); return; }
            List<ItemStack> left;
            if (to == wh.hut.block) {
                left = wh.store(load);
                if (!left.isEmpty()) note = "The warehouse is full.";
            } else {
                Inventory chest = serving.chest();
                left = chest == null ? new ArrayList<>(load) : new ArrayList<>(chest.addItem(load.toArray(ItemStack[]::new)).values());
            }
            load.clear();
            load.addAll(left);
            w.swing();
            if (!load.isEmpty()) { // didn't fit: take it back to the warehouse, or wait for room there
                if (to == wh.hut.block) { idle(w, note, 60, wh.hut.block); return; }
                toWarehouse = true;
                return;
            }
            serving = null; want = null; toWarehouse = false; note = null;
            cooldown = 10;
            return;
        }

        if (serving == null && !pickTask(wh)) { idle(w, null, 60, wh.hut.block); return; }
        w.setStatus("Courier: working");
        if (want != null) { // fetch from the warehouse
            if (!w.isBeside(wh.hut.block)) { w.goBeside(wh.hut.block); return; }
            load.addAll(wh.take(want.test(), want.amount(), maxStacks()));
            if (load.isEmpty()) { serving = null; want = null; } // gone meanwhile
            w.swing();
            cooldown = 10;
        } else { // collect surplus from the hut
            if (!w.isBeside(serving.hut.block)) { w.goBeside(serving.hut.block); return; }
            load.addAll(surplus(serving, maxStacks()));
            if (load.isEmpty()) serving = null;
            toWarehouse = true;
            w.swing();
            cooldown = 10;
        }
    }

    /** Picks the next job: a want the warehouse can fill, else a hut with surplus. @return false if there is none. */
    private boolean pickTask(Warehouse wh) {
        for (Job j : colony.jobs) {
            if (j instanceof Courier || taken(j)) continue;
            for (Want wt : j.wants()) if (wt.amount() > 0 && wh.count(wt.test()) > 0) {
                serving = j; want = wt; toWarehouse = false;
                note = "Bringing " + wt.label() + " to the " + j.hut.type.title + ".";
                return true;
            }
        }
        for (Job j : colony.jobs) {
            if (j instanceof Courier || taken(j) || !hasSurplus(j)) continue;
            serving = j; want = null;
            note = "Taking what the " + j.hut.type.title + " doesn't need to the warehouse.";
            return true;
        }
        return false;
    }

    /** Another courier is already on its way for this hut. */
    private boolean taken(Job j) {
        for (Job o : colony.jobs) if (o != this && o instanceof Courier c && c.serving == j) return true;
        return false;
    }

    private static boolean hasSurplus(Job j) {
        Inventory chest = j.chest();
        if (chest == null) return false;
        java.util.Map<org.bukkit.Material, Integer> seen = new java.util.EnumMap<>(org.bukkit.Material.class);
        for (ItemStack i : chest.getContents()) {
            if (i == null || i.isEmpty()) continue;
            int total = seen.merge(i.getType(), i.getAmount(), Integer::sum);
            if (total > j.keep(i)) return true;
        }
        return false;
    }

    /** Takes from the hut chest everything beyond what the hut keeps, as much as fits in {@code maxStacks} stacks. */
    private static List<ItemStack> surplus(Job j, int maxStacks) {
        List<ItemStack> taken = new ArrayList<>();
        Inventory chest = j.chest();
        if (chest == null) return taken;
        java.util.Map<org.bukkit.Material, Integer> kept = new java.util.EnumMap<>(org.bukkit.Material.class);
        for (int slot = 0; slot < chest.getSize(); slot++) {
            ItemStack i = chest.getItem(slot);
            if (i == null || i.isEmpty()) continue;
            int keep = j.keep(i), already = kept.getOrDefault(i.getType(), 0);
            int stays = (int) Math.min(i.getAmount(), Math.max(0L, (long) keep - already));
            kept.put(i.getType(), already + stays);
            int n = Math.min(Warehouse.room(taken, i, maxStacks), i.getAmount() - stays);
            if (n <= 0) continue;
            taken.add(i.asQuantity(n));
            i.subtract(n);
            chest.setItem(slot, i.isEmpty() ? null : i);
        }
        return taken;
    }

}
