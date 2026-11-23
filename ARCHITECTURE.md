# Architecture Overview — Sol (سول)

Sol is an offline-first Persian (`fa-IR`, RTL) voice and life assistant built with **Kotlin**, **Jetpack Compose**, **Room (FTS4)**, **DataStore**, **WorkManager**, **Vosk**, **PDFBox-Android**, **Jsoup**, **LiteRT-LM**, and **ONNX Runtime (`arm64-v8a`)**.  
All state and inference live on the phone; network access occurs only on explicit user confirmation.

---

## 1. End-to-End Command & Dialogue Pipeline

```
  [Microphone: VoskVoiceEngine / MicForegroundService / Android SpeechRecognizer]
         │                                       ▲
         ▼ (Optional N5 Owner Speaker Check: SpeakerIdManager + EncryptedFile)
  [Text Input / ASR Transcript]
         │
         ▼
  [PersianTextNormalizer & PersianNumbers & ColloquialLexicon (colloquial_map.json)]
         │  Normalizes ي/ك, ZWNJ, Arabic/Persian digits, spoken -> standard forms
         ▼
  [HybridNluEngine]
    ├── Rule Path: IntentParser + EntityExtractor + Matchers (Pure Kotlin)
    ├── Statistical Path: StatisticalIntentClassifier (TF-IDF + Multinomial LR on intents_fa.jsonl)
    └── Margin / Confidence Arbiter -> Execute | Clarify | UnknownFallback
         │
         ▼
  [DialogueManager]
    ├── Multi-turn slot filling (MissingSlot)
    ├── Ambiguity resolution (AmbiguousIntent + online learning -> user_utterance_examples)
    ├── Contact disambiguation (AwaitingContactChoice) & pronoun resolution
    └── Mandatory confirmation state (AwaitingConfirmation) for sensitive actions
         │
         ▼
  [Confirmation Gate: ConfirmationManager / TaskManager]
    ├── Sensitive actions (CALL, SEND_SMS, MULTI_STEP_NOTIFY, SEARCH_WEB,
    │   WEB_BOOK_LEARN_ROLE, Backup Restore) MUST pass explicit user confirmation
    └── Outgoing SMS/call previews transparently identify Sol
         │
         ▼
  [SkillRegistry (29 registered SolSkill implementations)]
    ├── Core & Memory: Greeting, Identity, Capabilities, Help, Alias, Fact,
    │                  ListMemory, ForgetMemory, SavePerson, SaveLocation,
    │                  Set/GetCurrentLocation
    ├── Calendar & Tasks: TimeDateQa (JalaliCalendar), ReminderAlarm (ExactAlarmScheduler),
    │                     TimerStopwatch (TimerController), CalendarEvent,
    │                     NotesTodo, BackgroundControl
    ├── Knowledge & Web: WebBookRole (confirmed fetch), WebSearch (confirmed Jsoup/DDG),
    │                    TeachRoleVoice, ConsultRoleBook (BookRagEngine + optional LocalSlmEngine)
    ├── Device & Utilities: DeviceStatus, VolumeControl, OpenSettings, OpenApp,
    │                       Routine, Flashlight (CameraManager torch, no CAMERA perm),
    │                       Calculator (SafeCalculator), MediaControl
    └── UnknownFallback -> Searches memory/locations/books safely before refusing
         │
         ▼
  [Response & Output]
    └── Chat UI + Optional Speech (TtsManager: Piper ONNX -> Android TextToSpeech)
```

---

## 2. Offline Knowledge, RAG & Optional On-Device SLM

1. **Ingestion (`BookWebFetcher` / PDF/TXT import):** Streams documents into chunked segments stored in Room (`books`, `book_chunks`) and indexed in SQLite **FTS4** (`book_chunks_fts` with sync triggers).
2. **Retrieval (`BookRagEngine`):** Tokenizes Persian queries, builds safe FTS4 `MATCH` queries, fetches top candidates directly from SQLite (zero full-corpus RAM cache), and re-ranks with BM25.
3. **Optional Synthesis (`LocalSlmEngine`):**
   - Disabled/unloaded by default to fit **4 GB RAM** (`Galaxy A12`) constraints (`minRequiredRamForFile`: 620 MB INT4, 720 MB INT8, 1400 MB 1.5B).
   - Wraps retrieved context inside `<DATA>...</DATA>` boundaries, strips injection tokens, validates output against forged tool/call/SMS directives, and refuses honestly when no `.litertlm` model is installed.

---

## 3. Reliability, Background Scheduling & Memory Budget

- **Alarms & Reboot Recovery:** `ExactAlarmScheduler` persists every reminder/timer in `scheduled_alarms` with unique `requestCode`s. `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`) and `ExactAlarmPermissionReceiver` restore pending alarms automatically; `ExactReminderReceiver` updates `lastReminderDeliveredAt` diagnostics.
- **WorkManager Workers:** `DailyRoutineWorker`, `BatteryMonitorWorker`, `MemoryBackupWorker`, `MemoryIndexerWorker`, `LocationCheckInWorker`, `HistoryMaintenanceWorker`, `BookFetchWorker`, and `DownloadWorker`.
- **Foreground Services (Android 12+):** `MicForegroundService` (`microphone`) and `SolBackgroundService` (`mediaPlayback`) check permissions and start safely from user actions; background FGS restrictions throw no uncaught crashes.
- **4 GB RAM Lifecycle (`SolMemoryOptimizer`):** Lazy-initializes heavy dependencies in `SolAppContainer` and responds to `Application.onTrimMemory` by evicting caches, releasing SLM sessions, and unloading idle Vosk models.

---

## 4. Storage, Network & Security Layers

- **Persistence (`MemoryDatabase` v7):** 12 Room entities with deterministic migrations `v1 -> v7`; destructive fallback is strictly disabled when `isDebugBuild = false`.
- **Preferences (`PreferencesRepository`):** Jetpack DataStore (`sol_preferences`).
- **Encrypted Backup (`EncryptedBackupManager`):** Streaming `SOL_BACKUP_V3` (`PBKDF2WithHmacSHA256` 600k iterations + `AES-256-GCM` with header AAD + `GZIP` + atomic Room `withTransaction` restore).
- **Network (`SolHttpClient`, `DownloadWorker`):** HTTPS-only, public-IP DNS SSRF guard, host allowlist, `Range` resume, `1.25x + 1 GiB` storage guard, and strict SHA-256 verification (refusing `"TODO(verify)"`).
