package dev.discordbridge.backend;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.ChatColor;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Backend half of DiscordBridge. Velocity can't see deaths or advancements (they happen inside the
 * game server), so this plugin forwards them to the proxy over the plugin message channel
 * "discordbridge:events". The proxy decides which server announces what and posts to Discord.
 *
 * Wire format (DataOutputStream.writeUTF):
 *   death:        "death", deathMessage, killerName, killerUuid, weapon, killerInvisible ("true"/"false")
 *                 (killer* / weapon may be "")
 *   advancement:  "advancement", message
 *   chat:         "chat", message   (the FINAL text, after ChatSentry & co. censored / modified it;
 *                                    blocked (cancelled) messages are never sent)
 */
public final class BridgeBackend extends JavaPlugin implements Listener {

    static final String CHANNEL = "discordbridge:events";

    private boolean sendDeaths;
    private boolean sendAdvancements;
    private boolean sendChat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Forwarding deaths / kills / advancements to the proxy on " + CHANNEL);
        getLogger().info("CHAT FORWARDING " + (sendChat ? "ON" : "OFF") + " (v" + getDescription().getVersion()
                + "): Discord gets chat from this server AFTER ChatSentry/filters ran.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    private void loadSettings() {
        sendDeaths = getConfig().getBoolean("events.deaths", true);
        sendAdvancements = getConfig().getBoolean("events.advancements", true);
        sendChat = getConfig().getBoolean("events.chat", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bridgereload")) return false;
        reloadConfig();
        loadSettings();
        sender.sendMessage("DiscordBridge-Backend reloaded (deaths=" + sendDeaths
                + ", advancements=" + sendAdvancements
                + ", chat=" + sendChat + ").");
        return true;
    }

    // ------------------------------------------------------------------ events

    /**
     * Chat -> proxy -> Discord. This runs at MONITOR, i.e. AFTER chat filters such as ChatSentry have
     * censored (setMessage) or blocked (setCancelled) the message. The proxy used to read the chat
     * itself, but it sees the message before the backend server does, so Discord got the uncensored text.
     * ChatSentry listens to this same legacy event, so we see exactly what it left behind.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!sendChat) return;
        Player player = event.getPlayer();
        String message = ChatColor.stripColor(event.getMessage());
        if (message == null || message.trim().isEmpty()) return;
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[chat debug] after filters, sending to proxy: " + player.getName() + ": " + message);
        }
        // Plugin messages are sent from the main thread (this event is usually async)
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) send(player, "chat", message);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!sendDeaths) return;
        Player victim = event.getEntity();

        String message = plain(event.deathMessage());
        if (message.isEmpty()) {
            message = victim.getName() + " died";
        }

        String killerName = "";
        String killerUuid = "";
        String weapon = "";
        boolean killerInvisible = false;
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            killerName = killer.getName();
            killerUuid = killer.getUniqueId().toString();
            weapon = weaponName(killer.getInventory().getItemInMainHand());
            // checked right now, at the moment of the kill (the effect may expire later)
            killerInvisible = killer.hasPotionEffect(PotionEffectType.INVISIBILITY);
        }

        send(victim, "death", message, killerName, killerUuid, weapon, String.valueOf(killerInvisible));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!sendAdvancements) return;
        // Paper gives null when Minecraft would not announce it in chat (recipes, hidden advancements,
        // or the announceAdvancements gamerule being off) - so those are skipped automatically.
        Component component = event.message();
        if (component == null) return;
        String message = plain(component);
        if (message.isEmpty()) return;
        send(event.getPlayer(), "advancement", message);
    }

    // ------------------------------------------------------------------ helpers

    private void send(Player carrier, String type, String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(type);
            for (String field : fields) {
                out.writeUTF(field == null ? "" : field);
            }
            // Plugin messages travel through a player's connection; the carrier is always online here.
            carrier.sendPluginMessage(this, CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Could not send " + type + " event: " + e.getMessage());
        }
    }

    /** Component -> plain text. Paper's serializer also turns vanilla translation keys into English text. */
    private static String plain(Component component) {
        if (component == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(component).trim();
    }

    /** Custom item name if it has one, otherwise the item type ("DIAMOND_SWORD" -> "Diamond Sword"). */
    private static String weaponName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getType().isAir()) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String custom = plain(meta.displayName());
            if (!custom.isEmpty()) return custom;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : item.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
