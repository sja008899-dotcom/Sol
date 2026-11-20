# Sol — Milestone M7: Performance, memory, release build

This message is SELF-CONTAINED. Ignore any earlier long prompt you may have lost or summarized; everything you need for M7 is below.

You are the lead Android engineer for **Sol**, an offline-first Persian (fa-IR, RTL) voice and life assistant (Kotlin, Jetpack Compose, Room, DataStore, WorkManager, Vosk, LiteRT-LM, PDFBox-Android, Jsoup). Milestones before M7 are considered DONE; do not redo them, but do not break them. Re-read the current code before editing.

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

## TASKS FOR M7 (implement ALL of them, and ONLY these)

## PART D — PERFORMANCE, MEMORY, RELEASE QUALITY

1. RAG (`knowledge/BookRagEngine.kt`): stop caching the ENTIRE `book_chunks` table (`getAllChunksOnce()`). Use a Room FTS4/FTS5 table (or an inverted index) and fetch only top-K chunks per query; keep BM25-style ranking if feasible. Cap imported book size and chunk count with a user-visible message.
2. `LearningEngine`: cache the alias map in memory, invalidate via the Room Flow; no full-table load per command.
3. `SolViewModel` (~1000 lines): split into feature ViewModels (chat, memory, contacts, settings, backup, SLM); reduce the 10+ eagerly collected `stateIn` flows to what each screen needs; stable keys in every `LazyColumn`; `remember`/`derivedStateOf` for derived UI state; no heavy work in composition.
4. `LocalSlmEngine` (~950 lines): split into `ModelCatalog`, `ModelDownloader`, `ModelStore`, `InferenceEngine`.
5. Release build: `isMinifyEnabled = true`, `isShrinkResources = true`; keep rules for Room, Vosk/JNA, LiteRT-LM (JNI), PDFBox-Android, Jsoup. **Smoke-test the release APK** (start, voice, backup export/import, model load, reminder) because JNI libraries break easily under R8.
6. Baseline Profile + `androidx.profileinstaller` (low-end CPU benefits most). AndroidX SplashScreen for a clean Android 12 launch.
7. Convert `ic_sol_emblem_v2_*.jpg` and `img_sol_hero_banner_*.jpg` to WebP at sensible sizes (<= ~1080 px wide, correct density bucket or `drawable-nodpi`) to avoid large bitmaps. Check the real file sizes first.
8. Debug-only tooling: `StrictMode` (thread + VM policy, `penaltyLog`), and optionally LeakCanary as `debugImplementation`.
9. Optional dependency refresh (only if the build stays green): Compose BOM (currently 2024.09.00), lifecycle, navigation, Room. Remove or finish the commented-out unused dependencies and dead catalog entries.
10. Accessibility and UX on a 720x1600 phone: `contentDescription` on icon buttons, 48 dp touch targets, correct RTL, usable at font scale 1.3, TalkBack labels for the confirmation dialog. Move hard-coded Persian strings out of Compose into `strings.xml` (the UI uses `stringResource` nowhere) without changing visible text.

## PROTOCOL FOR THIS REPLY
1. Implement only milestone M7. Keep `:app:testDebugUnitTest` and `:app:assembleDebug` green and also verify `:app:assembleRelease`. If the build breaks, fix it BEFORE reporting.
2. When finished print: (a) a table: task id -> done / partial / skipped(reason) -> files changed; (b) every `TODO(verify)`; (c) the commands you ran and their real results; (d) manual test steps on a 4 GB Android 12 phone.
3. Then STOP and wait for the user to send the next milestone. Do not start any other milestone.
4. Never invent checksums, URLs, versions or accuracy numbers (hard rule 7).
