package com.sakuraclient.integration.paper;

import com.sakuraclient.integration.api.SakuraPlayer;
import com.sakuraclient.integration.wire.Wire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** The handshake, the answers, and the moments a player's set can change. */
final class PaperListener implements Listener, PluginMessageListener {

    private final SakuraIntegrationPlugin plugin;

    PaperListener(SakuraIntegrationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        if (Wire.CHANNEL.equals(event.getChannel())) plugin.sendHello(event.getPlayer());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Wire.CHANNEL.equals(channel)) return;
        Wire.Message m;
        try {
            m = Wire.decode(message);
        } catch (Wire.WireException e) {
            return; // Not ours to fix: a broken or hostile client.
        }
        if (!(m instanceof Wire.ClientHello)) return;
        Wire.ClientHello hello = (Wire.ClientHello) m;
        // An answer to the network's hello is the proxy's, not ours.
        if (hello.scope != Wire.SCOPE_SERVER) return;
        SakuraPlayer joined = plugin.players().answered(player.getUniqueId(), hello);
        if (joined != null) {
            plugin.getServer().getPluginManager().callEvent(new SakuraPlayerJoinEvent(player, joined));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent event) {
        plugin.players().refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.players().left(event.getPlayer().getUniqueId());
    }
}
