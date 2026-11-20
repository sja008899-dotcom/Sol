# Sol — Milestone M4: Statistical intent layer, slot filling, dialogue manager

This message is SELF-CONTAINED. Ignore any earlier long prompt you may have lost or summarized; everything you need for M4 is below.

You are the lead Android engineer for **Sol**, an offline-first Persian (fa-IR, RTL) voice and life assistant (Kotlin, Jetpack Compose, Room, DataStore, WorkManager, Vosk, LiteRT-LM, PDFBox-Android, Jsoup). Milestones before M4 are considered DONE; do not redo them, but do not break them. Re-read the current code before editing.

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

## BACKGROUND CONTEXT FOR THIS MILESTONE
### Current state (verified by reading the code)
- `brain/IntentParser.parse()` is a ~500-line chain of `if` checks using substring tests such as `norm.contains("وب")`, `norm.contains("برو")`. These match inside other words
  (`وب` inside `خوب`/`خوبی`, `برو` inside `بروم`), causing wrong routing. There are no word boundaries and no scoring.
- `SolAction` has 27 values; `ParsedCommand` is a flat data class with ~25 nullable fields; `CommandBrain.process()` is one big `when`.
- `PersianNormalizer.normalize/cleanText` build new `Regex` objects on every call, do not remove diacritics (harakat U+064B-U+0652), tatweel (U+0640), or normalize `ۀ`, Persian/Arabic punctuation.
- `LearningEngine.resolveAliasesInText` calls `memoryRepository.getAllOnce()` (loads the whole table) on every unknown command and uses naive `String.replace`.
- `SolCapabilitiesCatalog` is a hand-written list of 20 items; UI text says "20 capabilities" and a test hard-codes the count, so it can drift from reality.
- `DeviceController` has no flashlight, timer, calendar, calculator or notes skills.

### Target pipeline (every stage deterministic, testable, offline)

```
speech/text -> A.Normalize -> B.Rules(matchers) -> C.Statistical intent (kNN over examples)
            -> D.Slot filling -> E.Dialogue manager (clarify/confirm/undo)
            -> Skill.execute()      (low confidence & user enabled) -> F.SLM fallback (constrained, optional)
```

## TASKS FOR M4 (implement ALL of them, and ONLY these)

### B4. Statistical intent layer (pure Kotlin, no new dependency)
- Data: `assets/nlu/intents_fa.jsonl` (same schema as the golden file, DIFFERENT utterances; never train on the test split).
- Model: char n-gram (2-4) TF-IDF vectors per example + cosine similarity, k-nearest-neighbour vote with per-intent centroid fallback. Build lazily on first use on a background dispatcher (target well under 200 ms for a few thousand examples), keep memory small (sparse maps, no giant dense arrays).
- Confidence = top score and margin over the runner-up. Initial thresholds (CONFIGURABLE, tune with the harness, then record them in `NluConfig`):
  HIGH: score >= 0.75 and margin >= 0.15 -> proceed;
  MEDIUM: score 0.50-0.75 or small margin -> ask a one-line clarification offering the top 2 intents as buttons;
  LOW: below 0.50 -> fallback (F) if enabled and available, otherwise a helpful "I did not understand" message with 3 example commands.
- Rule layer and statistical layer are combined by an explicit, tested policy (e.g. exact rule hit with high score wins; otherwise statistical).
- User teaching: new Room entity `UserUtteranceExample(intent, text, slotsJson, createdAt)`. When the user corrects Sol ("نه، منظورم ... بود" or taps a suggested intent) store the utterance as a new example. Provide "forget this example" in the Memory screen.
- Report measured accuracy (dev and test) in the milestone summary.

### B5. Slot filling (`brain/EntityExtractor.kt` + new)
- Time and date: reuse `calendar/JalaliCalendar`, add relative expressions ("۱۰ دقیقه دیگه"، "نیم ساعت دیگه"، "فردا ساعت ۸ صبح"، "پس فردا"، "آخر هفته"، "شنبه آینده"). All in Asia/Tehran semantics via device timezone; never hard-code offsets.
- Contacts: fuzzy matching with Persian normalization (normalized exact -> prefix -> token overlap -> edit distance <= 1 for names of >= 4 letters), nickname/relation matching ("مامان"، "همسرم"), ambiguous matches produce a numbered choice (voice and touch), never a silent guess.
- Apps and settings: same fuzzy matcher over `getLaunchableApps()` with a cached, invalidated list (not re-queried on every command).
- Phone numbers: keep the current regex, but validate and format (E.164 for Iran mobile) and reject obviously invalid ones.

### B6. Dialogue manager (new `dialogue/` package)
- Short-term context (last ~5 turns): pronouns and ellipsis ("به او هم بگو"، "همین را فردا هم یادآوری کن").
- Missing slot -> ask ONE targeted question ("به چه کسی؟"), keep the pending intent, allow "نه / بیخیال / لغو" at any time and "دوباره بگو / چی گفتی؟".
- Sensitive actions (CALL, SEND_SMS, MULTI_STEP_NOTIFY, delete/forget, restore backup) ALWAYS require the existing confirmation flow, spoken or touched, with large buttons (small 720x1600 screen). Confirmation vocabulary: بله، آره، تایید، باشه، نه، نخیر، لغو.
- `requireConfirmation` must be enforced centrally (in the dialogue manager), not per `when` branch in `CommandBrain`.
- Split `CommandBrain.process()` so each `SolAction` is handled by a skill (B7); `CommandBrain` becomes a thin orchestrator.

## PROTOCOL FOR THIS REPLY
1. Implement only milestone M4. Keep `:app:testDebugUnitTest` and `:app:assembleDebug` green. If the build breaks, fix it BEFORE reporting.
2. When finished print: (a) a table: task id -> done / partial / skipped(reason) -> files changed; (b) every `TODO(verify)`; (c) the commands you ran and their real results; (d) manual test steps on a 4 GB Android 12 phone.
3. Then STOP and wait for the user to send the next milestone. Do not start any other milestone.
4. Never invent checksums, URLs, versions or accuracy numbers (hard rule 7).
