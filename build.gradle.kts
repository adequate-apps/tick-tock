// SPDX-License-Identifier: GPL-3.0-or-later
//
// The root script declares plugin versions only. Each module applies what it
// needs, and every version lives in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}
