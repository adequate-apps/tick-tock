// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Release signing reads a gitignored file; the keystore never enters the tree.
val releaseSigning = Properties().apply {
    val file = rootProject.file("private/signing.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

/**
 * Deliberate placeholder. Stores reject the com.example namespace, so an
 * unfinished build can never ship by accident; checkReleaseApplicationId
 * below fails any release build while the id still starts with it.
 */
val placeholderPrefix = "com.example."
val placeholderApplicationId = "com.example.ticktock.placeholder"

android {
    namespace = "ticktock.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = placeholderApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "2.0.0-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            if (releaseSigning.isNotEmpty()) {
                signingConfigs.create("upload") {
                    storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                    storePassword = releaseSigning.getProperty("storePassword")
                    keyAlias = releaseSigning.getProperty("keyAlias")
                    keyPassword = releaseSigning.getProperty("keyPassword")
                }
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = false } }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperties(robolectricOffline(rootProject)) }
        }
    }
}

// The screenshot goldens are compared inside the unit tests, so a changed
// golden must re-run them; otherwise verifyRoborazziDebug could stay green
// on a stale result.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/test/screenshots")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// Each release variant's effective application id, as AGP computes it after
// flavors and suffixes. The guard reads these rather than the constant above,
// so a flavor or suffix cannot slip a com.example id past it.
val releaseApplicationIds = objects.mapProperty(String::class.java, String::class.java)
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        releaseApplicationIds.put(variant.name, variant.applicationId)
    }
}

val checkReleaseApplicationId = tasks.register("checkReleaseApplicationId") {
    group = "verification"
    description = "Fails while any release variant's application id is still the com.example placeholder."
    val ids = releaseApplicationIds
    doLast {
        val found = ids.get()
        if (found.isEmpty()) throw GradleException("Release guard found no release variant to check.")
        for ((variant, id) in found) {
            if (id.startsWith(placeholderPrefix)) {
                throw GradleException(
                    "Release builds are blocked: applicationId '$id' of variant '$variant' is a placeholder. " +
                        "Set the owner's real id in app/build.gradle.kts before building a release.",
                )
            }
        }
    }
}

// preReleaseBuild is the first task of every release variant, so the guard
// fires before any compile work and before the signing config is consulted.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseApplicationId)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":design"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestUtil(libs.androidx.test.orchestrator)
}

/** See the note in engine/build.gradle.kts: Robolectric runs offline and fails closed. */
fun robolectricOffline(root: Project): Map<String, String> = mapOf(
    "robolectric.enabledSdks" to "36",
    "robolectric.offline" to "true",
    "robolectric.dependency.dir" to root.file("tools/robolectric-deps").absolutePath,
)
