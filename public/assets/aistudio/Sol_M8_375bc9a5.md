# Sol — Milestone M8: Tests, CI, documentation, repo hygiene

This message is SELF-CONTAINED. Ignore any earlier long prompt you may have lost or summarized; everything you need for M8 is below.

You are the lead Android engineer for **Sol**, an offline-first Persian (fa-IR, RTL) voice and life assistant (Kotlin, Jetpack Compose, Room, DataStore, WorkManager, Vosk, LiteRT-LM, PDFBox-Android, Jsoup). Milestones before M8 are considered DONE; do not redo them, but do not break them. Re-read the current code before editing.

## 0. HARD RULES (never violate)

1. Do NOT modify or remove `signingConfigs`, the `signingConfig` assignments, or `applicationId`
   (`com.aistudio.solassistant.kpvmqr`) in `app/build.gradle.kts`. AI Studio needs them to install/update.
2. Do NOT downgrade AGP / Kotlin / Gradle. Dependency bumps only if the build stays green.
3. Keep every existing Persian user-facing text and behaviour unless a task says otherwise.
4. Honesty principle: never fake AI output; Sol always identifies itself in outgoing messages; calls and SMS are
   NEVER started without an explicit user confirmation event; UI claims (privacy, capabilities) must be literally true.
5. Everything runs ON THE PHONE. No home PC, no server, no cloud AI, no analytics/ads/tracking SDKs.
   The only allowed network use is the explicit, user-confirmed download of books/web pages and of model files.
6. Prefer pure Kotlin. Do not add heavy native libraries. If you believe a new dependency is unavoidable, justify it in writing
   with its APK-size and RAM impact before adding it.
7. NEVER fabricate checksums, URLs, version numbers or accuracy numbers. Unknown value => `TODO(verify)` constant + code that fails safe
   (refuses to use the file) instead of skipping the check. Any accuracy/latency figure you report must come from a test or benchmark you actually ran.
8. No new runtime permissions without a one-line justification. Never log personal data (names, phone numbers, message bodies, memory text).
9. Edit `metadata.json` only in its `description` text.

## 1. TARGET DEVICE (design for the weakest realistic user)

Samsung Galaxy A12 (SM-A125F), One UI Core 4.1, **Android 12 / API 31**, 4 GB RAM (about 2 GB already used by the system),
64 GB storage with only ~10 GB free, low-end CPU/GPU, 720x1600 screen, aggressive Samsung battery management. The owner has no other computer.

Platform facts the code MUST respect:
- Starting a foreground service from the background throws `ForegroundServiceStartNotAllowedException` (API 31+).
- Microphone capture is blocked for apps that are not visible and have no foreground service.
- `SCHEDULE_EXACT_ALARM` is granted by default on API 31 but can be revoked; **all alarms are wiped on reboot**.
- No runtime `POST_NOTIFICATIONS` below API 33. Apps cannot toggle Wi-Fi/Bluetooth/mobile data programmatically on modern Android (only open settings).
- Free RAM ~2 GB, free disk ~10 GB: no giant in-memory copies, no multi-GB models.

Performance budgets to design for and then MEASURE (report real numbers; mark unmeasured ones "not measured"):
- Typed command -> result (rule/classifier path): p95 under 150 ms.
- End of speech -> action ready with Vosk: under ~1.5 s.
- App idle PSS: as low as possible; target under ~250 MB with Vosk model unloaded; Vosk loaded + SLM unloaded under ~450 MB.
- No frame jank (>32 ms) during scrolling of Chat/Memory lists at 60 Hz. No disk, network or heavy CPU work on the main thread (enable `StrictMode` in debug).

## TASKS FOR M8 (implement ALL of them, and ONLY these)

## PART E — TESTING

Add (current state: ~10 unit + 6 Robolectric tests, one trivial instrumented test):
- Part B3 harness and golden files (mandatory CI gate).
- `PersianNormalizer`/`PersianNumbers`: table-driven.
- `JalaliCalendar`: leap years 1399, 1403, 1408 leap and 1404 not; Esfand 29/30 boundaries; month lengths; round-trip conversion over a wide range.
- Dialogue manager: missing slot, cancel, ambiguity choice, confirmation required for every sensitive action, "no dialer/SMS intent without confirmation".
- Skills: each skill has at least one success and one failure test, including permission-missing paths.
- `ExactAlarmScheduler` + `BootReceiver` with `ShadowAlarmManager`; request-code uniqueness.
- Workers with `TestListenableWorkerBuilder`.
- Room migrations; FTS retrieval; large (multi-MB) backup round-trip; wrong password; tampered file; truncated file; atomic restore (failure leaves data untouched).
- Download logic with a fake HTTP server: resume (`Range`), wrong hash, non-https redirect, HTML instead of binary, host not in allow-list.
- SLM safety tests (B10) and a test that the SLM stays honest when no model file exists (already present; keep it).
- Compose UI tests: confirmation dialog, settings reliability screen, clarification buttons.
- JaCoCo coverage printed in CI (no hard threshold at first).

### F1. CI (`.github/workflows/build-android-apk.yml`)
- **Signature bug:** CI creates a brand-new `debug.keystore` on every run (`debug.keystore.base64` is not in the repo), so each CI debug APK has a different signature and cannot update the previous install; the owner must uninstall and loses data.
  Restore the debug keystore from a repository secret (`DEBUG_KEYSTORE_BASE64`) or sign consistently with the release key.
- Add `concurrency` (cancel in progress), `timeout-minutes`, Gradle cache, upload test/lint/coverage reports as artifacts.
- Add `lint` (fail on new errors), `ktlint` or `detekt`, `.github/dependabot.yml` (gradle + github-actions).
- Derive APK name/version from `versionName` (hard-coded `Sol-v2.0.0` in two places today) and publish `SHA256SUMS` with each release.
- Commit the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`); today only `gradle-wrapper.properties` exists. Pin third-party actions to commit SHAs and verify that every referenced action major version exists.
- Run the evaluation harness in CI and fail on regression.

### F2. Reliability screen in Settings (One UI Core)
- Show and explain, with deep links and NO new permissions: battery-optimization state (`PowerManager.isIgnoringBatteryOptimizations`, `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; do NOT request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), exact-alarm access (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`), notification state.
- Persian step-by-step for Samsung: Settings -> Battery and device care -> Battery -> Background usage limits -> add Sol to "Never sleeping apps".
- "Last reminder delivered at ..." diagnostic and a one-tap self-test (schedule a reminder 1 minute ahead).
- Show RAM/disk status and the recommended model for the device.

### F3. Documentation and hygiene
- Delete or archive `public/assets/aistudio/` (old 1.0.0 copy: "no internet permission", AGP 8.9.2, compileSdk 35 — contradicts the app).
- Real `README.md` (Persian first, English second): what Sol is, supported commands with examples, permissions and why each is needed, what works offline, what may use the network, build steps, model/Vosk download notes, backup format, known limitations for 4 GB phones.
- Add `PRIVACY.md` (data inventory), `SECURITY.md`, `CHANGELOG.md`, `ARCHITECTURE.md` (pipeline diagram from Part B + data flow), `CONTRIBUTING.md` (how to add a skill and golden examples).
- `LICENSE`: add only a placeholder asking the owner to choose; do not pick a license.
- Fix `metadata.json` `description` and `.env.example` so they do not imply a Gemini/server-side feature the code does not use.
- Before any Play Store release (NOT in this task): change namespace/applicationId away from `com.example` / the AI Studio name, and prepare Play Console declarations (foreground service types, exact alarms).

## OUT OF SCOPE
- Cloud or LAN/PC-hosted models; Whisper or other large models; background GPS tracking; auto-sending SMS/calls; scraping; analytics.
- Rewriting the whole UI design. Improve behaviour, performance and accessibility only.

## FINAL ACCEPTANCE CHECKLIST (report each as pass/fail with evidence)
[ ] `:app:testDebugUnitTest` green; `:app:assembleDebug` and `:app:assembleRelease` green; release APK smoke-tested.
[ ] No destructive migration in release; migration tests pass.
[ ] Reminder survives reboot and exact-alarm revocation (manual test steps documented).
[ ] No foreground-service crash from background on Android 12; no `runBlocking` on the main thread.
[ ] Model download verified by SHA-256 (or refused when hash is `TODO(verify)`); resume works.
[ ] Backup V3 round-trip, wrong password, tampered file, atomic restore all tested.
[ ] Evaluation harness: per-intent metrics and baseline recorded; the `وب`/`خوب`, `برو`/`بروم`, `زنگ تفریح` regressions covered.
[ ] Calls/SMS cannot start without confirmation; SLM/book/web text cannot trigger actions (tests).
[ ] Privacy and capabilities texts are literally true (generated from the registry).
[ ] Measured: command latency, idle PSS, Vosk-loaded PSS (or "not measured" with reason).

## PROTOCOL FOR THIS REPLY
1. Implement only milestone M8. Keep `:app:testDebugUnitTest` and `:app:assembleDebug` green and also verify `:app:assembleRelease`. If the build breaks, fix it BEFORE reporting.
2. When finished print: (a) a table: task id -> done / partial / skipped(reason) -> files changed; (b) every `TODO(verify)`; (c) the commands you ran and their real results; (d) manual test steps on a 4 GB Android 12 phone.
3. Then STOP and wait for the user to send the next milestone. Do not start any other milestone.
4. Never invent checksums, URLs, versions or accuracy numbers (hard rule 7).
