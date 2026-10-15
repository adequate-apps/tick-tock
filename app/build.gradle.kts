// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Private keys stay outside version control and are loaded by release tasks.
val signingPropertiesFile = providers.gradleProperty("ticktock.signingProperties")
    .map { rootProject.file(it) }.getOrElse(rootProject.file("private/signing.properties"))
val releaseSigning = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.reader(Charsets.UTF_8).use { load(it) }
}
val signingFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "certificateSha256")
fun signingField(name: String, prefix: String = ""): String =
    if (prefix.isEmpty()) name else prefix + name.replaceFirstChar { it.uppercase() }
fun signingComplete(prefix: String = "") = signingFields.all {
    !releaseSigning.getProperty(signingField(it, prefix)).isNullOrBlank()
}
fun signingStore(prefix: String = ""): File {
    val path = File(releaseSigning.getProperty(signingField("storeFile", prefix)))
    return if (path.isAbsolute) path else File(signingPropertiesFile.parentFile, path.path)
}
class ReleaseKey(val key: PrivateKey, val certificate: X509Certificate)
fun readReleaseKey(prefix: String = ""): ReleaseKey {
    if (!signingComplete(prefix)) throw GradleException("Release signing is required: provide all signing properties.")
    try {
        fun field(name: String) = releaseSigning.getProperty(signingField(name, prefix))
        val store = KeyStore.getInstance("PKCS12")
        signingStore(prefix).inputStream().use { store.load(it, field("storePassword").toCharArray()) }
        val key = store.getKey(field("keyAlias"), field("keyPassword").toCharArray()) as PrivateKey
        val certificate = store.getCertificate(field("keyAlias")) as X509Certificate
        certificate.checkValidity()
        require(key.algorithm == "RSA") { "release signing requires an RSA key" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = field("certificateSha256").filterNot { it == ':' || it.isWhitespace() }.lowercase()
        if (fingerprint != expected) throw GradleException("Release signing certificate differs from the expected fingerprint.")
        return ReleaseKey(key, certificate)
    } catch (failure: GradleException) {
        throw failure
    } catch (failure: Exception) {
        throw GradleException("Release signing material is invalid or unavailable.")
    }
}

/**
 * The published application id (decision 17). It is permanent once on a store.
 * checkReleaseApplicationId below still fails any release build whose id starts
 * with the com.example placeholder namespace; CI proves that by overriding the
 * id with the ticktock.applicationIdOverride property, which nothing else sets.
 */
val placeholderPrefix = "com.example."
val releaseApplicationId = providers.gradleProperty("ticktock.applicationIdOverride")
    .getOrElse("com.adequateapps.metronome")

android {
    namespace = "ticktock.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = releaseApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
            if (signingComplete()) {
                signingConfigs.create("upload") {
                    storeFile = signingStore()
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

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Requires valid signing material and its expected public certificate."
    dependsOn(checkReleaseApplicationId)
    doLast {
        readReleaseKey()
        if (signingFields.any { releaseSigning.containsKey(signingField(it, "upload")) }) readReleaseKey("upload")
    }
}

// Both release entry points require the identity and signing checks.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
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

    testImplementation(project(":core-testing"))
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(project(":core-testing"))
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

// A failing case prints its assertion in the console, which is all CI keeps.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Bundle the repository's notices so they remain readable without a browser.
val prepareNotices by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))
    val fontLicence = rootProject.file("tools/fonts/IBM-Plex-OFL-1.1.txt")
    inputs.file(fontLicence)
    into(layout.buildDirectory.dir("generated/notices"))
    doLast {
        layout.buildDirectory.file("generated/notices/THIRD_PARTY.md").get().asFile.writeText(
            rootProject.file("THIRD_PARTY.md").readText() + "\n\n## Bundled font licence\n\n" + fontLicence.readText() + "\n")
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices").get().asFile)
tasks.named("preBuild") { dependsOn(prepareNotices) }
