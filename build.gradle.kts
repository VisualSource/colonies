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
val blueprints = layout.projectDirectory.dir("minecolonies/src/main/resources/blueprints/minecolonies")

val convertTasks = packStyles.associateWith { style ->
    tasks.register<Exec>("convertDatapack_$style") {
        group = "minecp"
        description = "Converts the $style blueprints into a data pack folder."
        val source = blueprints.dir(style)
        val out = layout.buildDirectory.dir("datapacks/minecp-$style")
        val report = layout.projectDirectory.file("docs/structures/$style.md")
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
        commandLine("python3", "tools/convert_blueprints.py", source.asFile.path, out.get().asFile.path, report.asFile.path, "--version", v)
    }
}

val zipTasks = packStyles.map { style ->
    tasks.register<Zip>("zipDatapack_$style") {
        group = "minecp"
        description = "Zips the $style data pack, named with the plugin version."
        from(convertTasks.getValue(style)) // its output folder: pack.mcmeta at the zip's root
        archiveFileName = "minecp-$style-${project.version}.zip"
        destinationDirectory = layout.buildDirectory.dir("datapacks")
    }
}

tasks.register("datapacks") {
    group = "minecp"
    description = "Builds every style's structure data pack as build/datapacks/minecp-<style>-<version>.zip."
    dependsOn(zipTasks)
}

val installTasks = packStyles.map { style ->
    tasks.register<Sync>("installDatapack_$style") {
        group = "minecp"
        description = "Puts the $style data pack in run/world/datapacks."
        from(convertTasks.getValue(style))
        into(layout.projectDirectory.dir("run/world/datapacks/minecp-$style")) // only this pack's folder is replaced
    }
}

tasks.register("installDatapacks") {
    group = "minecp"
    description = "Builds the data packs and puts them in run/world/datapacks for the dev server."
    dependsOn(installTasks)
}
