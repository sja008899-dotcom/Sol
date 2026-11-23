# Privacy Policy & Data Inventory — Sol (سول)

**Last Updated:** Version 2.0.0 (`com.aistudio.solassistant.kpvmqr`)

Sol is an **offline-first** Persian (`fa-IR`) Android voice and life assistant. Everything runs locally on your phone: there is no Sol backend server, no cloud AI, and no analytics, advertising, or tracking SDK in the application.

---

## 1. Local Data Inventory (Stored Only on Your Device)

### 1.1 Room Database (`sol_memory.db`, schema v7 — 12 entities)
| Table / Entity | What It Stores | Retention & Deletion |
|---|---|---|
| `memories` (`MemoryFactEntity`) | Personal key-value facts and spoken aliases taught by you (e.g., `owner_name`, custom aliases). | Kept locally; deleted per item or via one-tap clear all. |
| `persons` (`PersonEntity`) | Names, phone numbers, relationship labels, and call script notes entered by you. | Kept locally; excluded from Android Auto Backup (`allowBackup="false"`). |
| `locations` (`LocationEntity`) | Saved place aliases (e.g., "خانه", "دفتر") and optional notes. | Strictly separated from current live location; user-controlled. |
| `history` (`HistoryEntity`) | Conversation turns (`userText`, `solText`, `intentAction`, `status`, `createdAt`). | Controlled by `historyRetentionMode`: Off / 7 days / Last 200 items (`HistoryMaintenanceWorker`), plus **Private Mode** (`privateModeEnabled`) which prevents saving turns at all. |
| `books` (`BookKnowledgeEntity`) | Titles, authors, subjects, summaries, voice notes, source type/URL of imported books/pages. | Kept locally until deleted by user. |
| `book_chunks` (`BookChunkEntity`) & `book_chunks_fts` (`BookChunkFtsEntity`) | Chunked book text and SQLite FTS4 full-text index tokens for offline BM25 retrieval. | Kept locally until parent book or data is deleted. |
| `sol_roles` (`SolRoleEntity`) | Custom assistant personas/roles taught by you and linked book titles. | Kept locally until deleted by user. |
| `background_logs` (`BackgroundJobLogEntity`) | Execution status logs of local `WorkManager` / alarm jobs (no PII). | Kept locally; pruned by user clear-all. |
| `scheduled_alarms` (`ScheduledAlarmEntity`) | Persisted reminders and countdown timers (`triggerAtMillis`, `jalaliLabel`, `titleFa`, `requestCode`) so alarms are restored on boot. | Kept until triggered/cancelled or cleared. |
| `user_utterance_examples` (`UserUtteranceExampleEntity`) | Utterance corrections taught by you during clarification dialogs to adapt NLU locally. | Kept locally until deleted by user. |
| `notes_todos` (`NoteTodoEntity`) | Personal notes and shopping/to-do list items (`kind`, `content`, `isDone`). | Kept locally until marked done or deleted. |

### 1.2 DataStore Preferences (`sol_preferences`)
Stores local app settings only: owner display name, TTS engine/voice/speech rate/persona, invocation & wake-word preferences, VAD silence timeout, communication delivery & confirmation policy, dark theme toggle, confirmed manual location label (`confirmedCurrentLocation`, set **only** when you explicitly confirm it), offline Vosk / screen-off mic / continuous listening toggles, SLM path & GPU preference, history retention & private mode toggles, backup timestamp, and `lastReminderDeliveredAt` / `lastReminderDeliveredTitle` reliability diagnostics.

### 1.3 Encrypted Owner Voice Profile (`owner_voice_profile.enc`) & Models
- **Optional Speaker ID (N5):** If you enroll your voice and import a speaker ONNX model, the computed speaker embedding vector is stored in app-private storage via Jetpack `EncryptedFile` (AES256-GCM MasterKey). Raw enrollment audio is not uploaded anywhere.
- **On-device models:** Vosk ASR, optional LiteRT-LM SLM (`.litertlm`), and optional Piper TTS (`.onnx` + `.onnx.json`) live inside app-private storage (`filesDir`).

---

## 2. Permissions Declared in `AndroidManifest.xml` & Exact Usage

| Permission | Why It Is Needed | Optional / Graceful Degradation |
|---|---|---|
| `RECORD_AUDIO` | Voice command capture (Vosk offline ASR / Android `SpeechRecognizer`) and optional owner voice enrollment. | Requested at runtime when using voice features; typed text works without it. |
| `READ_CONTACTS` | Lookup contact phone numbers by Persian name when preparing calls or SMS. | Requested at runtime when needed; works with saved Sol contacts without it. |
| `POST_NOTIFICATIONS` | Foreground service notifications and local reminder/timer/battery alerts (Android 13+). | Works on Android 12 (`API 31`) without runtime prompt; optional on API 33+. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps active microphone listening (when screen-off mic is enabled by you) and background TTS playback compliant with Android 12+ foreground rules. | Only started from explicit user interaction / active feature state. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Used **only** for explicit user-confirmed web searches, fetching user-requested book/web URLs, or downloading approved model files. | Zero background telemetry; zero silent network calls. |
| `SCHEDULE_EXACT_ALARM` | Fires Persian calendar reminders and timers on time even in Doze mode. | Degrades cleanly to inexact WorkManager/AlarmManager if revoked in Settings. |
| `RECEIVE_BOOT_COMPLETED` | Re-registers persisted `scheduled_alarms` after device reboot, app update, or time/timezone change. | Passive receiver (`BootReceiver`). |

*Note:* Camera flash (`FLASHLIGHT`) uses `CameraManager.setTorchMode` **without** requesting the `CAMERA` permission.

---

## 3. What Works Offline vs. What Uses the Network

- **100% Offline:** Text & voice NLU, hybrid intent classification, dialogue slot-filling, Jalali calendar Q&A, reminders/timers/stopwatch, calculator, notes & shopping lists, local Room memory, FTS4+BM25 book Q&A, optional LiteRT-LM generation, optional Piper TTS, optional speaker verification, and encrypted V3 backups.
- **Note on ASR/TTS Fallback:** If Vosk Persian offline model is not installed and you use Android's system `SpeechRecognizer` (or a cloud-backed system TTS voice), Android's OS speech/TTS provider may contact its own service according to your device settings. `PrivacyManager` reports this honestly in the Privacy & Capabilities view.
- **Explicit Network Actions Only:** Web search (`SEARCH_WEB` via DuckDuckGo HTML/Jsoup), fetching a user-requested book/article (`BookWebFetcher`), and downloading models (`DownloadWorker`) always require explicit user confirmation before any network connection is made.

---

## 4. Cloud Backups, Logging, and User Control

- **Android Cloud Auto Backup Disabled:** `android:allowBackup="false"`, `data_extraction_rules`, and `backup_rules` exclude app data from automatic Google cloud backup/device-to-device transfer.
- **No PII in Logs:** Sol avoids logging names, phone numbers, SMS bodies, or personal memories; `StrictMode` is enabled only in `BuildConfig.DEBUG`.
- **Full Deletion & Portable Export:** Users can clear all Room tables, DataStore preferences, and voice profiles inside the app, or export an encrypted portable backup (`SOL_BACKUP_V3`).
