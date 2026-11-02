package com.sakuraclient.integration.paper;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI: {@code %sakura_using%}, {@code %sakura_badge%} and
 * {@code %sakura_online%}. Loaded only when PlaceholderAPI is installed.
 */
final class SakuraExpansion extends PlaceholderExpansion {

    private final SakuraIntegrationPlugin plugin;

    SakuraExpansion(SakuraIntegrationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "sakura";
    }

    @Override
    public String getAuthor() {
        return "Sakura";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        boolean sakura = player != null && plugin.players().isSakura(player.getUniqueId());
        switch (params) {
            case "using":
                return sakura ? "true" : "false";
            case "badge":
                return sakura ? plugin.badge() : "";
            case "online":
                return Integer.toString(plugin.players().players().size());
            default:
                return null;
        }
    }
}
