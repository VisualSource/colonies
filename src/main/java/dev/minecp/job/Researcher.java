package dev.minecp.job;

import dev.minecp.citizen.Colonist;
import dev.minecp.colony.Colony;
import dev.minecp.colony.Hut;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The University's researcher (the mod's EntityAIWorkResearcher): it walks to a random bookshelf in its building and
 * studies there, once a minute (STUDY_DELAY 1200 ticks). The research itself moves on each colony tick while the
 * University has its researcher ({@link dev.minecp.research.Studies#colonyTick}), as in the mod.
 */
public final class Researcher extends Job {
    static final int STUDY_DELAY = 1200;
    private static final int SHELF_BELOW = 4, SHELF_ABOVE = 12; // ours: heights around the hut block searched (the footprint has none)

    private final List<Block> shelves = new ArrayList<>();
    private int shelvesAt = -1; // hut level they were found at
    private Block shelf;
    private int studying;

    private final Colony colony;

    public Researcher(Hut hut, Colony colony) {
        super(hut);
        this.colony = colony;
    }

    @Override
    public List<Component> info(Colony c) {
        var on = c.research.inProgress();
        return List.of(Component.text(hut.level == 0 ? "The researcher starts once level 1 is built."
                : on.isEmpty() ? "Nothing to study: start a research." : "Studying up to " + hut.level + " researches at a time (more with each level).",
                NamedTextColor.GRAY));
    }

    @Override
    public void tick(Colonist w) {
        if (hut.level < 1) {
            w.setStatus("Researcher: idle");
            w.wander(hut.block.getLocation().toCenterLocation());
            return;
        }
        if (w.isMoving() || --cooldown > 0) return;
        if (shelf == null) shelf = randomShelf();
        if (!w.isBeside(shelf) && studying < STUDY_DELAY / 20) { // the mod gives up walking after STUDY_DELAY and studies where it is
            w.setStatus("Researcher: working");
            w.goBeside(shelf);
            studying++;
            cooldown = 20;
            return;
        }
        w.setStatus("Researcher: studying");
        w.swing();
        shelf.getWorld().playSound(shelf.getLocation().toCenterLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.25f, 1.5f);
        shelf = null;
        studying = 0;
        cooldown = STUDY_DELAY;
    }

    /** A bookshelf inside its building, or the hut block if it has none (the mod's getRandomBookShelf). */
    private Block randomShelf() {
        if (shelvesAt != hut.level) {
            shelves.clear();
            int[] box = colony.footprint(hut); // minX, maxX+1, minZ, maxZ+1
            if (box != null) for (int x = box[0]; x < box[1]; x++)
                for (int y = hut.block.getY() - SHELF_BELOW; y <= hut.block.getY() + SHELF_ABOVE; y++)
                    for (int z = box[2]; z < box[3]; z++) {
                        Block b = hut.block.getWorld().getBlockAt(x, y, z);
                        if (b.getType() == Material.BOOKSHELF) shelves.add(b);
                    }
            shelvesAt = hut.level;
        }
        shelves.removeIf(b -> b.getType() != Material.BOOKSHELF);
        return shelves.isEmpty() ? hut.block : shelves.get(ThreadLocalRandom.current().nextInt(shelves.size()));
    }
}
