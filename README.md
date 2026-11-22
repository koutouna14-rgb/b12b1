# DiscordBridge

Two jars, one repo:

| Module | Goes on | What it does |
| --- | --- | --- |
| `proxy` -> `DiscordBridge-<ver>.jar` | **Velocity** | Discord <-> Minecraft chat, LuckPerms ranks, `!ip` / `!website`, channel topic, **death / kill / advancement posts, status channel** |
| `backend` -> `DiscordBridge-Backend-<ver>.jar` | **every Paper 1.21.11 server** (Lobby, SMP, Events) | Sees deaths and advancements (the proxy can't) and forwards them to the proxy |

## Build
Push to GitHub. Actions builds both jars (Java 21), then download them from Actions -> latest run -> Artifacts
(`DiscordBridge-Velocity` and `DiscordBridge-Backend-Paper`).

## Install
1. Put `DiscordBridge-<ver>.jar` in your Velocity `plugins` folder (LuckPerms-Velocity must also be installed).
2. Put `DiscordBridge-Backend-<ver>.jar` in the `plugins` folder of each Paper server and restart those servers.
3. Start the proxy once, then edit `plugins/discordbridge/config.properties`:
   set `token` and `webhook-url` (never commit these to GitHub).
   When you update from an older version, new settings are appended to your existing config automatically.
4. Restart the proxy.

## Chat filters (ChatSentry etc.)
Velocity sees a chat message *before* the Paper server does, so a filter plugin on the Paper server (ChatSentry, ...)
can't change what the proxy already read. To keep Discord in sync, the backend jar reports the **final** chat text
(after the filters ran) and the proxy posts that to Discord and relays it to other servers. Blocked messages never
reach Discord. Controlled by `chat-from-backend=true` (proxy `config.properties`) and `events.chat: true` (backend
`config.yml`). Set `chat-from-backend=false` to go back to reading chat at the proxy (uncensored, no backend needed).
Needs the **updated backend jar on every Paper server**, otherwise chat from that server won't reach Discord.

## Deaths, kills and advancements
Posted as coloured cards with the player's head and the server name (dark red = death, orange = kill, gold = advancement).
- A player killing a player is posted as a **kill** ("A killed B using Diamond Sword") instead of the death line, so it isn't shown twice.
- Advancements that Minecraft doesn't announce in chat (recipes, hidden ones) are skipped.
- Per-server switches in `config.properties`, all `true` by default. If Events is too spammy:
  `announce-deaths.events=false` and/or `announce-kills.events=false`, then `/bridge reload`.
- `/bridgereload` on a Paper server re-reads the backend's own `config.yml`.

## Status channel
Set `status-channel-id` (already filled in). The bot renames the channel and keeps **one** status card up to date:
- green = online, orange = maintenance, red = proxy stopped or every backend unreachable.
- Each server (Lobby / SMP / Events) has its own icon, status and player count on the card.
- `/bridge maintenance on|off` toggles orange (permission `discordbridge.maintenance`); it survives proxy restarts.
- Bot permissions for that channel: View Channel, Send Messages, Embed Links, Manage Channel.
- Discord allows ~2 renames per 10 minutes, so the name only changes when the state changes; the card refreshes every `status-refresh-seconds`.
- A proxy **crash** can't announce itself: the card and name keep the last state until the proxy is back.

## Commands and permissions (Velocity)
| Command | Permission |
| --- | --- |
| `/bridge reload` (also from console) | `discordbridge.reload` |
| `/bridge maintenance on\|off` | `discordbridge.maintenance` |

`/bridge reload` re-reads `config.properties` live (toggles, formats, status card, topic, link interval, emoji).
Only the bot `token` and `channel-id` need a restart.

## Playerlist
Type `playerlist` (also `Playerlist`, `pleyerlist`, `player list`, `!playerlist`, `who's online`) in the Discord channel.
The bot replies with an embed: players per server (Lobby / SMP / Events) + total online.
Your typed message is deleted after 7 seconds, and so is the bot reply - give the bot the **Manage Messages** permission.
`playerlist`, `ip` / `!ip` / "what's the ip" and `website` / "what's the website" work in **every Discord channel** the bot can read
(`info-channels=all`, or a list of channel IDs). They are not answered in Minecraft chat (`info-ingame=false`).
Settings are under `playerlist-*` in `config.properties`.

## Mentions and replies
Account linking is handled by the separate AetherMC plugin (`/aethermc link`). DiscordBridge only reads
its links (read-only, every 30s), so the two plugins stay separate but in sync.
- Linked players get pinged (highlight + sound) when someone @mentions them in Discord or types `@TheirName`.
- Typing `@Name` of a linked player in game pings their Discord account (needs the webhook).
- Discord replies show `replying to Name: ...` in game chat.

## Invisible killers
If a player kills while they have the Invisibility effect, Discord shows `[REDACTED]` and a question-mark head
instead of their name and skin (the in-game chat already hides it). Needs BOTH jars updated.
Set `invisible-killer-avatar-url` in the proxy `config.properties` to a hosted copy of `assets/unknown_head.png`
(for example the raw GitHub link), and `invisible-killer-name` to change the text.
