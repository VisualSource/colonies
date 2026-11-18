plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.minecp"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.3.build.253-stable") // matches the server build; includes the API
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
