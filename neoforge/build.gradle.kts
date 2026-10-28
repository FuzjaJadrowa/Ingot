plugins {
    java
    id("net.neoforged.moddev") version "2.0.147"
}

val modId = providers.gradleProperty("mod_id").get()
val modName = providers.gradleProperty("mod_name").get()
val modVersion = providers.gradleProperty("mod_version").get()
val modLoader = providers.gradleProperty("mod_loader").get()
val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orElse(providers.gradleProperty("build_number"))
    .orElse("0")
    .get()
require(buildNumber.matches(Regex("[0-9]+"))) { "Build number must contain only digits: $buildNumber" }

version = modVersion
base { archivesName.set(modId) }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
repositories { mavenCentral() }

neoForge {
    version = providers.gradleProperty("neo_version").get()
    addModdingDependenciesTo(sourceSets.test.get())
    val ingotMod = mods.create(modId) { sourceSet(sourceSets.main.get()) }
    runs {
        create("client") { client(); loadedMods.set(setOf(ingotMod)) }
        create("server") {
            server()
            programArgument("--nogui")
            loadedMods.set(setOf(ingotMod))
        }
    }
}

dependencies {
    implementation("com.github.luben:zstd-jni:1.5.6-9")
    add("clientAdditionalRuntimeClasspath", "com.github.luben:zstd-jni:1.5.6-9")
    add("serverAdditionalRuntimeClasspath", "com.github.luben:zstd-jni:1.5.6-9")
    jarJar("com.github.luben:zstd-jni:1.5.6-9") {
        version { strictly("[1.5.6-9,1.6)"); prefer("1.5.6-9") }
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
    maxHeapSize = "1G"
}
tasks.jar {
    manifest.attributes("Implementation-Version" to project.version)
    archiveFileName.set("$modName-$modLoader-$modVersion+$minecraftVersion-build.$buildNumber.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.withType<ProcessResources>().configureEach {
    val metadata = mapOf("mod_version" to modVersion)
    inputs.properties(metadata)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "ingot-version.properties")) { expand(metadata) }
}