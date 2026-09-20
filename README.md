# CobbleSync

A web dashboard for tracking each player's pokédex progress on a
[Cobblemon](https://modrinth.com/mod/cobblemon) server — caught, seen, forms,
genders, shiny — updated in real time, no external plugin or third-party
server required.

## Features

- **One tab per player**, with a summary (caught / seen / % progress).
- **Live updates**: the dashboard refreshes itself the moment a player
  catches or encounters a Pokémon, no page reload needed.
- **Per-species detail cards**: types, height/weight, abilities (with
  hover descriptions), egg groups, and where to find it in-game (biomes —
  color-coded by dimension: Overworld/Nether/End — and structures).
- **Form, gender, and shiny tracking** per species, with a gold highlight
  for fully completed species.
- **Live activity feed**: recent captures across all players, shiny catches
  highlighted.
- **Leaderboard**: completion %, shinies caught, and captures in the last 7
  days.
- **1v1 comparison**: pick two players and see what each has that the other
  doesn't.
- **Discord notifications**: optionally post shiny catches (and, if enabled,
  first-time species captures) to a Discord channel via webhook.
- **Bilingual FR/EN**, instant language switch, no reload.
- **Customizable interface**: the mod extracts its web page to an editable
  folder — change it freely (see *Customization*).

## Installation

1. Install [Cobblemon](https://modrinth.com/mod/cobblemon) and
   [Fabric API](https://modrinth.com/mod/fabric-api) on your server.
2. Drop the CobbleSync jar into the server's `mods/` folder.
3. Start the server once — it creates `config/cobblesync/` with default
   settings and extracts the web page.
4. Open `http://<server-address>:8080` in a browser.

## Configuration

Editable in `config/cobblesync/webserver.conf`:

```
enabled=true
bind-address=0.0.0.0
port=8080
```

- `enabled=false` disables the built-in HTTP server, if you'd rather expose
  the dashboard through an external reverse proxy.
- `bind-address`/`port` control where the server listens.

## Discord webhook notifications

Editable in `config/cobblesync/discord.conf` (created on first start):

```
enabled=false
webhook-url=
language=en
notify-shiny=true
notify-new-species=false
```

1. In Discord, go to your channel's *Edit Channel → Integrations → Webhooks*,
   create a webhook, and copy its URL.
2. Paste it into `webhook-url`, set `enabled=true`, and restart the server.
3. `language` controls the message language (independent of any individual
   viewer's dashboard toggle, since a Discord channel has one shared
   audience). `notify-shiny`/`notify-new-species` control which catches post.

## Customizing the interface

The served page lives in `config/cobblesync/web/` after the first start —
edit it freely (theme, mascot, etc.), the mod never overwrites it while the
folder exists.

To add your own mascot to the dashboard screen, drop an image at
`config/cobblesync/web/mascot.png`.

## Before exposing it publicly

The dashboard has **no access control**: anyone who can reach the configured
port sees every player's progress. Fine for a private/friends server; keep it
behind a firewall or an authenticated reverse proxy otherwise.

## Credits

- Game data via [Cobblemon](https://gitlab.com/cable-mc/cobblemon).
- Sprites via [PokeAPI/sprites](https://github.com/PokeAPI/sprites).
- [Monocraft](https://github.com/IdreesInc/Monocraft) font (SIL Open Font
  License).
