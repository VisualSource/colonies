package dev.minecp.research;

import com.google.gson.Gson;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's research tree (its data/minecolonies/researches), as tools/convert_research.py writes it to research.json:
 * branches, and researches with their depth, parent, costs, building requirements and effects. The same for every
 * colony; each colony's progress is its {@link Studies}.
 */
public final class ResearchTree {
    /** The mod's BASE_RESEARCH_TIME: progress steps of a depth 1 research (a step each colony tick of 500 ticks, 30 min). */
    static final int BASE_STEPS = 60 * 60 / 25 / 2;
    /** The deepest level; a branch allows only one research this deep (the mod's MAX_DEPTH). */
    static final int MAX_DEPTH = 6;

    public record Branch(String id, String name, double baseTime, boolean hidden, int sort) {}

    /** {@code items}: item keys ("minecraft:carrot", "minecp:rack"), any of them; or {@code tag}: a vanilla item tag. */
    public record Cost(List<String> items, String tag, int count) {}

    /** Levels of these huts (any of them) adding up to {@code level}, or ({@code single}) one hut at that level. */
    public record Requirement(List<String> buildings, int level, boolean single) {}

    public record Effect(String id, double strength) {}

    public record Research(String id, String branch, String name, int depth, String parent, int sort, boolean onlyChild, boolean noReset,
                           List<Cost> costs, List<Requirement> requirements, List<Effect> effects) {}

    private record File(List<Branch> branches, List<Research> researches) {}

    private static final Map<String, Branch> BRANCHES = new LinkedHashMap<>();
    private static final Map<String, Research> RESEARCHES = new LinkedHashMap<>();
    private static final Map<String, List<Research>> CHILDREN = new LinkedHashMap<>();

    static {
        try (var in = ResearchTree.class.getResourceAsStream("/research.json")) {
            File f = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), File.class);
            f.branches().stream().sorted(Comparator.comparingInt(Branch::sort)).forEach(b -> BRANCHES.put(b.id(), b));
            f.researches().stream().sorted(Comparator.comparingInt(Research::sort).thenComparing(Research::name))
                    .forEach(r -> RESEARCHES.put(r.id(), r));
            for (Research r : RESEARCHES.values())
                CHILDREN.computeIfAbsent(r.parent() == null ? r.branch() : r.parent(), k -> new ArrayList<>()).add(r);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalStateException("research.json couldn't be read", e);
        }
    }

    private ResearchTree() {}

    /** The branches players see, in the mod's order (not its hidden Unlockables, whose researches start themselves). */
    public static List<Branch> branches() {
        return BRANCHES.values().stream().filter(b -> !b.hidden()).toList();
    }

    public static Research get(String id) {
        return RESEARCHES.get(id);
    }

    /** Its parent research, or null at the top of a branch. */
    public static Research parent(Research r) {
        return r.parent() == null ? null : RESEARCHES.get(r.parent());
    }

    public static Branch branch(String id) {
        return BRANCHES.get(id);
    }

    /** A research's children, or a branch's first researches (by the branch id), in the mod's order. */
    public static List<Research> children(String id) {
        return CHILDREN.getOrDefault(id, List.of());
    }

    static java.util.Collection<Research> all() {
        return RESEARCHES.values();
    }

    /** Progress steps to finish it: the mod's base time, times its branch's, doubled for each level deeper. */
    public static int steps(Research r) {
        return (int) (BASE_STEPS * BRANCHES.get(r.branch()).baseTime() * Math.pow(2, r.depth() - 1));
    }
}
