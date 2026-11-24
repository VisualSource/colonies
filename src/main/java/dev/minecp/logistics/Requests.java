package dev.minecp.logistics;

import dev.minecp.colony.Colony;
import dev.minecp.job.Courier;
import dev.minecp.job.Crafter;
import dev.minecp.job.Job;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * The colony's open requests (the mod's request system): what each hut wants ({@link Job#wants()}) and who will fill
 * it, worked out again each second. As the mod's resolvers: the warehouse takes a request once it has at least the
 * request's minimum (a courier brings what it has); if that isn't all of it, or it has less than the minimum, a crafter
 * that knows a recipe makes the rest (the mod's child request); else the request waits for a player to put it in the
 * hut's chest. A request a crafter took stays with it until its share is made and handed over, across restarts too.
 */
public final class Requests {
    public enum State {
        WAREHOUSE("in the warehouse: a courier will bring it"),
        CRAFTING("being crafted"),
        DELIVERING("on its way"),
        WAITING("nobody can make it: put it in the hut's chest");

        private final String text;

        State(String text) {
            this.text = text;
        }
    }

    /** One thing one hut is waiting for. */
    public static final class Request {
        public final Job job;
        private Job.Want want;
        public State state = State.WAITING;
        public Crafter crafter;   // set while CRAFTING
        public int crafterShare;  // what the crafter still owes: what the warehouse couldn't send when it took the request
        public int inTransit;     // what couriers are carrying to the hut for it now

        Request(Job job, Job.Want want) {
            this.job = job;
            this.want = want;
        }

        public String label() {
            return want.label();
        }

        public Predicate<ItemStack> test() {
            return want.test();
        }

        /** How many the hut still wants (not counting what is on its way). */
        public int amount() {
            return want.amount();
        }

        private int min() {
            return Math.min(want.min(), want.amount());
        }

        /** "being crafted at the Sawmill". */
        public String stateText() {
            return state == State.CRAFTING && crafter != null ? "being crafted at the " + crafter.hut.type.title : state.text;
        }
    }

    /** A crafter's request kept over a restart: requester hut, label, crafter hut (indexes into the colony's huts). */
    private record Saved(int job, String label, int crafter, int share) {}

    private static final int MAX_CHAIN = 16; // the longest chain of crafters asking crafters waited for when restoring saved requests

    private final Colony colony;
    private List<Request> all = List.of();
    private final List<Saved> saved = new ArrayList<>(); // from the save file, until the requests come back
    private int restoring; // refreshes left to match them: a crafter asks for ingredients only once its own tasks are back

    public Requests(Colony colony) {
        this.colony = colony;
    }

    public List<Request> all() {
        return all;
    }

    public List<Request> of(Job job) {
        return all.stream().filter(r -> r.job == job).toList();
    }

    /** Reads every hut's wants again and decides who fills each. */
    public void refresh() {
        Map<Key, Request> old = new HashMap<>();
        for (Request r : all) old.put(new Key(r.job, r.label()), r);
        Map<Crafter, List<Request>> before = new IdentityHashMap<>(); // last second's tasks, for the cycle check
        for (Job j : colony.jobs) if (j instanceof Crafter c) before.put(c, List.copyOf(c.tasks));
        List<Request> now = new ArrayList<>();
        for (Job j : colony.jobs) {
            if (j instanceof Courier) continue; // couriers want nothing of their own
            for (Job.Want w : j.wants()) {
                if (w.amount() <= 0) continue;
                Request r = old.remove(new Key(j, w.label()));
                if (r == null) restore(r = new Request(j, w));
                r.want = w;
                r.inTransit = 0;
                for (Job o : colony.jobs) if (o instanceof Courier c) r.inTransit += c.carrying(j, r.test());
                now.add(r);
            }
        }
        if (--restoring <= 0) saved.clear();
        Warehouse wh = colony.warehouse();
        for (Job j : colony.jobs) if (j instanceof Crafter c) c.tasks.clear();
        for (Request r : now) {
            if (r.crafter == null || r.crafterShare <= 0 || !colony.jobs.contains(r.crafter) || !canCraft(r.crafter, r, before)) {
                r.crafter = null;
                r.crafterShare = 0;
                int need = r.amount() - r.inTransit;
                if (need <= 0) { r.state = State.DELIVERING; continue; } // all of it is being carried there
                int stock = wh == null ? 0 : wh.count(r.test());
                int fromWarehouse = stock >= Math.min(r.min(), need) ? Math.min(stock, need) : 0; // below the minimum it doesn't take it
                Crafter c = fromWarehouse >= need ? null : crafterFor(r, before);
                if (c != null) { r.crafter = c; r.crafterShare = need - fromWarehouse; }
                else { r.state = fromWarehouse > 0 ? State.WAREHOUSE : State.WAITING; continue; }
            }
            r.state = State.CRAFTING;
            r.crafter.tasks.add(r);
        }
        all = List.copyOf(now);
    }

    /** What a courier should fetch from the warehouse for {@code r}: the part a crafter isn't making and that isn't on its way. */
    public static int fromWarehouse(Request r) {
        return switch (r.state) {
            case WAREHOUSE -> Math.max(0, r.amount() - r.inTransit);
            case CRAFTING -> Math.max(0, r.amount() - r.crafterShare - r.inTransit);
            case DELIVERING, WAITING -> 0;
        };
    }

    private Crafter crafterFor(Request r, Map<Crafter, List<Request>> tasks) {
        for (Job j : colony.jobs) if (j instanceof Crafter c && canCraft(c, r, tasks)) return c;
        return null;
    }

    /**
     * A built crafter with a worker and a recipe for it that doesn't, through any chain of crafters, need what it makes.
     * A crafter may make its own ingredients, as the mod's (the Sawmill: planks for the stairs it was asked for; the
     * Glassblower: glass for its panes). Never its own fuel: a smelter's fuel excludes what its recipes make.
     */
    private boolean canCraft(Crafter c, Request r, Map<Crafter, List<Request>> tasks) {
        if (c.hut.level < 1 || colony.worker(c) == null) return false;
        Recipe recipe = c.recipeFor(r.test());
        return recipe != null && !Crafter.uses(recipe, r.test()) && !cycle(t -> Crafter.uses(recipe, t.test()), r, tasks, Requests::feeds, new java.util.HashSet<>());
    }

    /**
     * Whether making {@code r} would use up ({@code usesUp}) something a crafter further up the chain is making
     * (the mod's createsCraftingCycle): r asks for an ingredient of a crafter's task, that task may be an ingredient of
     * another crafter's, and so on. Each request is looked at once: a crafter's own ingredient request is among its
     * own tasks, and following it again would go round forever. So are the other links of a chain it makes itself
     * (awkward, then swiftness, then splash potions): only the tasks r {@code feeds} count, or all if it feeds none (fuel).
     */
    static boolean cycle(Predicate<Request> usesUp, Request r, Map<Crafter, List<Request>> tasks, BiPredicate<Request, Request> feeds, java.util.Set<Request> seen) {
        if (!seen.add(r) || !(r.job instanceof Crafter up)) return false;
        List<Request> others = tasks.getOrDefault(up, List.of()).stream().filter(t -> t != r).toList();
        List<Request> fed = others.stream().filter(t -> feeds.test(r, t)).toList();
        for (Request parent : fed.isEmpty() ? others : fed) if (usesUp.test(parent) || cycle(usesUp, parent, tasks, feeds, seen)) return true;
        return false;
    }

    /** Whether {@code r} is an ingredient of {@code task}, one of the same crafter's. */
    private static boolean feeds(Request r, Request task) {
        Recipe recipe = ((Crafter) r.job).recipeFor(task.test());
        return recipe != null && Crafter.uses(recipe, r.test());
    }

    private void restore(Request r) {
        for (Saved s : saved) {
            if (colony.hutByIndex(s.job) != r.job.hut || !s.label.equals(r.label())) continue;
            if (colony.jobAt(colony.hutByIndex(s.crafter)) instanceof Crafter c) { r.crafter = c; r.crafterShare = s.share; }
            saved.remove(s);
            return;
        }
    }

    public void save(ConfigurationSection s) {
        int i = 0;
        for (Request r : all) {
            if (r.state != State.CRAFTING || r.crafter == null) continue;
            var rs = s.createSection(String.valueOf(i++));
            rs.set("hut", colony.huts.indexOf(r.job.hut));
            rs.set("label", r.label());
            rs.set("crafter", colony.huts.indexOf(r.crafter.hut));
            rs.set("share", r.crafterShare);
        }
    }

    public void load(ConfigurationSection s) {
        for (String k : s.getKeys(false)) {
            var rs = s.getConfigurationSection(k);
            saved.add(new Saved(rs.getInt("hut", -1), rs.getString("label", ""), rs.getInt("crafter", -1), rs.getInt("share")));
        }
        restoring = MAX_CHAIN; // one link of a chain of crafters comes back per refresh
    }

    private record Key(Job job, String label) {} // jobs compare by identity
}
