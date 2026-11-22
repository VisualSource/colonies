package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;
import dev.minecp.logistics.Requests;
import dev.minecp.logistics.Warehouse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries items between the huts, as the mod's couriers do: first what crafters made for a hut, straight from the
 * crafter, then what huts want from the warehouse (materials for the builder, seeds and tools, the cook's food and
 * fuel), then the surplus in huts (logs, harvest, what the builder dug up) to the warehouse.
 */
public final class Courier extends Job {

    Job serving;          // the hut it is bringing to or fetching from, so couriers don't double up
    private Want want;    // what it fetches for {@link #serving}; null: it collects surplus
    private Requests.Request crafted; // when fetching what a crafter made for {@link #serving}: that request; null: the warehouse
    private boolean toWarehouse; // with a load: heading to the warehouse (surplus, or a delivery that no longer fit)

    public Courier(Hut hut, Colony colony) {
        super(hut, colony);
    }

    private static final int COURIERS_PER_LEVEL = 2; // the mod's CourierAssignmentModule: a warehouse takes 2 couriers per level

    /** Past the warehouse's couriers (the oldest Courier's Huts with a courier come first): it waits for an upgrade. */
    private boolean overLimit(Warehouse wh) {
        if (wh == null) return false;
        int before = 0;
        for (Job j : colony.jobs) {
            if (j == this) break;
            if (j instanceof Courier && colony.worker(j) != null) before++;
        }
        return before >= wh.hut.level * COURIERS_PER_LEVEL;
    }

    /** All it carries is for others: tools and food it was asked to bring are delivered too. */
    @Override
    boolean keepsOwn() {
        return false;
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
        if (serving != null && (!colony.jobs.contains(serving) || crafted != null && !colony.jobs.contains(crafted.crafter))) drop();

        if (loaded()) { // deliver what it carries
            // Where it goes: the hut it is for; else the warehouse; with neither (the hut is gone, no warehouse), its own hut.
            Job toJob = toWarehouse || serving == null ? null : serving;
            Block to = toJob != null ? toJob.hut.block : wh != null ? wh.hut.block : hut.block;
            if (!w.isBeside(to)) { w.setStatus("Courier: working"); w.goBeside(to); return; }
            Inventory into = toJob != null ? toJob.chest() : wh != null ? null : chest();
            List<ItemStack> load = unload();
            List<ItemStack> left = into == null && wh != null && toJob == null ? wh.store(load)
                    : into == null ? load : new ArrayList<>(into.addItem(load.toArray(ItemStack[]::new)).values());
            gather(left, w); // what didn't fit, back in its inventory
            act(w);
            experience(w, 1.5); // the mod's, for each delivery
            if (!left.isEmpty()) { // didn't fit
                if (toJob != null && wh != null) { toWarehouse = true; return; } // take it back to the warehouse
                // No warehouse to take it back to, or the warehouse is full: wait for room and try again.
                idle(w, "The " + (toJob != null ? toJob.hut.type.title : wh != null ? "warehouse" : hut.type.title) + "'s chest is full.", 60, to);
                return;
            }
            drop();
            cooldown = 10;
            return;
        }

        if (serving == null && overLimit(wh)) {
            idle(w, "The level " + wh.hut.level + " Warehouse takes " + wh.hut.level * COURIERS_PER_LEVEL + " couriers: upgrade it for more.", 100, hut.block);
            return;
        }
        if (serving == null && !pickTask(wh)) {
            idle(w, wh == null ? "No Warehouse: until one is built (level 1 or higher), couriers only carry what crafters make." : null,
                    60, wh == null ? hut.block : wh.hut.block);
            return;
        }
        w.setStatus("Courier: working");
        if (want != null) { // fetch it from the crafter that made it, or from the warehouse
            Block source = crafted != null ? crafted.crafter.hut.block : wh == null ? null : wh.hut.block;
            if (source == null) { drop(); return; }
            if (!w.isBeside(source)) { w.goBeside(source); return; }
            int stacks = Math.min(maxStacks(), freeSlots()); // only what fits in its inventory
            if (stacks <= 0) { idle(w, "Its inventory is full: open it from its menu and take something out.", 100, null); return; }
            List<ItemStack> got = crafted != null ? crafted.crafter.handOver(crafted, stacks) : wh.take(want.test(), want.amount(), stacks);
            gather(got, w);
            if (got.isEmpty()) drop(); // gone meanwhile
            act(w);
            cooldown = 10;
        } else { // collect surplus from the hut
            if (!w.isBeside(serving.hut.block)) { w.goBeside(serving.hut.block); return; }
            List<ItemStack> got = surplus(serving, Math.min(maxStacks(), freeSlots()));
            gather(got, w);
            if (got.isEmpty()) serving = null;
            toWarehouse = true;
            act(w);
            experience(w, 0.05); // the mod's, for each pickup
            cooldown = 10;
        }
    }

    /**
     * Picks the next job: something a crafter made for a hut (the mod's delivery requests: straight from the crafter),
     * then a want the warehouse can fill, else a hut with surplus. @return false if there is none.
     */
    private boolean pickTask(Warehouse wh) {
        for (Requests.Request r : colony.requests.all()) {
            if (!craftedReady(r) || taken(r.job) || r.job == r.crafter) continue; // made for itself: already in its own chest
            serving = r.job; crafted = r; toWarehouse = false;
            want = new Want(r.label(), r.test(), r.amount());
            note = "Bringing " + r.label() + " from the " + r.crafter.hut.type.title + " to the " + r.job.hut.type.title + ".";
            return true;
        }
        crafted = null;
        if (wh == null) return false; // the rest is to or from the warehouse
        for (Requests.Request r : colony.requests.all()) { // the warehouse's part of each request (the mod's deliveries)
            int n = Requests.fromWarehouse(r);
            if (n <= 0 || taken(r.job) || wh.count(r.test()) <= 0) continue;
            serving = r.job; toWarehouse = false;
            want = new Want(r.label(), r.test(), n);
            note = "Bringing " + r.label() + " to the " + r.job.hut.type.title + ".";
            return true;
        }
        for (Job j : colony.jobs) {
            if (j instanceof Courier || taken(j) || !hasSurplus(j)) continue;
            serving = j; want = null;
            note = "Taking what the " + j.hut.type.title + " doesn't need to the warehouse.";
            return true;
        }
        return false;
    }

    /** A crafter has something made for this request, ready for a courier to take to the hut. */
    private static boolean craftedReady(Requests.Request r) {
        return r.state == Requests.State.CRAFTING && r.crafter != null && r.crafter.ready(r.test()) > 0;
    }

    /** Forgets its task: nothing to fetch, nothing to deliver. */
    private void drop() {
        serving = null; want = null; crafted = null; toWarehouse = false; note = null;
    }

    /** How many items matching {@code test} it is carrying to {@code to} now, crafted or from the warehouse. */
    public int carrying(Job to, java.util.function.Predicate<ItemStack> test) {
        return serving != to || toWarehouse ? 0 : has(test);
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
            if (total > j.keptHere(i)) return true;
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
            int keep = j.keptHere(i), already = kept.getOrDefault(i.getType(), 0);
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
