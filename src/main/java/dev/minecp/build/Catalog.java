package dev.minecp.build;

import dev.minecp.colony.Hut;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * What a style pack offers the build tool, by its category folders as the mod's build tool shows them
 * (fundamentals, craftsmanship/storage, decorations/misc...): MineCP's huts, with their alternative versions
 * ("altbuilder", cavern's "alternative" and "megahall" folders), and the builds without a hut under decorations,
 * infrastructure and walls. Read once per style from the data packs' files (structures the server hasn't loaded
 * aren't listed by its structure manager).
 */
public final class Catalog {
    /** The plantation fields' folder: every style also offers the original style's (the medieval styles have none of their own). */
    private static final String FIELDS = "agriculture/fields";
    /** Top folders of builds without a hut; huts the plugin doesn't have (barracks, school...) aren't offered. */
    private static final List<String> BUILDS = List.of("decorations", "infrastructure", "walls", FIELDS);
    /** Not placed by hand: the miner's shafts, and the supply camp and ship (the mod places those from their items). */
    private static final List<String> HIDDEN = List.of("infrastructure/mineshafts", "decorations/supplies");

    /** One thing to place: its path in the pack without a level, what it is, and whether its files have levels. */
    public record Entry(String path, String name, Hut.Type hut, boolean leveled) {
        /** Its first level's structure in a style. */
        public String structure(String style) {
            String file = path + (leveled ? "1" : "");
            // a build without a hut is placed from its own style, but for the plantation fields, which may be borrowed
            return hut == null && !path.startsWith(FIELDS + "/") ? style + "/" + file : Hut.Type.orFallback(style, file);
        }
    }

    private static final Map<String, List<String>> FILES = new ConcurrentHashMap<>();

    private Catalog() {}

    /** The folders inside {@code folder} ("" for the top) that hold anything to place, sorted. */
    public static List<String> folders(String style, String folder) {
        TreeSet<String> out = new TreeSet<>();
        String prefix = folder.isEmpty() ? "" : folder + "/";
        for (String f : files(style)) {
            if (!f.startsWith(prefix)) continue;
            String rest = f.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0 && entry(f) != null) out.add(prefix + rest.substring(0, slash));
        }
        return List.copyOf(out);
    }

    /** What can be placed directly in {@code folder}. */
    public static List<Entry> entries(String style, String folder) {
        List<Entry> out = new ArrayList<>();
        String prefix = folder.isEmpty() ? "" : folder + "/";
        for (String f : files(style)) {
            if (!f.startsWith(prefix) || f.indexOf('/', prefix.length()) >= 0) continue;
            Entry e = entry(f);
            if (e != null && out.stream().noneMatch(o -> o.path.equals(e.path))) out.add(e);
        }
        out.sort(java.util.Comparator.comparing(Entry::name)); // each hut before its other versions
        return out;
    }

    /**
     * A file as something to place, or null: a hut's level 1 (any version), or a build without a hut (its level 1 if
     * it has levels, as the mod places leveled decorations).
     */
    static Entry entry(String file) {
        int slash = file.lastIndexOf('/');
        String folder = slash < 0 ? "" : file.substring(0, slash), name = file.substring(slash + 1);
        int digits = name.length();
        while (digits > 0 && Character.isDigit(name.charAt(digits - 1))) digits--;
        String base = name.substring(0, digits), level = name.substring(digits);
        if (!level.isEmpty() && !level.equals("1")) return null; // the other levels come with upgrades
        String path = (folder.isEmpty() ? "" : folder + "/") + base;
        for (Hut.Type t : Hut.Type.values()) {
            if (!t.isBuilding() || level.isEmpty()) continue;
            int s = t.path.lastIndexOf('/');
            String typeFolder = t.path.substring(0, s), typeName = t.path.substring(s + 1);
            boolean alt = base.equals("alt" + typeName);
            if (!(base.equals(typeName) || alt) || !(folder.equals(typeFolder) || folder.startsWith(typeFolder + "/"))) continue;
            if (t == Hut.Type.BARRACKS_TOWER) return null; // comes with its barracks
            String variant = folder.substring(typeFolder.length()).replace("/", " ").trim(); // cavern: alternative, megahall
            if (variant.equals("default")) variant = "";
            if (alt) variant = (variant + " alternative").trim();
            return new Entry(path, t.title + (variant.isEmpty() ? "" : " (" + variant + ")"), t, true);
        }
        if (BUILDS.stream().noneMatch(b -> folder.equals(b) || folder.startsWith(b + "/")) 
                || HIDDEN.stream().anyMatch(h -> folder.equals(h) || folder.startsWith(h + "/"))) return null;
        String nice = base.replace('_', ' ').trim();
        return new Entry(path, nice.isEmpty() ? name : Character.toUpperCase(nice.charAt(0)) + nice.substring(1), null, !level.isEmpty());
    }

    /** Every structure of every installed style, as "style/path" (for /colonies build's suggestions). */
    public static List<String> structures() {
        List<String> out = new ArrayList<>();
        for (String style : dev.minecp.colony.Colony.styles()) for (String f : own(style)) out.add(style + "/" + f);
        for (String f : own(Hut.Type.FALLBACK_STYLE)) out.add(Hut.Type.FALLBACK_STYLE + "/" + f); // the borrowed ones, once, under their own name
        return out;
    }

    /** A category folder's name for the menus: "craftsmanship/storage" -> "Storage". */
    public static String title(String folder) {
        String last = folder.substring(folder.lastIndexOf('/') + 1).replace('_', ' ');
        return last.isEmpty() ? last : Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    /**
     * Every structure file of a style, as paths inside it without ".nbt": the data packs' (folders or zips, as the game
     * reads both), and hand-fixed ones.
     */
    private static List<String> files(String style) {
        if (style.equals(Hut.Type.FALLBACK_STYLE)) return own(style);
        TreeSet<String> all = new TreeSet<>(own(style));
        for (String f : own(Hut.Type.FALLBACK_STYLE)) { // a hut the style lacks it borrows from the original style (the quarries, the stable)
            Entry e = entry(f);
            if (e != null && e.hut() != null && e.hut().structure(style, 1).startsWith(Hut.Type.FALLBACK_STYLE + "/")) all.add(f);
            else if (e != null && f.startsWith(FIELDS + "/")) all.add(f);
        }
        return List.copyOf(all);
    }

    /** A style's own structure files. */
    private static List<String> own(String style) {
        return FILES.computeIfAbsent(style, s -> {
            TreeSet<String> out = new TreeSet<>();
            // The save's own folder, which holds datapacks/ and generated/ (a world's folder is its dimension's in 26.3).
            Path world = Bukkit.getWorldContainer().toPath().resolve(Bukkit.getWorlds().getFirst().getName());
            scan(world.resolve("generated/minecp/structure").resolve(s), out);
            try (Stream<Path> packs = Files.list(world.resolve("datapacks"))) {
                for (Path pack : packs.toList()) {
                    if (Files.isDirectory(pack)) scan(pack.resolve("data/minecp/structure").resolve(s), out);
                    else if (pack.toString().endsWith(".zip"))
                        try (var zip = java.nio.file.FileSystems.newFileSystem(pack)) {
                            scan(zip.getPath("data/minecp/structure", s), out);
                        } catch (IOException ignored) {} // not a readable zip: left out
                }
            } catch (IOException ignored) {} // no data packs folder: nothing to list
            return List.copyOf(out);
        });
    }

    /** Adds the structures under {@code root} (paths inside it, without ".nbt"), if it exists. */
    private static void scan(Path root, TreeSet<String> out) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".nbt")).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.add(rel.substring(0, rel.length() - 4));
            });
        } catch (IOException ignored) {} // unreadable: left out
    }
}
