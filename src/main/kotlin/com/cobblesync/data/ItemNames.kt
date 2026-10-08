package com.cobblesync.data

import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * Real display names for items, from the lang file of the mod that registers them. The key comes
 * from the item itself (getDescriptionId), so block items resolve to "block.<ns>.<path>" as they
 * should. French falls back to English, and both are omitted when nothing is found (the frontend
 * then prettifies the id).
 */
object ItemNames {
    fun nameEn(id: ResourceLocation): String? = lookup(id, "en_us")

    fun nameFr(id: ResourceLocation): String? = lookup(id, "fr_fr") ?: nameEn(id)

    /** Adds "<prefix>NameEn"/"<prefix>NameFr" properties when a name is known. */
    fun addTo(obj: JsonObject, id: ResourceLocation, prefix: String) {
        nameEn(id)?.let { obj.addProperty("${prefix}NameEn", it) }
        nameFr(id)?.let { obj.addProperty("${prefix}NameFr", it) }
    }

    private fun lookup(id: ResourceLocation, file: String): String? {
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(null) ?: return null
        return LangFiles.get(id.namespace, file)[item.descriptionId]
    }
}
