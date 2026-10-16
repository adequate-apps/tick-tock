// SPDX-License-Identifier: GPL-3.0-or-later
//
// :design is Gridline in Compose Foundation: tokens, type, relief and the
// components every screen is built from. It depends on Compose and nothing
// from the rest of the app, so screenshot tests can render it in isolation.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "ticktock.design"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

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
    // Release lint resolves this module separately from the app's runtime graph.
    constraints {
        implementation("androidx.activity:activity:${libs.versions.activityCompose.get()}")
        implementation("androidx.activity:activity-ktx:${libs.versions.activityCompose.get()}")
    }
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
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
