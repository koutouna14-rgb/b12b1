package dev.discordbridge;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Keeps a dedicated Discord channel in sync with the network state:
 * <ul>
 *   <li>renames the channel (green / orange / red circle in the name)</li>
 *   <li>keeps ONE embed message up to date (edited in place, never re-posted) with every server's status</li>
 * </ul>
 * States: ONLINE, MAINTENANCE (set with /bridge maintenance on|off) and DOWN (shutdown, or every
 * backend unreachable). Discord only allows ~2 channel renames per 10 minutes, so the name is only
 * touched when the state really changes; the embed has no such limit and refreshes every minute.
 */
final class StatusManager {

    enum State { ONLINE, MAINTENANCE, DOWN }

    private record Srv(String key, String label, boolean up, int players) {}

    private static final Color GREEN = new Color(0x57F287);
    private static final Color ORANGE = new Color(0xFEA500);
    private static final Color RED = new Color(0xED4245);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<JDA> jda;
    private final BiFunction<String, String, String> cfg;
    private final java.util.function.Function<String, String> labelFor;
    private final Path maintenanceFile;
    private final Path messageFile;

    private ScheduledTask task;
    private volatile boolean maintenance;
    private volatile State state = State.ONLINE;
    private State renamedTo;
    private int downStrikes = 1; // first check after startup is not debounced
    private volatile String messageId;
    private boolean warnedChannel;

    StatusManager(Object plugin, ProxyServer proxy, Logger logger, Path dataDirectory, Supplier<JDA> jda,
                  BiFunction<String, String, String> cfg, java.util.function.Function<String, String> labelFor) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.jda = jda;
        this.cfg = cfg;
        this.labelFor = labelFor;
        this.maintenanceFile = dataDirectory.resolve("maintenance.flag");
        this.messageFile = dataDirectory.resolve("status-message.txt");
        this.maintenance = Files.exists(maintenanceFile);
        try {
            if (Files.exists(messageFile)) {
                String id = Files.readString(messageFile, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) messageId = id;
            }
        } catch (IOException e) {
            logger.warn("Could not read status-message.txt", e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** (Re)starts the refresh loop. Safe to call again after a config reload. */
    synchronized void start() {
        stopTask();
        if (!enabled()) return;
        int seconds = Math.max(15, parseInt(cfg.apply("status-refresh-seconds", "60"), 60));
        warnedChannel = false;
        task = proxy.getScheduler().buildTask(plugin, this::refresh)
                .delay(5, TimeUnit.SECONDS) // lets Velocity finish registering the servers first
                .repeat(seconds, TimeUnit.SECONDS)
                .schedule();
    }

    private synchronized void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    boolean isMaintenance() {
        return maintenance;
    }

    boolean isEnabled() {
        return enabled();
    }

    /** /bridge maintenance on|off */
    void setMaintenance(boolean on) {
        maintenance = on;
        try {
            if (on) {
                Files.writeString(maintenanceFile, "maintenance" + System.lineSeparator(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(maintenanceFile);
            }
        } catch (IOException e) {
            logger.warn("Could not save maintenance flag", e);
        }
        proxy.getScheduler().buildTask(plugin, this::refresh).schedule();
    }

    /** Proxy is stopping: show red / offline. Blocks briefly so the edit really goes out. */
    synchronized void shutdown() {
        stopTask();
        JDA j = jda.get();
        if (j == null || !enabled()) return;
        TextChannel channel = j.getTextChannelById(channelId());
        if (channel == null) return;
        state = State.DOWN;
        try {
            List<Srv> offline = new ArrayList<>();
            for (Srv s : probeNames()) offline.add(new Srv(s.key(), s.label(), false, 0));
            publish(channel, buildEmbed(State.DOWN, offline));
        } catch (Exception e) {
            logger.warn("Could not update the status message on shutdown: {}", e.getMessage());
        }
        if (renameEnabled() && renamedTo != State.DOWN) {
            String name = channelName(State.DOWN, channel.getName());
            if (!name.equals(channel.getName())) {
                try {
                    channel.getManager().setName(name).submit().get(8, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("Could not rename the status channel on shutdown (Discord rate limit?): {}",
                            e.getMessage());
                }
            }
            renamedTo = State.DOWN;
        }
    }

    // ------------------------------------------------------------------ refresh

    synchronized void refresh() {
        JDA j = jda.get();
        if (j == null || !enabled()) return;
        try {
            TextChannel channel = j.getTextChannelById(channelId());
            if (channel == null) {
                if (!warnedChannel) {
                    logger.warn("status-channel-id {} was not found (is the bot in that channel?)", channelId());
                    warnedChannel = true;
                }
                return;
            }

            List<Srv> servers = probe();
            boolean anyConfigured = !servers.isEmpty();
            boolean allDown = anyConfigured && servers.stream().noneMatch(Srv::up);

            State desired;
            if (maintenance) {
                desired = State.MAINTENANCE;
                downStrikes = 0;
            } else if (allDown) {
                // two bad checks in a row, so one ping blip doesn't burn a channel rename
                downStrikes++;
                desired = downStrikes >= 2 ? State.DOWN : state;
            } else {
                downStrikes = 0;
                desired = State.ONLINE;
            }
            state = desired;

            applyRename(channel, desired);
            publish(channel, buildEmbed(desired, servers));
        } catch (Throwable t) {
            logger.warn("Status update failed: {}", t.toString());
        }
    }

    private void applyRename(TextChannel channel, State s) {
        if (!renameEnabled() || renamedTo == s) return;
        String name = channelName(s, channel.getName());
        renamedTo = s;
        if (name.equals(channel.getName())) return; // already correct (e.g. after a restart)
        channel.getManager().setName(name).queue(null, err -> {
            renamedTo = null; // try again next refresh
            logger.warn("Could not rename the status channel (bot needs Manage Channel): {}", err.getMessage());
        });
    }

    /** Edits the one status message, or posts it the first time (or if someone deleted it). */
    private void publish(TextChannel channel, MessageEmbed embed) throws Exception {
        String id = messageId;
        if (id != null) {
            try {
                channel.editMessageEmbedsById(id, embed).submit().get(15, TimeUnit.SECONDS);
                return;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ErrorResponseException ere
                        && ere.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                    messageId = null; // deleted - fall through and post a new one
                } else {
                    throw e;
                }
            }
        }
        var sent = channel.sendMessageEmbeds(embed).submit().get(15, TimeUnit.SECONDS);
        messageId = sent.getId();
        try {
            Files.writeString(messageFile, messageId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Could not save status-message.txt", e);
        }
    }

    // ------------------------------------------------------------------ embed

    private MessageEmbed buildEmbed(State overall, List<Srv> servers) {
        int totalPlayers = overall == State.DOWN ? 0 : proxy.getPlayerCount();
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(color(overall))
                .setTitle(cfg.apply("status-title", "\u2726 AetherMC \u2022 Server Status"))
                .setTimestamp(Instant.now());

        String desc = emoji(overall) + " **" + text(overall) + "**";
        if (overall != State.DOWN) {
            desc += "\n" + totalPlayers + (totalPlayers == 1 ? " player" : " players") + " online";
        }
        eb.setDescription(desc);

        for (Srv s : servers) {
            State per = !s.up() ? State.DOWN : (overall == State.MAINTENANCE ? State.MAINTENANCE : State.ONLINE);
            String icon = cfg.apply("status.server-emoji." + s.key(), "");
            String title = (icon.isEmpty() ? "" : icon + " ") + s.label();
            String value = emoji(per) + " " + text(per);
            if (per != State.DOWN) {
                value += "\n" + s.players() + (s.players() == 1 ? " player" : " players");
            }
            eb.addField(title, value, true);
        }
        return eb.build();
    }

    private Color color(State s) {
        return switch (s) {
            case ONLINE -> GREEN;
            case MAINTENANCE -> ORANGE;
            case DOWN -> RED;
        };
    }

    private String emoji(State s) {
        return switch (s) {
            case ONLINE -> cfg.apply("status-emoji-online", "\uD83D\uDFE2");
            case MAINTENANCE -> cfg.apply("status-emoji-maintenance", "\uD83D\uDFE0");
            case DOWN -> cfg.apply("status-emoji-down", "\uD83D\uDD34");
        };
    }

    private String text(State s) {
        return switch (s) {
            case ONLINE -> cfg.apply("status-text-online", "Online");
            case MAINTENANCE -> cfg.apply("status-text-maintenance", "Maintenance");
            case DOWN -> cfg.apply("status-text-down", "Offline");
        };
    }

    /**
     * Name for the channel in state s. Default ("auto"): keep the channel's current name exactly as it is
     * (custom font and all) and only swap the circle emoji. Falls back to status-channel-name if the current
     * name has no circle yet.
     */
    private String channelName(State s, String currentName) {
        String setting = cfg.apply("status-channel-name", "auto").trim();
        String target = emoji(s);
        String name = null;
        if (setting.isEmpty() || setting.equalsIgnoreCase("auto")) {
            String swapped = currentName;
            for (State other : State.values()) {
                swapped = swapped.replace(emoji(other), target);
            }
            if (!swapped.equals(currentName) || currentName.contains(target)) {
                name = swapped;
            }
            setting = "\u300E{emoji}\u300F\u30FB\uD835\uDE68\uD835\uDE5A\uD835\uDE67\uD835\uDE6B\uD835\uDE5A\uD835\uDE67\u002D\uD835\uDE68\uD835\uDE69\uD835\uDE56\uD835\uDE69\uD835\uDE6A\uD835\uDE68";
        }
        if (name == null) {
            name = setting.replace("{emoji}", target).replace("{state}", text(s).toLowerCase(Locale.ROOT));
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    // ------------------------------------------------------------------ pinging the backends

    /** The servers to show, in order, with up=false (used for the shutdown card). */
    private List<Srv> probeNames() {
        List<Srv> out = new ArrayList<>();
        for (String key : serverKeys()) {
            out.add(new Srv(key, labelFor.apply(key), false, 0));
        }
        return out;
    }

    private List<Srv> probe() {
        Map<String, CompletableFuture<Boolean>> pings = new LinkedHashMap<>();
        Map<String, RegisteredServer> found = new LinkedHashMap<>();
        for (String key : serverKeys()) {
            Optional<RegisteredServer> rs = proxy.getServer(key);
            if (rs.isEmpty()) continue;
            found.put(key, rs.get());
            pings.put(key, rs.get().ping().orTimeout(4, TimeUnit.SECONDS).handle((p, e) -> e == null));
        }
        List<Srv> out = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<Boolean>> e : pings.entrySet()) {
            boolean up = Boolean.TRUE.equals(e.getValue().join());
            int players = found.get(e.getKey()).getPlayersConnected().size();
            out.add(new Srv(e.getKey(), labelFor.apply(e.getKey()), up, players));
        }
        return out;
    }

    private List<String> serverKeys() {
        List<String> keys = new ArrayList<>();
        for (String part : cfg.apply("status-servers", "").split(",")) {
            if (!part.isBlank()) keys.add(part.trim());
        }
        if (keys.isEmpty()) {
            for (RegisteredServer rs : proxy.getAllServers()) keys.add(rs.getServerInfo().getName());
        }
        return keys;
    }

    // ------------------------------------------------------------------ config helpers

    private boolean enabled() {
        return Boolean.parseBoolean(cfg.apply("status-enabled", "true")) && !channelId().isEmpty();
    }

    private boolean renameEnabled() {
        return Boolean.parseBoolean(cfg.apply("status-rename-enabled", "true"));
    }

    private String channelId() {
        return cfg.apply("status-channel-id", "");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
