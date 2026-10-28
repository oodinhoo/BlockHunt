plugins {
    java
    // ./gradlew runServer: downloads Paper 26.2 and starts it with the plugin loaded.
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "fr.blockhunt"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.x coordinate: no more "-R0.1-SNAPSHOT", the format is <version>.build.<number>-<stage>.
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")

    testImplementation("io.papermc.paper:paper-api:26.2.build.135-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.117.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
    // MockBukkit aborts a test that reaches a method it does not implement: JUnit reports it as
    // skipped, not failed. A skipped test proves nothing, so it fails the build.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(test: TestDescriptor) {}
        override fun afterTest(test: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.skippedTestCount > 0) {
                throw GradleException("${result.skippedTestCount} test(s) skipped: see the test report.")
            }
        }
    })
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
