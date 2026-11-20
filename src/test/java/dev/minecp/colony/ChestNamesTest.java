package dev.minecp.colony;

import dev.minecp.logistics.Rack;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resource pack script picks a placed chest's model by the name MineCP gives it (ChestNames): its CHEST_NAMES must
 * list every chest hut's title and the rack's name, or that model silently stops showing. Runs without a server.
 */
class ChestNamesTest {
    @Test
    void scriptListsEveryChestName() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.exists(dir.resolve("tools/build_resourcepack.py"))) dir = dir.getParent(); // tests run in build/test-run
        String script = Files.readString(dir.resolve("tools/build_resourcepack.py"));
        Matcher block = Pattern.compile("CHEST_NAMES = \\{(.*?)\\}", Pattern.DOTALL).matcher(script);
        if (!block.find()) throw new AssertionError("no CHEST_NAMES in the script");
        Set<String> listed = new TreeSet<>();
        Matcher key = Pattern.compile("\"([^\"]+)\":").matcher(block.group(1));
        while (key.find()) listed.add(key.group(1));

        Set<String> named = new TreeSet<>(Set.of(Rack.NAME));
        for (Hut.Type t : Hut.Type.values()) if (t.block == Material.CHEST) named.add(t.title);
        assertEquals(named, listed);
    }
}
