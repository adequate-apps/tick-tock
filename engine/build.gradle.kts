// SPDX-License-Identifier: GPL-3.0-or-later
//
// :engine owns the audio path: capture and analysis threads in Kotlin, the
// render callback, mixer, voices and beat generator in C++. In M0 the native
// target is an empty library so the NDK, CMake and ABI settings are proven
// before any DSP lands.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ticktock.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Phones and the x86_64 emulator; nothing else is tested.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { jniLibs { useLegacyPackaging = false } }

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

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}

/**
 * Robolectric runs fail closed: the android-all jar must already sit in
 * tools/robolectric-deps (see its MANIFEST.sha256), so a test can never fetch
 * a runtime from the network on its own.
 */
fun robolectricOffline(root: Project): Map<String, String> = mapOf(
    "robolectric.enabledSdks" to "36",
    "robolectric.offline" to "true",
    "robolectric.dependency.dir" to root.file("tools/robolectric-deps").absolutePath,
)
