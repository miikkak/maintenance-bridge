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

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    // Provided by the Maintenance plugin itself at runtime (softdepend); not shaded.
    compileOnly("eu.kennytv.maintenance:maintenance-api-proxy:5.0.0")

    // Not shaded either - already on Velocity's own runtime classpath (Adventure's
    // GsonComponentSerializer pulls it in; confirmed present in velocity-4.0.0-6.jar).
    // Pinned to the version actually bundled there so we never compile against an
    // API newer than what's available at runtime.
    compileOnly("com.google.code.gson:gson:2.8.0")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.8.0")
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
        val packageDir = outputDir.get().asFile.resolve("net/guesswhoami/maintenancebridge")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.java").writeText(
            """
            package net.guesswhoami.maintenancebridge;

            final class BuildInfo {
                static final String VERSION = "$versionValue";

                private BuildInfo() {
                }
            }
            """.trimIndent()
        )
    }
}

sourceSets {
    main {
        java.srcDir(generatedSourcesDir)
    }
}

tasks.compileJava {
    dependsOn(generateBuildInfo)
}
