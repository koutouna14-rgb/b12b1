package dev.discordbridge;

import com.velocitypowered.api.proxy.Player;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

/**
 * Isolated in its own class so DiscordBridge still loads when LuckPerms is not installed on the proxy.
 * This class is only ever instantiated after we've confirmed LuckPerms is present.
 */
final class LuckPermsHook {

    private final LuckPerms luckPerms = LuckPermsProvider.get();

    /** Raw LuckPerms prefix (still contains &-codes / hex), or "" if the player has none. */
    String prefix(Player player) {
        String prefix = null;
        try {
            prefix = luckPerms.getPlayerAdapter(Player.class).getMetaData(player).getPrefix();
        } catch (Exception e) {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                prefix = user.getCachedData().getMetaData().getPrefix();
            }
        }
        return prefix == null ? "" : prefix;
    }
}
