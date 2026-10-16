# Release runbook

Status: release tooling is prepared; production signing custody, device acceptance, account actions
and public publication require owner approval. The acceptance ledger records the remaining criteria.
Preparing these files creates no tag, release or Play upload.

## Signing identity

The permanent application id is `com.adequateapps.metronome`. Debug installs use `.debug` and can
coexist with the release. The release targets Android 9 or later and the arm64-v8a and x86_64 ABIs.

The owner generates the app-signing key offline, keeps custody and enrolls that existing key in
Play App Signing. GitHub's universal APK uses the same app-signing certificate. A separate upload
key signs the AAB submitted to Play; Play signs delivered APKs with the enrolled app-signing key.
Record both public certificate SHA256 fingerprints and verify them independently before use.
See [Android app signing](https://developer.android.com/studio/publish/app-signing).

Use a PKCS12 store and an RSA key. The proposed production parameters are RSA 4096,
SHA256withRSA and 10,000 days of validity. Choose passwords privately. Keep two encrypted backups
in owner-controlled locations and prove restoration by matching the certificate fingerprint.
Record custody and backup locations outside this repository. A lost upload key can be reset through
Play; loss of the app-signing key affects the direct APK channel's update continuity.

Local configuration is the ignored `private/signing.properties`, readable only by its owner. Java
properties escapes apply to backslashes, whitespace and special characters. Fields are `storeFile`,
`storePassword`, `keyAlias`, `keyPassword` and `certificateSha256`. Relative store paths are resolved
against the properties file. Optional separate-upload fields use the `upload` prefix and capitalized
field name, such as `uploadStoreFile`. Supplying any upload field requires all five.

`-Pticktock.signingProperties=PATH` selects another private configuration. Never put passwords on
command lines, in repository files or in logs. Both `assembleRelease` and `bundleRelease` refuse
absent, partial, invalid or certificate-mismatched signing material. Debug builds require no release key.

## Build and verify

Use the pinned SDK, NDK, CMake and verified dependencies already staged by the approved build.
Set `JAVA_HOME` to JDK 21, `ANDROID_HOME` to the SDK, and the private identity-scan environment as
described in the contributor setup. No build may download a JDK or bypass strict artifact verification.

```sh
./gradlew --offline --no-configuration-cache :app:releaseArtifacts
tools/reproduce-release.sh HEAD private/signing.properties
```

The artifact task depends on both release builds in one invocation and creates exactly three files
under `build/release`: `adequate-metronome-vVERSION-universal.apk`,
`adequate-metronome-vVERSION.aab`, and `SHA256SUMS`. It checks both expected certificates, every signed
AAB payload, the release package/version, absence of debuggability, exact permission allowlist,
complete ABIs, every native library's 16 KB ELF alignment, APK ZIP alignment and checksums. The AAB
also passes the already pinned bundle validator. Upload signing preserves every payload entry.

The reproduction script makes two fresh clones at the same commit with different paths, disables
the task-output cache and builds both artifacts independently. It requires byte equality of the
complete signed APK and AAB. Shared dependency caches contain only hash-verified inputs. Compare
like toolchains and signing keys; local repeatability does not establish equality across different
JDK or SDK versions. Logs and source clones remain under ignored `build/reproduction`.

Use `tools/test-signing-gate.sh`, `tools/test-release.sh`, `tools/test-stage-signing.py` and the artifact
mutation checks described in `tools/README.md` for temporary-key validation. These two-day fixture
keys must never sign a public release. Production material is unnecessary for development tests.

## Tag workflow

1. Resolve the release-blocking ledger rows, complete physical acceptance, obtain both required
   reviews and pass the full local gate, safeguard selftests and history scan. Merge normally.
2. Require both main CI jobs green on the exact release commit. Main CI validates temporary signed
   artifacts and stages approved Gradle dependencies into the pinned setup action's cache.
3. Confirm the application version, version code, curated `CHANGELOG.md` section and production
   fingerprints. Approve the public tag and release assets separately from normal repository pushes.
4. With owner approval, stage the CI secrets below. Create and push the matching `vMAJOR.MINOR.PATCH`
   tag on the reviewed main commit. The workflow verifies main ancestry, successful CI, a clean
   checkout, version/tag agreement, identity rules and the Gradle wrapper hash.
5. The release job restores the approved dependency cache and fails closed on a miss. It stages the
   same approved SDK packages as main CI, then runs Gradle offline. A missing verified dependency
   fails the job. Re-run approved main CI to stage inputs; never disable verification or add an
   online fallback to the release job.
6. The workflow stages keys outside the checkout with private file permissions, builds from two
   clean clones and compares both artifacts. It publishes the first clone's APK, AAB and SHA256SUMS
   with the curated notes only after verification. Temporary signing files are cleared even on failure.
7. Download the published assets, check SHA256SUMS and inspect the APK certificate against the
   recorded app certificate. Upload the verified AAB to the approved Play track through the owner.

CI secrets: `RELEASE_STORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_CERTIFICATE_SHA256`; optional separate upload credentials use
`RELEASE_UPLOAD_` with the same suffixes. Base64 only transports the encrypted PKCS12 bytes.
The existing `TICKTOCK_DENYLIST` secret is also required. Account and secret changes belong to the owner.
Only the release job receives `contents: write`; the check workflow remains read-only. Cache restoration
is enabled on main and tag builds. Actions stay pinned to full commit SHAs.

## Rollback and recovery

Before publication, discard the candidate by leaving its tag unpublished and retain the prior release.
Never replace a published tag or silently overwrite its assets. After publication, stop a Play rollout
through the owner and prepare a reviewed correction with a higher version code. Android generally
refuses a version-code downgrade; a previous APK is useful for clean-install testing, and may require
removing application data. Export needed practice data before an owner-directed reinstall.

A signature mismatch is a stop condition. Compare the public certificates and restore the intended
key from its tested backup. Do not create a replacement app identity to make a failing build pass.
If a secret may have escaped, the owner revokes access and uses the store's supported key recovery
procedure. Keep passwords and private key bytes out of issue reports.

## Store and device acceptance

See [store preparation](store.md), [privacy policy](privacy.md), and [acceptance ledger](ACCEPTANCE.md).
The owner records actual device, Android version and candidate hash with each physical result.
Pending TalkBack speech, audio timing, route/unplug and microphone behavior cannot be established by
screenshots or emulator assertions. Open software criteria also remain release blockers until resolved
or explicitly revised in the specification. The production date follows acceptance and the closed-test
calendar; the completion of release tooling starts neither enrollment nor approval.
