package com.cobblesync.client

import com.cobblesync.CobbleSync
import com.cobblesync.CobbleSync.LOGGER
import com.cobblesync.data.PlayerProgress
import com.cobblesync.data.RegionalForms
import com.cobblesync.data.WorldDataCache
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import java.util.ArrayDeque

/** One render: the species, the aspects to render with, and the PNG name the dashboard expects. */
private data class ExportJob(val speciesId: ResourceLocation, val aspects: Set<String>, val fileName: String)

/**
 * Normal + shiny renders for the species and, unless [onlyForm] narrows it down, each of its
 * regional forms. Names come from RegionalForms.modelFileStem, the same rule the dashboard uses
 * to look them up ("37.png", "37-shiny.png", "37-alolan.png", "37-alolan-shiny.png").
 */
private fun jobsFor(speciesId: ResourceLocation, onlyForm: FormData? = null, includeRegional: Boolean = true): List<ExportJob> {
    val species = PokemonSpecies.getByIdentifier(speciesId) ?: return listOf(ExportJob(speciesId, emptySet(), "$speciesId.png"))
    val forms = when {
        onlyForm != null -> listOf(onlyForm)
        includeRegional -> listOf(species.standardForm) + RegionalForms.regionalForms(species)
        else -> listOf(species.standardForm)
    }
    return forms.flatMap { form ->
        val stem = RegionalForms.modelFileStem(species.nationalPokedexNumber, form)
        val formAspects = if (form == species.standardForm) emptySet() else form.aspects.toSet()
        listOf(
            ExportJob(speciesId, formAspects, "$stem.png"),
            ExportJob(speciesId, formAspects + "shiny", "$stem-shiny.png")
        )
    }
}

/**
 * Experimental client-side entrypoint. Registers /cobblesync commands for capturing real
 * Cobblemon model renders as static PNGs (see ModelExportScreen), to use as sprites in the web
 * dashboard instead of/alongside the external PokeAPI ones. Not wired into the normal dashboard
 * data flow beyond writing into web/models/ (served automatically by StaticFileHandler).
 */
object CobbleSyncClient : ClientModInitializer {
    // Every export (single or batch) goes through this one queue, processed strictly one job per
    // client tick. Opening the next screen has to be deferred to END_CLIENT_TICK rather than done
    // synchronously in a command handler or inside the previous job's onDone callback, since
    // whatever screen is "currently active" (chat, or our own previous export screen) closes
    // itself via setScreen(null) in the same call stack right after, which would immediately
    // stomp a screen we just opened synchronously. A real tick boundary runs strictly after that.
    private val queue = ArrayDeque<ExportJob>()

    @Volatile
    private var processing = false

    @Volatile
    private var activeSource: FabricClientCommandSource? = null

    @Volatile
    private var batchTotal = 0

    @Volatile
    private var batchCompleted = 0

    @Volatile
    private var batchFailed = 0

    @Volatile
    private var pendingPreview: ResourceLocation? = null

    override fun onInitializeClient() {
        LOGGER.info("CobbleSyncClient.onInitializeClient() running, registering /cobblesync commands")
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                // Not "cobblesync": that's the server's dashboard-link command, and once connected the
                // server's command tree shadows a client root of the same name (subcommands rejected).
                ClientCommandManager.literal("cobblesyncexport")
                    .then(
                        ClientCommandManager.literal("previewmodel")
                            .then(
                                ClientCommandManager.argument("species", ResourceLocationArgument.id())
                                    .executes { context ->
                                        val speciesId = context.getArgument("species", ResourceLocation::class.java)
                                        pendingPreview = speciesId
                                        1
                                    }
                            )
                    )
                    .then(
                        ClientCommandManager.literal("exportmodel")
                            .then(
                                ClientCommandManager.argument("species", ResourceLocationArgument.id())
                                    // No form: the species plus all its regional forms, normal and shiny.
                                    .executes { context ->
                                        // Client commands use FabricClientCommandSource, not CommandSourceStack.
                                        // ResourceLocationArgument.getId() only accepts the latter, so read the
                                        // parsed value directly instead.
                                        val speciesId = context.getArgument("species", ResourceLocation::class.java)
                                        startBatch(jobsFor(speciesId), context.source)
                                        1
                                    }
                                    // e.g. "/cobblesyncexport exportmodel cobblemon:vulpix alola": that form only.
                                    .then(
                                        ClientCommandManager.argument("form", StringArgumentType.word())
                                            .executes { context ->
                                                val speciesId = context.getArgument("species", ResourceLocation::class.java)
                                                val formName = StringArgumentType.getString(context, "form")
                                                val species = PokemonSpecies.getByIdentifier(speciesId)
                                                val form = species?.let { RegionalForms.formByName(it, formName) }
                                                if (form == null) {
                                                    val known = species?.forms?.joinToString(", ") { it.name } ?: "-"
                                                    context.source.sendError(Component.literal("[CobbleSync] Unknown form '$formName' for $speciesId (forms: $known)"))
                                                } else {
                                                    startBatch(jobsFor(speciesId, onlyForm = form), context.source)
                                                }
                                                1
                                            }
                                    )
                            )
                    )
                    .then(
                        ClientCommandManager.literal("exportallmodels")
                            // Every known species with its regional forms; "noregional" skips them.
                            .executes { context ->
                                startBatch(allJobs(includeRegional = true), context.source)
                                1
                            }
                            .then(
                                ClientCommandManager.literal("noregional").executes { context ->
                                    startBatch(allJobs(includeRegional = false), context.source)
                                    1
                                }
                            )
                    )
                    .then(
                        ClientCommandManager.literal("exportregionalmodels")
                            // Only the regional forms, to complete an existing export.
                            .executes { context ->
                                val jobs = PlayerProgress.relevantSpeciesIds(WorldDataCache.get()).flatMap { id ->
                                    val species = PokemonSpecies.getByIdentifier(id) ?: return@flatMap emptyList()
                                    RegionalForms.regionalForms(species).flatMap { jobsFor(id, onlyForm = it) }
                                }
                                startBatch(jobs, context.source)
                                1
                            }
                    )
            )
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            val preview = pendingPreview
            if (preview != null) {
                pendingPreview = null
                Minecraft.getInstance().setScreen(ModelPreviewScreen(preview))
            }

            if (processing) return@register
            val job = queue.poll() ?: return@register
            processing = true
            val outputDir = CobbleSync.configDir.resolve("web").resolve("models")
            try {
                LOGGER.info("export: opening ModelExportScreen for {} aspects={}", job.speciesId, job.aspects)
                Minecraft.getInstance().setScreen(
                    ModelExportScreen(job.speciesId, job.aspects, outputDir, job.fileName) { success, message ->
                        LOGGER.info("export finished: success={} message={}", success, message)
                        onJobDone(success, message)
                    }
                )
            } catch (t: Throwable) {
                LOGGER.error("export: failed to open export screen", t)
                onJobDone(false, "Failed to open export screen: $t")
            }
        }
    }

    private fun allJobs(includeRegional: Boolean): List<ExportJob> =
        PlayerProgress.relevantSpeciesIds(WorldDataCache.get()).flatMap { jobsFor(it, includeRegional = includeRegional) }

    private fun startBatch(jobs: List<ExportJob>, source: FabricClientCommandSource) {
        queue.clear()
        queue.addAll(jobs)
        batchTotal = jobs.size
        batchCompleted = 0
        batchFailed = 0
        activeSource = source
        source.sendFeedback(
            Component.literal(
                "[CobbleSync] Starting export of ${jobs.size} image(s)" +
                    if (jobs.size > 10) ", this can take a few minutes, ~1 image per game tick." else "..."
            )
        )
    }

    private fun onJobDone(success: Boolean, message: String) {
        processing = false
        if (success) batchCompleted++ else batchFailed++
        val done = batchCompleted + batchFailed
        val source = activeSource ?: return
        val total = batchTotal

        if (total <= 1) {
            // Single exportmodel invocation: report every result directly, same as before.
            val text = Component.literal("[CobbleSync] $message")
            if (success) source.sendFeedback(text) else source.sendError(text)
        } else if (done % 100 == 0 || done == total) {
            source.sendFeedback(Component.literal("[CobbleSync] Exported $done/$total ($batchFailed failed)"))
        }

        if (done == total) {
            if (total > 1) {
                source.sendFeedback(
                    Component.literal("[CobbleSync] Export complete: $batchCompleted saved, $batchFailed failed.")
                )
            }
            activeSource = null
        }
    }
}
