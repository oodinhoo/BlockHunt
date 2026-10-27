plugins {
    // Lets Gradle download the JDK 25 that Paper 26.2 requires on its own.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "BlockHunt"
