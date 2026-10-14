// SPDX-License-Identifier: GPL-3.0-or-later
//
// :core is a plain JVM module. It holds the detector, the schemas and the frozen
// contracts, and it compiles with no Android SDK on the classpath, so an
// android.* import is a compile error here and ModuleWallTest explains why.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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
    implementation(libs.coroutines.core)

    // The fakes ship as test fixtures so :engine, :data and :app tests can build
    // against them while the real implementations are in flight.
    testFixturesImplementation(libs.coroutines.core)
    // The KnownGap rule is a fixture every module's tests apply, so JUnit is
    // part of the fixtures' API.
    testFixturesApi(libs.junit)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.test {
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
