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

// Activity requests legacy Lifecycle modules before graph conflict resolution finishes.
// Align the family before older metadata can introduce unapproved transitive requests.
val approvedLifecycleVersion = libs.versions.lifecycle.get()
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.lifecycle") {
                useVersion(approvedLifecycleVersion)
                because("Select approved Lifecycle metadata before repository lookup")
            }
        }
    }
}
