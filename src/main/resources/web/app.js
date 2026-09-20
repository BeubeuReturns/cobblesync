(() => {
  const tabsEl = document.getElementById("player-tabs");
  const tabsEmptyEl = document.getElementById("tabs-empty");
  const dashboardEl = document.getElementById("dashboard");
  const gridEl = document.getElementById("species-grid");
  const gridEmptyEl = document.getElementById("grid-empty");
  const searchInput = document.getElementById("search-input");
  const generationSelect = document.getElementById("generation-filter");
  const filterButtons = document.querySelectorAll(".filter-row .segmented-option");
  const langButtons = document.querySelectorAll(".lang-toggle .segmented-option");
  const kpiCaught = document.getElementById("kpi-caught");
  const kpiSeen = document.getElementById("kpi-seen");
  const kpiPercent = document.getElementById("kpi-percent");
  const kpiMeterFill = document.getElementById("kpi-meter-fill");
  const teamRowEl = document.getElementById("team-row");
  const teamRowCardsEl = document.getElementById("team-row-cards");
  const mascotImg = document.getElementById("mascot-img");
  const filterPanel = document.getElementById("filter-panel");
  const filterToggle = document.getElementById("filter-toggle");
  const tabsPrevBtn = document.getElementById("tabs-prev");
  const tabsNextBtn = document.getElementById("tabs-next");
  const activityFeedEl = document.getElementById("activity-feed");
  const activityEmptyEl = document.getElementById("activity-empty");
  const leaderboardListEl = document.getElementById("leaderboard-list");
  const leaderboardEmptyEl = document.getElementById("leaderboard-empty");
  const leaderboardTabButtons = document.querySelectorAll(".leaderboard-tabs .segmented-option");
  const comparePanel = document.getElementById("compare-panel");
  const compareASelect = document.getElementById("compare-a");
  const compareBSelect = document.getElementById("compare-b");
  const compareAHeader = document.getElementById("compare-a-header");
  const compareBHeader = document.getElementById("compare-b-header");
  const compareAList = document.getElementById("compare-a-list");
  const compareBList = document.getElementById("compare-b-list");
  const speciesModalEl = document.getElementById("species-modal");
  const speciesModalBackdropEl = document.getElementById("species-modal-backdrop");
  const speciesModalCloseBtn = document.getElementById("species-modal-close");
  const speciesModalBodyEl = document.getElementById("species-modal-body");

  let currentData = null;
  let currentFilter = "all";
  let currentGeneration = "all";
  let currentEventSource = null;
  let teamPollTimer = null;
  let currentActivityEventSource = null;
  let currentLeaderboardEventSource = null;
  let leaderboardData = null;
  let currentLeaderboardCategory = "completion";
  let knownPlayers = [];
  let currentLang = localStorage.getItem("cobblesync-lang") === "en" ? "en" : "fr";
  const speciesInfoCache = new Map();

  // --- i18n: UI chrome strings ---------------------------------------------------------------
  const STRINGS = {
    fr: {
      subtitle: "Progression du pokédex par joueur",
      tabsEmpty: "Aucun joueur connu pour l'instant — connecte-toi une fois au serveur.",
      kpiCaught: "Capturés",
      kpiSeen: "Vus (non capturés)",
      kpiProgress: "Progression",
      searchPlaceholder: "Rechercher une espèce…",
      allGenerations: "Toutes générations",
      generationLabel: "Génération",
      filterCaught: "Capturés",
      filterSeen: "Vus seulement",
      filterAll: "Tout le pokédex",
      filtersLabel: "Filtres",
      gridEmpty: "Aucune espèce ne correspond à ce filtre.",
      badgeCaught: "Capturé",
      badgeSeen: "Vu",
      caughtOn: "Capturé le",
      toggleVariant: "Afficher shiny/normal",
      activityTab: "Activité",
      activityVerb: "a capturé",
      activityEmpty: "Aucune capture pour l'instant.",
      leaderboardTitle: "Classement",
      leaderboardCompletion: "Complétion",
      leaderboardShinyCount: "Shinys",
      leaderboardWeekly: "Cette semaine",
      leaderboardEmpty: "Aucune donnée pour cette catégorie.",
      compareTitle: "Comparaison",
      compareOnlyLabel: "Seulement chez",
      compareNone: "Rien à montrer.",
      details: "Détails",
      forms: "Formes",
      infos: "Infos",
      whereToFind: "Où le trouver",
      loading: "Chargement…",
      unavailable: "Indisponible.",
      height: "Taille",
      weight: "Poids",
      abilitiesLabel: "Capacités",
      eggGroupsLabel: "Groupes d'œufs",
      noSpawn: "Pas de spawn naturel connu (évolution, œuf, événement…).",
      safariZonePrefix: "Safari : ",
      skyLightLabel: "Luminosité :",
      level: "niv.",
      formNormal: "Normale",
      caughtWord: "capturé",
      seenWord: "vu",
      unregisteredWord: "pas encore trouvée",
      shinySeenWord: "shiny vu",
      male: "♂ Mâle",
      female: "♀ Femelle",
      closeModal: "Fermer",
      baseStatsLabel: "Statistiques de base",
      bstLabel: "Total (BST)",
      catchRateLabel: "Taux de capture",
      genderRatioLabel: "Ratio de genre",
      genderless: "Asexué",
      evolutionsLabel: "Évolutions",
      levelAbbrev: "Niv.",
      dropsLabel: "Butin",
      noDrops: "Ne lâche rien de particulier.",
      movesLabel: "Capacités",
      noMoves: "Aucune capacité connue.",
      levelUpMovesLabel: "Montée de niveau",
      tmMovesLabel: "CT",
      tutorMovesLabel: "Tuteur de capacités",
      eggMovesLabel: "Capacités d'œuf",
      movePowerLabel: "Puissance",
      moveAccuracyLabel: "Précision",
      moveNeverMiss: "Ne rate jamais",
      movePpLabel: "PP",
      moveCritRatioLabel: "Ratio critique",
      teamLabel: "Équipe actuelle",
      natureLabel: "Nature",
      abilityLabel: "Talent",
      heldItemLabel: "Objet tenu",
      noHeldItem: "Aucun",
      ivEvLabel: "IV / EV",
    },
    en: {
      subtitle: "Pokédex progress per player",
      tabsEmpty: "No known players yet — join the server once.",
      kpiCaught: "Caught",
      kpiSeen: "Seen (not caught)",
      kpiProgress: "Progress",
      searchPlaceholder: "Search a species…",
      allGenerations: "All generations",
      generationLabel: "Generation",
      filterCaught: "Caught",
      filterSeen: "Seen only",
      filterAll: "Full pokédex",
      filtersLabel: "Filters",
      gridEmpty: "No species match this filter.",
      badgeCaught: "Caught",
      badgeSeen: "Seen",
      caughtOn: "Caught on",
      toggleVariant: "Toggle shiny/normal",
      activityTab: "Activity",
      activityVerb: "caught",
      activityEmpty: "No captures yet.",
      leaderboardTitle: "Leaderboard",
      leaderboardCompletion: "Completion",
      leaderboardShinyCount: "Shinies",
      leaderboardWeekly: "This week",
      leaderboardEmpty: "No data for this category.",
      compareTitle: "Comparison",
      compareOnlyLabel: "Only in",
      compareNone: "Nothing to show.",
      details: "Details",
      forms: "Forms",
      infos: "Info",
      whereToFind: "Where to find it",
      loading: "Loading…",
      unavailable: "Unavailable.",
      height: "Height",
      weight: "Weight",
      abilitiesLabel: "Abilities",
      eggGroupsLabel: "Egg groups",
      noSpawn: "No natural spawn known (evolution, egg, event…).",
      safariZonePrefix: "Safari: ",
      skyLightLabel: "Sky light:",
      level: "lvl",
      formNormal: "Normal",
      caughtWord: "caught",
      seenWord: "seen",
      unregisteredWord: "not found yet",
      shinySeenWord: "shiny seen",
      male: "♂ Male",
      female: "♀ Female",
      closeModal: "Close",
      baseStatsLabel: "Base stats",
      bstLabel: "Total (BST)",
      catchRateLabel: "Catch rate",
      genderRatioLabel: "Gender ratio",
      genderless: "Genderless",
      evolutionsLabel: "Evolutions",
      levelAbbrev: "Lvl",
      dropsLabel: "Drops",
      noDrops: "Doesn't drop anything special.",
      movesLabel: "Moves",
      noMoves: "No known moves.",
      levelUpMovesLabel: "Level up",
      tmMovesLabel: "TM",
      tutorMovesLabel: "Move Tutor",
      eggMovesLabel: "Egg moves",
      movePowerLabel: "Power",
      moveAccuracyLabel: "Accuracy",
      moveNeverMiss: "Never misses",
      movePpLabel: "PP",
      moveCritRatioLabel: "Crit ratio",
      teamLabel: "Current team",
      natureLabel: "Nature",
      abilityLabel: "Ability",
      heldItemLabel: "Held item",
      noHeldItem: "None",
      ivEvLabel: "IV / EV",
    },
  };

  function t(key) {
    return STRINGS[currentLang][key] || key;
  }

  // --- Translation tables for game data (stable keys returned by the backend) ----------------
  const TYPE_NAMES = {
    Normal: { fr: "Normal", en: "Normal" },
    Fire: { fr: "Feu", en: "Fire" },
    Water: { fr: "Eau", en: "Water" },
    Electric: { fr: "Électrik", en: "Electric" },
    Grass: { fr: "Plante", en: "Grass" },
    Ice: { fr: "Glace", en: "Ice" },
    Fighting: { fr: "Combat", en: "Fighting" },
    Poison: { fr: "Poison", en: "Poison" },
    Ground: { fr: "Sol", en: "Ground" },
    Flying: { fr: "Vol", en: "Flying" },
    Psychic: { fr: "Psy", en: "Psychic" },
    Bug: { fr: "Insecte", en: "Bug" },
    Rock: { fr: "Roche", en: "Rock" },
    Ghost: { fr: "Spectre", en: "Ghost" },
    Dragon: { fr: "Dragon", en: "Dragon" },
    Dark: { fr: "Ténèbres", en: "Dark" },
    Steel: { fr: "Acier", en: "Steel" },
    Fairy: { fr: "Fée", en: "Fairy" },
  };

  const EGG_GROUP_NAMES = {
    MONSTER: { fr: "Monstre", en: "Monster" },
    WATER_1: { fr: "Eau 1", en: "Water 1" },
    BUG: { fr: "Insecte", en: "Bug" },
    FLYING: { fr: "Vol", en: "Flying" },
    FIELD: { fr: "Champ", en: "Field" },
    FAIRY: { fr: "Fée", en: "Fairy" },
    GRASS: { fr: "Plante", en: "Grass" },
    HUMAN_LIKE: { fr: "Humanoïde", en: "Human-Like" },
    WATER_3: { fr: "Eau 3", en: "Water 3" },
    MINERAL: { fr: "Minéral", en: "Mineral" },
    AMORPHOUS: { fr: "Amorphe", en: "Amorphous" },
    WATER_2: { fr: "Eau 2", en: "Water 2" },
    DITTO: { fr: "Métamorph", en: "Ditto" },
    DRAGON: { fr: "Dragon", en: "Dragon" },
    UNDISCOVERED: { fr: "Découverte impossible", en: "Undiscovered" },
  };

  const RARITY_NAMES = {
    common: { fr: "Commun", en: "Common" },
    uncommon: { fr: "Peu commun", en: "Uncommon" },
    rare: { fr: "Rare", en: "Rare" },
    "ultra-rare": { fr: "Ultra rare", en: "Ultra Rare" },
  };

  const STAT_NAMES = {
    hp: { fr: "PV", en: "HP" },
    atk: { fr: "Attaque", en: "Attack" },
    def: { fr: "Défense", en: "Defense" },
    spa: { fr: "Atq. Spé.", en: "Sp. Atk" },
    spd: { fr: "Déf. Spé.", en: "Sp. Def" },
    spe: { fr: "Vitesse", en: "Speed" },
  };
  // Display order — species.baseStats from the backend is keyed by showdownId but not ordered.
  const STAT_ORDER = ["hp", "atk", "def", "spa", "spd", "spe"];
  const STAT_MAX = 255; // Theoretical base-stat ceiling — fixed scale so bars stay comparable
  // across species, rather than each species' own highest stat looking "full".
  const BST_MAX = 780; // Highest real BST (Mega Mewtwo/Rayquaza) — same "fixed scale" reasoning.

  // Thresholds tuned to the realistic base-stat range (most stats fall well under the 255
  // ceiling) rather than splitting the full 0-255 scale evenly, which would bunch almost every
  // real stat into the bottom bucket.
  const STAT_TIER_THRESHOLDS = [60, 80, 100, 120];
  function statValueTier(value) {
    for (let i = 0; i < STAT_TIER_THRESHOLDS.length; i++) {
      if (value < STAT_TIER_THRESHOLDS[i]) return i + 1;
    }
    return STAT_TIER_THRESHOLDS.length + 1;
  }

  const EVOLUTION_TRIGGER_NAMES = {
    trade: { fr: "Échange", en: "Trade" },
    item: { fr: "Objet", en: "Item" },
    friendship: { fr: "Bonheur", en: "Friendship" },
    other: { fr: "Condition spéciale", en: "Special condition" },
  };

  // Classic Pokémon type colors — a known convention, not a generated palette.
  const TYPE_COLORS = {
    Normal:   { bg: "#A8A878", fg: "#2b2b1f" },
    Fire:     { bg: "#F08030", fg: "#ffffff" },
    Water:    { bg: "#6890F0", fg: "#ffffff" },
    Electric: { bg: "#F8D030", fg: "#2b2b1f" },
    Grass:    { bg: "#78C850", fg: "#173109" },
    Ice:      { bg: "#98D8D8", fg: "#123333" },
    Fighting: { bg: "#C03028", fg: "#ffffff" },
    Poison:   { bg: "#A040A0", fg: "#ffffff" },
    Ground:   { bg: "#E0C068", fg: "#3a2e0d" },
    Flying:   { bg: "#A890F0", fg: "#221948" },
    Psychic:  { bg: "#F85888", fg: "#ffffff" },
    Bug:      { bg: "#A8B820", fg: "#20260a" },
    Rock:     { bg: "#B8A038", fg: "#2b230d" },
    Ghost:    { bg: "#705898", fg: "#ffffff" },
    Dragon:   { bg: "#7038F8", fg: "#ffffff" },
    Dark:     { bg: "#705848", fg: "#ffffff" },
    Steel:    { bg: "#B8B8D0", fg: "#26263a" },
    Fairy:    { bg: "#EE99AC", fg: "#3a1420" },
  };

  const GENDER_COLORS = {
    MALE: { bg: "#3a7bd5", fg: "#ffffff" },
    FEMALE: { bg: "#e0609e", fg: "#ffffff" },
    GENDERLESS: { bg: "#2f9e44", fg: "#ffffff" },
  };

  // Damage category — fixed 3-value set (not a generated palette), colors chosen to stay
  // distinct from the accent red and from each other: orange for physical, violet for special,
  // neutral gray for status (no damage).
  const CATEGORY_NAMES = {
    physical: { fr: "Physique", en: "Physical" },
    special: { fr: "Spéciale", en: "Special" },
    status: { fr: "Statut", en: "Status" },
  };
  const CATEGORY_COLORS = {
    physical: { bg: "#e8823c", fg: "#2b1704" },
    special: { bg: "#7b6fde", fg: "#ffffff" },
    status: { bg: "#8a93a3", fg: "#1c2128" },
  };

  const RARITY_COLORS = {
    common: { bg: "#5b5b57", fg: "#ffffff" },
    uncommon: { bg: "#2f9e44", fg: "#ffffff" },
    rare: { bg: "#2f6fd1", fg: "#ffffff" },
    "ultra-rare": { bg: "#8347d9", fg: "#ffffff" },
  };

  const EGG_GROUP_COLOR = { bg: "#7c6fd1", fg: "#ffffff" };
  const STRUCTURE_COLOR = { bg: "#8a6d3b", fg: "#ffffff" };
  const SHINY_COLOR = { bg: "#e8b923", fg: "#3a2b04" };

  // Biome badge color by category — backend-resolved (BiomeCategoryResolver.kt) via real
  // vanilla biome tags where they exist, keyword fallback otherwise. Chosen and validated with
  // the dataviz skill's categorical checker; a couple of adjacent-pair near-misses remain among
  // naturally similar earth/vegetation tones (e.g. forest vs jungle) — accepted per the skill's
  // own allowance for the CVD 6-8 floor band when a secondary encoding exists, which it does
  // here (every badge always carries its own biome name as a text label, never color alone).
  const BIOME_CATEGORY_COLORS = {
    ocean:    { bg: "#3a6fd1", fg: "#ffffff" },
    beach:    { bg: "#a3812f", fg: "#ffffff" },
    river:    { bg: "#2a8fb0", fg: "#ffffff" },
    mountain: { bg: "#a86a2a", fg: "#ffffff" },
    badlands: { bg: "#b5451f", fg: "#ffffff" },
    taiga:    { bg: "#0a9c94", fg: "#ffffff" },
    jungle:   { bg: "#0f8a3a", fg: "#ffffff" },
    forest:   { bg: "#5a9c3c", fg: "#ffffff" },
    savanna:  { bg: "#ad8f13", fg: "#ffffff" },
    desert:   { bg: "#c15a1f", fg: "#ffffff" },
    swamp:    { bg: "#6b6b0a", fg: "#ffffff" },
    plains:   { bg: "#7aa32a", fg: "#ffffff" },
    snow:     { bg: "#3a8fc0", fg: "#ffffff" },
    cave:     { bg: "#7a5a9e", fg: "#ffffff" },
    mushroom: { bg: "#c14f8f", fg: "#ffffff" },
    nether:   { bg: "#d6362c", fg: "#ffffff" },
    end:      { bg: "#7a3fc9", fg: "#ffffff" },
    other:    { bg: "#75726c", fg: "#ffffff" },
    // Not a real biome (CobbleSafari's per-type themed zones) — deliberately distinct hue plus a
    // text prefix (see displayBiome) so it reads as "different kind of thing", not just another
    // color in the natural-biome set.
    safari:   { bg: "#5b6ee8", fg: "#ffffff" },
  };

  // Fixed display order for grouping biome badges by category — same category always appears in
  // the same relative position across every species, making lists easier to scan/compare.
  const BIOME_CATEGORY_ORDER = [
    "forest", "jungle", "taiga", "savanna", "plains", "swamp", "desert", "badlands", "mountain",
    "snow", "cave", "mushroom", "ocean", "river", "beach", "nether", "end", "safari", "other",
  ];

  const BIOME_CATEGORY_NAMES = {
    forest: { fr: "Forêt", en: "Forest" },
    jungle: { fr: "Jungle", en: "Jungle" },
    taiga: { fr: "Taïga", en: "Taiga" },
    savanna: { fr: "Savane", en: "Savanna" },
    plains: { fr: "Plaines", en: "Plains" },
    swamp: { fr: "Marais", en: "Swamp" },
    desert: { fr: "Désert", en: "Desert" },
    badlands: { fr: "Badlands", en: "Badlands" },
    mountain: { fr: "Montagne", en: "Mountain" },
    snow: { fr: "Neige", en: "Snow" },
    cave: { fr: "Grotte", en: "Cave" },
    mushroom: { fr: "Champignons", en: "Mushroom" },
    ocean: { fr: "Océan", en: "Ocean" },
    river: { fr: "Rivière", en: "River" },
    beach: { fr: "Plage", en: "Beach" },
    nether: { fr: "Nether", en: "Nether" },
    end: { fr: "End", en: "End" },
    safari: { fr: "Zone Safari", en: "Safari Zone" },
    other: { fr: "Autre", en: "Other" },
  };

  // Separate from the rarity/biome palettes — day/night is a spawn-time restriction, not a
  // location, so it gets its own small badge next to the rarity/level ones instead of living in
  // the biome row.
  const SKY_LIGHT_COLOR = { bg: "#3a4a6b", fg: "#ffffff" };

  // National Pokédex ranges by generation (up to Gen. 9).
  const GENERATIONS = [
    [1, 151], [152, 251], [252, 386], [387, 493], [494, 649],
    [650, 721], [722, 809], [810, 905], [906, 1025],
  ];

  function generationOf(dexNumber) {
    for (let i = 0; i < GENERATIONS.length; i++) {
      const [min, max] = GENERATIONS[i];
      if (dexNumber >= min && dexNumber <= max) return i + 1;
    }
    return null;
  }

  function toRoman(n) {
    const romans = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"];
    return romans[n - 1] || String(n);
  }

  function capitalizeFirst(str) {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  // Fallback until /api/species/{id} responds: name derived from the id (always English).
  function displaySpeciesNameFallback(resourceLocation) {
    const path = resourceLocation.includes(":") ? resourceLocation.split(":")[1] : resourceLocation;
    return path.split("_").map(capitalizeFirst).join(" ");
  }

  function displayFormName(formName) {
    return formName === "Normal" ? t("formNormal") : formName;
  }

  function displayBiome(resourceLocation) {
    const path = resourceLocation.includes(":") ? resourceLocation.split(":")[1] : resourceLocation;
    return path.split("_").map(capitalizeFirst).join(" ");
  }

  function spriteUrl(nationalDexNumber, shiny) {
    const variant = shiny ? "shiny/" : "";
    return `https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/${variant}${nationalDexNumber}.png`;
  }

  // Local models are produced by the /cobblesync exportmodel(s) admin command (real in-game
  // Cobblemon renders, see ModelExportScreen) and served straight out of web/models/ — same
  // naming convention as the other tiers below (national dex number, "-shiny" suffix).
  function localModelUrl(nationalDexNumber, shiny) {
    return `models/${nationalDexNumber}${shiny ? "-shiny" : ""}.png`;
  }

  // Community-hosted pre-rendered set (same export pipeline, just already run once and shared),
  // for servers that haven't run the local export command themselves.
  function hostedModelUrl(nationalDexNumber, shiny) {
    return `https://raw.githubusercontent.com/BeubeuReturns/cobblemon_sprites/main/models/${nationalDexNumber}${shiny ? "-shiny" : ""}.png`;
  }

  // Fallback order: this server's own local export, then the community-hosted real-model set,
  // then PokeAPI (shiny, then normal as a last resort for shiny requests), then hidden.
  function setSprite(imgEl, nationalDexNumber, shiny) {
    const candidates = [localModelUrl(nationalDexNumber, shiny), hostedModelUrl(nationalDexNumber, shiny), spriteUrl(nationalDexNumber, shiny)];
    if (shiny) candidates.push(spriteUrl(nationalDexNumber, false));

    let index = 0;
    const tryNext = () => {
      if (index >= candidates.length) {
        imgEl.hidden = true;
        return;
      }
      imgEl.src = candidates[index++];
    };
    imgEl.onerror = tryNext;
    imgEl.hidden = false;
    tryNext();
  }

  // "Complete" = caught + every known form caught + a shiny seen + every possible gender seen.
  // possibleGenders comes from Cobblemon (not guessed here), to avoid false negatives on
  // single-gender species like Tauros/Miltank.
  function isFullyComplete(species) {
    if (species.tier !== "caught") return false;
    const forms = Object.values(species.forms || {});
    if (forms.length === 0) return false;
    if (!forms.every((f) => f.tier === "caught")) return false;
    if (!forms.some((f) => f.shinyStates.includes("shiny"))) return false;

    const seenGenders = new Set();
    forms.forEach((f) => f.genders.forEach((g) => seenGenders.add(g)));
    const possibleGenders = species.possibleGenders || [];
    if (!possibleGenders.every((g) => seenGenders.has(g))) return false;

    return true;
  }

  function badge(text, colors, extraClass, title) {
    const el = document.createElement("span");
    el.className = "badge-pill" + (extraClass ? ` ${extraClass}` : "");
    el.textContent = text;
    el.style.backgroundColor = colors.bg;
    el.style.color = colors.fg;
    if (title) {
      el.title = title;
      el.classList.add("has-tooltip");
    }
    return el;
  }

  function fieldLabel(text) {
    const el = document.createElement("div");
    el.className = "field-label";
    el.textContent = text;
    return el;
  }

  async function getSpeciesInfo(id) {
    if (speciesInfoCache.has(id)) return speciesInfoCache.get(id);
    const promise = fetch(`/api/species/${id}`)
      .then((res) => (res.ok ? res.json() : null))
      .catch(() => null);
    speciesInfoCache.set(id, promise);
    return promise;
  }

  function applyStaticI18n() {
    document.documentElement.lang = currentLang;
    document.querySelectorAll("[data-i18n]").forEach((el) => {
      el.textContent = t(el.dataset.i18n);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((el) => {
      el.placeholder = t(el.dataset.i18nPlaceholder);
    });
    langButtons.forEach((btn) => btn.classList.toggle("is-active", btn.dataset.lang === currentLang));
    speciesModalCloseBtn.setAttribute("aria-label", t("closeModal"));
  }

  function setLanguage(lang) {
    currentLang = lang;
    localStorage.setItem("cobblesync-lang", lang);
    applyStaticI18n();
    if (currentData) {
      populateGenerationFilter();
      renderGrid();
    }
    loadActivityFeed();
    if (leaderboardData) renderLeaderboard();
    if (!comparePanel.hidden) loadComparison();
    // Re-render in place rather than closing: getSpeciesInfo is cached, so this is free.
    if (!speciesModalEl.hidden && speciesModalSpecies) openSpeciesModal(speciesModalSpecies, speciesModalTrigger);
    // No fetch involved (the data came from the team poll, already in hand) — just rebuild.
    if (!speciesModalEl.hidden && speciesModalPokemon) openPokemonModal(speciesModalPokemon, speciesModalTrigger);
  }

  async function loadPlayers() {
    const res = await fetch("/api/players");
    const players = await res.json();
    knownPlayers = players;

    if (players.length === 0) {
      tabsEmptyEl.hidden = false;
      dashboardEl.hidden = true;
      return;
    }

    tabsEmptyEl.hidden = true;
    // [data-uuid] excludes the static activity-tab button from the dynamic player tabs.
    tabsEl.querySelectorAll(".player-tab[data-uuid]").forEach((btn) => btn.remove());

    players.forEach((player, index) => {
      const btn = document.createElement("button");
      btn.className = "player-tab" + (index === 0 ? " is-active" : "");
      btn.type = "button";
      btn.textContent = player.name;
      btn.dataset.uuid = player.uuid;
      btn.addEventListener("click", () => selectPlayer(player.uuid, btn));
      tabsEl.appendChild(btn);
    });

    dashboardEl.hidden = false;
    selectPlayer(players[0].uuid, tabsEl.querySelector(".player-tab[data-uuid]"));

    populateCompareSelects();
  }

  function populateCompareSelects() {
    comparePanel.hidden = knownPlayers.length < 2;
    if (knownPlayers.length < 2) return;

    const previousA = compareASelect.value;
    const previousB = compareBSelect.value;

    [compareASelect, compareBSelect].forEach((select) => {
      select.innerHTML = "";
      knownPlayers.forEach((player) => {
        const opt = document.createElement("option");
        opt.value = player.uuid;
        opt.textContent = player.name;
        select.appendChild(opt);
      });
    });

    const hasA = knownPlayers.some((p) => p.uuid === previousA);
    const hasB = knownPlayers.some((p) => p.uuid === previousB);
    compareASelect.value = hasA ? previousA : knownPlayers[0].uuid;
    compareBSelect.value = hasB ? previousB : knownPlayers[1].uuid;

    loadComparison();
  }

  function selectPlayer(uuid, btn) {
    tabsEl.querySelectorAll(".player-tab").forEach((b) => b.classList.remove("is-active"));
    btn.classList.add("is-active");
    loadPokedex(uuid);
    connectEvents(uuid);
    connectTeamPolling(uuid);
  }

  async function loadActivityFeed() {
    const res = await fetch("/api/capture-log");
    const entries = await res.json();
    renderActivityFeed(entries);
  }

  function connectActivityEvents() {
    if (currentActivityEventSource) currentActivityEventSource.close();
    currentActivityEventSource = new EventSource("/api/capture-log/events");
    currentActivityEventSource.addEventListener("capture-log-updated", loadActivityFeed);
  }

  function renderActivityFeed(entries) {
    activityFeedEl.innerHTML = "";
    activityEmptyEl.hidden = entries.length > 0;

    entries.forEach((entry) => {
      const row = document.createElement("div");
      row.className = "activity-row" + (entry.shiny ? " activity-row-shiny" : "");

      const sprite = document.createElement("img");
      sprite.className = "activity-sprite";
      sprite.loading = "lazy";
      sprite.hidden = true;
      row.appendChild(sprite);

      const text = document.createElement("div");
      text.className = "activity-text";

      const playerName = document.createElement("span");
      playerName.className = "activity-player";
      const player = knownPlayers.find((p) => p.uuid === entry.playerUuid);
      playerName.textContent = player ? player.name : "?";
      text.appendChild(playerName);

      const verb = document.createElement("span");
      verb.className = "activity-verb";
      verb.textContent = ` ${t("activityVerb")} `;
      text.appendChild(verb);

      const speciesName = document.createElement("span");
      speciesName.className = "activity-species";
      speciesName.textContent = "…";
      text.appendChild(speciesName);

      if (entry.shiny) {
        const star = document.createElement("span");
        star.className = "activity-shiny";
        star.textContent = " ✦";
        text.appendChild(star);
      }

      row.appendChild(text);

      const time = document.createElement("span");
      time.className = "activity-time";
      time.textContent = new Intl.DateTimeFormat(currentLang === "fr" ? "fr-FR" : "en-US", { dateStyle: "short", timeStyle: "short" }).format(new Date(entry.timestampMillis));
      row.appendChild(time);

      activityFeedEl.appendChild(row);

      getSpeciesInfo(entry.speciesId).then((info) => {
        if (!info) return;
        speciesName.textContent = currentLang === "fr" ? info.nameFr : info.nameEn;
        if (info.nationalDexNumber) {
          sprite.src = spriteUrl(info.nationalDexNumber, entry.shiny);
          sprite.hidden = false;
          sprite.addEventListener("error", () => { sprite.hidden = true; }, { once: true });
        }
      });
    });
  }

  async function loadLeaderboard() {
    const res = await fetch("/api/leaderboard");
    leaderboardData = await res.json();
    renderLeaderboard();
  }

  function connectLeaderboardEvents() {
    if (currentLeaderboardEventSource) currentLeaderboardEventSource.close();
    currentLeaderboardEventSource = new EventSource("/api/leaderboard/events");
    currentLeaderboardEventSource.addEventListener("leaderboard-updated", loadLeaderboard);
  }

  function formatLeaderboardValue(category, entry) {
    switch (category) {
      case "completion":
        return `${entry.percent.toFixed(1)}% (${entry.caughtCount}/${entry.totalKnownSpecies})`;
      case "shinyCount":
        return String(entry.count);
      case "weekly":
        return String(entry.count);
      default:
        return "";
    }
  }

  function renderLeaderboard() {
    leaderboardListEl.innerHTML = "";
    const entries = (leaderboardData && leaderboardData[currentLeaderboardCategory]) || [];
    leaderboardEmptyEl.hidden = entries.length > 0;

    entries.forEach((entry, index) => {
      const row = document.createElement("div");
      row.className = "leaderboard-row" + (index === 0 ? " leaderboard-row-top" : "");
      row.innerHTML = `<span class="leaderboard-rank">#${index + 1}</span><span class="leaderboard-name"></span><span class="leaderboard-value"></span>`;
      row.querySelector(".leaderboard-name").textContent = entry.name;
      row.querySelector(".leaderboard-value").textContent = formatLeaderboardValue(currentLeaderboardCategory, entry);
      leaderboardListEl.appendChild(row);
    });
  }

  // 1v1 comparison reuses /api/players/{uuid}/pokedex for both players — no dedicated
  // endpoint, just a client-side diff. No SSE hookup: it's an on-demand check, not an
  // ambient panel like activity/leaderboard, so a manual re-select is enough to refresh it.
  async function loadComparison() {
    const uuidA = compareASelect.value;
    const uuidB = compareBSelect.value;
    if (!uuidA || !uuidB) return;

    const [resA, resB] = await Promise.all([
      fetch(`/api/players/${uuidA}/pokedex`),
      fetch(`/api/players/${uuidB}/pokedex`),
    ]);
    const dataA = await resA.json();
    const dataB = await resB.json();
    renderComparison(dataA, dataB);
  }

  function speciesDisplayName(id, data) {
    return (currentLang === "fr" ? data.nameFr : data.nameEn) || displaySpeciesNameFallback(id);
  }

  function renderComparisonSide(listEl, headerEl, ownData, otherData, playerName) {
    headerEl.textContent = `${t("compareOnlyLabel")} ${playerName}`;
    listEl.innerHTML = "";

    const onlyOwn = Object.entries(ownData.species)
      .filter(([id, data]) => data.tier === "caught" && otherData.species[id]?.tier !== "caught")
      .map(([id, data]) => ({ id, data }))
      .sort((a, b) => (a.data.nationalDexNumber ?? 9999) - (b.data.nationalDexNumber ?? 9999));

    if (onlyOwn.length === 0) {
      const empty = document.createElement("p");
      empty.className = "empty-hint";
      empty.textContent = t("compareNone");
      listEl.appendChild(empty);
      return;
    }

    onlyOwn.forEach(({ id, data }) => {
      const row = document.createElement("div");
      row.className = "compare-row";
      row.innerHTML = `<span class="compare-row-dex"></span><span class="compare-row-name"></span>`;
      row.querySelector(".compare-row-dex").textContent = data.nationalDexNumber ? `#${String(data.nationalDexNumber).padStart(3, "0")}` : "";
      row.querySelector(".compare-row-name").textContent = speciesDisplayName(id, data);
      listEl.appendChild(row);
    });
  }

  function renderComparison(dataA, dataB) {
    const nameA = knownPlayers.find((p) => p.uuid === compareASelect.value)?.name || "?";
    const nameB = knownPlayers.find((p) => p.uuid === compareBSelect.value)?.name || "?";
    renderComparisonSide(compareAList, compareAHeader, dataA, dataB, nameA);
    renderComparisonSide(compareBList, compareBHeader, dataB, dataA, nameB);
  }

  function connectEvents(uuid) {
    if (currentEventSource) currentEventSource.close();
    // Signal only (no diff): just refetch /pokedex normally.
    currentEventSource = new EventSource(`/api/players/${uuid}/events`);
    currentEventSource.addEventListener("pokedex-updated", () => loadPokedex(uuid));
  }

  // Polled rather than pushed over SSE — the team endpoint is only ever a cheap in-memory read
  // for online players (see PokedexHandler.handleTeam), so a plain interval avoids adding yet
  // another open socket + Cobblemon event subscription just to show something that's inherently
  // "good enough" to refresh every few seconds rather than instantly.
  function connectTeamPolling(uuid) {
    if (teamPollTimer) clearInterval(teamPollTimer);
    loadTeam(uuid);
    teamPollTimer = setInterval(() => loadTeam(uuid), 10_000);
  }

  async function loadTeam(uuid) {
    const res = await fetch(`/api/players/${uuid}/team`);
    const data = await res.json();
    renderTeam(data);
  }

  function hpBarColor(percent) {
    if (percent > 50) return "var(--status-good)";
    if (percent > 20) return "var(--status-warn)";
    return "var(--status-bad)";
  }

  function renderTeam(data) {
    if (!data.online || !data.team || data.team.length === 0) {
      teamRowEl.hidden = true;
      teamRowCardsEl.innerHTML = "";
      return;
    }

    teamRowEl.hidden = false;
    teamRowCardsEl.innerHTML = "";
    data.team.forEach((pokemon) => {
      const card = document.createElement("div");
      card.className = "team-card";

      const sprite = document.createElement("img");
      sprite.className = "team-card-sprite";
      sprite.alt = pokemon.nameEn;
      setSprite(sprite, pokemon.nationalDexNumber, pokemon.shiny);
      card.appendChild(sprite);

      if (pokemon.shiny) card.appendChild(badge("✨", { bg: "var(--shiny-color)", fg: "#3a2b00" }, "team-card-shiny"));

      const name = document.createElement("div");
      name.className = "team-card-name";
      name.textContent = pokemon.nickname || (currentLang === "fr" ? pokemon.nameFr : pokemon.nameEn);
      card.appendChild(name);

      const meta = document.createElement("div");
      meta.className = "team-card-meta";
      const level = document.createElement("span");
      level.className = "team-card-level";
      level.textContent = `${t("levelAbbrev")} ${pokemon.level}`;
      meta.appendChild(level);
      if (GENDER_COLORS[pokemon.gender]) {
        const genderGlyph = pokemon.gender === "MALE" ? "♂" : pokemon.gender === "FEMALE" ? "♀" : "";
        if (genderGlyph) meta.appendChild(badge(genderGlyph, GENDER_COLORS[pokemon.gender], "team-card-gender"));
      }
      card.appendChild(meta);

      const hpPercent = pokemon.maxHealth > 0 ? Math.round((pokemon.currentHealth / pokemon.maxHealth) * 100) : 0;
      const hpTrack = document.createElement("div");
      hpTrack.className = "team-card-hp-track";
      const hpFill = document.createElement("div");
      hpFill.className = "team-card-hp-fill";
      hpFill.style.width = `${hpPercent}%`;
      hpFill.style.backgroundColor = hpBarColor(hpPercent);
      hpTrack.appendChild(hpFill);
      card.appendChild(hpTrack);
      const hpLabel = document.createElement("span");
      hpLabel.className = "team-card-hp-label";
      hpLabel.textContent = `${pokemon.currentHealth}/${pokemon.maxHealth}`;
      card.appendChild(hpLabel);

      // Opens the same nature/ability/held-item/IV-EV detail popup used for the species modal
      // shell (see openPokemonModal) — same clickable-card accessibility pattern as species names.
      card.setAttribute("role", "button");
      card.setAttribute("tabindex", "0");
      const openModal = () => openPokemonModal(pokemon, card);
      card.addEventListener("click", openModal);
      card.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          openModal();
        }
      });

      teamRowCardsEl.appendChild(card);
    });
  }

  async function loadPokedex(uuid) {
    const res = await fetch(`/api/players/${uuid}/pokedex`);
    currentData = await res.json();
    renderKpis();
    populateGenerationFilter();
    renderGrid();
  }

  function renderKpis() {
    if (!currentData) return;
    const { caughtCount, seenCount, totalKnownSpecies } = currentData;
    kpiCaught.textContent = caughtCount;
    kpiSeen.textContent = seenCount;

    const percent = totalKnownSpecies > 0 ? Math.round((caughtCount / totalKnownSpecies) * 100) : 0;
    kpiPercent.textContent = `${percent}% (${caughtCount}/${totalKnownSpecies})`;
    kpiMeterFill.style.width = `${percent}%`;
  }

  function populateGenerationFilter() {
    const present = new Set();
    Object.values(currentData.species).forEach((s) => {
      if (s.nationalDexNumber) present.add(generationOf(s.nationalDexNumber));
    });

    const previousValue = generationSelect.value;
    generationSelect.innerHTML = `<option value="all">${t("allGenerations")}</option>`;
    [...present].filter(Boolean).sort((a, b) => a - b).forEach((gen) => {
      const opt = document.createElement("option");
      opt.value = String(gen);
      opt.textContent = `${t("generationLabel")} ${toRoman(gen)}`;
      generationSelect.appendChild(opt);
    });
    generationSelect.value = [...generationSelect.options].some((o) => o.value === previousValue)
      ? previousValue
      : "all";
    currentGeneration = generationSelect.value;
  }

  function renderGrid() {
    if (!currentData) return;
    gridEl.innerHTML = "";

    const query = searchInput.value.trim().toLowerCase();
    const entries = Object.entries(currentData.species)
      // "???" if never seen (no spoiler, not searchable). Otherwise the localized name, falling
      // back to the id if missing.
      .map(([id, data]) => ({
        id,
        name: data.tier === "unregistered"
          ? "???"
          : (currentLang === "fr" ? data.nameFr : data.nameEn) || displaySpeciesNameFallback(id),
        ...data,
      }))
      .filter((s) => (currentFilter === "all" ? true : s.tier === currentFilter))
      .filter((s) => (currentGeneration === "all" ? true : String(generationOf(s.nationalDexNumber)) === currentGeneration))
      .filter((s) => s.name.toLowerCase().includes(query))
      .sort((a, b) => (a.nationalDexNumber ?? 9999) - (b.nationalDexNumber ?? 9999));

    gridEmptyEl.hidden = entries.length > 0;

    entries.forEach((species) => {
      if (species.tier === "unregistered") {
        gridEl.appendChild(renderPlaceholderCard(species));
        return;
      }
      const card = renderCard(species);
      gridEl.appendChild(card);
      enrichCard(card, species);
    });
  }

  function renderPlaceholderCard(species) {
    const card = document.createElement("article");
    card.className = "species-card species-card-unknown";

    const placeholder = document.createElement("div");
    placeholder.className = "species-sprite-placeholder";
    placeholder.textContent = "?";

    if (species.nationalDexNumber) {
      const dexNumber = document.createElement("span");
      dexNumber.className = "dex-number";
      dexNumber.textContent = `#${String(species.nationalDexNumber).padStart(3, "0")}`;
      placeholder.appendChild(dexNumber);
    }

    card.appendChild(placeholder);

    const name = document.createElement("div");
    name.className = "species-name";
    name.textContent = "???";
    card.appendChild(name);

    return card;
  }

  function renderCard(species) {
    const card = document.createElement("article");
    card.className = "species-card" + (isFullyComplete(species) ? " species-card-complete" : "");

    const spriteWrap = document.createElement("div");
    spriteWrap.className = "sprite-wrap";

    const sprite = document.createElement("img");
    sprite.className = "species-sprite";
    sprite.alt = species.name;
    sprite.loading = "lazy";
    sprite.hidden = true;
    spriteWrap.appendChild(sprite);

    // species.aspects (the species-level aggregate from Cobblemon) doesn't reliably include
    // "shiny" even when a form's own shinyStates does — same per-form source isFullyComplete()
    // already trusts for the gold border, so use that here too instead of species.aspects.
    const hasShinyForm = Object.values(species.forms || {}).some((f) => f.shinyStates.includes("shiny"));

    if (hasShinyForm) {
      const shinyIcon = document.createElement("span");
      shinyIcon.className = "shiny-icon";
      shinyIcon.setAttribute("aria-hidden", "true");
      shinyIcon.innerHTML = '<svg viewBox="0 0 24 24" width="38" height="38"><path fill="currentColor" d="M12 2l1.8 5.9L20 9l-6.2 1.1L12 17l-1.8-6.9L4 9l6.2-1.1z"/></svg>';
      spriteWrap.appendChild(shinyIcon);
    }

    // Shiny shown by default when one has been seen/caught. Arrows just flip between the two
    // sprites — only shown when there's actually a shiny to toggle to.
    card._isShiny = hasShinyForm;
    if (card._isShiny) {
      const toggleSprite = () => {
        card._isShiny = !card._isShiny;
        if (species.nationalDexNumber) setSprite(sprite, species.nationalDexNumber, card._isShiny);
      };

      const prevBtn = document.createElement("button");
      prevBtn.type = "button";
      prevBtn.className = "sprite-nav sprite-nav-prev";
      prevBtn.setAttribute("aria-label", t("toggleVariant"));
      prevBtn.textContent = "◀";
      prevBtn.addEventListener("click", toggleSprite);
      spriteWrap.appendChild(prevBtn);

      const nextBtn = document.createElement("button");
      nextBtn.type = "button";
      nextBtn.className = "sprite-nav sprite-nav-next";
      nextBtn.setAttribute("aria-label", t("toggleVariant"));
      nextBtn.textContent = "▶";
      nextBtn.addEventListener("click", toggleSprite);
      spriteWrap.appendChild(nextBtn);
    }

    if (species.nationalDexNumber) {
      const dexNumber = document.createElement("span");
      dexNumber.className = "dex-number";
      dexNumber.textContent = `#${String(species.nationalDexNumber).padStart(3, "0")}`;
      spriteWrap.appendChild(dexNumber);
    }

    card.appendChild(spriteWrap);

    const header = document.createElement("div");
    header.className = "species-card-header";

    const name = document.createElement("div");
    name.className = "species-name";
    name.textContent = species.name;
    header.appendChild(name);

    const statusGroup = document.createElement("div");
    statusGroup.className = "status-group";

    const statusBadge = document.createElement("span");
    statusBadge.className = "badge " + (species.tier === "caught" ? "badge-caught" : "badge-seen");
    statusBadge.textContent = species.tier === "caught" ? t("badgeCaught") : t("badgeSeen");
    statusGroup.appendChild(statusBadge);

    if (species.caughtAtMillis) {
      const caughtDate = new Date(species.caughtAtMillis);
      const dateEl = document.createElement("span");
      dateEl.className = "caught-date";
      dateEl.textContent = new Intl.DateTimeFormat(currentLang === "fr" ? "fr-FR" : "en-US", { day: "2-digit", month: "2-digit", year: "2-digit" }).format(caughtDate);
      dateEl.title = `${t("caughtOn")} ${new Intl.DateTimeFormat(currentLang === "fr" ? "fr-FR" : "en-US", { dateStyle: "medium" }).format(caughtDate)}`;
      statusGroup.appendChild(dateEl);
    }

    header.appendChild(statusGroup);

    card.appendChild(header);

    const typeRow = document.createElement("div");
    typeRow.className = "badge-row";
    card.appendChild(typeRow);

    const traitsRow = document.createElement("div");
    traitsRow.className = "badge-row";
    // Only render aspects we know how to label meaningfully — other mods (cosmetic/hat addons,
    // etc.) can inject arbitrary extra aspect strings we have no useful way to display.
    species.aspects.forEach((aspect) => {
      if (aspect === "shiny") {
        traitsRow.appendChild(badge("✦ Shiny", SHINY_COLOR));
      } else if (aspect === "male") {
        traitsRow.appendChild(badge(t("male"), GENDER_COLORS.MALE));
      } else if (aspect === "female") {
        traitsRow.appendChild(badge(t("female"), GENDER_COLORS.FEMALE));
      }
    });
    if (traitsRow.children.length > 0) card.appendChild(traitsRow);

    // Clicking the name opens the full detail modal (forms, abilities, egg groups, spawns, base
    // stats, evolutions, description — everything that used to be an inline "Détails ▾" expand
    // now lives there instead, keeping the card itself scannable).
    name.classList.add("species-name-clickable");
    name.setAttribute("role", "button");
    name.setAttribute("tabindex", "0");
    const openModal = () => openSpeciesModal(species, name);
    name.addEventListener("click", openModal);
    name.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        openModal();
      }
    });

    card._nameEl = name;
    card._sprite = sprite;
    card._typeRow = typeRow;

    return card;
  }

  /** Height/weight + abilities + egg groups — shared between the modal and nothing else now,
   * but kept as its own function since it's a natural, reusable content unit. */
  function buildInfoSection(info) {
    const container = document.createElement("div");

    const infoGrid = document.createElement("div");
    infoGrid.className = "info-grid";
    infoGrid.innerHTML = `
      <div class="info-item"><span class="info-label">${t("height")}</span><span class="info-value">${info.heightM.toFixed(1)} m</span></div>
      <div class="info-item"><span class="info-label">${t("weight")}</span><span class="info-value">${info.weightKg.toFixed(1)} kg</span></div>
    `;
    container.appendChild(infoGrid);

    if (info.abilities.length > 0) {
      container.appendChild(fieldLabel(t("abilitiesLabel")));
      const abilitiesRow = document.createElement("div");
      abilitiesRow.className = "badge-row";
      info.abilities.forEach((ability) => {
        const label = currentLang === "fr" ? ability.fr : ability.en;
        const desc = currentLang === "fr" ? ability.descFr : ability.descEn;
        abilitiesRow.appendChild(badge(label, { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }, null, desc));
      });
      container.appendChild(abilitiesRow);
    }

    // Egg groups + drops side by side — both are short lists, no reason to each take a full row.
    const dualRow = document.createElement("div");
    dualRow.className = "dual-column-row";

    if (info.eggGroups.length > 0) {
      const eggCol = document.createElement("div");
      eggCol.className = "dual-column";
      eggCol.appendChild(fieldLabel(t("eggGroupsLabel")));
      const eggRow = document.createElement("div");
      eggRow.className = "badge-row";
      info.eggGroups.forEach((group) => {
        const label = (EGG_GROUP_NAMES[group] && EGG_GROUP_NAMES[group][currentLang]) || group;
        eggRow.appendChild(badge(label, EGG_GROUP_COLOR));
      });
      eggCol.appendChild(eggRow);
      dualRow.appendChild(eggCol);
    }

    const dropsCol = document.createElement("div");
    dropsCol.className = "dual-column";
    dropsCol.appendChild(fieldLabel(t("dropsLabel")));
    dropsCol.appendChild(buildDropsSection(info));
    dualRow.appendChild(dropsCol);

    container.appendChild(dualRow);

    return container;
  }

  function buildSpawnSection(info) {
    const container = document.createElement("div");
    if (info.spawns.length === 0) {
      container.textContent = t("noSpawn");
      return container;
    }

    info.spawns.forEach((spawn) => {
      const entry = document.createElement("div");
      entry.className = "spawn-entry";

      const head = document.createElement("div");
      head.className = "spawn-entry-head";
      const bucketKey = spawn.bucket.toLowerCase();
      const rarityLabel = (RARITY_NAMES[bucketKey] && RARITY_NAMES[bucketKey][currentLang]) || spawn.bucket;
      head.appendChild(badge(rarityLabel, RARITY_COLORS[bucketKey] || { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }));
      if (spawn.levelRange) {
        const level = document.createElement("span");
        level.className = "spawn-level";
        level.textContent = `${t("level")} ${spawn.levelRange}`;
        head.appendChild(level);
      }
      if (spawn.skyLightRange) {
        head.appendChild(badge(`${t("skyLightLabel")} ${spawn.skyLightRange}`, SKY_LIGHT_COLOR));
      }
      entry.appendChild(head);

      // Grouped by category (forest, ocean, desert...) instead of one long flat wrapped list —
      // each group gets its own small colored heading and its own "+N" toggle, so a 100+-biome
      // species reads as a handful of labeled clusters instead of a wall of same-shaped pills.
      const biomesByCategory = new Map();
      spawn.biomes.forEach((biome) => {
        const category = biome.category || "other";
        if (!biomesByCategory.has(category)) biomesByCategory.set(category, []);
        biomesByCategory.get(category).push(biome);
      });

      const groupsContainer = document.createElement("div");
      groupsContainer.className = "biome-groups";
      entry.appendChild(groupsContainer);

      // Compact by design: the category label sits inline as the row's first item (not its own
      // heading line) and only 3 biomes show per category by default — a species with a dozen
      // categories still reads as a dozen short lines, not a dozen full sections.
      const visibleCount = 3;
      BIOME_CATEGORY_ORDER.filter((category) => biomesByCategory.has(category)).forEach((category) => {
        const biomesInGroup = biomesByCategory.get(category);
        const colors = BIOME_CATEGORY_COLORS[category] || BIOME_CATEGORY_COLORS.other;

        const groupRow = document.createElement("div");
        groupRow.className = "badge-row biome-group-row";

        const label = document.createElement("span");
        label.className = "biome-group-label";
        label.style.color = colors.bg;
        label.textContent = (BIOME_CATEGORY_NAMES[category] && BIOME_CATEGORY_NAMES[category][currentLang]) || category;
        groupRow.appendChild(label);

        let groupExpanded = false;
        const renderGroupRow = () => {
          groupRow.querySelectorAll(".badge-pill, .biomes-more").forEach((el) => el.remove());
          const list = groupExpanded ? biomesInGroup : biomesInGroup.slice(0, visibleCount);
          list.forEach((biome) => {
            const biomeLabel = category === "safari" ? `${t("safariZonePrefix")}${displayBiome(biome.id)}` : displayBiome(biome.id);
            groupRow.appendChild(badge(biomeLabel, colors));
          });
          if (biomesInGroup.length > visibleCount) {
            const more = document.createElement("button");
            more.type = "button";
            more.className = "biomes-more";
            more.textContent = groupExpanded ? "−" : `+${biomesInGroup.length - visibleCount}`;
            more.addEventListener("click", () => {
              groupExpanded = !groupExpanded;
              renderGroupRow();
            });
            groupRow.appendChild(more);
          }
        };
        renderGroupRow();

        groupsContainer.appendChild(groupRow);
      });

      if (spawn.structures.length > 0) {
        const structureRow = document.createElement("div");
        structureRow.className = "badge-row";
        spawn.structures.forEach((structure) => {
          structureRow.appendChild(badge(displayBiome(structure), STRUCTURE_COLOR));
        });
        entry.appendChild(structureRow);
      }

      container.appendChild(entry);
    });

    return container;
  }

  function buildDropsSection(info) {
    const container = document.createElement("div");
    if (!info.drops || info.drops.length === 0) {
      container.textContent = t("noDrops");
      return container;
    }

    const row = document.createElement("div");
    row.className = "badge-row";
    info.drops.forEach((drop) => {
      const chip = document.createElement("span");
      chip.className = "item-chip";

      // Not every item has an extractable icon (vanilla items have no texture available
      // server-side at all) — hide the <img> rather than show a broken-image icon.
      const icon = document.createElement("img");
      icon.className = "item-chip-icon";
      icon.src = `/api/item/${drop.item}`;
      icon.alt = "";
      icon.addEventListener("error", () => { icon.hidden = true; }, { once: true });
      chip.appendChild(icon);

      const label = document.createElement("span");
      label.textContent = `${displayItemName(drop.item)} · ${drop.percentage}%`;
      chip.appendChild(label);

      row.appendChild(chip);
    });
    container.appendChild(row);
    return container;
  }

  // Single shared tooltip element (not document.body-per-row) — populated and repositioned on
  // each hover instead of building one per move, since a species can have hundreds of moves.
  // Kept visibility:hidden rather than display:none while idle so getBoundingClientRect() still
  // returns real dimensions for positioning before it's actually revealed (no flash-then-jump).
  let moveTooltipEl = null;
  function ensureMoveTooltip() {
    if (moveTooltipEl) return moveTooltipEl;
    const el = document.createElement("div");
    el.className = "move-tooltip";
    el.innerHTML =
      '<div class="move-tooltip-head"><span class="move-tooltip-name"></span></div>' +
      '<div class="move-tooltip-stats"></div>' +
      '<div class="move-tooltip-desc"></div>';
    document.body.appendChild(el);
    moveTooltipEl = el;
    return el;
  }

  function showMoveTooltip(move, anchorEl) {
    const el = ensureMoveTooltip();
    el.querySelector(".move-tooltip-name").textContent = currentLang === "fr" ? move.nameFr : move.nameEn;

    const head = el.querySelector(".move-tooltip-head");
    head.querySelectorAll(".badge-pill").forEach((b) => b.remove());
    if (move.type) {
      const typeLabel = (TYPE_NAMES[move.type] && TYPE_NAMES[move.type][currentLang]) || move.type;
      head.appendChild(badge(typeLabel, TYPE_COLORS[move.type] || { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }));
    }

    const statsEl = el.querySelector(".move-tooltip-stats");
    statsEl.innerHTML = "";
    if (move.category) {
      const categoryLabel = (CATEGORY_NAMES[move.category] && CATEGORY_NAMES[move.category][currentLang]) || move.category;
      statsEl.appendChild(badge(categoryLabel, CATEGORY_COLORS[move.category] || { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }));
    }
    const lines = [];
    if (move.power > 0) lines.push(`${t("movePowerLabel")}: ${move.power}`);
    if (move.accuracy !== undefined) {
      lines.push(move.accuracy > 0 ? `${t("moveAccuracyLabel")}: ${move.accuracy}` : t("moveNeverMiss"));
    }
    if (move.pp !== undefined) lines.push(`${t("movePpLabel")}: ${move.pp}/${move.maxPp}`);
    // Cobblemon's default crit ratio is 1 for nearly every move — only worth surfacing when a
    // move actually deviates from that (e.g. Slash, Night Slash), otherwise it's noise repeated
    // on every single tooltip.
    if (move.critRatio && move.critRatio > 1) lines.push(`${t("moveCritRatioLabel")}: ×${move.critRatio}`);
    const statLines = document.createElement("div");
    statLines.className = "move-tooltip-stat-lines";
    statLines.textContent = lines.join(" · ");
    statsEl.appendChild(statLines);

    const descEl = el.querySelector(".move-tooltip-desc");
    const desc = currentLang === "fr" ? move.descFr : move.descEn;
    descEl.textContent = desc || "";
    descEl.style.display = desc ? "" : "none";

    // Measured while still visibility:hidden (see ensureMoveTooltip), so this reads real
    // dimensions for the content that was just set, not stale ones from the previous move.
    const anchorRect = anchorEl.getBoundingClientRect();
    const tooltipRect = el.getBoundingClientRect();
    let top = anchorRect.bottom + 6;
    let left = anchorRect.left;
    if (left + tooltipRect.width > window.innerWidth - 8) left = window.innerWidth - tooltipRect.width - 8;
    if (top + tooltipRect.height > window.innerHeight - 8) top = anchorRect.top - tooltipRect.height - 6;
    el.style.left = `${Math.max(8, left)}px`;
    el.style.top = `${Math.max(8, top)}px`;
    el.classList.add("is-visible");
  }

  function hideMoveTooltip() {
    if (moveTooltipEl) moveTooltipEl.classList.remove("is-visible");
  }

  /** Move name/level row shared by the level-up grid and the TM/tutor/egg name lists — attaches
   * the hover/focus tooltip so full battle detail (type/power/accuracy/PP/crit/description) is
   * available without permanently showing it, in the theme of this mod rather than a dependency
   * on the in-game mod that inspired it. */
  function buildMoveRow(move) {
    const entry = document.createElement("div");
    entry.className = "move-row";
    entry.tabIndex = 0;
    if (move.level !== undefined) {
      const level = document.createElement("span");
      level.className = "move-level";
      level.textContent = `${t("levelAbbrev")} ${move.level}`;
      entry.appendChild(level);
    }
    const name = document.createElement("span");
    name.className = "move-name";
    name.textContent = currentLang === "fr" ? move.nameFr : move.nameEn;
    entry.appendChild(name);

    entry.addEventListener("mouseenter", () => showMoveTooltip(move, entry));
    entry.addEventListener("mouseleave", hideMoveTooltip);
    entry.addEventListener("focus", () => showMoveTooltip(move, entry));
    entry.addEventListener("blur", hideMoveTooltip);

    return entry;
  }

  // Name-only move grid (TM/tutor/egg moves have no associated level) with a "+N" collapse —
  // some species have a hundred-plus TM moves alone (Clefairy ~150), so showing them all by
  // default would dwarf the rest of the modal. Same collapse pattern as the biome groups above.
  function buildMoveNameList(moves, visibleCount) {
    const wrap = document.createElement("div");
    const grid = document.createElement("div");
    grid.className = "moves-grid";
    wrap.appendChild(grid);

    let expanded = false;
    let moreBtn = null;
    const render = () => {
      grid.innerHTML = "";
      const list = expanded ? moves : moves.slice(0, visibleCount);
      list.forEach((move) => grid.appendChild(buildMoveRow(move)));
      if (moves.length > visibleCount) {
        if (!moreBtn) {
          moreBtn = document.createElement("button");
          moreBtn.type = "button";
          moreBtn.className = "biomes-more";
          moreBtn.addEventListener("click", () => {
            expanded = !expanded;
            render();
          });
          wrap.appendChild(moreBtn);
        }
        moreBtn.textContent = expanded ? "−" : `+${moves.length - visibleCount}`;
      }
    };
    render();
    return wrap;
  }

  function buildMovesSection(info) {
    const container = document.createElement("div");
    const hasLevelUp = info.levelUpMoves && info.levelUpMoves.length > 0;
    const namedGroups = [
      { moves: info.tmMoves, labelKey: "tmMovesLabel" },
      { moves: info.tutorMoves, labelKey: "tutorMovesLabel" },
      { moves: info.eggMoves, labelKey: "eggMovesLabel" },
    ];
    if (!hasLevelUp && namedGroups.every((group) => !group.moves || group.moves.length === 0)) {
      container.textContent = t("noMoves");
      return container;
    }

    if (hasLevelUp) {
      const heading = document.createElement("div");
      heading.className = "moves-subheading";
      heading.textContent = t("levelUpMovesLabel");
      container.appendChild(heading);

      const grid = document.createElement("div");
      grid.className = "moves-grid";
      info.levelUpMoves.forEach((move) => grid.appendChild(buildMoveRow(move)));
      container.appendChild(grid);
    }

    namedGroups.forEach(({ moves, labelKey }) => {
      if (!moves || moves.length === 0) return;
      const heading = document.createElement("div");
      heading.className = "moves-subheading";
      heading.textContent = t(labelKey);
      container.appendChild(heading);
      container.appendChild(buildMoveNameList(moves, 12));
    });

    return container;
  }

  /** Forms list — comes from the player's own pokedex data (species param), not /api/species. */
  function buildFormsSection(species) {
    const formEntries = Object.entries(species.forms || {});
    if (formEntries.length === 0) return null;

    const container = document.createElement("div");
    const heading = document.createElement("div");
    heading.className = "detail-heading";
    heading.textContent = `${t("forms")} (${formEntries.length})`;
    container.appendChild(heading);

    formEntries.forEach(([formName, form]) => {
      const row = document.createElement("div");
      row.className = "form-row";

      const label = document.createElement("span");
      label.className = "form-name";
      // Never-encountered form: no name spoiler, same as an unseen species.
      label.textContent = form.tier === "unregistered" ? "???" : displayFormName(formName);

      const details = document.createElement("span");
      if (form.tier === "unregistered") {
        details.textContent = t("unregisteredWord");
      } else {
        const bits = [];
        if (form.genders.length > 0) bits.push(form.genders.join("/"));
        if (form.shinyStates.includes("shiny")) bits.push(t("shinySeenWord"));
        bits.push(form.tier === "caught" ? t("caughtWord") : t("seenWord"));
        details.textContent = bits.join(" · ");
      }

      row.appendChild(label);
      row.appendChild(details);
      container.appendChild(row);
    });

    return container;
  }

  // One consistent hue for all 6 bars (they're the same "kind" of value, not distinct series
  // needing identity) — reuses the exact fill/track pair already used for the completion meter.
  function buildStatsSection(baseStats) {
    const container = document.createElement("div");
    container.className = "stat-bars";
    let total = 0;

    STAT_ORDER.forEach((key) => {
      const value = baseStats[key];
      if (value === undefined) return;
      total += value;

      const row = document.createElement("div");
      row.className = "stat-bar-row";

      const label = document.createElement("span");
      label.className = "stat-bar-label";
      label.textContent = (STAT_NAMES[key] && STAT_NAMES[key][currentLang]) || key;
      row.appendChild(label);

      const track = document.createElement("div");
      track.className = "stat-bar-track";
      const fill = document.createElement("div");
      fill.className = "stat-bar-fill";
      fill.style.width = `${Math.min(100, (value / STAT_MAX) * 100)}%`;
      fill.style.background = `var(--stat-tier-${statValueTier(value)})`;
      track.appendChild(fill);
      row.appendChild(track);

      const valueEl = document.createElement("span");
      valueEl.className = "stat-bar-value";
      valueEl.textContent = value;
      row.appendChild(valueEl);

      container.appendChild(row);
    });

    const totalRow = document.createElement("div");
    totalRow.className = "stat-bar-row stat-bar-row-total";

    const totalLabel = document.createElement("span");
    totalLabel.className = "stat-bar-label";
    totalLabel.textContent = t("bstLabel");
    totalRow.appendChild(totalLabel);

    const totalTrack = document.createElement("div");
    totalTrack.className = "stat-bar-track";
    const totalFill = document.createElement("div");
    totalFill.className = "stat-bar-fill";
    totalFill.style.width = `${Math.min(100, (total / BST_MAX) * 100)}%`;
    totalTrack.appendChild(totalFill);
    totalRow.appendChild(totalTrack);

    const totalValueEl = document.createElement("span");
    totalValueEl.className = "stat-bar-value";
    totalValueEl.textContent = total;
    totalRow.appendChild(totalValueEl);

    container.appendChild(totalRow);

    return container;
  }

  function coloredSpan(text, colorHex) {
    const span = document.createElement("span");
    span.textContent = text;
    span.style.color = colorHex;
    span.style.fontWeight = "600";
    return span;
  }

  function buildGenderRatioValue(malePercent) {
    const wrap = document.createElement("span");
    if (malePercent === undefined) {
      wrap.appendChild(coloredSpan(t("genderless"), GENDER_COLORS.GENDERLESS.bg));
      return wrap;
    }
    const femalePercent = 100 - malePercent;
    wrap.appendChild(coloredSpan(`♂ ${malePercent.toFixed(1)}%`, GENDER_COLORS.MALE.bg));
    wrap.appendChild(document.createTextNode(" · "));
    wrap.appendChild(coloredSpan(`♀ ${femalePercent.toFixed(1)}%`, GENDER_COLORS.FEMALE.bg));
    return wrap;
  }

  // No item lang-file resolver in this project (unlike species/ability names) — same
  // capitalize-the-id fallback convention already used for biome/structure names.
  function displayItemName(resourceLocation) {
    const path = resourceLocation.includes(":") ? resourceLocation.split(":")[1] : resourceLocation;
    return path.split("_").map(capitalizeFirst).join(" ");
  }

  function evolutionTriggerLabel(trigger) {
    if (trigger.kind === "level") return `${t("levelAbbrev")} ${trigger.level}`;
    if (trigger.kind === "item" && trigger.item) return displayItemName(trigger.item);
    const table = EVOLUTION_TRIGGER_NAMES[trigger.kind] || EVOLUTION_TRIGGER_NAMES.other;
    return table[currentLang];
  }

  function buildEvolutionNode(ref, current) {
    const node = document.createElement("div");
    node.className = "evolution-node" + (current ? " evolution-node-current" : "");

    const sprite = document.createElement("img");
    sprite.className = "evolution-node-sprite";
    sprite.alt = ref.nameEn;
    if (ref.nationalDexNumber) setSprite(sprite, ref.nationalDexNumber, false);
    node.appendChild(sprite);

    const name = document.createElement("span");
    name.className = "evolution-node-name";
    name.textContent = currentLang === "fr" ? ref.nameFr : ref.nameEn;
    node.appendChild(name);

    return node;
  }

  function evolutionArrow(trigger, isBranchStart) {
    const arrow = document.createElement("div");
    arrow.className = "evolution-arrow" + (isBranchStart ? " evolution-arrow-branch" : "");
    const glyph = document.createElement("span");
    glyph.className = "evolution-arrow-glyph";
    // A branch's first arrow uses a distinct "branches off from above" glyph instead of the plain
    // continuation arrow — otherwise a branch row starts with a bare → pointing at nothing, with
    // no visual link back to the species it's an alternative evolution of.
    glyph.textContent = isBranchStart ? "↳" : "→";
    arrow.appendChild(glyph);
    if (trigger) {
      const label = document.createElement("span");
      label.className = "evolution-arrow-label";
      label.textContent = evolutionTriggerLabel(trigger);
      arrow.appendChild(label);
    }
    return arrow;
  }

  // Recurses into each evolution's own nested evolvesTo so a 3+-stage line (Zubat -> Golbat ->
  // Crobat) shows fully from the first species' card, not just the immediate next stage.
  function appendEvolutionBranch(container, evo, isBranchStart) {
    container.appendChild(evolutionArrow(evo.trigger, isBranchStart));
    container.appendChild(buildEvolutionNode(evo, false));
    (evo.evolvesTo || []).forEach((next) => appendEvolutionBranch(container, next, false));
  }

  // Informational only — entries aren't clickable (they're static species refs, not the
  // player-specific caught/seen data the modal needs for the currently-open card).
  //
  // A branch can appear at two different points: an ANCESTOR had another evolution besides the
  // one that leads to the currently-open species (info.evolvesFrom[i].altEvolutions — e.g.
  // opening Poliwrath's own card still needs to show Politoed, even though Politoed isn't on
  // Poliwrath's ancestry or descendant line), or the CURRENT species itself branches
  // (info.evolvesTo, beyond the first). Both cases render as their own row below the trunk,
  // each anchored (for horizontal alignment) to the arrow that follows the node it branches from.
  function buildEvolutionSection(info) {
    const container = document.createElement("div");
    container.className = "evolution-chain";

    const trunkRow = document.createElement("div");
    trunkRow.className = "evolution-chain-row";
    container.appendChild(trunkRow);

    // { anchorEl, evos } pairs — anchorEl is the trunk-row arrow the branch row(s) should line
    // up under, resolved once this section is attached to the live DOM (see _alignEvolutionBranches).
    const branchGroups = [];

    // Full ancestry (could be 2+ hops, e.g. Cleffa -> Clefairy -> Clefable), oldest first.
    (info.evolvesFrom || []).forEach((ancestor) => {
      trunkRow.appendChild(buildEvolutionNode(ancestor, false));
      // ancestor.trigger describes how THAT ancestor evolves into the next step in the chain
      // (looked up server-side via its own forward evolutions) — not the reverse direction.
      const arrowEl = evolutionArrow(ancestor.trigger || null);
      trunkRow.appendChild(arrowEl);
      if (ancestor.altEvolutions && ancestor.altEvolutions.length > 0) {
        branchGroups.push({ anchorEl: arrowEl, evos: ancestor.altEvolutions });
      }
    });

    trunkRow.appendChild(buildEvolutionNode({ nameEn: info.nameEn, nameFr: info.nameFr, nationalDexNumber: info.nationalDexNumber }, true));

    const evolvesTo = info.evolvesTo || [];
    // The first evolution continues on the same line as the trunk (matches the no-branching case
    // visually) — only additional alternatives (e.g. Poliwhirl -> Politoed via trade, on top of
    // -> Poliwrath via Water Stone) drop to their own row below, instead of every branch getting
    // its own row including the first.
    if (evolvesTo.length > 0) {
      const beforeCount = trunkRow.children.length;
      appendEvolutionBranch(trunkRow, evolvesTo[0]);
      if (evolvesTo.length > 1) {
        branchGroups.push({ anchorEl: trunkRow.children[beforeCount], evos: evolvesTo.slice(1) });
      }
    }

    branchGroups.forEach(({ anchorEl, evos }) => {
      evos.forEach((evo) => {
        const branchRow = document.createElement("div");
        branchRow.className = "evolution-chain-row evolution-chain-branch";
        appendEvolutionBranch(branchRow, evo, true);
        branchRow._alignAnchor = anchorEl;
        container.appendChild(branchRow);
      });
    });

    // offsetLeft only reflects real layout once this subtree is attached to the visible
    // document — this section is still detached at this point, so the actual alignment happens
    // in a callback the caller invokes right after appending it.
    container._alignEvolutionBranches = () => {
      container.querySelectorAll(".evolution-chain-branch").forEach((row) => {
        if (row._alignAnchor) row.style.marginLeft = `${row._alignAnchor.offsetLeft}px`;
      });
    };

    return container;
  }

  let speciesModalTrigger = null;
  let speciesModalSpecies = null;
  let speciesModalPokemon = null;

  function closeSpeciesModal() {
    speciesModalEl.hidden = true;
    speciesModalBodyEl.innerHTML = "";
    hideMoveTooltip();
    if (speciesModalTrigger) speciesModalTrigger.focus();
    speciesModalTrigger = null;
    speciesModalSpecies = null;
    speciesModalPokemon = null;
  }

  async function openSpeciesModal(species, triggerEl) {
    speciesModalSpecies = species;
    speciesModalPokemon = null;
    speciesModalTrigger = triggerEl || null;
    speciesModalEl.hidden = false;
    speciesModalBodyEl.innerHTML = "";
    speciesModalBodyEl.textContent = t("loading");
    speciesModalCloseBtn.focus();

    const info = await getSpeciesInfo(species.id);
    if (speciesModalEl.hidden) return; // closed again before the fetch resolved
    speciesModalBodyEl.innerHTML = "";
    if (!info) {
      speciesModalBodyEl.textContent = t("unavailable");
      return;
    }

    try {
      renderSpeciesModalBody(info, species);
    } catch (err) {
      // A thrown error here previously left the modal silently half-built (whatever appended
      // before the throw stayed, everything after it just never ran) — surface it instead.
      console.error("CobbleSync: failed to render species modal", err);
      const errorEl = document.createElement("p");
      errorEl.className = "species-modal-description";
      errorEl.textContent = `${t("unavailable")} (${err.message || err})`;
      speciesModalBodyEl.appendChild(errorEl);
    }
  }

  function renderSpeciesModalBody(info, species) {
    const header = document.createElement("div");
    header.className = "species-modal-header";

    const sprite = document.createElement("img");
    sprite.className = "species-modal-sprite";
    sprite.alt = currentLang === "fr" ? info.nameFr : info.nameEn;
    if (info.nationalDexNumber) setSprite(sprite, info.nationalDexNumber, false);
    header.appendChild(sprite);

    const titleWrap = document.createElement("div");
    titleWrap.className = "species-modal-title";

    if (info.nationalDexNumber) {
      const dexNumber = document.createElement("span");
      dexNumber.className = "dex-number";
      dexNumber.textContent = `#${String(info.nationalDexNumber).padStart(3, "0")}`;
      titleWrap.appendChild(dexNumber);
    }

    const nameEl = document.createElement("h2");
    nameEl.className = "species-modal-name";
    nameEl.textContent = currentLang === "fr" ? info.nameFr : info.nameEn;
    titleWrap.appendChild(nameEl);

    const typeRow = document.createElement("div");
    typeRow.className = "badge-row";
    info.types.forEach((type) => {
      const label = (TYPE_NAMES[type] && TYPE_NAMES[type][currentLang]) || type;
      typeRow.appendChild(badge(label, TYPE_COLORS[type] || { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }));
    });
    titleWrap.appendChild(typeRow);

    header.appendChild(titleWrap);
    speciesModalBodyEl.appendChild(header);

    const description = currentLang === "fr" ? info.descriptionFr : info.descriptionEn;
    if (description) {
      const desc = document.createElement("p");
      desc.className = "species-modal-description";
      desc.textContent = description;
      speciesModalBodyEl.appendChild(desc);
    }

    if (info.baseStats && Object.keys(info.baseStats).length > 0) {
      speciesModalBodyEl.appendChild(fieldLabel(t("baseStatsLabel")));
      speciesModalBodyEl.appendChild(buildStatsSection(info.baseStats));
    }

    const miscGrid = document.createElement("div");
    miscGrid.className = "info-grid";

    const catchRateItem = document.createElement("div");
    catchRateItem.className = "info-item";
    catchRateItem.innerHTML = `<span class="info-label">${t("catchRateLabel")}</span>`;
    const catchRateValue = document.createElement("span");
    catchRateValue.className = "info-value";
    catchRateValue.textContent = info.catchRate;
    catchRateItem.appendChild(catchRateValue);
    miscGrid.appendChild(catchRateItem);

    const genderItem = document.createElement("div");
    genderItem.className = "info-item";
    genderItem.innerHTML = `<span class="info-label">${t("genderRatioLabel")}</span>`;
    const genderValue = document.createElement("span");
    genderValue.className = "info-value";
    genderValue.appendChild(buildGenderRatioValue(info.malePercent));
    genderItem.appendChild(genderValue);
    miscGrid.appendChild(genderItem);

    speciesModalBodyEl.appendChild(miscGrid);

    if ((info.evolvesFrom && info.evolvesFrom.length > 0) || (info.evolvesTo && info.evolvesTo.length > 0)) {
      speciesModalBodyEl.appendChild(fieldLabel(t("evolutionsLabel")));
      const evolutionSection = buildEvolutionSection(info);
      speciesModalBodyEl.appendChild(evolutionSection);
      // Branch-row indent needs the trunk's real rendered layout, which only exists once this
      // subtree is actually attached to the visible document — see _alignEvolutionBranches.
      if (evolutionSection._alignEvolutionBranches) evolutionSection._alignEvolutionBranches();
    }

    const formsSection = buildFormsSection(species);
    if (formsSection) speciesModalBodyEl.appendChild(formsSection);

    speciesModalBodyEl.appendChild(fieldLabel(t("infos")));
    speciesModalBodyEl.appendChild(buildInfoSection(info));

    speciesModalBodyEl.appendChild(fieldLabel(t("whereToFind")));
    speciesModalBodyEl.appendChild(buildSpawnSection(info));

    speciesModalBodyEl.appendChild(fieldLabel(t("movesLabel")));
    speciesModalBodyEl.appendChild(buildMovesSection(info));
  }

  function statLabelOf(showdownId) {
    return (STAT_NAMES[showdownId] && STAT_NAMES[showdownId][currentLang]) || showdownId;
  }

  /** One .stat-bars block (reused from buildStatsSection's markup) scaled to [max] instead of
   * the base-stat ceiling — used for both the IV block (max 31) and EV block (max 252). */
  function buildStatValueBars(values, max, color) {
    const wrap = document.createElement("div");
    wrap.className = "stat-bars";
    STAT_ORDER.forEach((key) => {
      const value = values[key];
      if (value === undefined) return;

      const row = document.createElement("div");
      row.className = "stat-bar-row";

      const label = document.createElement("span");
      label.className = "stat-bar-label";
      label.textContent = statLabelOf(key);
      row.appendChild(label);

      const track = document.createElement("div");
      track.className = "stat-bar-track";
      const fill = document.createElement("div");
      fill.className = "stat-bar-fill";
      fill.style.width = `${Math.min(100, (value / max) * 100)}%`;
      fill.style.background = color;
      track.appendChild(fill);
      row.appendChild(track);

      const valueEl = document.createElement("span");
      valueEl.className = "stat-bar-value";
      valueEl.textContent = value;
      row.appendChild(valueEl);

      wrap.appendChild(row);
    });
    return wrap;
  }

  const IV_MAX = 31;
  const EV_MAX = 252;

  /**
   * Detail popup for one of THIS player's live party Pokémon (nature/ability/held item/IV-EV) —
   * reuses the same modal shell as the species detail view (speciesModalEl/BodyEl/CloseBtn)
   * rather than a second modal element, since the open/close/backdrop/Escape wiring is already
   * fully generic; only the content differs. Data comes straight from the team poll already in
   * hand, so unlike openSpeciesModal there's no fetch/loading state to show.
   */
  function openPokemonModal(pokemon, triggerEl) {
    speciesModalPokemon = pokemon;
    speciesModalSpecies = null;
    speciesModalTrigger = triggerEl || null;
    speciesModalEl.hidden = false;
    speciesModalBodyEl.innerHTML = "";
    speciesModalCloseBtn.focus();

    const header = document.createElement("div");
    header.className = "species-modal-header";

    const sprite = document.createElement("img");
    sprite.className = "species-modal-sprite";
    sprite.alt = pokemon.nameEn;
    setSprite(sprite, pokemon.nationalDexNumber, pokemon.shiny);
    header.appendChild(sprite);

    const titleWrap = document.createElement("div");
    titleWrap.className = "species-modal-title";

    const levelTag = document.createElement("span");
    levelTag.className = "dex-number";
    levelTag.textContent = `${t("levelAbbrev")} ${pokemon.level}`;
    titleWrap.appendChild(levelTag);

    const nameEl = document.createElement("h2");
    nameEl.className = "species-modal-name";
    nameEl.textContent = pokemon.nickname || (currentLang === "fr" ? pokemon.nameFr : pokemon.nameEn);
    titleWrap.appendChild(nameEl);

    const badgeRow = document.createElement("div");
    badgeRow.className = "badge-row";
    if (pokemon.shiny) badgeRow.appendChild(badge("✨ Shiny", { bg: "var(--shiny-color)", fg: "#3a2b00" }));
    const genderGlyph = pokemon.gender === "MALE" ? "♂" : pokemon.gender === "FEMALE" ? "♀" : null;
    if (genderGlyph) badgeRow.appendChild(badge(genderGlyph, GENDER_COLORS[pokemon.gender]));
    titleWrap.appendChild(badgeRow);

    header.appendChild(titleWrap);
    speciesModalBodyEl.appendChild(header);

    const hpPercent = pokemon.maxHealth > 0 ? Math.round((pokemon.currentHealth / pokemon.maxHealth) * 100) : 0;
    const hpRow = document.createElement("div");
    hpRow.className = "stat-bar-row";
    const hpLabel = document.createElement("span");
    hpLabel.className = "stat-bar-label";
    hpLabel.textContent = "HP";
    hpRow.appendChild(hpLabel);
    const hpTrack = document.createElement("div");
    hpTrack.className = "stat-bar-track";
    const hpFill = document.createElement("div");
    hpFill.className = "stat-bar-fill";
    hpFill.style.width = `${hpPercent}%`;
    hpFill.style.background = hpBarColor(hpPercent);
    hpTrack.appendChild(hpFill);
    hpRow.appendChild(hpTrack);
    const hpValue = document.createElement("span");
    hpValue.className = "stat-bar-value";
    hpValue.textContent = `${pokemon.currentHealth}/${pokemon.maxHealth}`;
    hpRow.appendChild(hpValue);
    speciesModalBodyEl.appendChild(hpRow);

    const miscGrid = document.createElement("div");
    miscGrid.className = "info-grid";

    const natureItem = document.createElement("div");
    natureItem.className = "info-item";
    natureItem.innerHTML = `<span class="info-label">${t("natureLabel")}</span>`;
    const natureValue = document.createElement("span");
    natureValue.className = "info-value";
    natureValue.appendChild(document.createTextNode(currentLang === "fr" ? pokemon.natureNameFr : pokemon.natureNameEn));
    if (pokemon.natureIncreasedStat) {
      const up = document.createElement("span");
      up.className = "nature-stat-up";
      up.textContent = ` +${statLabelOf(pokemon.natureIncreasedStat)}`;
      natureValue.appendChild(up);
    }
    if (pokemon.natureDecreasedStat) {
      const down = document.createElement("span");
      down.className = "nature-stat-down";
      down.textContent = ` -${statLabelOf(pokemon.natureDecreasedStat)}`;
      natureValue.appendChild(down);
    }
    natureItem.appendChild(natureValue);
    miscGrid.appendChild(natureItem);

    const abilityItem = document.createElement("div");
    abilityItem.className = "info-item";
    abilityItem.innerHTML = `<span class="info-label">${t("abilityLabel")}</span>`;
    const abilityValue = document.createElement("span");
    abilityValue.className = "info-value";
    abilityValue.textContent = currentLang === "fr" ? pokemon.abilityNameFr : pokemon.abilityNameEn;
    const abilityDesc = currentLang === "fr" ? pokemon.abilityDescFr : pokemon.abilityDescEn;
    if (abilityDesc) {
      abilityValue.title = abilityDesc;
      abilityValue.classList.add("has-tooltip");
    }
    abilityItem.appendChild(abilityValue);
    miscGrid.appendChild(abilityItem);

    const itemItem = document.createElement("div");
    itemItem.className = "info-item";
    itemItem.innerHTML = `<span class="info-label">${t("heldItemLabel")}</span>`;
    if (pokemon.heldItemId) {
      const itemRow = document.createElement("span");
      itemRow.className = "info-value team-modal-held-item";
      const icon = document.createElement("img");
      icon.className = "item-chip-icon";
      icon.alt = "";
      icon.src = `/api/item/${pokemon.heldItemId}`;
      icon.addEventListener("error", () => { icon.style.display = "none"; });
      itemRow.appendChild(icon);
      itemRow.appendChild(document.createTextNode(displayItemName(pokemon.heldItemId)));
      itemItem.appendChild(itemRow);
    } else {
      const noneValue = document.createElement("span");
      noneValue.className = "info-value";
      noneValue.textContent = t("noHeldItem");
      itemItem.appendChild(noneValue);
    }
    miscGrid.appendChild(itemItem);

    speciesModalBodyEl.appendChild(miscGrid);

    speciesModalBodyEl.appendChild(fieldLabel(t("ivEvLabel")));
    const ivEvWrap = document.createElement("div");
    const ivHeading = document.createElement("div");
    ivHeading.className = "moves-subheading";
    ivHeading.textContent = "IV";
    ivEvWrap.appendChild(ivHeading);
    ivEvWrap.appendChild(buildStatValueBars(pokemon.ivs, IV_MAX, "var(--accent)"));
    const evHeading = document.createElement("div");
    evHeading.className = "moves-subheading";
    evHeading.textContent = "EV";
    ivEvWrap.appendChild(evHeading);
    ivEvWrap.appendChild(buildStatValueBars(pokemon.evs, EV_MAX, "var(--status-good)"));
    speciesModalBodyEl.appendChild(ivEvWrap);
  }

  async function enrichCard(card, species) {
    const info = await getSpeciesInfo(species.id);
    if (!info) return;

    card._nameEl.textContent = currentLang === "fr" ? info.nameFr : info.nameEn;

    if (info.nationalDexNumber) {
      setSprite(card._sprite, info.nationalDexNumber, card._isShiny);
    }

    info.types.forEach((type) => {
      const label = (TYPE_NAMES[type] && TYPE_NAMES[type][currentLang]) || type;
      card._typeRow.appendChild(badge(label, TYPE_COLORS[type] || { bg: "var(--neutral-bg)", fg: "var(--text-secondary)" }));
    });
  }

  let searchDebounceTimer = null;
  searchInput.addEventListener("input", () => {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(renderGrid, 200);
  });
  generationSelect.addEventListener("change", () => {
    currentGeneration = generationSelect.value;
    renderGrid();
  });
  filterButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      filterButtons.forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
      currentFilter = btn.dataset.filter;
      renderGrid();
    });
  });
  langButtons.forEach((btn) => {
    btn.addEventListener("click", () => setLanguage(btn.dataset.lang));
  });

  // Cleanly hides the mascot image until web/mascot.png is provided.
  mascotImg.addEventListener("error", () => { mascotImg.hidden = true; }, { once: true });

  filterToggle.addEventListener("click", () => {
    filterPanel.hidden = !filterPanel.hidden;
    filterToggle.setAttribute("aria-expanded", String(!filterPanel.hidden));
  });

  function cyclePlayerTab(direction) {
    const tabs = [...tabsEl.querySelectorAll(".player-tab[data-uuid]")];
    if (tabs.length === 0) return;
    const currentIndex = tabs.findIndex((tab) => tab.classList.contains("is-active"));
    const nextIndex = (currentIndex + direction + tabs.length) % tabs.length;
    tabs[nextIndex].click();
  }

  tabsPrevBtn.addEventListener("click", () => cyclePlayerTab(-1));
  tabsNextBtn.addEventListener("click", () => cyclePlayerTab(1));

  leaderboardTabButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      leaderboardTabButtons.forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
      currentLeaderboardCategory = btn.dataset.leaderboardCategory;
      renderLeaderboard();
    });
  });

  compareASelect.addEventListener("change", loadComparison);
  compareBSelect.addEventListener("change", loadComparison);

  speciesModalCloseBtn.addEventListener("click", closeSpeciesModal);
  speciesModalBackdropEl.addEventListener("click", closeSpeciesModal);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !speciesModalEl.hidden) closeSpeciesModal();
  });

  applyStaticI18n();
  loadPlayers();
  loadActivityFeed();
  connectActivityEvents();
  loadLeaderboard();
  connectLeaderboardEvents();
})();
