package com.cobblesync.web

import com.cobblesync.CobbleSync
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts the default web page bundled in the jar (src/main/resources/web) to
 * config/cobblesync/web/, BlueMap-style: customizable by admins once extracted.
 */
object StaticFileExtractor {
    fun extractIfMissing(webRoot: Path) {
        if (Files.exists(webRoot)) return

        val container = FabricLoader.getInstance().getModContainer(CobbleSync.MOD_ID).orElse(null)
        if (container == null) {
            CobbleSync.LOGGER.warn("Could not locate the CobbleSync mod container to extract the web page.")
            return
        }

        Files.createDirectories(webRoot)

        for (rootPath in container.rootPaths) {
            val source = rootPath.resolve("web")
            if (!Files.isDirectory(source)) continue

            Files.walk(source).use { stream ->
                stream.forEach { path ->
                    val target = webRoot.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        CobbleSync.LOGGER.info("Default web page extracted to {}", webRoot)
    }
}
