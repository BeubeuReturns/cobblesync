# Projet : Mod Cobblemon — Pokédex web synchronisé

## Objectif
Créer un mod Fabric pour Cobblemon, publiable (Modrinth/CurseForge), qui expose
un dashboard web affichant la progression du pokédex (vu/capturé/formes) de
chaque joueur d'un serveur Minecraft — inspiré de cobblemon.tools/pokedex pour
la présentation, mais avec les vraies données de progression des joueurs.

## Décisions d'architecture
- **Mod loader** : Fabric (aligné avec Cobblemon, qui supporte aussi NeoForge
  mais Fabric a été choisi).
- **Le mod sert lui-même la page web**, sur le modèle de **BlueMap** :
  - Serveur HTTP intégré au mod (pas de dépendance externe lourde ;
    candidats : `com.sun.net.httpserver.HttpServer` du JDK, ou NanoHTTPD).
  - Contenu statique (HTML/JS/CSS) packagé dans le jar ou extrait vers
    `config/<modid>/web/` au premier lancement (personnalisable par l'admin).
  - Endpoints dynamiques type `/api/players/{uuid}/pokedex` générés à la
    volée depuis les données en mémoire/Mongo, pas de fichiers à relire à
    chaque requête.
  - Config type `webserver.conf` (port, bind address, activer/désactiver le
    serveur intégré pour ceux qui préfèrent un reverse proxy).
- **Rafraîchissement** : écouter les events Cobblemon plutôt que du polling.

## Stockage des données Cobblemon (confirmé par grep du repo source)
Repo : https://gitlab.com/cable-mc/cobblemon

Deux systèmes de stockage distincts :
- **Party/PC des Pokémon** : NBT, dans `pokemon/playerpartystore` et
  `pokemon/pcstore` du dossier du monde, par UUID joueur.
- **Données joueur (dont le pokédex)** : backend pluggable, PAS forcément
  NBT — d'où l'échec de recherche via NBT Explorer. Trois implémentations
  trouvées dans le code :
  - `PlayerDataJsonBackend.kt` / `DexDataJsonBackend.kt` → défaut (JSON)
  - `PlayerDataNbtBackend.kt` / `DexDataNbtBackend.kt` → option NBT
  - `PlayerDataMongoBackend.kt` / `DexDataMongoBackend.kt` → **MongoDB
    natif**, pas besoin d'un addon tiers (CobbledSync) pour ça.

### Classes API clés à utiliser (au lieu de parser des fichiers)
- `api/pokedex/PokedexManager.kt`, `AbstractPokedexManager.kt` — lecture de
  la progression pokédex d'un joueur (capturé/vu/formes).
- `api/storage/player/PlayerInstancedDataStoreManager.kt`,
  `PlayerInstancedDataStoreType.kt` — accès aux différentes données joueur.
- `api/storage/player/GeneralPlayerData.kt`, `InstancedPlayerData.kt` —
  structures de données.
- `api/events/pokemon/PokedexDataChangedEvent.kt` et
  `api/events/pokedex/scanning/PokemonScannedEvent.kt` — events à écouter
  pour mettre à jour le dashboard en temps réel sans polling.

## API pokédex — méthodes confirmées (lecture du code source local)
Repo Cobblemon cloné dans `C:\Users\Game\Documents\GitHub\cobblemon`.

### Récupérer le manager d'un joueur
- Singleton global : `Cobblemon.playerDataManager` (type
  `PlayerInstancedDataStoreManager`), initialisé dans `SERVER_STARTING`
  (`Cobblemon.kt`).
- `playerDataManager.getPokedexData(playerId: UUID): PokedexManager` — accès
  direct par UUID, peu importe le backend (JSON/NBT/Mongo), sans se soucier
  du parsing de fichiers.
- Existe aussi `getGenericData(uuid): GeneralPlayerData` et
  `getTMData(uuid): TMMoveManager` sur le même manager.

### Structure des données pokédex (`PokedexManager` / `AbstractPokedexManager`)
- `speciesRecords: MutableMap<ResourceLocation, SpeciesDexRecord>` — une
  entrée par espèce rencontrée/possédée.
- Par espèce (`SpeciesDexRecord`) :
  - `getKnowledge(): PokedexEntryProgress` — niveau max toutes formes
    confondues (`UNREGISTERED`, `SEEN`, `OWNED`).
  - `highestLevel: Int` — plus haut niveau possédé pour l'espèce.
  - `getAspects(): Set<String>` — aspects cosmétiques vus (inclut
    indirectement genre/forme).
  - `getFormRecord(formName): FormDexRecord?` / `formRecords` — détail par
    forme.
- Par forme (`FormDexRecord`, dans `SpeciesDexRecord.kt` voisin) :
  `knowledge`, `getGenders()`, `getSeenShinyStates()` — c'est ici que vit
  l'info shiny/genre par forme.
- Méthodes pratiques côté `AbstractPokedexManager` : `getEncounteredForms`,
  `getCaughtForms`, `getSeenShinyStates`, `getSeenGenders`,
  `getHighestKnowledgeForSpecies`.

→ Tout ce qu'il faut pour construire le payload JSON de
`/api/players/{uuid}/pokedex` (vu/capturé/formes/shiny/genre) est exposé
sans avoir à parser NBT/JSON/Mongo directement.

### Events temps réel (confirmés)
- `PokedexDataChangedEvent.Post` (package
  `api.events.pokemon`) — fire à chaque MAJ d'une `FormDexRecord` ; expose
  `playerUUID`, `knowledge` (nouveau niveau), `record` (la FormDexRecord),
  `dataSource` (le Pokémon/disguise concerné). C'est l'event à écouter pour
  pousser les mises à jour au dashboard sans polling.
- `PokemonScannedEvent` (package `api.events.pokedex.scanning`) — fire
  quand un joueur scanne un Pokémon avec le Pokédex (avant que la
  connaissance ne soit forcément mise à jour) ; utile si on veut aussi
  logger les tentatives de scan.

## Scaffold du mod "cobbledex" (créé, build OK)
Projet Fabric standalone dans ce dossier (`build.gradle.kts`, `settings.gradle.kts`,
`src/main/kotlin/com/cobbledex/...`). `./gradlew build` réussit (jar produit dans
`build/libs/cobbledex-0.1.0.jar`). Points importants pour ne pas refaire les mêmes
erreurs :

- **Plugin Loom** : utiliser `id("fabric-loom")` (standard), PAS
  `dev.architectury.loom` — la fork architectury tire des dépendances
  Forge/NeoForge (`installertools`, `mcinjector`, `DiffPatch`) qui ne
  résolvent pas sans le dépôt `maven.minecraftforge.net`. On n'a pas besoin
  du multi-loader ici.
- **JDK** : la machine n'a qu'un JDK 25 (trop récent pour Gradle 8.14.3) et
  un JRE 21. Un JDK 21 complet a été installé manuellement par l'utilisateur
  dans `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`, référencé
  via `org.gradle.java.home` dans `gradle.properties` (n'affecte que ce
  projet, pas le `JAVA_HOME` système).
- **Dépendance Cobblemon (Modrinth Maven)** : coordonnées
  `maven.modrinth:cobblemon:<version_number>` où `version_number` est
  exactement la chaîne affichée sur Modrinth (ex. `1.7.3`), **sans** suffixe
  `+mc_version` — vérifié via `api.modrinth.com/v2/project/cobblemon/version`.
- **Dérive repo local vs release publiée** : le repo Cobblemon cloné
  localement est en `mod_version=1.8.0` (dev, non publié), en avance sur la
  dernière release Modrinth utilisée (`1.7.3`). Au moins un champ vu dans le
  code source local n'existe pas encore dans le jar publié :
  `SpeciesDexRecord.highestLevel`/`getHighestLevel()` (retiré du payload
  JSON pour l'instant, commentaire laissé dans `PokedexHandler.kt`). **Avant
  d'utiliser une méthode/propriété vue en lisant le repo source local,
  vérifier qu'elle compile contre le jar Modrinth réellement déclaré dans
  `gradle.properties`**, pas seulement contre le code source.
- Kotlin : les symboles de Cobblemon remappés par Loom peuvent ne pas être
  accessibles en syntaxe propriété Kotlin (`record.highestLevel`) même
  quand la méthode existe — préférer l'appel explicite du getter
  (`record.getKnowledge()`, `record.getAspects()`, etc.) pour ce genre de
  dépendance externe remappée.
- **`object` Kotlin comme entrypoint Fabric** : `fabric.mod.json` doit
  déclarer l'entrypoint avec l'adaptateur `kotlin` explicitement, sinon
  Fabric Loader essaie d'instancier via un constructeur public (l'adaptateur
  Java par défaut) et crash au lancement (`IllegalAccessException`, le
  constructeur d'un `object` Kotlin est privé) :
  ```json
  "main": [{ "adapter": "kotlin", "value": "com.cobbledex.Cobbledex" }]
  ```
- **`"environment"` dans `fabric.mod.json`** : ne PAS mettre `"server"` même
  si le mod ne sert que du serveur HTTP — en solo le client héberge un
  serveur intégré mais reste physiquement l'environnement "client", donc
  Fabric Loader ne charge pas le mod du tout. Utiliser `"*"`.
- **Enum `PokedexEntryProgress` — noms legacy en 1.7.3** : la release
  publiée renvoie encore `NONE`/`ENCOUNTERED`/`CAUGHT` (pas
  `UNREGISTERED`/`SEEN`/`OWNED`, qui sont les noms du repo source local en
  dev). Confirmé en test réel : `{"knowledge":"CAUGHT", "aspects":["male"]}`
  pour un Bulbizarre capturé. Le frontend/dashboard devra gérer les deux
  nommages si `cobblemon_version` est mis à jour un jour vers une release où
  le renommage 1.8 est sorti.
- **`FormDexRecord.getKnowledge()` inaccessible** (contrairement à
  `SpeciesDexRecord.getKnowledge()`, une fonction explicite qui marche très
  bien) : `Unresolved reference` contre le jar 1.7.3, cause exacte pas
  identifiée (probablement un accesseur Kotlin auto-généré `private set`
  mal exposé après remap). Contournement utilisé dans `PokedexHandler.kt` :
  classer une forme via `pokedex.getCaughtForms(entry)` /
  `getEncounteredForms(entry)` (fonctions explicites, publiques, qui
  marchent) plutôt que de lire `formRecord.knowledge` directement, puis
  récupérer le nom réel via `PokedexEntryProgress.values()[n].name`
  (méthode Java native de l'enum, jamais affectée par ce genre de souci).
  `FormDexRecord.getGenders()` et `getSeenShinyStates()` sont des fonctions
  explicites elles aussi et fonctionnent normalement.

## Endpoint `/api/species/{id}` — infos statiques (types, stats, spawn)
Ajouté pour enrichir les cartes du dashboard (types, taille/poids, capacités,
groupes d'œufs, où trouver l'espèce en jeu). Indépendant du joueur, mis en
cache côté client (`speciesInfoCache` dans `app.js`). Testé en jeu, sprites +
types + capacités + spawn s'affichent correctement (captures d'écran
utilisateur validées). Les `SpawnDetail` dont `validBiomes` est vide (aucun
biome du monde ne satisfait la condition — souvent un tag de mod de compat
absent) sont filtrés côté backend plutôt qu'affichés en "biomes non résolus".
- Données d'espèce : `PokemonSpecies.getByIdentifier(resourceLocation): Species?`
  (`api.pokemon.PokemonSpecies`). Champs utilisés : `nationalPokedexNumber`,
  `primaryType`/`secondaryType` (`.name` sur `ElementalType`), `height`/`weight`
  (décimètres/hectogrammes → m/kg par /10.0), `abilities` (Iterable de
  `PotentialAbility`, `.template.name`), `eggGroups` (Iterable d'`EggGroup`,
  `.showdownID`).
- Données de spawn : `CobblemonSpawnPools.WORLD_SPAWN_POOL` (`SpawnPool`,
  implémente `Iterable<SpawnDetail>`) filtré aux `PokemonSpawnDetail` dont
  `pokemon.species` correspond à `species.showdownId()`. Le biome final
  résolu (tags compris) est dans `detail.validBiomes: Set<ResourceLocation>`
  — calculé par Cobblemon lui-même au démarrage serveur, pas besoin
  d'interpréter les conditions JSON (biomes/tags/sky light) nous-mêmes.
- **`SpawnDetail.bucket` n'est PAS un `String` dans le jar 1.7.3** (cause
  résolue) : contrairement au repo source local (dev, `var bucket = ""`),
  dans la release publiée `bucket` est typé
  `com.cobblemon.mod.common.api.spawning.SpawnBucket` (classe qui n'existe
  plus dans le repo local — refactorée vers un simple `String` après la
  1.7.3). `.toString()` donnait donc `SpawnBucket@<hash>` au lieu du nom
  ("common"/"rare"/etc). Confirmé en testant en jeu. Fix : `detail.bucket.name`
  (compile et affiche correctement). Encore un cas de dérive de version, pas
  un souci d'accesseur Kotlin cette fois.
- **Pas de sprites 2D dans Cobblemon** (uniquement des textures de modèle
  3D) : sur decision utilisateur, le frontend pointe vers
  `raw.githubusercontent.com/PokeAPI/sprites` (official-artwork) via le
  numéro de pokédex national — dépendance réseau externe assumée pour cette
  seule fonctionnalité (le reste du dashboard reste self-hosted).

## Refonte UI (tri/filtre/couleurs) — sur demande utilisateur
- `/api/players/{uuid}/pokedex` renvoie maintenant aussi `nationalDexNumber`
  par espèce (via `PokemonSpecies.getByIdentifier`), pour trier/filtrer côté
  client sans attendre les fetch async de `/api/species/{id}` par carte.
- Frontend : tri par n° de pokédex (plus alphabétique), filtre par
  génération (bornes du national dex codées en dur jusqu'à Gén. IX/1025).
- **`totalKnownSpecies`** : `Species.implemented: Boolean` (exposé via
  `PokemonSpecies.implemented`) ne couvre QUE le roster officiel Cobblemon —
  testé en jeu avec l'addon **MissingMons** : le compteur n'a bougé que de
  +1 alors que l'addon ajoute bien plus d'espèces, confirmant que ces
  addons ne positionnent pas ce flag. Fix : une espèce compte dans le
  dénominateur si `implemented == true` **OU** si elle apparaît dans
  `CobblemonSpawnPools.WORLD_SPAWN_POOL` (comparaison par
  `species.showdownId()` vs `PokemonSpawnDetail.pokemon.species`) — donc
  réellement spawnable en jeu, peu importe qui l'a ajoutée. Limite connue :
  une espèce d'addon uniquement obtenable sans spawn naturel (échange,
  œuf/starter exclusif, événement) ne serait toujours pas comptée — pas
  rencontré en pratique mais à garder en tête si un utilisateur signale un
  total encore trop bas avec un autre addon.
  **Validé en jeu** : 851 → 881 après ajout de MissingMons (+30, cohérent),
  carte Ogerpon (espèce ajoutée par l'addon) affichée correctement.

## Cache + push temps réel (implémenté)
- `data/WorldDataCache.kt` : cache (un seul `WorldSnapshot`, `@Volatile` +
  double-checked locking) de `entriesBySpecies`/`implementedSpeciesIds`/
  `spawnableShowdownIds` — calculé une fois au lieu de reparcourir dex/
  espèces/spawn pool à chaque requête HTTP, pour chaque joueur. Utilisé par
  `PokedexHandler`.
- `data/SpeciesInfoCache.kt` : cache du JSON complet par espèce
  (`ConcurrentHashMap`), car `/api/species/{id}` ne change jamais pendant
  qu'un serveur tourne. Utilisé par `SpeciesInfoHandler`.
- Invalidation : les deux caches sont vidés sur rechargement de données
  Cobblemon, via abonnement à `Dexes.observable`, `PokemonSpecies.observable`
  et `CobblemonSpawnPools.WORLD_SPAWN_POOL.observable` — câblé dans
  `Cobbledex.kt` **à l'intérieur de `SERVER_STARTED`** (pas `onInitialize`),
  car `WORLD_SPAWN_POOL` n'est initialisé par Cobblemon qu'à
  `SERVER_STARTING`.
- Push temps réel : `PokedexHandler` sert aussi `GET
  /api/players/{uuid}/events` en SSE (`text/event-stream`, chunked via
  `sendResponseHeaders(200, 0)`, boucle bloquante avec ping `:ping\n\n`
  toutes les 25s pour éviter les timeouts). `PokedexEventBroadcaster`
  (`web/PokedexEventBroadcaster.kt`) garde les connexions ouvertes par UUID
  et pousse un event `pokedex-updated` (signal seul, pas de diff) sur
  `CobblemonEvents.POKEDEX_DATA_CHANGED_POST`. Le frontend écoute via
  `EventSource` (`connectEvents()` dans `app.js`, une connexion par joueur
  sélectionné, fermée/rouverte au changement d'onglet) et refait un simple
  `loadPokedex(uuid)` classique à la réception — pas de diff côté client
  non plus, on garde ça simple.
- **Validé en jeu** : capture pendant que le dashboard reste ouvert → mise à
  jour automatique sans F5, confirmé par l'utilisateur.

## Optimisations supplémentaires + pokédex complet (sur demande utilisateur)
- **Debounce recherche** (`app.js`) : 200ms avant de re-render la grille sur
  saisie, évite de tout reconstruire à chaque frappe.
- **ETag + `Cache-Control: no-cache`** sur `StaticFileHandler` : identité
  bon marché (mtime+taille, pas de hash de contenu), réponse 304 si
  `If-None-Match` correspond — économise le re-téléchargement du corps sans
  jamais servir un fichier périmé après une modif admin.
- **Noms de capacités via le lang file Cobblemon** (`data/AbilityNames.kt`) :
  charge une fois `assets/cobblemon/lang/en_us.json` depuis le classpath
  (bundlé dans le jar Cobblemon, accessible même côté serveur), résout
  `cobblemon.ability.<nom interne>` → nom propre ("magicguard" → "Magic
  Guard"). Repli sur simple capitalisation si la clé est introuvable
  (version différente, addon). Jamais invalidé (fichier statique du jar).
- **Pokédex complet affiché, y compris les espèces jamais vues** :
  `PokedexHandler` itère maintenant sur TOUTES les espèces "connues" (même
  filtre que `totalKnownSpecies` : implémentée ou spawnable), pas seulement
  `pokedex.speciesRecords`. Pour une espèce jamais rencontrée
  (`record == null`), seul `tier:"unregistered"` + `nationalDexNumber` sont
  renvoyés — pas d'aspects, pas de formes, pas de sprite (pas de spoil,
  comme un vrai pokédex). Le frontend distingue ces entrées et rend une
  carte minimale ("???" + numéro, opacité réduite, pas de fetch
  `/api/species/{id}`).
- **Filtre par défaut passé à "Capturés"** (au lieu de "Tous") : afficher le
  pokédex complet par défaut serait très bruyant (800+ cartes "???"). Le
  segmented control a aussi été réordonné/renommé : Capturés / Vus
  seulement / Tout le pokédex.
- Conséquence attendue : la réponse `/api/players/{uuid}/pokedex` est
  beaucoup plus grosse qu'avant (toutes les espèces connues, pas juste
  celles du joueur) — resté acceptable en taille (JSON, pas d'assets) pour
  l'usage visé (dashboard self-hosted, pas de pagination ajoutée).

## Internationalisation FR/EN (sur demande utilisateur — l'UI était un mix des deux langues)
- **`data/CobblemonLang.kt`** (remplace `AbilityNames.kt`) : charge
  `assets/cobblemon/lang/en_us.json` ET `fr_fr.json` depuis le jar Cobblemon
  (confirmé présents dans le repo source : clés `cobblemon.ability.<nom>` et
  `cobblemon.species.<path>.name`, ex. `cobblemon.species.bulbasaur.name` →
  "Bulbizarre"). Résout noms de capacités ET noms d'espèce dans les deux
  langues, jamais invalidé (fichiers statiques du jar).
- **`/api/species/{id}`** renvoie maintenant `nameEn`/`nameFr` (racine) et
  `abilities` sous forme d'objets `{en, fr}` (plus une liste de strings) —
  les deux langues sont dans le même objet caché, donc changer de langue
  dans l'UI ne déclenche aucune requête réseau supplémentaire (déjà en
  cache). `eggGroups` envoie maintenant la constante d'enum stable
  (`HUMAN_LIKE`) plutôt que le libellé anglais (`Human-Like`), pour que le
  frontend puisse la traduire sans dépendre du wording anglais exact.
- **Frontend** (`app.js`) : dictionnaire `STRINGS.{fr,en}` pour tout le
  chrome UI (labels, boutons, placeholders — appliqué aux éléments
  `[data-i18n]`/`[data-i18n-placeholder]` de `index.html` via
  `applyStaticI18n()`), + tables `TYPE_NAMES`/`EGG_GROUP_NAMES`/
  `RARITY_NAMES` codées en dur côté client (vocabulaire fixe et restreint,
  pas besoin d'un fichier de langue pour ça). Toggle FR/EN dans le header
  (persisté via `localStorage`), change la langue sans re-fetch réseau
  (juste un re-render depuis les données déjà en cache/mémoire).
- **Limite assumée** : les noms de biomes (dans "Où le trouver") restent
  dérivés de l'identifiant Minecraft (toujours en anglais capitalisé,
  ex. "Birch Forest"), pas traduits. Les lang files de Minecraft lui-même
  (pas Cobblemon) ne sont pas garantis présents sur un vrai serveur dédié
  (contrairement au jar Cobblemon qui embarque toujours tous ses assets) —
  risque de rupture jugé pas rentable pour traduire ~70 noms de biomes.
- Pas encore testé en jeu au moment de la rédaction (vérifier le toggle
  FR/EN, en particulier que les noms d'espèces/capacités se mettent bien à
  jour sans re-fetch visible).
- Cartouches colorées : couleurs de type "classiques" Pokémon (convention
  reconnue, pas la palette catégorielle du skill dataviz — délibéré, demande
  explicite user), genre bleu/rose, groupes d'œufs violet, biomes teal,
  raretés colorées par palier (commun/peu commun/rare/ultra-rare), shiny en
  doré. Capacités et raretés capitalisées (simple 1ère lettre / split
  hyphen+titlecase — pas de résolution via lang file Cobblemon, jugé hors
  scope pour cette itération).
- Accent UI général passé de bleu à vert (`--accent`), plus proche de
  l'identité visuelle Cobblemon.
- Cartes restructurées : sprite + n°dex + nom + badge statut en header,
  rangées de cartouches séparées (types / genre-shiny), grille taille/poids
  à deux colonnes, spawns groupés par entrée avec rareté+niveau en tête et
  biomes en cartouches dédiées (au lieu d'une liste texte brute).

## État actuel : backend + frontend construits, backend testé en jeu
`/api/players/{uuid}/pokedex` répond correctement en solo (serveur intégré),
avec le détail par forme (genre + shiny) inclus. Testé avec un shiny réel
(Abra : `aspects:["shiny","male"]`, `forms.Normal.shinyStates:["shiny"]`) —
confirme que la détection shiny fonctionne. Jar installé manuellement dans
`C:\Users\Game\AppData\Roaming\ModrinthApp\profiles\Test Cobblemod\mods\`
pour les tests — à recopier depuis `build\libs\cobbledex-0.1.0.jar` après
chaque changement tant qu'il n'y a pas de tâche Gradle dédiée pour ça.

### Contrat JSON stabilisé (indépendant du nommage d'enum Cobblemon)
`/api/players/{uuid}/pokedex` renvoie maintenant `caughtCount`, `seenCount`,
`totalKnownSpecies` (taille de l'index espèce→dex, toutes dexes confondues),
et par espèce/forme un champ `tier` (`"caught"`/`"seen"`/`"unregistered"`) —
vocabulaire propre à l'API, dérivé via `.ordinal` ou l'appartenance aux
listes `getCaughtForms`/`getEncounteredForms`, jamais via un nom d'enum en
dur. `knowledgeRaw` garde le nom brut de l'enum pour debug uniquement.

### Liste des joueurs (`/api/players`) — nouveau
Cobblemon n'expose pas d'API pour énumérer tous les joueurs connus. Solution :
`PlayerRegistry` (`player/PlayerRegistry.kt`) — un registre maison uuid→pseudo,
alimenté par `ServerPlayConnectionEvents.JOIN` (Fabric API) dans `Cobbledex.kt`,
persisté dans `config/cobbledex/players.json`. `PlayersHandler` l'expose en
JSON. **Piège d'extraction statique** : `StaticFileExtractor` ne réécrit
JAMAIS `config/cobbledex/web/` s'il existe déjà (pour ne pas écraser une
personnalisation admin) — en dev, après une modif de `web/*.js|css|html`, il
faut recopier manuellement le dossier `src/main/resources/web/*` par-dessus
`config/cobbledex/web/` du profil de test (ou supprimer ce dossier pour
forcer une ré-extraction), sinon les changements ne s'affichent pas.

### Frontend (`src/main/resources/web/`)
Page statique vanilla HTML/CSS/JS (pas de build step), thème clair/sombre via
`prefers-color-scheme`, palette et conventions du skill dataviz interne
(statut "good"=vert pour capturé, tuiles KPI, meter de progression). Onglets
par joueur (un par pseudo connu), résumé (capturés/vus/% via meter), filtre
recherche + segmented (Tous/Capturés/Vus), grille de cartes espèce avec
pastilles (shiny/genre/aspects) et détail dépliable par forme.

## Spawns liés à une structure (ex. manoir), pas seulement aux biomes
Sur question utilisateur ("un Pokémon peut spawn dans un manoir non ?") :
confirmé dans le repo source, `SpawningCondition.structures:
MutableList<Either<ResourceLocation, TagKey<Structure>>>?` — un champ
séparé de `biomes` sur la même condition. Contrairement aux biomes, pas de
précalcul type `validBiomes` (une structure a une position dans le monde,
pas une liste finie énumérable) : on lit `detail.conditions` directement et
on convertit chaque `Either` via `.map({ it.toString() }, { "#" + it.location() })`.
**Bug corrigé par la même occasion** : le filtre "pas de biome résolu = pas
affiché" (ajouté plus tôt pour les tags de biome non résolus) excluait
silencieusement TOUS les spawns liés uniquement à une structure, sans biome
du tout. Condition changée pour : biomes vides ET structures vides → on
masque ; sinon on affiche ce qu'on a. Le frontend affiche les structures en
cartouches marron distinctes des biomes (teal), même limite de traduction
(nom dérivé de l'identifiant, pas de lang file Minecraft testée server-side).

## Reskin visuel "boîtier Pokédex" (sur demande utilisateur)
Le thème est passé du bleu/vert dataviz générique à quelque chose de plus
proche du Pokédex en jeu (capture d'écran fournie par l'utilisateur), en
gardant la mise en page dashboard existante (option "reskin léger" choisie
plutôt qu'une réplique complète du boîtier — moins pratique pour parcourir
des centaines de cartes).
- **Accent rouge Pokédex** (`--accent`) à la place du vert Cobblemon
  précédent — les deux demandes ("plus Cobblemon" puis "esprit boîtier
  Pokédex") pointaient vers deux identités visuelles différentes ; celle-ci
  est la plus récente et prime. `--status-good` (vert, badge "Capturé")
  inchangé, aucun conflit.
- **Surfaces teintées cyan** au lieu de gris neutre pur (`--surface-1/2`),
  rappel de l'écran cyan du boîtier.
- **Coins carrés** (`--radius: 3px`) sur cartes/tuiles/inputs/segmented —
  les badges/pills gardent leur forme arrondie (999px), ce sont des tags,
  pas des panneaux.
- **Pas de police pixel embarquée** : Cobblemon ne bundle aucun fichier de
  police (juste des textures bitmap pour son propre rendu in-game, pas
  réutilisable en CSS `@font-face`). Plutôt que de télécharger/embarquer une
  police tierce (risque de dépendance externe ou de gestion de licence non
  négligeable pour un "reskin léger"), utilisé une police monospace système
  (`--font-digital`, `Courier New`/`ui-monospace`) sur les nombres (n° dex,
  valeurs KPI) et le titre — donne un effet "afficheur numérique" sans
  fichier binaire à gérer. Si l'utilisateur veut une vraie police pixel
  plus tard, ce serait une police open-license à bundler dans
  `src/main/resources/web/` (ex. Press Start 2P, OFL) + `@font-face`.
- **Validé en jeu** : accent rouge, surfaces cyan, coins carrés, police
  digitale — confirmé par l'utilisateur ("c'est clean"), avec un seul
  ajustement demandé (surfaces trop vertes/pas assez bleu-cyan, corrigé en
  poussant B au-dessus de G dans les teintes de fond).

## Investigation abandonnée : rendus 3D authentiques des Pokémon (sprites)
Sur demande utilisateur, tentative de remplacer les sprites PokeAPI externes
par de vrais rendus 3D des modèles Cobblemon. Deux approches essayées,
toutes deux abandonnées — recorder ici pour ne pas repartir de zéro si le
sujet revient.

**Tentative 1 : capture en jeu (client Fabric)**
- Ajouté un entrypoint client + commande `/cobbledex exportsprites`
  (`SpriteExportScreen`), réutilisant `drawProfilePokemon()` de Cobblemon
  (`client/gui/PokemonGuiUtils.kt`) — la même fonction que l'écran Pokédex/
  PC/équipe utilisent pour afficher un Pokémon hors-monde via
  `RenderablePokemon(species, aspects)` + `FloatingState()`. Fonction
  confirmée réutilisable et documentée (voir git history de ce fichier pour
  le détail de l'API si besoin).
- **Abandonné** : capture via `Screenshot.takeScreenshot(mc.mainRenderTarget)`
  provoque un blocage silencieux du rendu (aucune exception, aucun crash,
  juste plus aucune frame après le premier appel) — suspecté lecture
  concurrente du framebuffer en cours d'écriture. Décaler la capture au
  début de la frame SUIVANTE (buffer précédent garanti figé) n'a pas
  résolu le problème, donc la cause exacte reste non identifiée (jeu resté
  responsive/tickant, pas un vrai freeze JVM — possiblement un souci GPU/
  driver non catchable côté Java). Code retiré du mod (`client/` package
  supprimé, entrypoint `client` retiré de `fabric.mod.json`) pour ne pas
  shipper une commande qui bloque.

**Tentative 2 : extraction + rendu externe (Python, hors Minecraft)**
Plus prometteur mais révélé être un projet à part entière une fois creusé.
- Environnement : Python 3.14 disponible dans le contexte d'exécution (même
  machine que le jeu, même GPU AMD Radeon RX 9070). `moderngl` a échoué à
  s'installer (nécessite un compilateur C++ absent). Fonctionne avec
  `PyOpenGL` + `glfw` (wheels précompilées, aucune compilation requise) +
  `Pillow`, contexte OpenGL 3.3 hors-écran fonctionnel (fenêtre GLFW
  invisible + FBO).
- **Format des modèles** confirmé : Bedrock/Blockbench standard
  (`bedrock/pokemon/models/{ndex}_{nom}/{nom}.geo.json`) — hiérarchie de
  "bones" avec `pivot`/`parent`/`rotation` de repos (pas juste une pose
  neutre : carapace/queue/oreilles ont des rotations non nulles même au
  repos), et des "cubes" par bone avec `origin`/`size`/`uv`/`inflate`/
  `mirror`, certains avec leur propre `pivot`+`rotation` local.
- **Bug résolu #1 (majeur)** : Blockbench utilise le sens INVERSE de la
  convention main-droite standard pour ses angles de rotation en degrés —
  trouvé empiriquement en rendant 12 variantes (6 ordres d'axes × signe) et
  comparant visuellement ; l'ordre des axes importait peu, seul le signe
  changeait tout. Sans ce fix : silhouette en "escalier"/éclatée
  (accumulation d'erreur à travers la hiérarchie de bones). Avec : Blastoise
  reconnaissable.
- **Bug résolu #2** : quelques cubes isolés (ex. un cube de doigt de
  Blastoise) ont un `pivot` propre totalement excentré de leur géométrie
  (26 unités d'écart), créant un bras de levier qui les envoie très loin
  avec la moindre rotation — probablement une bizarrerie ponctuelle des
  données. Contournement : ignorer la rotation propre au cube si
  distance(centre, pivot) > 4× la diagonale du cube.
- **UV mapping** : implémenté le "box UV" standard Minecraft/Blockbench
  (texture en croix : up/down au-dessus, west/north/east/south en bande) —
  fonctionne dès le premier essai texturé (Blastoise correctement marron/
  bleu). Format UV par face (dict, alternative au simple `[u,v]`) repéré
  dans les fichiers mais non géré (juste ignoré).
- **Filtrage texture** : `GL_LINEAR` donnait un rendu flou (signalé par
  l'utilisateur) — `GL_NEAREST` est le bon choix pour ces textures.
- **Blocage final, pourquoi arrêté** : la POSE reste fausse même une fois
  la géométrie/hiérarchie correcte, car Cobblemon n'affiche jamais un
  Pokémon en pose de repos brute du `.geo.json` — une animation "idle" est
  toujours appliquée par-dessus
  (`bedrock/pokemon/animations/{ndex}_{nom}/{nom}.animation.json`, ex.
  `animation.pikachu.ground_idle`). Ces animations utilisent **MoLang**,
  un petit langage d'expressions évalué par bone
  (ex. `"-15+math.sin(q.anim_time*90*4-60)*-2"` pour la rotation), et
  peuvent aussi déplacer les bones (`position`, pas seulement `rotation` —
  jamais implémenté ici). Reproduire ça demanderait un évaluateur MoLang
  minimal (arithmétique + `math.sin`/`cos` + variable `q.anim_time`) et la
  résolution du nom d'animation "idle" par espèce (pas standardisé). Testé
  aussi sur 4 espèces (Bulbasaur, Pikachu, Charizard, Blastoise avant le
  fix pose) : Pikachu et Blastoise bons, Bulbasaur (bulbe) et Charizard
  (ailes) clairement cassés — confirme que même la géométrie a encore des
  cas particuliers par espèce à déboguer.
- **Décision** : trop gros pour continuer dans cette session (évaluateur
  MoLang + positions de bone + cas particuliers géométrie par espèce +
  batch sur ~1025 espèces + shiny + intégration mod = projet à part
  entière). Scripts Python laissés dans le scratchpad de session (non
  committés au repo, à recréer si le sujet revient :
  `bedrock_render2.py` a le pipeline complet géométrie+texture le plus
  abouti). Dashboard reste sur les sprites PokeAPI externes.

## Sprites : essais successifs sur demande utilisateur
- `official-artwork` (choix initial) → `showdown` (pixel art animé, jugé
  "trop pixelisé" avec `image-rendering: pixelated`, puis "flou" une fois
  ce rendering retiré — sprites sources trop petites nativement pour la
  taille de carte affichée, aucun des deux réglages ne convient) → `home`
  (choix actuel). Les trois sont dans `sprites/pokemon/other/<variante>/`
  du repo `PokeAPI/sprites`, format uniforme `{national_dex_number}.png`
  (`.gif` pour showdown) — un seul point à changer dans `spriteUrl()`
  (`app.js`) si on veut retester une autre variante.

## Description des capacités au survol (sur demande utilisateur)
`CobblemonLang` étendu avec `abilityDescEn`/`abilityDescFr`, clé
`cobblemon.ability.<nom>.desc` (confirmée présente en EN et FR dans le lang
file Cobblemon). Champs `descEn`/`descFr` ajoutés à chaque objet capacité
dans `/api/species/{id}` (nullable : absente pour certaines capacités
selon version/addons). Frontend : `title` HTML natif sur la cartouche
(pas de tooltip JS custom), soulignement pointillé + `cursor: help` en CSS
pour indiquer l'affordance.

## Renommage : cobbledex → CobbleSync (sur demande utilisateur)
Le nom "cobbledex" entrait en collision avec un projet GitHub existant sans
rapport (`Rafacasari/cobbledex`), repéré lors de l'investigation des rendus
3D — problème à régler avant toute publication Modrinth/CurseForge. Après
un brainstorm (plusieurs pistes refusées : Cobbledash, DexSync, Cobbleview,
DexHub, CobbleScan, CobbleRotom, CobbleWatch, CobbleNav, CobblePulse,
Cobbledeck), l'utilisateur a tranché pour **CobbleSync**, tout en gardant
l'esprit visuel "Rotomdex" pour l'interface (voir section suivante).
- **Toutes les occurrences renommées** : `mod_id`/`group` dans
  `gradle.properties` (`cobblesync`/`com.cobblesync`), `rootProject.name`
  dans `settings.gradle.kts`, le package Kotlin (`com.cobbledex` →
  `com.cobblesync`, dossier déplacé), l'objet `Cobbledex` → `CobbleSync`
  (fichier `CobbleSync.kt`), `CobbledexWebServer` → `CobbleSyncWebServer`,
  `fabric.mod.json` (`id`, `name`, entrypoint), et les quelques chaînes
  côté frontend (`<title>`, clé `localStorage`).
- **`MOD_ID` pilote le chemin de config** (`FabricLoader.configDir.resolve(MOD_ID)`
  dans `CobbleSync.kt`) : le renommage suffit à faire pointer tout le mod
  vers `config/cobblesync/` sans logique de migration à écrire. En dev,
  l'ancien `config/cobbledex/` (profil de test `Test Cobblemod`) a été
  laissé en place (pas supprimé) et son `players.json`/`webserver.conf` ont
  été copiés manuellement vers `config/cobblesync/` pour ne pas perdre
  l'historique des joueurs connus lors du test suivant — pas fait
  automatiquement par le mod, un vrai changement de modid n'a normalement
  pas vocation à migrer les données de l'ancien.
- Le dossier du dépôt Git reste nommé `cobbledex` sur disque (pas
  renommé : risque de casser des chemins ouverts côté IDE/outils pour un
  gain cosmétique nul, le nom du dossier n'apparaît nulle part dans le mod
  publié). Seul le nom affiché/publié (`CobbleSync`) compte.

## Reskin "boîtier Rotomdex" v2 + police pixel (sur demande utilisateur)
Après le renommage, l'utilisateur a fourni une image de référence (mockup)
d'un boîtier façon Rotomdex — cadre gris à coins chanfreinés, barre de
titre rouge avec flèches de navigation, écran avec grille et mascotte
(yeux/antenne) en haut, barre du bas avec un bouton "Filter" et une icône
grille. Décision explicite : *"j'aime bien cette idée"* pour l'interface,
mais *"pour le logo je vais le faire moi"* — la mascotte/icône reste à la
charge de l'utilisateur, seul le cadre autour a été construit ici.
- **Structure HTML** (`index.html`) : tout le contenu est maintenant dans
  `.device` (le boîtier), avec `.device-header` (flèches + titre),
  `.device-screen` (mascotte + toggle langue + onglets joueurs + dashboard),
  `.device-footer` (bouton filtres + icône grille décorative).
- **`.mascot-slot`** : emplacement réservé (`<img id="mascot-img"
  src="mascot.png">`), masqué automatiquement via l'event `error` en JS
  (`app.js`) si le fichier n'existe pas encore — pas d'icône d'image cassée
  tant que l'utilisateur n'a pas déposé son propre `web/mascot.png`.
- **Coins chanfreinés** du boîtier faits en pur CSS (`clip-path: polygon(...)`
  sur `.device`), pas de pseudo-éléments — plus simple et suffisant pour
  l'effet recherché (silhouette octogonale façon boîtier plutôt qu'un
  simple rectangle arrondi).
- **Flèches de navigation** (`#tabs-prev`/`#tabs-next`) rendues
  fonctionnelles plutôt que purement décoratives : elles cyclent l'onglet
  joueur actif (`cyclePlayerTab()` dans `app.js`, réutilise le
  `.click()` des boutons `.player-tab` existants) — cohérent avec l'esprit
  "device" de la maquette sans dupliquer la logique de sélection.
- **Bouton "Filtres" de la barre du bas** rendu fonctionnel : replie/déplie
  `#filter-panel` (la `.filter-row` existante, juste encapsulée avec un id)
  plutôt que d'être un simple élément de déco — l'icône grille à droite
  reste purement décorative (une seule vue grille existe, pas de bascule
  liste/grille à faire).
- **Grille de fond de l'écran** (`.device-screen`) : léger quadrillage via
  deux `linear-gradient` répétés (`background-size: 28px 28px`) en
  `var(--gridline)`, rappel discret de la grille visible dans la maquette
  sans surcharger la lisibilité des cartes par-dessus.
- **Police pixel** : `--font-digital` (déjà utilisée pour les nombres/titre
  depuis le reskin "boîtier Pokédex" v1) redéfinie pour pointer vers
  **Press Start 2P** (Google Fonts, OFL) en premier choix de la pile, puis
  appliquée à `body` entier — donc à toute l'interface, pas seulement aux
  éléments qui l'utilisaient déjà. Contrairement à la décision précédente
  documentée plus haut ("pas de police pixel embarquée" pour éviter une
  dépendance de licence à gérer), ici la police est chargée en direct
  depuis `fonts.googleapis.com`/`fonts.gstatic.com` (balises `<link>` dans
  `index.html`, pas de fichier bundlé dans le jar) — même logique de
  dépendance réseau externe déjà assumée pour les sprites PokeAPI. Aucun
  fichier de police à gérer/committer.
- Pas encore validé en jeu au moment de la rédaction (build Gradle
  réussi et jar redéployé, mais rendu visuel du boîtier/police pas encore
  confirmé par l'utilisateur) — à vérifier en priorité au prochain retour :
  lisibilité de Press Start 2P sur les textes denses (descriptions de
  capacités, badges), rendu des coins chanfreinés, comportement des
  flèches/bouton filtres.

## Vue par défaut "Tout le pokédex" + cartes "complètes" (sur demande utilisateur)
- **Filtre par défaut** repassé de "Capturés" à "Tout le pokédex" (`index.html` : classe
  `is-active` déplacée sur le bouton `data-filter="all"`, `currentFilter` initialisé à `"all"`
  dans `app.js`) — décision inverse de celle prise plus tôt dans le projet (l'affichage complet
  avait été jugé trop bruyant par défaut), l'utilisateur préfère maintenant voir tout le
  pokédex d'entrée.
- **Bordure spéciale pour les espèces "complètes"** (`isFullyComplete()` dans `app.js`,
  classe CSS `.species-card-complete` — liseré + halo dorés, même teinte que le badge shiny).
  Critères : `tier === "caught"`, toutes les formes connues à `tier === "caught"`, au moins un
  shiny vu sur une forme, et tous les genres réellement possibles pour l'espèce vus.
- **Deux enrichissements backend nécessaires côté `PokedexHandler.kt`** pour rendre ce calcul
  fiable (fait entièrement côté frontend, sans requête async supplémentaire par carte) :
  - Les formes **jamais rencontrées** sont maintenant incluses dans `forms{}` avec
    `tier:"unregistered"` (`genders`/`shinyStates` vides), au lieu d'être simplement absentes du
    JSON comme avant. Pas un problème de spoil : ce bloc n'est atteint que si l'espèce est déjà
    au moins "seen", donc on ne révèle rien sur une espèce totalement inconnue — juste "il existe
    encore une forme de cette espèce que tu n'as pas trouvée", ce qui sert justement le calcul de
    complétion. Sans ça, le frontend ne pouvait pas distinguer "toutes les formes connues sont
    capturées" de "il y a des formes qu'on n'a même pas encore vues".
  - Chaque espèce expose maintenant `possibleGenders` (ex. `["MALE","FEMALE"]`, `["GENDERLESS"]`,
    ou un seul des deux) via `Species.possibleGenders` (confirmé accessible tel quel contre le
    jar Cobblemon 1.7.3 publié, pas seulement le repo source local 1.8.0-dev — build vérifié).
    Sans cette donnée, la seule alternative aurait été de déduire "les deux genres sont
    possibles" à partir des genres déjà vus par le joueur, ce qui aurait produit un faux négatif
    permanent sur les espèces à genre unique (ex. Tauros/Miltank mâle uniquement) : elles
    n'auraient jamais pu valider le critère puisqu'on n'aurait jamais vu l'autre genre "manquant"
    qui n'existe pas. `Species.possibleGenders` lève l'ambiguïté à la source.

## Prochaine étape
- Tester le mod sur un vrai serveur dédié Fabric 1.21.1 (pas juste en
  solo), avec plusieurs joueurs pour valider les onglets et plusieurs
  connexions SSE simultanées — plus gros point aveugle actuel.
- Pas de contrôle d'accès sur le serveur web (n'importe qui sur le réseau
  voit le pokédex de tous les joueurs) — acceptable pour un serveur
  perso/entre amis, à documenter/traiter avant toute publication.
- Prêt à la publication (Modrinth/CurseForge, objectif d'origine) : pas de
  README, pas de LICENSE, pas d'icône dans `fabric.mod.json`.
- Valider en jeu le reskin "boîtier Rotomdex" v2 + police Press Start 2P
  (voir section ci-dessus) — non testé au moment de la rédaction.
- L'utilisateur doit encore fournir `web/mascot.png` (visage/mascotte façon
  Rotom) — l'emplacement `.mascot-slot` est prêt côté frontend.

## Environnement de dev
- Windows, avec Git Bash pour les commandes type grep/git (ou `rg`/ripgrep
  installé via winget pour des recherches plus rapides dans le repo source).
- Repo Cobblemon cloné localement (`C:\Users\Game\Documents\GitHub\cobblemon`)
  pour grep le code source au besoin — attention à la dérive de version
  ci-dessus.
- JDK 21 dédié au projet cobbledex : voir `org.gradle.java.home` dans
  `gradle.properties`.
