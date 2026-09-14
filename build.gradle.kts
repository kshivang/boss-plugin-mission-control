import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

// One definition so the compileOnly, testImplementation, plugin.json and CI
// pins cannot drift. processResources substitutes it into the manifest.
val bossPluginApiVersion = "1.0.89"

// One definition so the compileOnly and testImplementation pins cannot drift.
val bossPluginApiJar =
    if (useLocalDependencies) {
        files("$bossPluginApiPath/build/libs/boss-plugin-api-$bossPluginApiVersion.jar")
    } else {
        files("build/downloaded-deps/boss-plugin-api.jar")
    }

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    compileOnly(bossPluginApiJar)

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // The plugin API is compileOnly at runtime (host-provided), but tests run
    // outside the host so they need the same classes on the test classpath.
    testImplementation(bossPluginApiJar)
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // BossLogger resolves an SLF4J backend at first use. The host supplies one
    // at runtime; without it on the test classpath a logged warning throws
    // NoClassDefFoundError and masks the assertion that was actually failing.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only (dependencies provided by host)
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("mission-control-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Mission Control Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.missioncontrol.MissionControlDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    inputs.property("bossPluginApiVersion", bossPluginApiVersion)
    filesMatching("**/plugin.json") {
        filter { line ->
            line
                .replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
                // Keeps the declared apiVersion in step with the jar this was
                // actually compiled against.
                .replace(Regex(""""apiVersion"\s*:\s*"[^"]*""""), """"apiVersion": "$bossPluginApiVersion"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
