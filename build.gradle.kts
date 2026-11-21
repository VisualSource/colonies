plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.minecp"
version = "1.0.0"

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
