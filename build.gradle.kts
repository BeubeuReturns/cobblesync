import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("fabric-loom") version "1.9-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
}

val mcVersion = project.property("mc_version").toString()
val modId = project.property("mod_id").toString()
val modVersion = project.property("mod_version").toString()

version = modVersion
group = project.property("group").toString()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(project.property("java_version").toString().toInt()))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.parchmentmc.org")
    maven("https://maven.fabricmc.net/")
    // Jar Cobblemon (remappé intermediary), et dépendances qu'il expose
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("net.minecraft:minecraft:$mcVersion")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-1.21:${project.property("parchment_version")}")
    })

    modImplementation("net.fabricmc:fabric-loader:${project.property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")

    // Cobblemon fournit le pokédex/joueur — compileOnly + runtime fourni par le serveur qui héberge le mod.
    // Passer en modImplementation si tu préfères le bundler avec cobblesync.
    modCompileOnly("maven.modrinth:cobblemon:${project.property("cobblemon_version")}")
    modLocalRuntime("maven.modrinth:cobblemon:${project.property("cobblemon_version")}")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(project.property("java_version").toString().toInt())
    }

    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    processResources {
        inputs.property("version", modVersion)
        inputs.property("mc_version", mcVersion)
        inputs.property("fabric_loader_version", project.property("fabric_loader_version"))
        inputs.property("fabric_api_version", project.property("fabric_api_version"))
        inputs.property("java_version", project.property("java_version"))

        filesMatching("fabric.mod.json") {
            expand(
                "version" to modVersion,
                "mc_version" to mcVersion,
                "fabric_loader_version" to project.property("fabric_loader_version"),
                "fabric_api_version" to project.property("fabric_api_version"),
                "java_version" to project.property("java_version")
            )
        }
    }
}
