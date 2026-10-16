#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Release lint must use the Activity artifacts selected by the app.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
mkdir -p build/release-dependencies
cat > build/release-dependencies/check.gradle <<'GRADLE'
gradle.projectsEvaluated {
    rootProject.tasks.register('checkReleaseDependencyAlignment') {
        doLast {
            def activity = { project ->
                project.configurations.releaseRuntimeClasspath.incoming.resolutionResult.allComponents
                    .findAll { it.moduleVersion?.group == 'androidx.activity' }
                    .collectEntries { [(it.moduleVersion.name): it.moduleVersion.version] }
            }
            def app = activity(rootProject.project(':app'))
            def design = activity(rootProject.project(':design'))
            ['activity', 'activity-ktx'].each { name ->
                if (!app[name] || design[name] != app[name]) {
                    throw new GradleException("Release dependency differs for $name: design=${design[name]}, app=${app[name]}")
                }
                println "PASS: release $name ${app[name]} is aligned"
            }
        }
    }
}
GRADLE
./gradlew --offline --no-configuration-cache --init-script build/release-dependencies/check.gradle checkReleaseDependencyAlignment
