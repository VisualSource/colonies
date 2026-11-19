package dev.minecp.menu;

import dev.minecp.MineCP;
import dev.minecp.colony.Colony;
import dev.minecp.job.Crafter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Teaching a crafter a recipe (the mod's teach-recipe screen): a crafting table window opens; lay the recipe out and
 * click the result to teach it. Nothing is crafted or used up, and the items go back when the window closes.
 */
public final class Teaching implements Listener {
    private record Session(Colony colony, Crafter crafter) {}

    private final MineCP plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public Teaching(MineCP plugin) {
        this.plugin = plugin;
    }

    void start(Player p, Colony c, Crafter crafter) {
        p.openWorkbench(null, true);
        sessions.put(p.getUniqueId(), new Session(c, crafter));
        p.sendMessage(Component.text("Lay out the recipe, then click the result to teach it to the " + crafter.hut.type.worker.toLowerCase()
                + ". Nothing is used up.", NamedTextColor.GREEN));
    }

    /** Lowest priority: before the hut recipes' Build Tool handling, which skips cancelled crafts. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraft(CraftItemEvent e) {
        Session s = sessions.get(e.getWhoClicked().getUniqueId());
        if (s == null || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true); // teaching, not crafting
        if (!s.colony.jobs.contains(s.crafter)) { p.closeInventory(); return; } // the hut was removed meanwhile
        String problem = s.crafter.teach(e.getRecipe());
        if (problem != null) { p.sendMessage(Component.text(problem, NamedTextColor.RED)); return; }
        plugin.save(s.colony);
        p.sendMessage(Component.text("Taught: " + e.getRecipe().getResult().getAmount() + " × ", NamedTextColor.GREEN)
                .append(e.getRecipe().getResult().effectiveName()).append(Component.text(" (" + s.crafter.recipes.size() + " of "
                        + s.crafter.maxRecipes() + " recipes).")));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }
}
