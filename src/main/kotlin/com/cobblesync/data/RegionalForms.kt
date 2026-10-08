package com.cobblesync.data

import com.cobblemon.mod.common.api.pokedex.entry.PokedexForm
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species

/**
 * Regional forms (Alola, Galar, Hisui, Paldea) get their own dashboard card and sheet. Megas,
 * Gigantamax and cosmetic forms (incl. addon ones like Pikachu's "Alola" cap) are not regional
 * and stay on the species card.
 *
 * "-Bias" forms (Pikachu Alola-Bias, Cyndaquil Hisui-Bias...) are invisible markers: they look
 * like the normal form and only steer evolution toward a regional form (Alolan Raichu, Hisuian
 * Typhlosion). The dashboard folds them into the standard form, see [normalize]/[evolutionsOf].
 */
object RegionalForms {
    private val REGION_LABELS = mapOf(
        "alolan_form" to "alola",
        "galarian_form" to "galar",
        "hisuian_form" to "hisui",
        "paldean_form" to "paldea"
    )

    /** Region id -> its form aspect ("alola" -> "alolan"), also the render file name word. */
    private val REGION_ASPECTS = mapOf(
        "alola" to "alolan",
        "galar" to "galarian",
        "hisui" to "hisuian",
        "paldea" to "paldean"
    )

    fun isBias(form: FormData): Boolean =
        form.aspects.any { it.startsWith("region-bias") } || form.name.endsWith("-Bias", ignoreCase = true)

    /**
     * Never the standard form, never a Bias marker. A form's own labels decide when it has some:
     * Cobblemon also labels whole species ("alolan_form" on Rowlet, a native Alola species) and
     * forms without labels of their own inherit those, so inherited ones are ignored (getLabels
     * then returns the species' very set). Without own labels, and on clients where labels
     * aren't synced at all (the model export), the region aspect ("alolan") decides.
     */
    fun regionOf(form: FormData): String? {
        val species = form.species
        if (form === species.standardForm || isBias(form)) return null
        if (form.labels !== species.labels) {
            form.labels.firstNotNullOfOrNull { REGION_LABELS[it] }?.let { return it }
        }
        return REGION_ASPECTS.entries.firstOrNull { (_, aspect) -> aspect in form.aspects }?.key
    }

    fun isRegional(form: FormData): Boolean = regionOf(form) != null

    fun regionalForms(species: Species): List<FormData> = species.forms.filter { isRegional(it) }

    /** Bias markers display, spawn and evolve as the standard form on the dashboard. */
    fun normalize(form: FormData): FormData = if (isBias(form)) form.species.standardForm else form

    /**
     * A form's evolutions as the dashboard shows them: the standard form also gets its Bias
     * markers' ones, so Pikachu branches to both Raichu and Alolan Raichu.
     */
    fun evolutionsOf(form: FormData): List<Evolution> {
        val species = form.species
        return if (form === species.standardForm) {
            form.evolutions.toList() + species.forms.filter { isBias(it) }.flatMap { it.evolutions }
        } else {
            form.evolutions.toList()
        }
    }

    /**
     * What distinguishes this form within its region, lowercased: "combat" for "Paldea-Combat",
     * "white-striped" for Hisuian "White-Striped" Basculin, null for a plain "Alola".
     * Mirrored by formVariant() in app.js.
     */
    fun variantOf(form: FormData, region: String): String? = when {
        form.name.equals(region, ignoreCase = true) -> null
        form.name.startsWith("$region-", ignoreCase = true) -> form.name.substring(region.length + 1).lowercase()
        else -> form.name.lowercase()
    }

    /**
     * Render file stem the dashboard looks for: "37", "37-alolan", "128-paldean-combat".
     * Mirrored by modelKey() in app.js; shiny renders add "-shiny".
     */
    fun modelFileStem(nationalDexNumber: Int, form: FormData): String {
        val region = regionOf(form) ?: return "$nationalDexNumber"
        val variant = variantOf(form, region)
        return "$nationalDexNumber-${REGION_ASPECTS.getValue(region)}" + (variant?.let { "-$it" } ?: "")
    }

    fun formByName(species: Species, name: String?): FormData? =
        if (name == null) null else species.forms.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The FormData a pokedex display form stands for (its first unlocking form, else by name). */
    fun formOf(species: Species, pokedexForm: PokedexForm): FormData =
        pokedexForm.unlockForms.firstNotNullOfOrNull { formByName(species, it) }
            ?: formByName(species, pokedexForm.displayForm)
            ?: species.standardForm

    /**
     * Form described by spawn or evolution properties, e.g. "vulpix alolan" or "meowth form=galar".
     * Reads parsed aspects/form plus the raw tokens, since a bare "alolan" may be kept as a custom
     * property rather than an aspect. Falls back to the standard form.
     */
    fun formFor(species: Species, properties: PokemonProperties): FormData {
        val tokens = HashSet<String>()
        properties.aspects.forEach { tokens += it.lowercase() }
        properties.form?.let { tokens += it.lowercase() }
        properties.originalString.lowercase().split(' ', '=').filter { it.isNotEmpty() }.forEach { tokens += it }
        return species.forms.firstOrNull { form ->
            form !== species.standardForm &&
                ((form.aspects.isNotEmpty() && form.aspects.all { it.lowercase() in tokens }) || form.name.lowercase() in tokens)
        } ?: species.standardForm
    }

    /**
     * Species + form an evolution result points to (Bias markers folded into the standard form),
     * or null if the species doesn't exist.
     */
    fun resolve(properties: PokemonProperties): Pair<Species, FormData>? {
        val species = PokemonSpecies.getByName(properties.species ?: return null) ?: return null
        return species to normalize(formFor(species, properties))
    }
}
