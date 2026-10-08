package com.cobblesync.data

/**
 * Resolves translated strings (abilities, species, moves, natures) from Cobblemon's own lang files
 * (assets/cobblemon/lang/{en_us,fr_fr}.json), see [LangFiles].
 */
object CobblemonLang {

    fun abilityNameEn(internalName: String): String = abilityName(internalName, "en_us")
    fun abilityNameFr(internalName: String): String = abilityName(internalName, "fr_fr")
    fun abilityDescEn(internalName: String): String? = get("en_us")["cobblemon.ability.$internalName.desc"]
    fun abilityDescFr(internalName: String): String? = get("fr_fr")["cobblemon.ability.$internalName.desc"]

    fun speciesNameEn(speciesPath: String, fallback: String): String = speciesName(speciesPath, "en_us", fallback)
    fun speciesNameFr(speciesPath: String, fallback: String): String = speciesName(speciesPath, "fr_fr", fallback)
    fun speciesDescEn(speciesPath: String): String? = get("en_us")["cobblemon.species.$speciesPath.desc"]
    fun speciesDescFr(speciesPath: String): String? = get("fr_fr")["cobblemon.species.$speciesPath.desc"]

    fun moveNameEn(internalName: String): String = moveName(internalName, "en_us")
    fun moveNameFr(internalName: String): String = moveName(internalName, "fr_fr")
    fun moveDescEn(internalName: String): String? = get("en_us")["cobblemon.move.$internalName.desc"]
    fun moveDescFr(internalName: String): String? = get("fr_fr")["cobblemon.move.$internalName.desc"]

    fun natureNameEn(internalName: String): String = natureName(internalName, "en_us")
    fun natureNameFr(internalName: String): String = natureName(internalName, "fr_fr")

    private fun abilityName(internalName: String, file: String): String {
        val key = "cobblemon.ability.$internalName"
        return get(file)[key] ?: internalName.replaceFirstChar { it.uppercase() }
    }

    private fun moveName(internalName: String, file: String): String {
        val key = "cobblemon.move.$internalName"
        return get(file)[key] ?: internalName.replaceFirstChar { it.uppercase() }
    }

    // Nature.displayName is misleadingly named on the Cobblemon side: it's actually the lang key
    // itself ("cobblemon.nature.adamant"), not resolved text. Same "resolve server-side via the
    // bundled lang file" situation as abilities/moves/species above.
    private fun natureName(internalName: String, file: String): String {
        val key = "cobblemon.nature.$internalName"
        return get(file)[key] ?: internalName.replaceFirstChar { it.uppercase() }
    }

    private fun speciesName(speciesPath: String, file: String, fallback: String): String {
        val key = "cobblemon.species.$speciesPath.name"
        return get(file)[key] ?: fallback
    }

    private fun get(file: String): Map<String, String> = LangFiles.get("cobblemon", file)
}
