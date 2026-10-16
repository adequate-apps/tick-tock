#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Exercise Activity's legacy request without a higher graph version hiding it.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
mkdir -p build/release-metadata
cat > build/release-metadata/check.gradle <<'GRADLE'
import org.gradle.api.artifacts.VersionCatalogsExtension

gradle.projectsEvaluated {
    def expected = rootProject.extensions.getByType(VersionCatalogsExtension)
        .named('libs').findVersion('lifecycle').get().requiredVersion
    def probes = [':design', ':app'].collectEntries { name ->
        def project = rootProject.project(name)
        def reference = project.configurations.releaseRuntimeClasspath
        def probe = project.configurations.create('releaseMetadataProbe') {
            canBeConsumed = false
            canBeResolved = true
            transitive = false
        }
        reference.attributes.keySet().each { key ->
            probe.attributes.attribute(key, reference.attributes.getAttribute(key))
        }
        project.dependencies.add(probe.name, 'androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1')
        [(name): probe]
    }
    rootProject.tasks.register('checkReleaseMetadataSelection') {
        doLast {
            probes.each { name, probe ->
                def artifacts = probe.resolvedConfiguration.resolvedArtifacts
                def selected = artifacts.findAll {
                    it.moduleVersion.id.group == 'androidx.lifecycle' &&
                    it.moduleVersion.id.name.startsWith('lifecycle-viewmodel-savedstate')
                }
                if (selected.empty || selected.any { it.moduleVersion.id.version != expected }) {
                    throw new GradleException("$name legacy request must select approved Lifecycle $expected")
                }
                println "PASS: $name legacy metadata request resolves approved Lifecycle $expected offline"
            }
        }
    }
}
GRADLE
./gradlew --offline --no-configuration-cache --init-script build/release-metadata/check.gradle checkReleaseMetadataSelection
