package com.cobblesync.discord

import com.cobblesync.CobbleSync
import com.cobblesync.config.DiscordConfig
import com.cobblesync.data.CobblemonLang
import com.cobblesync.data.ItemIcons
import com.cobblesync.data.ItemNames
import com.cobblesync.data.PlayerProgress
import com.cobblesync.data.RegionalForms
import com.cobblesync.integration.PokeBadgesIntegration.BadgeNotice
import com.cobblesync.integration.PokeBadgesIntegration.RegionNotice
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Posts Discord webhook embeds (JDK HttpClient, no extra dependency): captures (shiny, new
 * species, legendary/mythical) and PokeBadges badges. Message data is gathered on the server
 * thread; images and HTTP run on a single background thread, so nothing blocks the game, and
 * notifications keep working with the dashboard's web server disabled.
 *
 * Images ride along as attachments where we have the bytes (exported model renders, badge
 * textures), so they work even when the dashboard isn't publicly reachable.
 */
object DiscordNotifier {
    @Volatile
    private var config: DiscordConfig = DiscordConfig(false, "", "en", true, false)

    @Volatile
    private var dashboardUrl: String = ""

    // Recreated on demand: shutdown() runs on every world stop, and in singleplayer the next
    // world reuses this same object.
    private var executor: ExecutorService? = null
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private const val COLOR_SHINY = 0xF1C40F
    private const val COLOR_RARE = 0x9B59B6
    private const val COLOR_NEW = 0x3498DB
    private const val COLOR_BADGE = 0xE67E22
    private const val COLOR_REGION = 0x2ECC71

    private const val HOSTED_MODELS = "https://raw.githubusercontent.com/BeubeuReturns/cobblemon_sprites/main/models"

    private enum class CaptureKind { RARE, SHINY, NEW }

    private class Attachment(val fileName: String, val bytes: ByteArray)

    fun configure(config: DiscordConfig, dashboardUrl: String) {
        this.config = config
        this.dashboardUrl = dashboardUrl
    }

    private fun active(): DiscordConfig? = config.takeIf { it.enabled && it.webhookUrl.isNotBlank() }

    /** Legendary and mythical captures go through [notifyRareCapture] instead, on every catch. */
    fun handlesRareCaptures(pokemon: Pokemon): Boolean =
        active()?.notifyLegendary == true && rarityOf(pokemon) != null

    /** First catch of a species, or first shiny of it. Server thread. */
    fun notifyCapture(playerUuid: UUID, playerName: String, pokemon: Pokemon, isNewSpecies: Boolean, isNewShiny: Boolean) {
        val c = active() ?: return
        if (handlesRareCaptures(pokemon)) return
        val kind = when {
            isNewShiny && c.notifyShiny -> CaptureKind.SHINY
            isNewSpecies && c.notifyNewSpecies -> CaptureKind.NEW
            else -> return
        }
        postCapture(c, kind, playerUuid, playerName, pokemon)
    }

    /** Every legendary or mythical catch, already-owned species included. Server thread. */
    fun notifyRareCapture(player: ServerPlayer, pokemon: Pokemon) {
        val c = active() ?: return
        if (!handlesRareCaptures(pokemon)) return
        postCapture(c, CaptureKind.RARE, player.uuid, player.gameProfile.name, pokemon)
    }

    fun notifyBadge(player: ServerPlayer, notice: BadgeNotice) {
        val c = active() ?: return
        if (!c.notifyBadges) return
        val fr = c.language == "fr"
        val badge = bold(if (fr) notice.nameFr else notice.nameEn)
        val region = if (fr) notice.regionFr else notice.regionEn
        val count = if (notice.owned != null && notice.total != null) ", ${notice.owned}/${notice.total}" else ""
        val embed = baseEmbed(player.uuid, player.gameProfile.name, COLOR_BADGE).apply {
            addProperty("title", if (fr) "🏅 Nouveau badge !" else "🏅 New badge!")
            addProperty(
                "description",
                if (fr) "${bold(player.gameProfile.name)} a obtenu le $badge ($region$count)"
                else "${bold(player.gameProfile.name)} earned the $badge ($region$count)"
            )
            (if (fr) notice.leaderFr else notice.leaderEn)?.let { leader ->
                add("fields", JsonArray().apply { add(field(if (fr) "Champion d'arène" else "Gym Leader", leader)) })
            }
        }
        post(c.webhookUrl, embed, "image") {
            ItemIcons.png(notice.badgeId)?.let { upscale(it, 128) }?.let { Attachment("badge.png", it) }
        }
    }

    fun notifyRegionComplete(player: ServerPlayer, notice: RegionNotice) {
        val c = active() ?: return
        if (!c.notifyRegionComplete) return
        val fr = c.language == "fr"
        val region = bold(if (fr) notice.regionFr else notice.regionEn)
        val embed = baseEmbed(player.uuid, player.gameProfile.name, COLOR_REGION).apply {
            addProperty("title", if (fr) "🏆 Région terminée !" else "🏆 Region complete!")
            addProperty(
                "description",
                if (fr) "${bold(player.gameProfile.name)} a obtenu tous les badges de $region !"
                else "${bold(player.gameProfile.name)} earned every badge of $region!"
            )
        }
        post(c.webhookUrl, embed, "image") {
            montage(notice.badgeIds.mapNotNull { ItemIcons.png(it) }, 64)?.let { Attachment("badges.png", it) }
        }
    }

    /**
     * /cobblesync discordtest: posts a sample embed and reports back, so setup problems show in
     * chat instead of only in the server log. [report] runs on the background thread.
     */
    fun sendTest(playerUuid: UUID, playerName: String, report: (String) -> Unit) {
        val c = config
        val fr = c.language == "fr"
        when {
            !c.enabled -> return report(if (fr) "Discord désactivé : mets enabled=true dans discord.conf." else "Discord is off: set enabled=true in discord.conf.")
            c.webhookUrl.isBlank() -> return report(if (fr) "Aucune webhook-url dans discord.conf." else "No webhook-url in discord.conf.")
        }
        val embed = baseEmbed(playerUuid, playerName, COLOR_NEW).apply {
            addProperty("title", if (fr) "✅ Test CobbleSync" else "✅ CobbleSync test")
            addProperty("description", if (fr) "Le webhook fonctionne." else "The webhook works.")
        }
        post(c.webhookUrl, embed, "thumbnail", report) { pokemonImage(25, "25.png", false) }
    }

    @Synchronized
    fun shutdown() {
        executor?.shutdown()
        executor = null
    }

    // --- Captures ---

    private fun rarityOf(pokemon: Pokemon): String? = pokemon.species.labels.let { labels ->
        when {
            "mythical" in labels -> "mythical"
            "legendary" in labels -> "legendary"
            else -> null
        }
    }

    private fun postCapture(c: DiscordConfig, kind: CaptureKind, playerUuid: UUID, playerName: String, pokemon: Pokemon) {
        val fr = c.language == "fr"
        val species = pokemon.species
        val form = RegionalForms.normalize(pokemon.form)
        val name = bold(pokemonName(species.resourceIdentifier.path, species.name, form, fr))
        val player = bold(playerName)
        val shiny = pokemon.shiny

        val (title, description, color) = when (kind) {
            CaptureKind.RARE -> {
                val mythical = rarityOf(pokemon) == "mythical"
                val rarity = when {
                    fr && mythical -> "Pokémon fabuleux"
                    fr -> "Pokémon légendaire"
                    mythical -> "mythical Pokémon"
                    else -> "legendary Pokémon"
                }
                Triple(
                    if (fr) "★ $rarity${if (shiny) " shiny" else ""} capturé !"
                    else "★ ${if (shiny) "Shiny $rarity" else rarity.replaceFirstChar { it.uppercase() }} caught!",
                    if (fr) "$player a capturé $name !" else "$player caught $name!",
                    if (shiny) COLOR_SHINY else COLOR_RARE
                )
            }
            CaptureKind.SHINY -> Triple(
                if (fr) "✨ Shiny capturé !" else "✨ Shiny caught!",
                if (fr) "$player a capturé un $name shiny !" else "$player caught a shiny $name!",
                COLOR_SHINY
            )
            CaptureKind.NEW -> Triple(
                if (fr) "📖 Nouvelle espèce" else "📖 New species",
                if (fr) "$player a capturé $name pour la première fois." else "$player caught $name for the first time.",
                COLOR_NEW
            )
        }

        val fields = JsonArray().apply {
            add(field(if (fr) "Niveau" else "Level", pokemon.level.toString()))
            val nature = pokemon.nature.name.path
            add(field("Nature", if (fr) CobblemonLang.natureNameFr(nature) else CobblemonLang.natureNameEn(nature)))
            ballName(pokemon, fr)?.let { add(field("Ball", it)) }
        }

        val embed = baseEmbed(playerUuid, playerName, color).apply {
            addProperty("title", title)
            addProperty("description", description)
            add("fields", fields)
            runCatching { PlayerProgress.summarize(playerUuid) }.getOrNull()?.let { progress ->
                val text = "Pokédex${if (fr) " :" else ":"} ${progress.caughtCount}/${progress.totalKnownSpecies}"
                add("footer", JsonObject().apply { addProperty("text", text) })
            }
        }

        // Rare catches get the big image, the more frequent new-species messages a thumbnail.
        val dex = species.nationalPokedexNumber
        val fileName = RegionalForms.modelFileStem(dex, form) + (if (shiny) "-shiny" else "") + ".png"
        post(c.webhookUrl, embed, if (kind == CaptureKind.NEW) "thumbnail" else "image") {
            pokemonImage(dex, fileName, shiny)
        }
    }

    private fun pokemonName(path: String, fallback: String, form: FormData, fr: Boolean): String {
        val base = if (fr) CobblemonLang.speciesNameFr(path, fallback) else CobblemonLang.speciesNameEn(path, fallback)
        val region = RegionalForms.regionOf(form) ?: return base
        return if (fr) {
            "$base " + mapOf("alola" to "d'Alola", "galar" to "de Galar", "hisui" to "de Hisui", "paldea" to "de Paldea").getValue(region)
        } else {
            mapOf("alola" to "Alolan", "galar" to "Galarian", "hisui" to "Hisuian", "paldea" to "Paldean").getValue(region) + " $base"
        }
    }

    private fun ballName(pokemon: Pokemon, fr: Boolean): String? = runCatching {
        val id = BuiltInRegistries.ITEM.getKey(pokemon.caughtBall.item())
        (if (fr) ItemNames.nameFr(id) else ItemNames.nameEn(id)) ?: pokemon.caughtBall.name.path
    }.getOrNull()

    /**
     * Background thread. Same order as the dashboard: the render exported on this server
     * (/cobblesyncexport, sent as a file), the community-hosted render, then PokeAPI's artwork.
     * Returns an attachment, or a URL string for Discord to fetch.
     */
    private fun pokemonImage(dex: Int, fileName: String, shiny: Boolean): Any? {
        val local = CobbleSync.configDir.resolve("web").resolve("models").resolve(fileName)
        if (Files.isRegularFile(local)) {
            return runCatching { Attachment("pokemon.png", Files.readAllBytes(local)) }.getOrNull()
        }
        val hosted = "$HOSTED_MODELS/$fileName"
        if (exists(hosted)) return hosted
        return "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/${if (shiny) "shiny/" else ""}$dex.png"
    }

    private fun exists(url: String): Boolean = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(5))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    // --- Embeds ---

    private fun baseEmbed(playerUuid: UUID, playerName: String, color: Int) = JsonObject().apply {
        add("author", JsonObject().apply {
            addProperty("name", playerName)
            // Minecraft head of the player, rendered by a public skin service.
            addProperty("icon_url", "https://mc-heads.net/avatar/$playerUuid/64")
        })
        addProperty("color", color)
        addProperty("timestamp", Instant.now().toString())
        if (dashboardUrl.isNotEmpty()) addProperty("url", dashboardUrl)
    }

    private fun field(name: String, value: String) = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value)
        addProperty("inline", true)
    }

    /** Bold, with markdown characters escaped: "Some_Name" would otherwise turn italic. */
    private fun bold(text: String) = "**" + text.replace(Regex("""([\\*_~`|])"""), """\\$1""") + "**"

    // --- Images ---

    /** Pixel art scaled up by a whole factor without blur. First frame only for animated strips. */
    private fun upscale(png: ByteArray, target: Int): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(png)) ?: return null
        val frame = if (src.height > src.width) src.getSubimage(0, 0, src.width, src.width) else src
        val scale = (target / maxOf(frame.width, frame.height)).coerceAtLeast(1)
        val out = BufferedImage(frame.width * scale, frame.height * scale, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(frame, 0, 0, out.width, out.height, null)
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }.getOrNull()

    /** Badges side by side, each scaled to [size] px. */
    private fun montage(pngs: List<ByteArray>, size: Int): ByteArray? = runCatching {
        val icons = pngs.mapNotNull { upscale(it, size) }.mapNotNull { ImageIO.read(ByteArrayInputStream(it)) }
        if (icons.isEmpty()) return null
        val gap = size / 8
        val out = BufferedImage(icons.sumOf { it.width } + gap * (icons.size - 1), icons.maxOf { it.height }, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        var x = 0
        for (icon in icons) {
            g.drawImage(icon, x, 0, null)
            x += icon.width + gap
        }
        g.dispose()
        ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
    }.getOrNull()

    // --- Sending ---

    @Synchronized
    private fun executor(): ExecutorService =
        executor?.takeUnless { it.isShutdown }
            ?: Executors.newSingleThreadExecutor { r -> Thread(r, "cobblesync-discord").apply { isDaemon = true } }
                .also { executor = it }

    /**
     * Sends one embed. [image] runs on the background thread: an [Attachment] goes up with the
     * message as a file, a String is used as the image URL, null sends the embed without image.
     */
    private fun post(
        webhookUrl: String,
        embed: JsonObject,
        imageSlot: String,
        report: ((String) -> Unit)? = null,
        image: () -> Any?
    ) {
        executor().submit {
            try {
                val attachment = when (val resolved = runCatching(image).getOrNull()) {
                    is Attachment -> resolved.also { embed.add(imageSlot, urlJson("attachment://${it.fileName}")) }
                    is String -> null.also { embed.add(imageSlot, urlJson(resolved)) }
                    else -> null
                }
                val payload = JsonObject().apply { add("embeds", JsonArray().apply { add(embed) }) }.toString()
                val request = if (attachment == null) {
                    HttpRequest.newBuilder(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                } else {
                    val boundary = "cobblesync-" + UUID.randomUUID()
                    HttpRequest.newBuilder(URI.create(webhookUrl))
                        .header("Content-Type", "multipart/form-data; boundary=$boundary")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, payload, attachment)))
                }
                val response = httpClient.send(request.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() >= 300) {
                    CobbleSync.LOGGER.warn("Discord webhook returned HTTP {}: {}", response.statusCode(), response.body().take(300))
                    report?.invoke("Discord: HTTP ${response.statusCode()} ${response.body().take(200)}")
                } else {
                    report?.invoke("Discord: OK (HTTP ${response.statusCode()})")
                }
            } catch (e: Exception) {
                CobbleSync.LOGGER.warn("Failed to post Discord webhook notification", e)
                report?.invoke("Discord: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
    }

    private fun urlJson(url: String) = JsonObject().apply { addProperty("url", url) }

    private fun multipart(boundary: String, payload: String, attachment: Attachment): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(StandardCharsets.UTF_8))
        text("--$boundary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n")
        text(payload)
        text("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"${attachment.fileName}\"\r\nContent-Type: image/png\r\n\r\n")
        out.write(attachment.bytes)
        text("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }
}
