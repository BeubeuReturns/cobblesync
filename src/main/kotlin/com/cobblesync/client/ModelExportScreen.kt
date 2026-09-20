package com.cobblesync.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.util.math.fromEulerXYZDegrees
import com.cobblesync.CobbleSync.LOGGER
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Experimental. Renders a single species via Cobblemon's own profile-render call (the same one
 * the pokedex/party screens use), directly on the MAIN render target using the screen's own
 * ambient GuiGraphics matrices/lighting (proven working via ModelPreviewScreen — no custom
 * off-screen TextureTarget, no manual projection/modelview setup).
 *
 * A dedicated off-screen TextureTarget was tried first and abandoned: drawProfilePokemon calls
 * RenderSystem.applyModelViewMatrix() unconditionally as its own first line, which in this MC
 * version has the side effect of rebinding the main render target — meaning a separate target
 * can never stay bound across that call, no matter how many times it's rebound beforehand
 * (confirmed via direct GL_FRAMEBUFFER_BINDING logging).
 *
 * Instead: draw directly onto the main target (where it demonstrably works), then read back just
 * the small region we drew into via a raw glReadPixels call — NOT the higher-level
 * Screenshot.takeScreenshot(RenderTarget) helper, which hung with no exception in an earlier,
 * separate investigation (see CLAUDE.md) when pointed at the main target; a smaller, lower-level
 * manual read is a meaningfully different code path and may avoid whatever caused that.
 *
 * Background isolation is a simple chroma-key: paint a solid, deliberately unusual color behind
 * the model before drawing it, then treat near-matching pixels as transparent when building the
 * final image. Simple and imperfect (a Pokémon that happens to share the key color would get
 * holes punched in it, and anti-aliased edges leave a faint fringe of the key color) but good
 * enough for a first pass — a real solution would need rendering twice against two different
 * backgrounds and diffing, which is more than this prototype needs yet.
 */
class ModelExportScreen(
    private val speciesId: ResourceLocation,
    private val aspects: Set<String>,
    private val outputDir: Path,
    private val onDone: (success: Boolean, message: String) -> Unit
) : Screen(Component.literal("CobbleSync model export")) {
    private var done = false
    private val state = FloatingState()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)
        if (done) return

        val species = PokemonSpecies.getByIdentifier(speciesId)
        if (species == null) {
            done = true
            onDone(false, "Unknown species: $speciesId")
            Minecraft.getInstance().setScreen(null)
            return
        }

        val captureSize = 400
        val centerX = width / 2.0
        val centerY = height * 0.65
        val guiLeft = (centerX - captureSize / 2.0).toInt()
        val guiTop = (centerY - captureSize * 0.55).toInt()

        graphics.fill(guiLeft, guiTop, guiLeft + captureSize, guiTop + captureSize, CHROMA_KEY_ARGB)

        graphics.pose().pushPose()
        graphics.pose().translate(centerX, centerY, 0.0)
        drawProfilePokemon(
            renderablePokemon = RenderablePokemon(species, aspects),
            matrixStack = graphics.pose(),
            rotation = Quaternionf().fromEulerXYZDegrees(Vector3f(13F, 35F, 0F)),
            state = state,
            partialTicks = delta,
            scale = 60f
        )
        graphics.pose().popPose()

        // ModelPreviewScreen showed a correct model from its very first rendered frame, so no
        // warm-up delay is needed here either — capture immediately after this same draw call.
        done = true
        val fileName = "${species.nationalPokedexNumber}${if (aspects.contains("shiny")) "-shiny" else ""}.png"
        try {
            captureRegion(guiLeft, guiTop, captureSize, fileName)
        } catch (t: Throwable) {
            LOGGER.error("exportAndClose: capture failed", t)
            onDone(false, "Failed: $t")
        } finally {
            Minecraft.getInstance().setScreen(null)
        }
    }

    override fun isPauseScreen() = false

    private fun captureRegion(guiLeft: Int, guiTop: Int, captureSize: Int, fileName: String) {
        val window = Minecraft.getInstance().window
        val guiScale = window.guiScale
        val physicalWidth = window.width
        val physicalHeight = window.height

        val physLeft = (guiLeft * guiScale).toInt().coerceIn(0, physicalWidth)
        val physTop = (guiTop * guiScale).toInt().coerceIn(0, physicalHeight)
        val physSize = (captureSize * guiScale).toInt()
        val physRight = (physLeft + physSize).coerceIn(0, physicalWidth)
        val physBottom = (physTop + physSize).coerceIn(0, physicalHeight)
        val readWidth = (physRight - physLeft).coerceAtLeast(1)
        val readHeight = (physBottom - physTop).coerceAtLeast(1)
        // glReadPixels' Y origin is the bottom of the framebuffer, but our GUI-space Y grows
        // downward from the top — flip here so glY=0 corresponds to the bottom of our capture box.
        val glY = (physicalHeight - physBottom).coerceIn(0, physicalHeight)

        LOGGER.info(
            "exportAndClose: capturing physical rect x={} y={} w={} h={} (guiScale={}, window={}x{})",
            physLeft, glY, readWidth, readHeight, guiScale, physicalWidth, physicalHeight
        )

        val buffer = MemoryUtil.memAlloc(readWidth * readHeight * 4)
        try {
            GL11.glReadPixels(physLeft, glY, readWidth, readHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            val glError = GL11.glGetError()
            LOGGER.info("exportAndClose: glReadPixels GL error = {}", glError)
            if (glError != GL11.GL_NO_ERROR) {
                onDone(false, "glReadPixels failed with GL error $glError")
                return
            }

            val image = NativeImage(readWidth, readHeight, false)
            try {
                for (row in 0 until readHeight) {
                    // glReadPixels rows are bottom-up; NativeImage rows are top-down.
                    val srcRow = readHeight - 1 - row
                    for (col in 0 until readWidth) {
                        val srcIndex = (srcRow * readWidth + col) * 4
                        val r = buffer.get(srcIndex).toInt() and 0xFF
                        val g = buffer.get(srcIndex + 1).toInt() and 0xFF
                        val b = buffer.get(srcIndex + 2).toInt() and 0xFF
                        var a = buffer.get(srcIndex + 3).toInt() and 0xFF
                        if (isChromaKey(r, g, b)) {
                            a = 0
                        }
                        val packed = (a shl 24) or (b shl 16) or (g shl 8) or r
                        image.setPixelRGBA(col, row, packed)
                    }
                }
                Files.createDirectories(outputDir)
                val cropped = autoCrop(image)
                try {
                    cropped.writeToFile(outputDir.resolve(fileName))
                    onDone(true, "Saved $fileName")
                } finally {
                    if (cropped !== image) cropped.close()
                }
            } finally {
                image.close()
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    /** Trims the image down to the bounding box of non-transparent pixels, plus a small margin. */
    private fun autoCrop(image: NativeImage): NativeImage {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = (image.getPixelRGBA(x, y) ushr 24) and 0xFF
                if (alpha != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            LOGGER.warn("exportAndClose: autoCrop found nothing non-transparent, keeping full frame")
            return image
        }

        val margin = 10
        val cropLeft = (minX - margin).coerceAtLeast(0)
        val cropTop = (minY - margin).coerceAtLeast(0)
        val cropRight = (maxX + margin).coerceAtMost(image.width - 1)
        val cropBottom = (maxY + margin).coerceAtMost(image.height - 1)
        val cropWidth = cropRight - cropLeft + 1
        val cropHeight = cropBottom - cropTop + 1

        val cropped = NativeImage(cropWidth, cropHeight, false)
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                cropped.setPixelRGBA(x, y, image.getPixelRGBA(cropLeft + x, cropTop + y))
            }
        }
        return cropped
    }

    private fun isChromaKey(r: Int, g: Int, b: Int): Boolean {
        val tolerance = 40
        return kotlin.math.abs(r - CHROMA_KEY_R) <= tolerance &&
            kotlin.math.abs(g - CHROMA_KEY_G) <= tolerance &&
            kotlin.math.abs(b - CHROMA_KEY_B) <= tolerance
    }

    companion object {
        // Deliberately unusual, high-saturation color unlikely to appear on a real Pokémon model —
        // pure "chroma key" green.
        private const val CHROMA_KEY_R = 0
        private const val CHROMA_KEY_G = 255
        private const val CHROMA_KEY_B = 0
        private const val CHROMA_KEY_ARGB = 0xFF00FF00.toInt()
    }
}
