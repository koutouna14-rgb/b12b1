package dev.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

@Plugin(
        id = "discordbridge",
        name = "DiscordBridge",
        version = "1.3.0",
        description = "Syncs chat between Discord and every server behind Velocity",
        authors = {"You"},
        dependencies = {@Dependency(id = "luckperms", optional = true), @Dependency(id = "aethermc", optional = true)}
)
public class DiscordBridge {

    private static final Color GREEN = new Color(0x57F287);
    private static final Color RED = new Color(0xED4245);
    private static final Color BLUE = new Color(0x5865F2);
    private static final Color DEATH = new Color(0x992D22);
    private static final Color KILL = new Color(0xE67E22);
    private static final Color ADVANCEMENT = new Color(0xF1C40F);

    /** Backend servers (DiscordBridge-Backend jar) send deaths / kills / advancements on this channel. */
    private static final MinecraftChannelIdentifier EVENT_CHANNEL =
            MinecraftChannelIdentifier.create("discordbridge", "events");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    // &x&r&r&g&g&b&b (what LuckPerms/Spigot sometimes store) -> &#rrggbb
    private static final Pattern X_HEX = Pattern.compile("(?i)[&\u00A7]x(?:[&\u00A7][0-9a-f]){6}");
    private static final Pattern MM_TAG = Pattern.compile("<[/!#a-zA-Z][^<>]*>");
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    // "!ip", "what is the ip", "whats the server ip" ...
    private static final String ASK = "(?i)\\b(?:what|whats|wat|wats|wut|wuts)\\s*(?:is\\s*|[s]\\s+|['\u2019]s\\s*)?"
            + "(?:the\\s+|ur\\s+|your\\s+|this\\s+|da\\s+)?(?:server\\s+|mc\\s+|minecraft\\s+)?";
    private static final Pattern IP_ASK = Pattern.compile(ASK + "(?:ip|address)\\b");
    private static final Pattern WEB_ASK = Pattern.compile(ASK + "(?:website|web\\s*site|webpage|web|site)\\b");
    private static final Pattern IP_CMD = Pattern.compile("(?i)^\\s*!(?:ip|address|serverip)\\s*$");
    private static final Pattern WEB_CMD = Pattern.compile("(?i)^\\s*!(?:website|web|site|webpage)\\s*$");
    private static final Pattern WEB_BARE = Pattern.compile("(?i)^\\s*(?:the\\s+|server\\s+)?(?:website|web\\s*site|webpage)\\s*\\??\\s*$");
    private static final Pattern IP_BARE = Pattern.compile("(?i)^\\s*(?:server\\s+)?ip\\s*\\??\\s*$");

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient http = HttpClient.newHttpClient();

    private final Properties config = new Properties();
    private JDA jda;
    private String channelId;
    private String webhookUrl;
    private String avatarTemplate;
    private boolean relayBetweenServers;
    /** true = chat reaches Discord via the backend jar (after ChatSentry etc.), false = read at the proxy (uncensored). */
    private volatile boolean chatFromBackend = true;
    private List<String> chatIgnorePrefixes = List.of("#");
    /** Players who switched justTeams team chat on (backend tells us). Their chat stays off Discord. */
    private final Set<java.util.UUID> teamChatToggled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean announceJoinLeave;
    private String lobbyServer;
    private String activeToken = "";

    private StatusManager status;
    private ScheduledTask topicTask;
    private ScheduledTask linkTask;

    private LinkStore links;
    private EmojiMapper emojis;

    private LuckPermsHook luckPerms;
    private boolean luckPermsWarned;

    private final long startMillis = System.currentTimeMillis();
    private final Set<UUID> uniquePlayers = ConcurrentHashMap.newKeySet();
    private Path uniqueFile;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    @Inject
    public DiscordBridge(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // ------------------------------------------------------------------ lifecycle

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        if (!loadConfig()) return;

        String token = config.getProperty("token", "").trim();
        activeToken = token;
        channelId = config.getProperty("channel-id", "").trim();
        applyConfig();
        logger.info("DiscordBridge chat source: {}", chatFromBackend
                ? "BACKEND (final text after ChatSentry; proxy does NOT post raw chat)"
                : "PROXY (raw text, uncensored - chat-from-backend=false)");

        loadUniquePlayers();

        proxy.getChannelRegistrar().register(EVENT_CHANNEL);
        CommandMeta bridgeMeta = proxy.getCommandManager().metaBuilder("bridge")
                .aliases("discordbridge")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(bridgeMeta, new BridgeCommand());

        CommandMeta hubMeta = proxy.getCommandManager().metaBuilder("hub")
                .aliases("lobby", "l")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(hubMeta, new HubCommand());

        // Discord links come from the separate AetherMC (linking) plugin - we only read them, never write
        links = new LinkStore(proxy, logger, cfg("link-plugin-id", "aethermc"));
        int linkRefresh = Math.max(5, parseInt(cfg("link-refresh-seconds", "30"), 30));
        linkTask = proxy.getScheduler().buildTask(this, links::refresh)
                .delay(5, TimeUnit.SECONDS)
                .repeat(linkRefresh, TimeUnit.SECONDS)
                .schedule();

        if (token.isEmpty() || token.equals("YOUR_BOT_TOKEN") || channelId.isEmpty()) {
            logger.error("Set 'token' and 'channel-id' in {} and restart the proxy.",
                    dataDirectory.resolve("config.properties"));
            return;
        }

        try {
            jda = JDABuilder.createLight(token,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new DiscordListener())
                    .build();
            jda.awaitReady();
            logger.info("Discord bot connected as {}", jda.getSelfUser().getName());
            sendEmbed("Network is online", null, GREEN, null, null);
            startTopicUpdater();
            status = new StatusManager(this, proxy, logger, dataDirectory, () -> jda, this::cfg, this::labelFor);
            status.start();
        } catch (Exception e) {
            logger.error("Failed to start Discord bot", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (jda == null) return;
        if (status != null) status.shutdown();
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) {
                channel.sendMessageEmbeds(new EmbedBuilder()
                        .setColor(RED).setAuthor("Network is offline").build()).complete();
            }
        } catch (Exception ignored) {
        }
        jda.shutdown();
    }

    // ------------------------------------------------------------------ Minecraft -> Discord / other servers

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        recordUniquePlayer(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;

        Player player = event.getPlayer();
        String message = event.getMessage();

        // "!ip", "!website", "what is the ip" -> answer the player (always handled here)
        if (!isPrivateChat(player, message)) {
            String request = Boolean.parseBoolean(cfg("info-ingame", "false")) ? detectInfoRequest(message) : null;
            if (request != null && !onCooldown("mc:" + player.getUniqueId())) {
                proxy.getScheduler().buildTask(this, () -> player.sendMessage(infoComponent(request)))
                        .delay(150, TimeUnit.MILLISECONDS)
                        .schedule();
            }
        }

        // The proxy sees chat BEFORE the backend server, so chat filters (ChatSentry ...) haven't run yet.
        // By default the backend jar reports the final message instead (see onPluginMessage "chat").
        if (chatFromBackend) {
            if (Boolean.parseBoolean(cfg("chat-debug", "false"))) {
                logger.info("[chat debug] proxy saw raw '{}' from {} - NOT posting, waiting for backend", message, player.getUsername());
            }
            return;
        }

        relayChat(player, currentServerName(player), message);
    }

    /** Messages starting with an ignored prefix (e.g. "#" = justTeams team chat) or team chat toggled on stay private. */
    private boolean isPrivateChat(Player player, String message) {
        for (String prefix : chatIgnorePrefixes) {
            if (message.startsWith(prefix)) return true;
        }
        return teamChatToggled.contains(player.getUniqueId());
    }

    /** Sends one chat message to Discord and to the players on the other servers. */
    private void relayChat(Player player, String server, String message) {
        // private messages are not sent to Discord and not relayed to other servers
        if (isPrivateChat(player, message)) return;

        String label = labelFor(server);

        // Discord: webhook = shows rank + name + skin head as the sender
        String senderName = discordSenderName(player, label);
        if (!webhookUrl.isEmpty()) {
            List<String> pingIds = new ArrayList<>();
            String discordText = withDiscordPings(player, sanitizeForDiscord(message), pingIds);
            sendWebhook(senderName, avatarFor(player), discordText, pingIds);
        } else {
            sendEmbed(senderName, avatarFor(player), BLUE, message, null);
        }

        // Other Minecraft servers (the sender's own server already shows the message)
        if (relayBetweenServers) {
            Component out = render(cfg("format.chat", "&a[{server}] &r{prefix}&f{name} &7\u00BB &f{message}"),
                    player, label, null, null, message);
            for (Player other : proxy.getAllPlayers()) {
                if (!currentServerName(other).equals(server)) {
                    other.sendMessage(out);
                }
            }
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        // new server: forget the old toggle state, the backend re-sends the real one right after join
        teamChatToggled.remove(event.getPlayer().getUniqueId());
        if (!announceJoinLeave) return;

        Player player = event.getPlayer();
        String to = labelFor(event.getServer().getServerInfo().getName());
        String display = discordDisplayName(player);

        if (event.getPreviousServer().isEmpty()) {
            sendEmbed(display + " joined the server", avatarFor(player), GREEN, null, to);
            broadcastMc(render(cfg("format.join", "&a[{server}] &e+ &r{prefix}&f{name} &ejoined the network"),
                    player, to, null, null, null));
        } else {
            String from = labelFor(event.getPreviousServer().get().getServerInfo().getName());
            sendEmbed(display + " moved from " + from + " to " + to, avatarFor(player), BLUE, null, null);
            broadcastMc(render(cfg("format.move", "&a[{server}] &r{prefix}&f{name} &emoved &7{from} &e\u2192 &7{to}"),
                    player, to, from, to, null));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        teamChatToggled.remove(event.getPlayer().getUniqueId());
        if (!announceJoinLeave) return;
        if (event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) return;

        Player player = event.getPlayer();
        String serverName = currentServerName(player);
        String label = serverName.equals("none") ? "Network" : labelFor(serverName);

        sendEmbed(discordDisplayName(player) + " left the server", avatarFor(player), RED, null, null);
        broadcastMc(render(cfg("format.leave", "&c[{server}] &e- &r{prefix}&f{name} &eleft the network"),
                player, label, null, null, null));
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            // ignores bots + webhooks, so our own relayed messages never loop back
            if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
            if (!event.getChannel().getId().equals(channelId)) {
                // other Discord channels: only answer playerlist / ip / website (never relayed to Minecraft)
                if (infoChannelAllowed(event.getChannel().getId())) {
                    String text = event.getMessage().getContentDisplay();
                    if (Boolean.parseBoolean(cfg("playerlist-enabled", "true")) && isPlayerListRequest(text)) {
                        handlePlayerList(event);
                        return;
                    }
                    String request = detectInfoRequest(text);
                    if (request != null && !onCooldown("dc:" + event.getAuthor().getId())) {
                        event.getChannel().sendMessage(plain(infoComponent(request)))
                                .setAllowedMentions(Collections.emptyList())
                                .queue(null, err -> logger.warn("Discord reply failed: {}", err.getMessage()));
                    }
                }
                return;
            }

            Message msg = event.getMessage();
            String name = emojis.convert(event.getMember() != null
                    ? event.getMember().getEffectiveName()
                    : event.getAuthor().getName());

            // strip legacy colour codes so people can't fake formatting,
            // and turn emoji (unicode + custom) into readable text like :joy: / XD
            String clean = emojis.convert(
                    msg.getContentDisplay().replaceAll("[§&][0-9a-fk-orA-FK-OR]", ""));

            // "playerlist", "Playerlist", "pleyerlist", "player list" ... -> pretty player list,
            // then the typed message is deleted after a few seconds (needs Manage Messages)
            if (Boolean.parseBoolean(cfg("playerlist-enabled", "true")) && isPlayerListRequest(clean)) {
                handlePlayerList(event);
                return;
            }

            // replies are shown on the SAME line: "[Discord] Mirai ↪ xAetherealx » message" (hover = quoted text)
            ReplyInfo replyInfo = Boolean.parseBoolean(cfg("reply-enabled", "true"))
                    ? replyInfo(msg.getReferencedMessage()) : null;
            boolean inlineReply = "inline".equalsIgnoreCase(cfg("reply-style", "line"));
            Component replyTag = (replyInfo != null && inlineReply) ? replyTag(replyInfo) : null;

            TextComponent.Builder out = Component.text()
                    .append(Component.text("[Discord] ", NamedTextColor.BLUE))
                    .append(discordRoleTag(event.getMember()))
                    .append(Component.text(name, NamedTextColor.WHITE));
            if (replyTag != null) out.append(replyTag);
            out.append(Component.text(" \u00BB ", NamedTextColor.DARK_GRAY));

            // the message part is kept separate so a mention highlight can never hit the rank / name prefix
            TextComponent.Builder body = Component.text();
            boolean hasContent = false;
            if (!clean.isBlank()) {
                body.append(linkify(clean));
                hasContent = true;
            }

            // stickers can't be shown in Minecraft either -> [Sticker: name]
            if (Boolean.parseBoolean(cfg("emoji-stickers", "true"))) {
                for (var sticker : msg.getStickers()) {
                    body.append(Component.text(clean.isBlank() && !hasContent ? "" : " "))
                            .append(Component.text("[Sticker: " + emojis.convert(sticker.getName()) + "]",
                                    NamedTextColor.LIGHT_PURPLE));
                    hasContent = true;
                }
            }

            // attachments become clickable links (Minecraft chat can't render images)
            boolean relayImages = Boolean.parseBoolean(cfg("discord-relay-images", "true"));
            boolean relayFiles = Boolean.parseBoolean(cfg("discord-relay-files", "true"));
            boolean preview = Boolean.parseBoolean(cfg("discord-image-preview", "true"));
            List<String> previewUrls = new ArrayList<>();
            for (Message.Attachment att : msg.getAttachments()) {
                if (att.isImage() ? !relayImages : !relayFiles) continue; // blocked: never reaches Minecraft
                boolean gif = isGif(att);
                String tag = att.isImage() ? (gif ? "[GIF] " : "[Image] ") : "[File: " + att.getFileName() + "] ";
                body.append(Component.text(" "))
                        .append(Component.text(tag, NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(att.getUrl()))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Click to open " + att.getFileName(), NamedTextColor.GRAY))));
                hasContent = true;
                // pixel-art preview for still images only (GIFs just get the clickable link)
                if (preview && att.isImage() && !gif && att.getSize() <= 8L * 1024 * 1024) {
                    previewUrls.add(att.getUrl());
                }
            }

            if (!hasContent) return;

            // classic style: "↪ replying to Name: text" on its own line above the message
            if (replyInfo != null && !inlineReply) broadcastMc(replyLine(replyInfo));

            // @mentions of linked players: highlighted line + sound for them, normal line for everyone else
            Component prefixPart = out.build();
            Component bodyPart = body.build();
            Component line = prefixPart.append(bodyPart);
            Map<Player, Ping> pinged = findMentionedPlayers(msg, clean, event.getAuthor().getId());
            if (pinged.isEmpty()) {
                broadcastMc(line);
            } else {
                for (Player p : proxy.getAllPlayers()) {
                    Ping ping = pinged.get(p);
                    if (ping == null) {
                        p.sendMessage(line);
                    } else {
                        p.sendMessage(prefixPart.append(highlightMention(bodyPart, ping)));
                        pingPlayer(p, name);
                    }
                }
            }
            // only the first image of a message, and only if the sender / network isn't on cooldown
            if (!previewUrls.isEmpty() && previewAllowed(event.getAuthor().getId())) {
                sendImagePreview(previewUrls.get(0));
            }

            // "!ip", "!website", "what is the ip" typed in Discord -> answer in Discord
            String request = detectInfoRequest(clean);
            if (request != null && !onCooldown("dc:" + event.getAuthor().getId())) {
                event.getChannel().sendMessage(plain(infoComponent(request)))
                        .setAllowedMentions(Collections.emptyList())
                        .queue(null, err -> logger.warn("Discord reply failed: {}", err.getMessage()));
            }
        }
    }

    /** Highest Discord role as a coloured tag, e.g. "OWNER " (empty if disabled / no role). */
    private Component discordRoleTag(Member member) {
        if (member == null || !Boolean.parseBoolean(cfg("discord-show-role", "true"))) return Component.empty();
        Role top = member.getRoles().stream()
                .filter(r -> !r.isPublicRole())
                .max(Comparator.comparingInt(Role::getPosition))
                .orElse(null);
        if (top == null) return Component.empty();

        String text = top.getName();
        if (Boolean.parseBoolean(cfg("discord-role-uppercase", "true"))) {
            text = text.toUpperCase(Locale.ROOT);
        }
        Color awt = top.getColor();
        TextColor color = awt == null ? NamedTextColor.WHITE : TextColor.color(awt.getRGB() & 0xFFFFFF);
        return Component.text(text + " ", color);
    }

    /** Turns URLs in text into clickable links. */
    private Component linkify(String text) {
        TextComponent.Builder b = Component.text();
        Matcher m = URL.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                b.append(Component.text(text.substring(last, m.start()), NamedTextColor.GRAY));
            }
            String url = m.group();
            b.append(Component.text(url, NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open", NamedTextColor.GRAY))));
            last = m.end();
        }
        if (last < text.length()) {
            b.append(Component.text(text.substring(last), NamedTextColor.GRAY));
        }
        return b.build();
    }


    // ------------------------------------------------------------------ playerlist (Discord)

    private static final String[] PL_WHOLE = {
            "playerlist", "playerslist", "plist", "onlineplayers", "playersonline",
            "whosonline", "whoisonline", "whoonline", "listplayers", "playerlst"
    };
    private static final String[] PL_TOKEN = {"playerlist", "playerslist", "plist"};

    /** True for playerlist / Playerlist / pleyerlist / player list / !playerlist / who's online ... */
    private boolean isPlayerListRequest(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty() || lower.length() > 40) return false;

        String compact = lower.replaceAll("[^a-z]", "");
        if (compact.isEmpty()) return false;
        for (String w : PL_WHOLE) {
            if (closeTo(compact, w)) return true;
        }
        // short sentences like "show playerlist pls"
        String[] tokens = lower.split("[^a-z]+");
        if (tokens.length <= 4) {
            for (String t : tokens) {
                if (t.isEmpty()) continue;
                for (String w : PL_TOKEN) {
                    if (closeTo(t, w)) return true;
                }
            }
        }
        return false;
    }

    private static boolean closeTo(String a, String b) {
        int allowed = b.length() >= 8 ? 2 : 1;
        if (Math.abs(a.length() - b.length()) > allowed) return false;
        return levenshtein(a, b) <= allowed;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private void handlePlayerList(MessageReceivedEvent event) {
        int deleteAfter = parseInt(cfg("playerlist-delete-seconds", "5"), 5);
        int deleteReplyAfter = parseInt(cfg("playerlist-delete-reply-seconds", "0"), 0);

        // delete what the user typed
        if (deleteAfter >= 0) {
            event.getMessage().delete().queueAfter(Math.max(deleteAfter, 0), TimeUnit.SECONDS, null,
                    err -> logger.warn("Could not delete the playerlist message (bot needs Manage Messages): {}",
                            err.getMessage()));
        }

        if (onCooldown("pl:" + event.getAuthor().getId())) return;

        event.getChannel().sendMessageEmbeds(buildPlayerListEmbed())
                .setAllowedMentions(Collections.emptyList())
                .queue(sent -> {
                    if (deleteReplyAfter > 0) {
                        sent.delete().queueAfter(deleteReplyAfter, TimeUnit.SECONDS, null, err -> { });
                    }
                }, err -> logger.warn("Could not send the player list: {}", err.getMessage()));
    }

    private MessageEmbed buildPlayerListEmbed() {
        int configuredMax = parseInt(cfg("topic-max-players", "-1"), -1);
        int max = configuredMax > 0 ? configuredMax : proxy.getConfiguration().getShowMaxPlayers();
        int total = proxy.getPlayerCount();

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(parseColor(cfg("playerlist-color", "9B59F5")));
        eb.setTitle(cfg("playerlist-title", "\u2726 AetherMC \u2022 Online Players"));
        eb.setDescription("**Total online:** `" + total + " / " + max + "` "
                + (total == 1 ? "player" : "players") + " across the network\n\u200B");

        String icon = cfg("playerlist-icon-url", "");
        if (icon.isEmpty() && jda != null) icon = jda.getSelfUser().getEffectiveAvatarUrl();
        if (!icon.isEmpty()) eb.setThumbnail(icon);

        for (RegisteredServer rs : playerListServers()) {
            String id = rs.getServerInfo().getName();
            List<Player> players = new ArrayList<>(rs.getPlayersConnected());
            players.sort(Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT)));

            String emoji = cfg("playerlist.emoji." + id, defaultEmoji(id));
            String title = emoji + " " + labelFor(id) + " \u2014 " + players.size()
                    + (players.size() == 1 ? " player" : " players");

            StringBuilder body = new StringBuilder();
            if (players.isEmpty()) {
                body.append("*Nobody here right now*");
            } else {
                int shown = 0;
                for (Player p : players) {
                    String rank = plain(parseRich(prefixRaw(p)));
                    String line = "\u25B8 `" + (rank.isEmpty() ? "" : rank + " ") + p.getUsername() + "`\n";
                    if (body.length() + line.length() > 950) {
                        body.append("*...and ").append(players.size() - shown).append(" more*");
                        break;
                    }
                    body.append(line);
                    shown++;
                }
            }
            eb.addField(title, body.toString().stripTrailing() + "\n\u200B", false);
        }

        eb.setFooter(cfg("server-ip", "play.aethermc.club") + " \u2022 AetherMC");
        eb.setTimestamp(Instant.now());
        return eb.build();
    }

    /** Servers shown in the list: `playerlist-servers` (in that order), or every server, lobby first. */
    private List<RegisteredServer> playerListServers() {
        List<RegisteredServer> out = new ArrayList<>();
        String configured = cfg("playerlist-servers", "");
        if (!configured.isEmpty()) {
            for (String name : configured.split(",")) {
                proxy.getServer(name.trim()).ifPresent(out::add);
            }
            return out;
        }
        Collection<RegisteredServer> all = proxy.getAllServers();
        out.addAll(all);
        out.sort(Comparator
                .comparing((RegisteredServer r) -> !r.getServerInfo().getName().equalsIgnoreCase(lobbyServer))
                .thenComparing(r -> r.getServerInfo().getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String defaultEmoji(String serverId) {
        String n = serverId.toLowerCase(Locale.ROOT);
        if (n.contains("lobby") || n.contains("hub")) return "\uD83C\uDFE0";
        if (n.contains("smp") || n.contains("survival")) return "\uD83C\uDF3F";
        if (n.contains("event") || n.contains("ffa") || n.contains("pvp")) return "\u2694\uFE0F";
        return "\uD83C\uDFAE";
    }

    private static Color parseColor(String hex) {
        try {
            return new Color(Integer.parseInt(hex.replace("#", "").trim(), 16));
        } catch (NumberFormatException e) {
            return new Color(0x9B59F5);
        }
    }


    // ------------------------------------------------------------------ Discord image -> Minecraft pixel art

    private final Map<String, Long> previewCooldowns = new ConcurrentHashMap<>();
    private long lastPreviewMillis;

    /** Anti-spam: per-user and network-wide cooldown for pixel-art previews (the [Image] link is always shown). */
    private synchronized boolean previewAllowed(String userId) {
        long now = System.currentTimeMillis();
        long perUser = Math.max(0, parseInt(cfg("discord-image-preview-cooldown-seconds", "30"), 30)) * 1000L;
        long global = Math.max(0, parseInt(cfg("discord-image-preview-global-cooldown-seconds", "5"), 5)) * 1000L;

        if (now - lastPreviewMillis < global) return false;
        Long last = previewCooldowns.get(userId);
        if (last != null && now - last < perUser) return false;

        if (previewCooldowns.size() > 200) {
            previewCooldowns.values().removeIf(t -> now - t > 3_600_000L);
        }
        previewCooldowns.put(userId, now);
        lastPreviewMillis = now;
        return true;
    }

    private static boolean isGif(Message.Attachment att) {
        String ext = att.getFileExtension();
        String type = att.getContentType();
        return "gif".equalsIgnoreCase(ext) || (type != null && type.toLowerCase(Locale.ROOT).contains("gif"));
    }

    /** Downloads the image off-thread, turns it into coloured block characters and sends it to every player. */
    private void sendImagePreview(String url) {
        proxy.getScheduler().buildTask(this, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "DiscordBridge")
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() >= 300) return;
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
                if (img == null) return; // format Java can't read (e.g. webp) - the link still works
                for (Component line : pixelArt(img)) broadcastMc(line);
            } catch (Throwable t) {
                logger.warn("Could not build image preview: {}", t.toString());
            }
        }).schedule();
    }

    private List<Component> pixelArt(BufferedImage img) {
        // crop away transparent margins first, so the picture has no empty frame around it
        int[] box = opaqueBounds(img);
        int ox = box[0], oy = box[1], w = box[2], h = box[3];

        int cols = Math.max(8, Math.min(96, parseInt(cfg("discord-image-preview-width", "48"), 48)));
        int maxRows = Math.max(4, Math.min(48, parseInt(cfg("discord-image-preview-max-rows", "32"), 32)));
        double ratio = 1.0; // height of one block relative to its width (the block cells look ~square in game)
        try {
            ratio = Math.max(0.3, Math.min(3.0, Double.parseDouble(cfg("discord-image-preview-char-ratio", "1.0"))));
        } catch (NumberFormatException ignored) {
        }
        int rows = Math.max(1, (int) Math.round((double) h / w * cols * ratio));
        if (rows > maxRows) {
            rows = maxRows;
            cols = Math.max(1, (int) Math.round(rows * (double) w / (h * ratio)));
        }
        // transparent cells inside the picture become spaces (about this many spaces = one block wide)
        int blankWidth = Math.max(1, Math.min(4, parseInt(cfg("discord-image-preview-blank-width", "2"), 2)));

        List<Component> lines = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            TextComponent.Builder line = Component.text();
            StringBuilder run = new StringBuilder();
            int runColor = -2; // -2 = nothing yet, -1 = transparent
            int y0 = oy + r * h / rows;
            int y1 = Math.min(oy + h, Math.max(y0 + 1, oy + (r + 1) * h / rows));
            for (int c = 0; c < cols; c++) {
                int x0 = ox + c * w / cols;
                int x1 = Math.min(ox + w, Math.max(x0 + 1, ox + (c + 1) * w / cols));
                int color = averageColor(img, x0, y0, x1, y1);
                if (color != -1) color &= 0xF8F8F8;
                if (color != runColor && run.length() > 0) {
                    appendRun(line, run.toString(), runColor);
                    run.setLength(0);
                }
                runColor = color;
                if (color == -1) {
                    for (int i = 0; i < blankWidth; i++) run.append(' ');
                } else {
                    run.append('\u2588');
                }
            }
            if (run.length() > 0) appendRun(line, run.toString(), runColor);
            lines.add(line.build());
        }
        return lines;
    }

    private static void appendRun(TextComponent.Builder line, String text, int color) {
        line.append(color == -1 ? Component.text(text) : Component.text(text, TextColor.color(color)));
    }

    /** {x, y, width, height} of the visible (non-transparent) part of the image. */
    private static int[] opaqueBounds(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (!img.getColorModel().hasAlpha()) return new int[]{0, 0, w, h};
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) >= 32) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return new int[]{0, 0, w, h}; // fully transparent: leave as is
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** Average RGB of a block (weighted by opacity), or -1 if the block is mostly transparent. */
    private static int averageColor(BufferedImage img, int x0, int y0, int x1, int y1) {
        long rs = 0, gs = 0, bs = 0, as = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24;
                rs += (long) ((argb >> 16) & 0xFF) * a;
                gs += (long) ((argb >> 8) & 0xFF) * a;
                bs += (long) (argb & 0xFF) * a;
                as += a;
                n++;
            }
        }
        if (n == 0 || as == 0) return -1;
        if (as * 100 / ((long) n * 255) < 30) return -1; // under 30% opaque -> empty
        return ((int) (rs / as) << 16) | ((int) (gs / as) << 8) | (int) (bs / as);
    }


    // ------------------------------------------------------------------ account linking, mention pings, replies

    private static final Pattern AT_NAME = Pattern.compile("@([A-Za-z0-9_]{3,16})");
    /** A Minecraft-style name as a whole word, with an optional leading "@" (group 1 = "@", group 2 = name). */
    private static final Pattern ANY_NAME = Pattern.compile("(?<![A-Za-z0-9_])(@)?([A-Za-z0-9_]{3,16})(?![A-Za-z0-9_])");
    /** One pinged player: the names to highlight in the message and the colour to highlight them with. */
    private static final class Ping {
        final List<String> names = new ArrayList<>();
        TextColor color;
    }

    /**
     * Discord -> Minecraft pings. A linked player is pinged when someone @mentions their Discord account,
     * or types their Minecraft name - with or without the "@" (needs a link when mention-require-link=true).
     */
    private Map<Player, Ping> findMentionedPlayers(Message msg, String clean, String authorId) {
        Map<Player, Ping> found = new LinkedHashMap<>();
        if (links == null || !Boolean.parseBoolean(cfg("mention-enabled", "true"))) return found;
        boolean requireLink = Boolean.parseBoolean(cfg("mention-require-link", "true"));
        boolean allowSelf = Boolean.parseBoolean(cfg("mention-allow-self", "false"));
        boolean plain = Boolean.parseBoolean(cfg("mention-without-at", "true"));
        int minPlain = 4;
        try {
            minPlain = Integer.parseInt(cfg("mention-plain-min-length", "4"));
        } catch (NumberFormatException ignored) {
            // keep default
        }

        // real Discord @mentions of a linked account
        for (User u : msg.getMentions().getUsers()) {
            if (!allowSelf && u.getId().equals(authorId)) continue;
            UUID uuid = links.uuid(u.getId());
            if (uuid == null) continue;
            Player p = proxy.getPlayer(uuid).orElse(null);
            if (p == null) continue;
            Ping ping = found.computeIfAbsent(p, k -> new Ping());
            ping.names.add(p.getUsername());
            String display = u.getName();
            Member known = null;
            for (Member m : msg.getMentions().getMembers()) {
                if (m.getId().equals(u.getId())) {
                    display = m.getEffectiveName();
                    known = m;
                }
            }
            ping.names.add(emojis.convert(display));
            if (ping.color == null) ping.color = discordColor(msg, u.getId(), known);
        }

        // typed Minecraft names: "@xAetherealx", and (if enabled) plain "xaetherealx" in any letter case
        for (Player p : proxy.getAllPlayers()) {
            String uname = p.getUsername();
            boolean allowPlain = plain && uname.length() >= minPlain;
            if (!nameTyped(clean, uname, allowPlain)) continue;
            String linked = links.discordId(p.getUniqueId());
            if (requireLink && linked == null) continue;
            if (!allowSelf && linked != null && linked.equals(authorId)) continue;
            Ping ping = found.computeIfAbsent(p, k -> new Ping());
            if (!ping.names.contains(uname)) ping.names.add(uname);
            if (ping.color == null && linked != null) ping.color = discordColor(msg, linked, null);
        }

        if (!found.isEmpty() && onCooldown("mention:dc:" + authorId)) found.clear();
        return found;
    }

    /** True if {@code name} appears as a whole word (any case); a leading "@" is optional only when allowPlain. */
    private static boolean nameTyped(String text, String name, boolean allowPlain) {
        Matcher m = Pattern.compile("(?i)(?<![A-Za-z0-9_])(@)?" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text);
        while (m.find()) {
            if (m.group(1) != null || allowPlain) return true;
        }
        return false;
    }

    /**
     * The member's Discord role colour (the colour Discord shows their name in), or null if they have
     * none / can't be found. Very dark colours are lightened so they stay readable on Minecraft's dark chat.
     */
    private TextColor discordColor(Message msg, String discordId, Member known) {
        if (!Boolean.parseBoolean(cfg("mention-use-discord-color", "true"))) return null;
        try {
            Member m = known;
            if (m == null && msg.isFromGuild()) {
                m = msg.getGuild().getMemberById(discordId);
                if (m == null) { // not cached (no member intent) -> one quick lookup
                    m = msg.getGuild().retrieveMemberById(discordId).submit().get(2, TimeUnit.SECONDS);
                }
            }
            if (m == null) return null;
            Color c = m.getColor();
            return c == null ? null : readable(c);
        } catch (Exception e) {
            return null;
        }
    }

    /** Makes the mention bold (in the member's Discord colour, yellow if unknown) in the pinged player's copy. */
    private Component highlightMention(Component body, Ping ping) {
        StringBuilder rx = new StringBuilder();
        for (String n : ping.names) {
            if (n == null || n.isBlank()) continue;
            if (rx.length() > 0) rx.append('|');
            rx.append(Pattern.quote(n));
        }
        if (rx.length() == 0) return body;
        TextColor color = ping.color != null ? ping.color : NamedTextColor.YELLOW;
        return body.replaceText(TextReplacementConfig.builder()
                .match(Pattern.compile("(?i)(?<![A-Za-z0-9_])@?(?:" + rx + ")(?![A-Za-z0-9_])"))
                .replacement((result, builder) -> builder.color(color).decorate(TextDecoration.BOLD))
                .build());
    }

    private void pingPlayer(Player player, String from) {
        try {
            Key key = Key.key(cfg("mention-sound", "minecraft:entity.experience_orb.pickup"));
            player.playSound(Sound.sound(key, Sound.Source.MASTER, 1f, 1.2f));
        } catch (Throwable t) {
            logger.warn("Could not play mention sound: {}", t.toString());
        }
        player.sendActionBar(Component.text("\u2605 " + from + " mentioned you", NamedTextColor.YELLOW));
    }

    /**
     * Defence in depth: game chat can never contain a working @everyone / @here / role / user mention.
     * (The webhook also sends allowed_mentions with no parse types, so Discord ignores them anyway.)
     */
    private static String sanitizeForDiscord(String text) {
        return text
                .replaceAll("(?i)@(everyone|here)", "@\u200B$1")
                .replace("<@", "<\u200B@")
                .replace("<#", "<\u200B#");
    }

    /** Minecraft -> Discord pings: "@Name" of a linked player becomes a real Discord mention of their account. */
    private String withDiscordPings(Player sender, String message, List<String> ids) {
        if (links == null || !Boolean.parseBoolean(cfg("mention-enabled", "true"))) return message;
        boolean plain = Boolean.parseBoolean(cfg("mention-without-at", "true"));
        if (!plain && message.indexOf('@') < 0) return message;
        int minPlain = 4;
        try {
            minPlain = Integer.parseInt(cfg("mention-plain-min-length", "4"));
        } catch (NumberFormatException ignored) {
            // keep default
        }
        // "@Name" or (if enabled) plain "name" - only names of LINKED players ever turn into a ping
        Matcher m = ANY_NAME.matcher(message);
        StringBuilder sb = new StringBuilder();
        List<String> found = new ArrayList<>();
        while (m.find()) {
            String typed = m.group(2);
            boolean hasAt = m.group(1) != null;
            UUID uuid = (hasAt || (plain && typed.length() >= minPlain)) ? links.uuidByName(typed) : null;
            boolean self = uuid != null && uuid.equals(sender.getUniqueId())
                    && !Boolean.parseBoolean(cfg("mention-allow-self", "false"));
            String id = (uuid == null || self) ? null : links.discordId(uuid);
            if (id != null && found.size() < 3) {
                found.add(id);
                m.appendReplacement(sb, Matcher.quoteReplacement("<@" + id + ">"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        if (found.isEmpty() || onCooldown("mention:mc:" + sender.getUniqueId())) return message;
        ids.addAll(found);
        return sb.toString();
    }

    /** Who a Discord message replies to, and what it said (already cleaned for Minecraft). */
    private record ReplyInfo(String who, TextColor color, String text) {}

    private ReplyInfo replyInfo(Message ref) {
        if (ref == null) return null;
        String who;
        TextColor color = null;
        if (ref.getMember() != null) {
            who = ref.getMember().getEffectiveName();
            if (ref.getMember().getColor() != null) color = readable(ref.getMember().getColor());
        } else {
            who = ref.getAuthor().getName();
        }
        who = emojis.convert(who);
        // relayed game messages are "Rank Name \u2022 SMP" -> keep just the player name (last word)
        who = who.replaceAll("\\s\u2022\\s.*$", "").trim();
        if (ref.isWebhookMessage() || ref.getMember() == null) {
            int sp = who.lastIndexOf(' ');
            if (sp >= 0 && sp < who.length() - 1) who = who.substring(sp + 1);
        }
        if (color == null) color = parseColor(cfg("reply-color", "#55FFFF"), NamedTextColor.AQUA);

        String text = emojis.convert(ref.getContentDisplay().replaceAll("[§&][0-9a-fk-orA-FK-OR]", ""))
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            if (!ref.getAttachments().isEmpty()) {
                text = ref.getAttachments().get(0).isImage() ? "[Image]" : "[File]";
            } else if (!ref.getEmbeds().isEmpty()) {
                text = "[Embed]";
            } else {
                text = "[message]";
            }
        }
        if (text.length() > 120) text = text.substring(0, 119).stripTrailing() + "\u2026";
        return new ReplyInfo(who, color, text);
    }

    /** Discord look:  "  ╭─ @Name first words of the replied message" (name in their role colour) */
    private Component replyLine(ReplyInfo r) {
        String text = r.text();
        int max = parseInt(cfg("reply-line-max-chars", "60"), 60);
        if (text.length() > max) text = text.substring(0, Math.max(1, max - 1)).stripTrailing() + "\u2026";
        TextColor line = parseColor(cfg("reply-label-color", "#6D6F78"), NamedTextColor.DARK_GRAY);
        return Component.text()
                .append(Component.text("  \u256D\u2500 ", line))
                .append(Component.text("@" + r.who(), r.color()))
                .append(Component.text(" " + text, parseColor(cfg("reply-text-color", "#B5BAC1"), NamedTextColor.GRAY)))
                .build();
    }

    /** Optional compact style (reply-style=inline): " ↪ Name" after the sender's name, hover = quoted text. */
    private Component replyTag(ReplyInfo r) {
        TextColor label = parseColor(cfg("reply-label-color", "#AAAAAA"), NamedTextColor.GRAY);
        TextColor textColor = parseColor(cfg("reply-text-color", "#FFFFFF"), NamedTextColor.WHITE);
        Component hover = Component.text()
                .append(Component.text("Replying to ", label))
                .append(Component.text(r.who(), r.color()))
                .append(Component.text(":\n", label))
                .append(Component.text(r.text(), textColor))
                .build();
        TextComponent.Builder tag = Component.text()
                .append(Component.text(" \u21AA ", parseColor(cfg("reply-arrow-color", "#FFAA00"), NamedTextColor.GOLD)))
                .append(Component.text(r.who(), r.color()).decorate(TextDecoration.BOLD));
        if (Boolean.parseBoolean(cfg("reply-show-text", "false"))) {
            String t = r.text();
            String snippet = t.length() > 24 ? t.substring(0, 23).stripTrailing() + "\u2026" : t;
            tag.append(Component.text(" \"" + snippet + "\"", textColor));
        }
        return tag.build().hoverEvent(HoverEvent.showText(hover));
    }

    /** "#RRGGBB" or a Minecraft colour name ("aqua", "gold" ...); falls back to {@code def} if it can't be read. */
    private static TextColor parseColor(String value, TextColor def) {
        if (value == null || value.isBlank()) return def;
        String v = value.trim();
        TextColor c = v.startsWith("#") ? TextColor.fromHexString(v) : NamedTextColor.NAMES.value(v.toLowerCase(Locale.ROOT));
        return c != null ? c : def;
    }

    /** Lightens very dark colours (blend 50% with white) so they stay readable on Minecraft's dark chat. */
    private static TextColor readable(Color c) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue();
        if ((r + g + b) / 3 < 90) {
            r = (r + 255) / 2;
            g = (g + 255) / 2;
            b = (b + 255) / 2;
        }
        return TextColor.color(r, g, b);
    }

    // ------------------------------------------------------------------ !ip / !website auto replies

    /** info-channels = "all" (every channel the bot can read) or a comma separated list of channel IDs. */
    private boolean infoChannelAllowed(String id) {
        String v = cfg("info-channels", "all").trim();
        if (v.isEmpty()) return false;
        if (v.equalsIgnoreCase("all")) return true;
        for (String part : v.split(",")) {
            if (part.trim().equals(id)) return true;
        }
        return false;
    }

    /** Returns "ip", "web" or null. */
    private String detectInfoRequest(String text) {
        if (text == null || text.isBlank() || text.length() > 120) return null;
        if (IP_CMD.matcher(text).find() || IP_BARE.matcher(text).find() || IP_ASK.matcher(text).find()) return "ip";
        if (WEB_CMD.matcher(text).find() || WEB_BARE.matcher(text).find() || WEB_ASK.matcher(text).find()) return "web";
        return null;
    }

    private Component infoComponent(String type) {
        if (type.equals("ip")) {
            String ip = cfg("server-ip", "play.example.com");
            return legacy(cfg("reply.ip", "&aServer IP: &f{ip}").replace("{ip}", ip))
                    .clickEvent(ClickEvent.copyToClipboard(ip))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to copy the IP", NamedTextColor.GRAY)));
        }
        String site = cfg("website", "https://example.com");
        String url = site.startsWith("http") ? site : "https://" + site;
        return legacy(cfg("reply.website", "&aWebsite: &b{website}").replace("{website}", site))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open the website", NamedTextColor.GRAY)));
    }

    private boolean onCooldown(String key) {
        long now = System.currentTimeMillis();
        if (cooldowns.size() > 200) {
            cooldowns.values().removeIf(t -> now - t > 60_000);
        }
        Long last = cooldowns.get(key);
        if (last != null && now - last < 5_000) return true;
        cooldowns.put(key, now);
        return false;
    }

    // ------------------------------------------------------------------ ranks (LuckPerms) + text rendering

    private LuckPermsHook luckPerms() {
        if (luckPerms == null && proxy.getPluginManager().isLoaded("luckperms")) {
            try {
                luckPerms = new LuckPermsHook();
                logger.info("LuckPerms found - ranks will be shown in chat and on Discord.");
            } catch (Throwable t) {
                if (!luckPermsWarned) {
                    luckPermsWarned = true;
                    logger.warn("LuckPerms is installed on the proxy but its API is not ready: {}", t.toString());
                }
            }
        } else if (luckPerms == null && !luckPermsWarned) {
            luckPermsWarned = true;
            logger.warn("LuckPerms is NOT installed on the Velocity proxy - ranks cannot be shown. "
                    + "Install LuckPerms (Velocity) and point it at the same database as your servers.");
        }
        return luckPerms;
    }

    private String prefixRaw(Player player) {
        LuckPermsHook hook = luckPerms();
        if (hook == null) return "";
        try {
            return hook.prefix(player);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Coloured prefix component with a trailing space, or empty if the player has no prefix. */
    private Component prefixComponent(Player player) {
        String raw = prefixRaw(player);
        if (raw.isBlank()) return Component.empty();
        Component c = parseRich(raw);
        String text = PlainTextComponentSerializer.plainText().serialize(c);
        return text.endsWith(" ") ? c : c.append(Component.space());
    }

    /** "OWNER xAetherealx" (plain text, for Discord). */
    private String discordDisplayName(Player player) {
        String rank = plain(parseRich(prefixRaw(player)));
        return rank.isEmpty() ? player.getUsername() : rank + " " + player.getUsername();
    }

    /** Webhook username: "OWNER xAetherealx • SMP" (configurable). */
    private String discordSenderName(Player player, String serverLabel) {
        String rank = plain(parseRich(prefixRaw(player)));
        String name = cfg("discord-name-format", "{prefix} {name} \u2022 {server}")
                .replace("{prefix}", rank)
                .replace("{name}", player.getUsername())
                .replace("{server}", serverLabel)
                .replaceAll("\\s+", " ")
                .trim();
        if (name.length() > 80) name = name.substring(0, 80);
        return name.isEmpty() ? player.getUsername() : name;
    }

    /** Builds a chat line from a format template. {message} is inserted as literal text (no code injection). */
    private Component render(String template, Player player, String server, String from, String to, String message) {
        String t = template
                .replace("{server}", server == null ? "" : server)
                .replace("{from}", from == null ? "" : from)
                .replace("{to}", to == null ? "" : to);
        Component c = legacy(t);
        c = replaceLiteral(c, "{prefix}", prefixComponent(player));
        c = replaceLiteral(c, "{name}", Component.text(player.getUsername()));
        if (message != null) {
            c = replaceLiteral(c, "{message}", Component.text(message));
        }
        return c;
    }

    private static Component replaceLiteral(Component c, String literal, Component with) {
        return c.replaceText(TextReplacementConfig.builder().matchLiteral(literal).replacement(with).build());
    }

    private static String normalizeLegacy(String raw) {
        String s = X_HEX.matcher(raw).replaceAll(r -> "&#" + r.group().replaceAll("(?i)[&\u00A7x]", ""));
        return s.replace('\u00A7', '&');
    }

    /** &-codes, &#rrggbb and &x&r&r&g&g&b&b. */
    private static Component legacy(String raw) {
        return LEGACY.deserialize(normalizeLegacy(raw));
    }

    /** Like legacy(), but also understands MiniMessage (<gradient:...>), which is bundled in this jar. */
    private Component parseRich(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (MM_TAG.matcher(raw).find()) {
            try {
                return MiniMessage.miniMessage().deserialize(raw);
            } catch (Throwable ignored) {
                // fall through to legacy parsing
            }
        }
        return legacy(raw);
    }

    /** Plain text: no colours, no resource-pack icon glyphs (Discord would show them as boxes). */
    private String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c)
                .replaceAll("[\\uE000-\\uF8FF]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ------------------------------------------------------------------ channel topic (players online / uptime / ...)

    private void startTopicUpdater() {
        if (topicTask != null) {
            topicTask.cancel();
            topicTask = null;
        }
        if (!Boolean.parseBoolean(cfg("topic-enabled", "true"))) return;
        // Discord only allows ~2 topic edits per 10 minutes per channel, so never go below 10.
        int minutes = Math.max(10, parseInt(cfg("topic-update-minutes", "10"), 10));
        if (topicTask != null) topicTask.cancel();
        topicTask = proxy.getScheduler().buildTask(this, this::updateTopic)
                .delay(15, TimeUnit.SECONDS)
                .repeat(minutes, TimeUnit.MINUTES)
                .schedule();
    }

    private void updateTopic() {
        if (jda == null) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        int configuredMax = parseInt(cfg("topic-max-players", "-1"), -1);
        int max = configuredMax > 0 ? configuredMax : proxy.getConfiguration().getShowMaxPlayers();
        long uptimeMinutes = (System.currentTimeMillis() - startMillis) / 60_000L;
        long unique = uniquePlayers.size() + parseInt(cfg("unique-players-offset", "0"), 0);
        String date = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                .format(ZonedDateTime.now(UTC));

        String topic = cfg("topic-format",
                "%playercount%/%playermax% players online | %uniqueplayers% unique players ever joined"
                        + " | Server online for %uptimeminutes% minutes | Last update: %date%")
                .replace("%playercount%", String.valueOf(proxy.getPlayerCount()))
                .replace("%playermax%", String.valueOf(max))
                .replace("%uniqueplayers%", String.valueOf(unique))
                .replace("%uptimeminutes%", String.valueOf(uptimeMinutes))
                .replace("%date%", date);
        if (topic.length() > 1024) topic = topic.substring(0, 1024);

        channel.getManager().setTopic(topic)
                .queue(null, err -> logger.warn("Could not update channel topic (does the bot have Manage Channel?): {}",
                        err.getMessage()));
    }

    // ------------------------------------------------------------------ unique players

    private void loadUniquePlayers() {
        uniqueFile = dataDirectory.resolve("unique-players.txt");
        if (!Files.exists(uniqueFile)) return;
        try {
            List<String> lines = Files.readAllLines(uniqueFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                try {
                    uniquePlayers.add(UUID.fromString(line.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read unique-players.txt", e);
        }
    }

    private void recordUniquePlayer(UUID uuid) {
        if (!uniquePlayers.add(uuid)) return;
        synchronized (this) {
            try {
                Files.writeString(uniqueFile, uuid + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warn("Could not save unique-players.txt", e);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void broadcastMc(Component component) {
        for (Player p : proxy.getAllPlayers()) {
            p.sendMessage(component);
        }
    }

    /**
     * Embed with a coloured side bar and the player's head as the icon (like the join/leave cards).
     * description = body text (chat fallback), footer = small text under the card (server name).
     */
    private void sendEmbed(String text, String iconUrl, Color color, String description, String footer) {
        if (jda == null) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) return;

        EmbedBuilder eb = new EmbedBuilder().setColor(color).setAuthor(text, null, iconUrl);
        if (description != null) eb.setDescription(description);
        if (footer != null) eb.setFooter(footer);
        channel.sendMessageEmbeds(eb.build())
                .setAllowedMentions(Collections.emptyList())
                .queue(null, err -> logger.warn("Discord send failed: {}", err.getMessage()));
    }

    /** Posts a chat line through the webhook so Discord shows the player's name and skin as the sender. */
    private void sendWebhook(String username, String avatarUrl, String content, List<String> mentionIds) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("avatar_url", avatarUrl);
        body.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray()); // no @everyone / @here / role pings
        if (!mentionIds.isEmpty()) {
            JsonArray users = new JsonArray(); // only the linked players that were @mentioned in game
            for (String id : mentionIds) users.add(id);
            mentions.add("users", users);
        }
        body.add("allowed_mentions", mentions);

        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
            if (res.statusCode() >= 300) {
                logger.warn("Webhook failed ({}): {}", res.statusCode(), res.body());
            }
        });
    }

    private String avatarFor(Player player) {
        return avatarTemplate
                .replace("{uuid}", player.getUniqueId().toString().replace("-", ""))
                .replace("{name}", player.getUsername());
    }

    private String currentServerName(Player player) {
        return player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("none");
    }

    private String labelFor(String serverName) {
        return config.getProperty("server." + serverName, serverName);
    }

    private String cfg(String key, String def) {
        return config.getProperty(key, def).trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ /hub command

    private class HubCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
                return;
            }

            RegisteredServer target = proxy.getServer(lobbyServer).orElse(null);
            if (target == null) {
                player.sendMessage(Component.text("The lobby server is not available.", NamedTextColor.RED));
                logger.warn("lobby-server '{}' is not defined in velocity.toml", lobbyServer);
                return;
            }

            boolean alreadyThere = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase(lobbyServer))
                    .orElse(false);
            if (alreadyThere) {
                player.sendMessage(Component.text("You are already in the lobby.", NamedTextColor.YELLOW));
                return;
            }

            player.sendMessage(Component.text("Sending you to the lobby...", NamedTextColor.GREEN));
            player.createConnectionRequest(target).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    player.sendMessage(Component.text("Could not connect to the lobby.", NamedTextColor.RED));
                }
            });
        }
    }

    // ------------------------------------------------------------------ config (load / migrate / reload)

    /** Config keys added in 1.3.0. Missing ones are appended to the existing config.properties automatically. */
    private static final String[] MIGRATE_PREFIXES = {
            "announce-deaths", "announce-kills", "announce-advancements", "discord-format-", "status", "info-", "invisible-killer-", "chat-ignore-", "chat-from-backend", "chat-debug"
    };

    private boolean loadConfig() {
        Properties fresh = readConfig();
        if (fresh == null) return false;
        config.putAll(fresh);
        return true;
    }

    /** Reads (and if needed creates / completes) plugins/discordbridge/config.properties. Null on failure. */
    private Properties readConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            if (migrateConfig(file, props)) {
                props.clear();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
            return props;
        } catch (IOException e) {
            logger.error("Could not load config", e);
            return null;
        }
    }

    /** Appends settings that are new in this version (never touches or overwrites existing lines). */
    private boolean migrateConfig(Path file, Properties existing) throws IOException {
        List<String> add = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
            if (in == null) return false;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R", -1)) {
                String t = line.trim();
                if (t.isEmpty()) {
                    pending.clear();
                    continue;
                }
                if (t.startsWith("#")) {
                    pending.add(line);
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq > 0) {
                    String key = t.substring(0, eq).trim();
                    boolean wanted = Arrays.stream(MIGRATE_PREFIXES).anyMatch(key::startsWith);
                    if (wanted && !existing.containsKey(key)) {
                        if (!add.isEmpty()) add.add("");
                        add.addAll(pending);
                        add.add(line);
                    }
                }
                pending.clear();
            }
        }
        if (add.isEmpty()) return false;
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(nl)
                .append("# ---- new settings added automatically by DiscordBridge 1.3.0 ----").append(nl);
        for (String line : add) sb.append(line).append(nl);
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        logger.info("Added {} new setting line(s) to config.properties", add.size());
        return true;
    }

    /** Settings that can change at runtime (also re-run by /bridge reload). */
    private void applyConfig() {
        webhookUrl = config.getProperty("webhook-url", "").trim();
        avatarTemplate = config.getProperty("avatar-url", "https://mc-heads.net/avatar/{uuid}/128").trim();
        relayBetweenServers = Boolean.parseBoolean(config.getProperty("relay-between-servers", "true"));
        chatFromBackend = Boolean.parseBoolean(config.getProperty("chat-from-backend", "true").trim());
        announceJoinLeave = Boolean.parseBoolean(config.getProperty("announce-join-leave", "true"));
        List<String> prefixes = new ArrayList<>();
        for (String part : config.getProperty("chat-ignore-prefixes", "#").split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) prefixes.add(t);
        }
        chatIgnorePrefixes = prefixes;
        emojis = new EmojiMapper(config);
        lobbyServer = config.getProperty("lobby-server", "lobby").trim();
    }

    /** /bridge reload - re-reads config.properties without restarting the proxy. Returns lines for the sender. */
    private List<Component> reload() {
        List<Component> out = new ArrayList<>();
        Properties fresh = readConfig();
        if (fresh == null) {
            out.add(Component.text("Could not read config.properties (see console). Nothing was changed.",
                    NamedTextColor.RED));
            return out;
        }
        String newToken = fresh.getProperty("token", "").trim();
        String newChannel = fresh.getProperty("channel-id", "").trim();

        config.keySet().removeIf(k -> !fresh.containsKey(k));
        config.putAll(fresh);
        applyConfig();

        if (jda != null) startTopicUpdater();
        if (links != null) {
            if (linkTask != null) linkTask.cancel();
            int seconds = Math.max(5, parseInt(cfg("link-refresh-seconds", "30"), 30));
            linkTask = proxy.getScheduler().buildTask(this, links::refresh)
                    .delay(1, TimeUnit.SECONDS)
                    .repeat(seconds, TimeUnit.SECONDS)
                    .schedule();
        }
        if (status != null) status.start();

        out.add(Component.text("DiscordBridge config reloaded.", NamedTextColor.GREEN));
        if (!newToken.equals(activeToken) || !newChannel.equals(channelId)) {
            out.add(Component.text("The bot token / channel-id changed - those only apply after a proxy restart.",
                    NamedTextColor.YELLOW));
        }
        return out;
    }

    // ------------------------------------------------------------------ backend events (deaths / kills / advancements)

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(EVENT_CHANNEL)) return;
        // Never forward our private channel to players, and only trust messages that come from a backend
        // server - a client can't be allowed to fake death / advancement posts.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection conn)) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String type = in.readUTF();
            if (type.equals("teamchat")) {
                // justTeams: player switched team chat on/off
                if (Boolean.parseBoolean(in.readUTF())) teamChatToggled.add(conn.getPlayer().getUniqueId());
                else teamChatToggled.remove(conn.getPlayer().getUniqueId());
                return;
            }
            if (type.equals("chat")) {
                // final chat text from the backend, AFTER ChatSentry (or any filter) censored it
                String message = in.readUTF();
                if (Boolean.parseBoolean(cfg("chat-debug", "false"))) {
                    logger.info("[chat debug] backend {} sent final '{}' from {}", conn.getServerInfo().getName(),
                            message, conn.getPlayer().getUsername());
                }
                if (chatFromBackend && !message.isBlank()) {
                    relayChat(conn.getPlayer(), conn.getServerInfo().getName(), message);
                }
                return;
            }
            if (jda == null) return;
            String serverKey = conn.getServerInfo().getName();
            String label = labelFor(serverKey);
            Player carrier = conn.getPlayer();

            switch (type) {
                case "death" -> {
                    String message = in.readUTF();
                    String killerName = in.readUTF();
                    String killerUuid = in.readUTF();
                    String weapon = in.readUTF();
                    // older backend jars don't send this field -> treat as visible
                    boolean killerInvisible = in.available() > 0 && Boolean.parseBoolean(in.readUTF());
                    handleDeath(serverKey, label, carrier, message, killerName, killerUuid, weapon, killerInvisible);
                }
                case "advancement" -> {
                    String message = in.readUTF();
                    handleAdvancement(serverKey, label, carrier, message);
                }
                default -> logger.debug("Unknown backend event type '{}'", type);
            }
        } catch (IOException e) {
            logger.warn("Bad plugin message from backend: {}", e.getMessage());
        }
    }

    private void handleDeath(String serverKey, String label, Player victim, String message,
                             String killerName, String killerUuid, String weapon, boolean killerInvisible) {
        String victimName = victim.getUsername();
        // invisible killer: hide the name everywhere in Discord and use the question-mark head
        String redacted = cfg("invisible-killer-name", "[REDACTED]");
        String shownKiller = killerInvisible ? redacted : killerName;
        if (killerInvisible && !killerName.isEmpty()) {
            message = message.replaceAll("(?i)" + java.util.regex.Pattern.quote(killerName),
                    java.util.regex.Matcher.quoteReplacement(redacted));
        }
        if (!killerName.isEmpty() && toggle("announce-kills", serverKey)) {
            // PvP kill: replaces the plain death line so the same event isn't posted twice
            String format = weapon.isEmpty()
                    ? cfg("discord-format-kill-noweapon", "\u2694 {killer} killed {victim}")
                    : cfg("discord-format-kill", "\u2694 {killer} killed {victim} using {weapon}");
            String line = format
                    .replace("{killer}", shownKiller)
                    .replace("{victim}", victimName)
                    .replace("{weapon}", weapon)
                    .replace("{server}", label);
            String icon = killerInvisible
                    ? cfg("invisible-killer-avatar-url", "https://mc-heads.net/avatar/MHF_Question/128")
                    : avatarTemplate
                        .replace("{uuid}", killerUuid.replace("-", ""))
                        .replace("{name}", killerName);
            sendEmbed(clip(line, 250), icon, KILL, null, label);
        } else if (toggle("announce-deaths", serverKey)) {
            String line = cfg("discord-format-death", "\u2620 {message}")
                    .replace("{message}", message)
                    .replace("{victim}", victimName)
                    .replace("{server}", label);
            sendEmbed(clip(line, 250), avatarFor(victim), DEATH, null, label);
        }
    }

    private void handleAdvancement(String serverKey, String label, Player player, String message) {
        if (!toggle("announce-advancements", serverKey)) return;
        String line = cfg("discord-format-advancement", "\uD83C\uDFC6 {message}")
                .replace("{message}", message)
                .replace("{player}", player.getUsername())
                .replace("{server}", label);
        sendEmbed(clip(line, 250), avatarFor(player), ADVANCEMENT, null, label);
    }

    /** announce-deaths.events=false overrides announce-deaths=true for that one server. Default: enabled. */
    private boolean toggle(String base, String serverKey) {
        String value = config.getProperty(base + "." + serverKey);
        if (value == null) value = config.getProperty(base, "true");
        return Boolean.parseBoolean(value.trim());
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }

    // ------------------------------------------------------------------ /bridge command

    private class BridgeCommand implements SimpleCommand {
        private static final String PERM_RELOAD = "discordbridge.reload";
        private static final String PERM_MAINTENANCE = "discordbridge.maintenance";

        @Override
        public void execute(Invocation invocation) {
            CommandSource src = invocation.source();
            String[] args = invocation.arguments();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "reload" -> {
                    if (!src.hasPermission(PERM_RELOAD)) {
                        src.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                        return;
                    }
                    try {
                        reload().forEach(src::sendMessage);
                    } catch (Exception e) {
                        logger.error("Reload failed", e);
                        src.sendMessage(Component.text("Reload failed: " + e.getMessage(), NamedTextColor.RED));
                    }
                }
                case "maintenance" -> {
                    if (!src.hasPermission(PERM_MAINTENANCE)) {
                        src.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                        return;
                    }
                    if (status == null || !status.isEnabled()) {
                        src.sendMessage(Component.text("The status channel isn't running (check status-enabled, "
                                + "status-channel-id and the bot token).", NamedTextColor.RED));
                        return;
                    }
                    if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                        src.sendMessage(Component.text("Maintenance is currently "
                                + (status.isMaintenance() ? "ON" : "OFF") + ". Use /bridge maintenance on|off",
                                NamedTextColor.YELLOW));
                        return;
                    }
                    boolean on = args[1].equalsIgnoreCase("on");
                    status.setMaintenance(on);
                    src.sendMessage(Component.text(on
                            ? "Maintenance mode ON - the status channel will turn orange."
                            : "Maintenance mode OFF - the status channel will go back to online.",
                            NamedTextColor.GREEN));
                }
                default -> src.sendMessage(Component.text("Usage: /bridge reload | /bridge maintenance <on|off>",
                        NamedTextColor.YELLOW));
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            CommandSource src = invocation.source();
            if (args.length <= 1) {
                List<String> subs = new ArrayList<>();
                if (src.hasPermission(PERM_RELOAD)) subs.add("reload");
                if (src.hasPermission(PERM_MAINTENANCE)) subs.add("maintenance");
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                subs.removeIf(x -> !x.startsWith(prefix));
                return subs;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("maintenance") && src.hasPermission(PERM_MAINTENANCE)) {
                List<String> opts = new ArrayList<>(List.of("on", "off"));
                opts.removeIf(x -> !x.startsWith(args[1].toLowerCase(Locale.ROOT)));
                return opts;
            }
            return List.of();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(PERM_RELOAD)
                    || invocation.source().hasPermission(PERM_MAINTENANCE);
        }
    }
}
