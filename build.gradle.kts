// SPDX-License-Identifier: GPL-3.0-or-later
//
// The root pins plugins and transitive metadata selection to catalog versions.
// Each module applies the plugins it needs.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Activity requests this legacy module before graph conflict resolution finishes.
// Select the approved version before Gradle looks up repository metadata.
val approvedLifecycleVersion = libs.versions.lifecycle.get()
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.lifecycle" &&
                requested.name == "lifecycle-viewmodel-savedstate"
            ) {
                useVersion(approvedLifecycleVersion)
                because("Select approved Lifecycle metadata before repository lookup")
            }
        }
    }
}
