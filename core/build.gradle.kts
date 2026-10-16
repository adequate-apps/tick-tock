// SPDX-License-Identifier: GPL-3.0-or-later
//
// :core is a Kotlin Multiplatform module (decision 18). It holds the detector,
// the schemas and the frozen contracts. commonMain sees neither the Android SDK
// nor the JDK, so an android.* or java.* import there is a compile error and
// ModuleWallTest explains why. Android modules consume the JVM target.
//
// The plugin is applied by id alone: kotlin-gradle-plugin is already on the
// build classpath through the kotlin.jvm marker in the root script, so no new
// plugin marker is resolved.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(project(":core-testing"))
            implementation(libs.junit)
            implementation(libs.coroutines.test)
        }
    }
}

val nativeFixtureDirectory = layout.buildDirectory.dir("native-fixtures")
val configureNativeFixtures by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("host-tests/CMakeLists.txt"))
    outputs.file(nativeFixtureDirectory.map { it.file("CMakeCache.txt") })
    commandLine("cmake", "-S", rootProject.file("host-tests").absolutePath,
        "-B", nativeFixtureDirectory.get().asFile.absolutePath, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release")
}
val buildNativeFixtures by tasks.registering(Exec::class) {
    dependsOn(configureNativeFixtures)
    inputs.file(rootProject.file("host-tests/AudioFixtureDriver.cpp"))
    inputs.dir(rootProject.file("engine/src/main/cpp/render"))
    outputs.file(nativeFixtureDirectory.map { it.file("audio_fixture_driver") })
    commandLine("cmake", "--build", nativeFixtureDirectory.get().asFile.absolutePath,
        "--target", "audio_fixture_driver")
}

tasks.named<Test>("jvmTest") {
    dependsOn(buildNativeFixtures)
    val nativeDriver = nativeFixtureDirectory.map { it.file("audio_fixture_driver") }
    inputs.file(nativeDriver)
    systemProperty("ticktock.nativeFixtureDriver", nativeDriver.get().asFile.absolutePath)
    // KnownGapRuleTest drives its nested suites through JUnitCore to observe the
    // rule from outside. Gradle would otherwise run those suites directly, and
    // one of them is meant to fail.
    filter.excludeTestsMatching("ticktock.core.testing.KnownGapRuleTest\$*")
    // One line per case in the console: a failing sweep must leave the rest visible.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
