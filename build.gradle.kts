plugins {
    java
}

group = "net.guesswhoami"
version = (project.findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Pins resolved dependency versions in gradle.lockfile so CI vulnerability scanning (Trivy's
// fs scan) can actually see the dependency graph instead of just the version ranges declared
// below.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    // Provided by the Maintenance plugin itself at runtime (softdepend); not shaded.
    compileOnly("eu.kennytv.maintenance:maintenance-api-proxy:5.0.0")

    // Not shaded either - already on Velocity's own runtime classpath (Adventure's
    // GsonComponentSerializer pulls it in). Pinned to the version Gradle actually resolves
    // velocity-api:4.0.0's own gson dependency to (verify with
    // `./gradlew dependencies --configuration compileClasspath`) so we never compile against an
    // API newer than what's available at runtime.
    compileOnly("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // MaintenanceStatusService references these compileOnly types in its field/constructor
    // signatures, so the test JVM needs them on the runtime classpath just to load the class -
    // even though the only thing actually under test is a static, dependency-free method.
    testImplementation("com.velocitypowered:velocity-api:4.0.0")
    testImplementation("eu.kennytv.maintenance:maintenance-api-proxy:5.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("maintenance-bridge")
}

// @Plugin's version attribute must be a compile-time constant, so it can't reference the
// Gradle version directly - generate a small constants source file instead, so the version
// baked into velocity-plugin.json (and thus Velocity's "Loaded plugin ..." log line) can never
// drift from the actual jar version again.
val generatedSourcesDir = layout.buildDirectory.dir("generated/sources/buildInfo/java/main")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = generatedSourcesDir
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        // Escaped even though releaseVersion is always a well-formed semver-ish string in
        // practice (it comes from a git tag via release.yml) - a malformed manual
        // workflow_dispatch input shouldn't be able to produce broken generated Java source.
        val escapedVersion = versionValue.replace("\\", "\\\\").replace("\"", "\\\"")
        val packageDir = outputDir.get().asFile.resolve("net/guesswhoami/maintenancebridge")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.java").writeText(
            """
            package net.guesswhoami.maintenancebridge;

            final class BuildInfo {
                static final String VERSION = "$escapedVersion";

                private BuildInfo() {
                }
            }
            """.trimIndent()
        )
    }
}

// Registering the task itself (not just its output directory) as the source dir lets Gradle
// infer compileJava's dependency on generateBuildInfo automatically from the source set's
// build dependencies, instead of needing an explicit compileJava.dependsOn(generateBuildInfo) -
// so tooling that queries source directories without running compileJava directly (IDE import,
// incremental analysis) still sees the correct producing task, not just a directory that may
// not exist yet.
sourceSets {
    main {
        java.srcDir(generateBuildInfo)
    }
}
