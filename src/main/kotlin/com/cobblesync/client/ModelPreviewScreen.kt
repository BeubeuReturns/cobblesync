package com.cobblesync.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.util.math.fromEulerXYZDegrees
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Diagnostic only: renders continuously using the screen's own ambient GuiGraphics matrices/
 * lighting (the exact context PokedexScreen/PC screens use), no custom render target, no manual
 * projection/lighting setup, no screenshot. Purely to answer "does drawProfilePokemon even show
 * up when called the normal way" — press Escape to close.
 */
class ModelPreviewScreen(private val speciesId: ResourceLocation) : Screen(Component.literal("CobbleSync model preview")) {
    private val state = FloatingState()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        val species = PokemonSpecies.getByIdentifier(speciesId) ?: return
        graphics.pose().pushPose()
        graphics.pose().translate(width / 2.0, height * 0.65, 0.0)
        drawProfilePokemon(
            renderablePokemon = RenderablePokemon(species, emptySet()),
            matrixStack = graphics.pose(),
            rotation = Quaternionf().fromEulerXYZDegrees(Vector3f(13F, 35F, 0F)),
            state = state,
            partialTicks = delta,
            scale = 60f
        )
        graphics.pose().popPose()
    }

    override fun isPauseScreen() = false
}
