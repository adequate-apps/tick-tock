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

tasks.named<Test>("jvmTest") {
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
