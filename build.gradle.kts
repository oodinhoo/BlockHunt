plugins {
    java
    // ./gradlew runServer: downloads Paper 26.2 and starts it with the plugin loaded.
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "fr.blockhunt"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.x coordinate: no more "-R0.1-SNAPSHOT", the format is <version>.build.<number>-<stage>.
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    // Read at configuration time and declared as an input: a version bump reruns the task.
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.runServer {
    minecraftVersion("26.2")
}
