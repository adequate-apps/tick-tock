# Store preparation

Draft for owner review. No Play declaration or account change has been submitted.

## Listing copy

Name: Adequate Metronome

Short description: Tuner, drone, metronome and intonation history for wind instruments.

Full description:

Tune with written or concert note names, an adjustable A4 reference and a pitch trace. Capture
per-note readings, compare session and history distributions, and save practice labels. Share
statistics as CSV through Android's share sheet.

Play reference tones or a sustained drone. Set tempo, metre, accents, subdivisions and gap bars
on the metronome. Adjust the visual timing offset for a Bluetooth output.

Choose Gridline, Daylight, Dark or Pit themes and optional settled-note announcements. Import and
export personal settings. Audio is processed on the phone; microphone access is optional for the
drone and metronome. Listening and playback stop when the app moves to the background.

Requires Android 9 or later on a supported 64-bit device. Review screenshots and the privacy policy
before installing. The repository's screenshot examples contain demonstration practice data.

## Data safety draft

The owner's final answers must match the submitted build and current form. The following assessment
is based on the code, exact manifest allowlist and dependency inventory.

| Topic | Proposed declaration and evidence |
|---|---|
| Collection | No data collected off-device by the app or developer. Microphone processing stays in memory; readings/settings stay in private storage. The app has no network permission, analytics or advertising SDK. |
| Sharing | No developer-directed sharing. CSV, settings and crash-report exports occur only through an explicit user action and selected destination. Review these under the user-initiated-transfer exception. |
| Audio | Raw microphone audio remains in working memory. The app stores pitch readings and derived statistics. |
| Retention | Raw readings last seven days; aggregates last until cleared. Uninstall removes private data. Exported documents remain with their chosen destination. |
| Deletion | Settings clears practice history. Users control exported documents through their storage provider. There are no app accounts to delete. |
| Security | Android private app storage, sandboxing and disabled app backup. No app-controlled network transfer or account credentials. |

VERIFIED: Google's definitions exclude data processed exclusively on-device from collection and
exclude expected user-initiated transfers from sharing. LIKELY: these exclusions support the proposed
answers for this build. The owner reviews the actual declarations before submitting them.
[Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)

Privacy URL: `https://github.com/adequate-apps/tick-tock/blob/main/docs/privacy.md`, also used by
Settings > About. The policy names AdequateApps, describes processing, retention, deletion and export,
and links the project's public issue tracker for privacy inquiries. Confirm this inquiry mechanism
and the publicly reachable policy before store submission. A private support channel requires an
owner-provided address; no address is invented by the build.
[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)

## Closed test and verification

The adopted release plan uses twelve testers continuously opted in for fourteen days before
production. Start the calendar only when eligible testers are actually enrolled. Retain enrollment
and device-test evidence, gather feedback, resolve failures, then request production access.
UNKNOWN: the owner's account type and creation date. Google's specific twelve-tester requirement
applies to personal accounts created after 2023-11-13; the owner checks the account's actual requirements.
[Closed-test requirements](https://support.google.com/googleplay/android-developer/answer/14151465)

The intended audience is ages 13 and up. The owner confirms the audience, content rating, pricing,
countries and declarations in Play Console. There is no background media service in 2.0; a future
background-playback version requires its own service declaration and evidence.

Developer verification enforcement started on 2026-09-30 in Brazil, Indonesia, Singapore and
Thailand, with broader rollout beginning in 2027. The owner completes the applicable identity and
package registration for direct APK distribution and checks current country coverage.
[Android developer verification](https://developer.android.com/developer-verification)

App signing, key enrollment, encrypted backups and publication approvals are described in
[the release runbook](release.md). Play-delivered APKs and the direct universal APK must have the
same app-signing certificate; a separate upload certificate is only for AAB submission.
