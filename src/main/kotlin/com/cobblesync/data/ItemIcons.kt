package com.cobblesync.data

import com.cobblesync.CobbleSync
import com.google.gson.JsonParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

/**
 * An item's own icon texture (PNG bytes), read straight from whatever mod jar bundles it. Items
 * are flat textures, no rendering needed. Used by /api/item and the Discord badge messages.
 *
 * Only works for mods that ship their assets in their jar (Cobblemon, PokeBadges, most Fabric
 * mods). Vanilla item textures are client-only, absent from a dedicated server: those give null.
 */
object ItemIcons {
    // Registered items only: the cache also remembers misses, and arbitrary ids from web requests
    // would grow it without bound.
    fun png(id: ResourceLocation): ByteArray? =
        if (BuiltInRegistries.ITEM.containsKey(id)) ItemIconCache.getOrCompute(id) { resolve(id) } else null

    private fun resolve(id: ResourceLocation): ByteArray? {
        // Common case: texture directly at textures/item/<path>.png.
        textureBytes(id.namespace, "item/${id.path}")?.let { return it }

        // Otherwise the item model's layer0 (e.g. Cobblemon's evolution stones, in a subfolder).
        val modelStream = classLoader().getResourceAsStream("assets/${id.namespace}/models/item/${id.path}.json")
            ?: return null
        val layer0 = modelStream.use { stream ->
            JsonParser.parseReader(stream.reader()).asJsonObject
                .getAsJsonObject("textures")
                ?.get("layer0")
                ?.asString
        } ?: return null

        val textureRef = ResourceLocation.tryParse(layer0) ?: return null
        return textureBytes(textureRef.namespace, textureRef.path)
    }

    private fun textureBytes(namespace: String, texturePath: String): ByteArray? {
        val stream = classLoader().getResourceAsStream("assets/$namespace/textures/$texturePath.png") ?: return null
        return stream.use { it.readBytes() }
    }

    private fun classLoader() = CobbleSync::class.java.classLoader
}
