// SPDX-License-Identifier: GPL-3.0-or-later
//
// :core-testing holds the fakes for the frozen contracts and the KnownGap rule.
// Every module's tests build against it while the real implementations are in
// flight. It replaces :core's java-test-fixtures, which a multiplatform module
// cannot carry.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.core)
    // The KnownGap rule is part of this module's API, so JUnit is too.
    api(libs.junit)
}
