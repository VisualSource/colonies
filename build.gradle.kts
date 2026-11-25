plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.minecp"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.3.build.253-stable") // matches the server build; includes the API
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) } // plugin.yml's version is the build's
}

tasks.jar {
    archiveBaseName = "Colonies" // the plugin's public name; the code and its namespace stay "minecp"
}

tasks.compileJava {
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    workingDir = layout.buildDirectory.dir("test-run").get().asFile.also { it.mkdirs() } // vanilla classes write logs/ here
}

tasks.runServer {
    minecraftVersion("26.3")
}

// The structure data packs (tools/convert_blueprints.py), from the mod's blueprints in the local MineColonies checkout
// (minecolonies/, git-ignored). `./gradlew datapacks` builds build/datapacks/minecp-<style>-<version>.zip, each pack's
// description naming the plugin version, and refreshes docs/structures/<style>.md; `./gradlew installDatapacks` also
// puts them in run/world/datapacks for the dev server (restart it or /reload to load them). Neither touches the jar.
val packStyles = listOf("medievaloak", "medievalspruce", "medievalbirch", "medievaldarkoak", "cavern")
// Each pack and the blueprint folder it is made from. The original style isn't one to choose: every style borrows the
// huts it lacks from it (the mod falls back to another pack too), the quarries and the stable among them.
val packSources = (packStyles + "original").associateWith { it }
val blueprints = layout.projectDirectory.dir("minecolonies/src/main/resources/blueprints/minecolonies")

val convertTasks = packSources.keys.associateWith { pack ->
    tasks.register<Exec>("convertDatapack_$pack") {
        group = "minecp"
        description = "Converts the $pack blueprints into a data pack folder."
        val source = blueprints.dir(packSources.getValue(pack))
        val out = layout.buildDirectory.dir("datapacks/minecp-$pack")
        val report = layout.projectDirectory.file("docs/structures/$pack.md")
        val v = project.version.toString()
        inputs.dir(source)
        inputs.file("tools/convert_blueprints.py")
        inputs.file("tools/nbt.py")
        inputs.file("tools/blocks-26.3.json")
        inputs.property("version", v)
        outputs.dir(out)
        outputs.file(report)
        doFirst {
            if (!source.asFile.isDirectory) throw GradleException("No blueprints at ${source.asFile}: clone MineColonies into minecolonies/")
            out.get().asFile.deleteRecursively()
        }
        // the original pack, which every server has, also carries the Raider's Bane enchantment (src/main/datapack)
        // and the Nether's supply ship, which only the warped style has
        val netherShip = blueprints.file("warped/decorations/supplies/nethership.blueprint")
        val extra = if (pack == "original") listOf("--extra", layout.projectDirectory.dir("src/main/datapack").asFile.path, "--also", netherShip.asFile.path) else listOf()
        if (pack == "original") { inputs.dir("src/main/datapack"); inputs.file(netherShip) }
        commandLine(listOf("python3", "tools/convert_blueprints.py", source.asFile.path, out.get().asFile.path, report.asFile.path, "--version", v,
                "--pack-name", packSources.getValue(pack)) + extra) // structures named minecp:<style>/<path>, as in the mod
    }
}

val zipTasks = packSources.keys.map { pack ->
    tasks.register<Zip>("zipDatapack_$pack") {
        group = "minecp"
        description = "Zips the $pack data pack, named with the plugin version."
        from(convertTasks.getValue(pack)) // its output folder: pack.mcmeta at the zip's root
        archiveFileName = "minecp-$pack-${project.version}.zip"
        destinationDirectory = layout.buildDirectory.dir("datapacks")
    }
}

tasks.register("datapacks") {
    group = "minecp"
    description = "Builds every style's structure data pack as build/datapacks/minecp-<style>-<version>.zip."
    dependsOn(zipTasks)
}

val installTasks = packSources.keys.map { pack ->
    tasks.register<Sync>("installDatapack_$pack") {
        group = "minecp"
        description = "Puts the $pack data pack in run/world/datapacks."
        from(convertTasks.getValue(pack))
        into(layout.projectDirectory.dir("run/world/datapacks/minecp-$pack")) // only this pack's folder is replaced
    }
}

tasks.register("installDatapacks") {
    group = "minecp"
    description = "Builds the data packs and puts them in run/world/datapacks for the dev server."
    dependsOn(installTasks)
}
