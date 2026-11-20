package dev.minecp;

import dev.minecp.logistics.Rack;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Hut chests and storage racks are named for what they stand in for ("Builder's Hut"): the title of their inventory,
 * and what the optional resource pack's OptiFine / Entity Model Features chest models select on. A vanilla server
 * never sends a chest's name to clients, so this sends it, in a block entity update with the name only, whenever a
 * player gets the chunk.
 */
public final class ChestNames implements Listener {
    private final MineCP plugin;

    ChestNames(MineCP plugin) {
        this.plugin = plugin;
    }

    /** Names a chest (plain text: the pack's name rules match it exactly) and tells the players who see it. */
    public static void name(Block b, String name) {
        if (!(b.getState() instanceof Chest c)) return;
        c.customName(Component.text(name));
        c.update(true, false);
        for (Player p : b.getWorld().getPlayersSeeingChunk(b.getChunk())) send(p, b);
    }

    @EventHandler
    public void onChunk(PlayerChunkLoadEvent e) {
        for (BlockState s : e.getChunk().getTileEntities(false)) { // no snapshots: chest contents aren't copied
            if (!(s instanceof Chest c) || c.customName() == null) continue;
            Block b = s.getBlock();
            if (Rack.is(b) || plugin.colonyAt(b) != null) send(e.getPlayer(), b);
        }
    }

    /** The chest's custom name, and nothing else of it (not its contents), to one player. */
    private static void send(Player p, Block b) {
        var level = ((CraftWorld) b.getWorld()).getHandle();
        var be = level.getBlockEntity(new BlockPos(b.getX(), b.getY(), b.getZ()));
        if (be == null) return;
        ((CraftPlayer) p).getHandle().connection.send(ClientboundBlockEntityDataPacket.create(be, (entity, registries) -> {
            CompoundTag tag = new CompoundTag();
            Tag name = entity.saveCustomOnly(registries).get("CustomName");
            if (name != null) tag.put("CustomName", name);
            return tag;
        }));
    }
}
