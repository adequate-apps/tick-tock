// SPDX-License-Identifier: GPL-3.0-or-later
//
// :data owns storage: framework SQLite with hand-written migrations, the prefs
// file and the crash log. It depends on :core for the schemas and on nothing
// else in the app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ticktock.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperties(robolectricOffline(rootProject)) }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(project(":core-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}

/** See the note in engine/build.gradle.kts: Robolectric runs offline and fails closed. */
fun robolectricOffline(root: Project): Map<String, String> = mapOf(
    "robolectric.enabledSdks" to "36",
    "robolectric.offline" to "true",
    "robolectric.dependency.dir" to root.file("tools/robolectric-deps").absolutePath,
)

// A failing case prints its assertion in the console, which is all CI keeps.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
