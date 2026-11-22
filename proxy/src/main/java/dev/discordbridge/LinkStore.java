package dev.discordbridge;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only copy of the Discord links kept by the separate AetherMC (linking) plugin.
 * Nothing is written here: the other plugin stays the only owner of the links, this class just
 * asks it for the list every few seconds, so both plugins stay in sync without being combined.
 */
final class LinkStore {

    private final ProxyServer proxy;
    private final Logger logger;
    private final String pluginId;

    private volatile Map<UUID, String> discordByUuid = Map.of();
    private volatile Map<String, UUID> uuidByDiscord = Map.of();
    private volatile Map<String, UUID> uuidByName = Map.of(); // lower-case minecraft name -> uuid

    private boolean warnedMissing;
    private boolean warnedBroken;

    LinkStore(ProxyServer proxy, Logger logger, String pluginId) {
        this.proxy = proxy;
        this.logger = logger;
        this.pluginId = pluginId;
    }

    /** Re-reads every link from the AetherMC plugin (runs on the scheduler, never on the Netty thread). */
    void refresh() {
        try {
            Object plugin = proxy.getPluginManager().getPlugin(pluginId)
                    .flatMap(PluginContainer::getInstance).orElse(null);
            if (plugin == null) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    logger.warn("Linking plugin '{}' is not loaded - @mention pings are disabled until it is.", pluginId);
                }
                return;
            }
            Object db = call(plugin, "database");
            if (db == null) return; // plugin still starting
            List<?> all = (List<?>) call(db, "all");
            if (all == null) return;

            Map<UUID, String> byUuid = new HashMap<>();
            Map<String, UUID> byDiscord = new HashMap<>();
            Map<String, UUID> byName = new HashMap<>();
            for (Object link : all) {
                UUID uuid = (UUID) call(link, "uuid");
                String discordId = (String) call(link, "discordId");
                String username = (String) call(link, "username");
                if (uuid == null || discordId == null) continue;
                byUuid.put(uuid, discordId);
                byDiscord.put(discordId, uuid);
                if (username != null) byName.put(username.toLowerCase(Locale.ROOT), uuid);
            }
            discordByUuid = byUuid;
            uuidByDiscord = byDiscord;
            uuidByName = byName;
            warnedMissing = false;
        } catch (Throwable t) {
            if (!warnedBroken) {
                warnedBroken = true;
                logger.warn("Could not read links from the '{}' plugin (is it version 2.0.0?): {}", pluginId, t.toString());
            }
        }
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    String discordId(UUID uuid) {
        return discordByUuid.get(uuid);
    }

    UUID uuid(String discordId) {
        return uuidByDiscord.get(discordId);
    }

    UUID uuidByName(String name) {
        return uuidByName.get(name.toLowerCase(Locale.ROOT));
    }
}
