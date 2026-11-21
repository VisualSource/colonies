package dev.minecp;

import dev.minecp.colony.Colony;
import dev.minecp.colony.ColonyStore;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * One {@code <owner uuid>.yml} per colony. A colony is read into YAML text on the main thread (it reads live game
 * state); one writer thread writes it to a temporary file and moves that over the old one, so a crash mid-write
 * never leaves half a file, and writes and deletes of a colony happen in the order they were asked for.
 */
final class Saves {
    private final File dir;
    private final Logger log;
    private final Set<UUID> unloaded = new HashSet<>(); // owners whose file was skipped: no new colony may overwrite it
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "Colonies saves"));

    Saves(File dir, Logger log) {
        this.dir = dir;
        this.log = log;
        dir.mkdirs();
    }

    /** Loads every colony file into {@code into}, by owner. A file that fails to load is left alone. */
    void loadAll(Map<UUID, Colony> into) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) for (File f : files) {
            UUID owner;
            try {
                owner = UUID.fromString(f.getName().substring(0, f.getName().length() - ".yml".length()));
            } catch (IllegalArgumentException e) {
                log.warning("Skipped " + f.getName() + ": the file name isn't a player UUID.");
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(f);
            } catch (IOException | InvalidConfigurationException e) {
                // Not loaded, so never saved over: the file stays as it is for someone to fix.
                log.severe("Could not load " + f.getName() + "; that colony is skipped and its file left as is: " + e.getMessage());
                unloaded.add(owner);
                continue;
            }
            Colony colony = ColonyStore.load(owner, yaml);
            if (colony == null) {
                log.warning("Colony of " + owner + " is in a world that is not loaded; skipped.");
                unloaded.add(owner);
            } else into.put(colony.owner, colony);
        }
    }

    /** Whether this owner has a colony file that couldn't be loaded. */
    boolean isUnloaded(UUID owner) {
        return unloaded.contains(owner);
    }

    /** Saves one colony: its state is read here, on the main thread; the file is written on the writer thread. */
    void save(Colony c) {
        YamlConfiguration yaml = new YamlConfiguration();
        ColonyStore.save(c, yaml);
        String text = yaml.saveToString();
        Path file = fileOf(c.owner);
        writer.execute(() -> write(file, text));
    }

    /** Removes a disbanded colony's file, after any save of it still waiting. */
    void delete(UUID owner) {
        Path file = fileOf(owner);
        writer.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.severe("Could not delete " + file.getFileName() + ": " + e.getMessage());
            }
        });
    }

    /** Runs the writes still queued, waiting up to 30 seconds. */
    void finish() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) log.severe("Colony saves didn't finish within 30 seconds.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path fileOf(UUID owner) {
        return dir.toPath().resolve(owner + ".yml");
    }

    private void write(Path file, String text) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.severe("Could not save " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
