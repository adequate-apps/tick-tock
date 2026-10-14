// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every artifact comes from these two repositories and nowhere else, so the
    // verification metadata describes the whole supply chain of the build.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ticktock"

include(":core")
include(":engine")
include(":data")
include(":design")
include(":app")
