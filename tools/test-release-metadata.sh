#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Exercise Activity's legacy family without a higher graph version hiding it.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
mkdir -p build/release-metadata
cat > build/release-metadata/check.gradle <<'GRADLE'
import org.gradle.api.artifacts.VersionCatalogsExtension

gradle.projectsEvaluated {
    def expected = rootProject.extensions.getByType(VersionCatalogsExtension)
        .named('libs').findVersion('lifecycle').get().requiredVersion
    def legacyModules = [
        'lifecycle-common', 'lifecycle-runtime', 'lifecycle-runtime-ktx',
        'lifecycle-viewmodel', 'lifecycle-viewmodel-ktx', 'lifecycle-viewmodel-savedstate'
    ]
    def probes = [:]
    [':design', ':app'].each { name ->
        def project = rootProject.project(name)
        def reference = project.configurations.releaseRuntimeClasspath
        legacyModules.eachWithIndex { module, index ->
            def probe = project.configurations.create("releaseMetadataProbe$index") {
                canBeConsumed = false
                canBeResolved = true
                transitive = false
            }
            reference.attributes.keySet().each { key ->
                probe.attributes.attribute(key, reference.attributes.getAttribute(key))
            }
            project.dependencies.add(probe.name, "androidx.lifecycle:$module:2.6.1")
            probes["$name $module"] = probe
        }
    }
    rootProject.tasks.register('checkReleaseMetadataSelection') {
        doLast {
            probes.each { name, probe ->
                def artifacts = probe.resolvedConfiguration.resolvedArtifacts
                def selected = artifacts.findAll {
                    it.moduleVersion.id.group == 'androidx.lifecycle'
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
