# CobbleSync - developer notes

Fabric mod for Cobblemon that serves a self-hosted web dashboard showing each
player's pokédex progress - caught/seen/forms/shiny, in real time.



## Architecture

- The mod runs its own HTTP server (`com.sun.net.httpserver.HttpServer`, JDK,
  no external dependency), BlueMap-style.
- The frontend (`src/main/resources/web/`) is vanilla HTML/CSS/JS, no build
  step. Extracted once to `config/cobblesync/web/` on first start
  (customizable afterward - the mod never overwrites that folder).
- Data comes from Cobblemon's Kotlin APIs (`PokedexManager`,
  `PlayerInstancedDataStoreManager`, `PokemonSpecies`, `CobblemonSpawnPools`),
  never from parsing save files.
- Real-time updates via SSE (`/api/players/{uuid}/events`), pushed on
  `CobblemonEvents.POKEDEX_DATA_CHANGED_POST`  no polling.

## Project layout

```
src/main/kotlin/com/cobblesync/
  CobbleSync.kt              entrypoint, wires events/config/cache/web server
  config/
    WebServerConfig.kt       reads/writes webserver.conf
    DiscordConfig.kt         reads/writes discord.conf
  discord/
    DiscordNotifier.kt       posts capture notifications to a Discord webhook
  player/
    PlayerRegistry.kt        uuid -> name registry (players.json)
    CaptureDates.kt          first-caught timestamp per player/species (capture-dates.json)
  data/
    WorldDataCache.kt        world data cache (dex/species/spawn pool)
    SpeciesInfoCache.kt      per-species JSON cache (/api/species/{id})
    CobblemonLang.kt         reads Cobblemon's lang files (EN/FR names/descriptions)
    CaptureLog.kt            rolling cross-player capture log (capture-log.json)
    PlayerProgress.kt        shared "caught/seen/forms count" computation
  web/
    CobbleSyncWebServer.kt   starts the HttpServer, routing
    HttpJson.kt              shared JSON response helpers
    PokedexHandler.kt        /api/players/{uuid}/pokedex + SSE /events
    SpeciesInfoHandler.kt    /api/species/{id}
    PlayersHandler.kt        /api/players
    CaptureLogHandler.kt     /api/capture-log + SSE /events
    LeaderboardHandler.kt    /api/leaderboard + SSE /events
    PokedexEventBroadcaster.kt  registry of open per-player SSE connections
    CaptureLogBroadcaster.kt    registry of open SSE connections for the activity feed
    LeaderboardBroadcaster.kt   registry of open SSE connections for the leaderboard
    StaticFileHandler.kt     serves config/cobblesync/web/ (ETag, 304)
    StaticFileExtractor.kt   initial extraction of the jar-bundled web/

src/main/resources/
  fabric.mod.json
  web/                       frontend bundled in the jar (source of truth)
```

## Data flow

1. `CobbleSync.onInitialize()` loads config and the player registry, then
   waits for `SERVER_STARTED` to subscribe to Cobblemon's observables
   (`Dexes`, `PokemonSpecies`, `WORLD_SPAWN_POOL`) and start the web server.
   The subscription happens at `SERVER_STARTED`, not earlier, because
   `WORLD_SPAWN_POOL` doesn't exist yet at `onInitialize`.
2. Each `/api/players/{uuid}/pokedex` request reads `WorldDataCache` (built
   once, invalidated on a Cobblemon data reload) instead of rescanning
   dex/species/spawn pool on every call.
3. A capture/encounter in-game fires `POKEDEX_DATA_CHANGED_POST` →
   `PokedexEventBroadcaster.notifyUpdated(uuid)` → the SSE client gets a
   signal → it does a plain refetch of `/pokedex` (no diff is pushed).

## API (internal summary)

| Route | Description |
|---|---|
| `GET /api/players` | Known players (uuid + name) |
| `GET /api/players/{uuid}/pokedex` | Full pokédex for the player (all known species, including never-seen ones) |
| `GET /api/players/{uuid}/events` | SSE, update signal |
| `GET /api/species/{id}` | Static per-species info (types, stats, spawns), cached |
| `GET /api/capture-log` | Last 200 captures across all players (newest first) |
| `GET /api/capture-log/events` | SSE, update signal for the activity feed |
| `GET /api/leaderboard` | Three ranked categories: completion %, shinies caught, captures this week |
| `GET /api/leaderboard/events` | SSE, update signal for the leaderboard |

The `tier` field (`caught`/`seen`/`unregistered`) is a stable vocabulary
owned by this API, derived via `.ordinal` - never from Cobblemon's raw enum
name, which changed between versions (`NONE/ENCOUNTERED/CAUGHT` in 1.7.3 vs
`UNREGISTERED/SEEN/OWNED` later). `knowledgeRaw` keeps the raw name for
debugging only.

`caughtAtMillis` (present once a species is caught) isn't a Cobblemon field -
Cobblemon doesn't track catch dates. `CaptureDates` records it itself the
first time `POKEDEX_DATA_CHANGED_POST` reports a species reaching "caught"
(`event.knowledge.ordinal == 2`), identifying the species via
`event.dataSource.getApparentSpecies()`. Species caught before this feature
existed simply have no recorded date.

`CaptureLog` records the same "genuinely new capture" transitions (reusing
`CaptureDates.recordIfMissing`'s return value to avoid double-detection
logic), keyed by player + species + shiny flag (`event.dataSource.pokemon.shiny`).
It's a flat, capped (200 entries) rolling log, not tied to any one player -
powers the persistent Activity panel, a cross-player feed independent of
which player tab is selected.

`PlayerProgress.summarize` is the one place that computes "which species
count, what tier" for a given player - extracted out of `PokedexHandler` so
`LeaderboardHandler` (looping every known player) uses the exact same
denominator instead of a second, possibly-diverging filter.
`PlayerProgress.relevantSpeciesIds` alone is the shared "known species"
filter (implemented OR in the spawn pool); `summarize` builds on it but
doesn't produce per-species JSON (no `CobblemonLang` calls) - that stays in
`PokedexHandler`, which needs the detail the leaderboard doesn't.

`DiscordNotifier` posts on the same event hook as `CaptureLog`/`CaptureDates`
(`POKEDEX_DATA_CHANGED_POST`), gated by `discord.conf`. Fire-and-forget on
its own single-thread daemon executor (`java.net.http.HttpClient`, no extra
dependency) - never blocks the Cobblemon event-dispatch thread, and keeps
working even if `webserver.conf enabled=false` (dashboard off, webhook still
wanted), since it has its own lifecycle separate from `CobbleSyncWebServer`.
A capture that is both a first-ever species catch *and* shiny only posts the
shiny message (see the `else if` in `CobbleSync.kt`'s event subscription).

The 1v1 comparison panel has no dedicated endpoint - it fetches
`/api/players/{uuid}/pokedex` for both selected players and diffs
client-side (`renderComparisonSide` in `app.js`). No SSE hookup: it's an
on-demand check (re-select a player to refresh), not an ambient panel like
Activity/Leaderboard.


## Configuration (`config/cobblesync/webserver.conf`)

```
enabled=true          # false disables the built-in server (external reverse proxy)
bind-address=0.0.0.0
port=8080
```

## Configuration (`config/cobblesync/discord.conf`)

```
enabled=false          # set to true and fill webhook-url to enable
webhook-url=
language=en             # en or fr - independent of any dashboard viewer's toggle
notify-shiny=true       # post on every shiny catch
notify-new-species=false  # post on a player's first-ever catch of a species
```

## Before publishing (Modrinth/CurseForge)

- No LICENSE file yet.
- No icon in `fabric.mod.json`.
- Decide on minimal access control, or clearly document the risk.
