# Changelog — Sol (سول)

All notable milestones in the development of **Sol** (`2.0.0`, `com.aistudio.solassistant.kpvmqr`).

---

## [2.0.0] — Milestone M8: Tests, CI, Reliability & Repo Hygiene
### Added
- **Comprehensive M8 Verification Suite:**
  - Table-driven `PersianTextNormalizer` & `PersianNumbers` coverage.
  - Exhaustive `JalaliCalendar` leap year, Esfand 29/30 boundaries, month length, and 200-year daily bijection round-trip tests + command latency benchmark (`p50`/`p95`).
  - Strict `DialogueManager` & `TaskManager` invariants verifying calls/SMS never launch without explicit confirmation, plus success/failure/permission-missing coverage across all 29 registered skills and 7 `WorkManager` workers.
  - Room `v6 -> v7` FTS4 rebuild migration test, release non-destructive DB guard test, multi-MB `SOL_BACKUP_V3` round-trip/tamper/truncation/rollback tests, and local `HttpServer` download resume/SSRF/MIME/`TODO(verify)` tests.
  - Robolectric Jetpack Compose UI tests for `CommunicationConfirmationDialog`, `ReliabilityScreen`, and `ChatBubbleCard` clarification actions.
- **Settings Reliability Screen (One UI Core / Android 12+):** Battery optimization status & deep link (no extra permissions), exact-alarm status, notification status, Persian Samsung "Never sleeping apps" instructions, last-reminder diagnostic, 1-minute self-test, RAM/disk status, and recommended SLM model display.
- **CI & Tooling:** Committed Gradle 9.3.1 Wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`), Detekt + Android Lint + JaCoCo (`0.8.13`) + NLU evaluation gate in `.github/workflows/build-android-apk.yml`, action pinning to commit SHAs, deterministic debug keystore restoration (`DEBUG_KEYSTORE_BASE64`), dynamic `versionName` APK packaging with `SHA256SUMS`, and `.github/dependabot.yml`.
- **Documentation & Hygiene:** Added bilingual `README.md`, `PRIVACY.md`, `SECURITY.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, and owner `LICENSE` placeholder; removed obsolete `public/assets/aistudio/` artifacts and fixed `metadata.json` / `.env.example`.

## Prior Milestones (M1–M7 & N1–N5)
- **M7 (Knowledge, RAG, SLM & Download Resilience):** Streaming Room FTS4 + BM25 `BookRagEngine`, optional lazy `LocalSlmEngine` (`LiteRT-LM`) with `<DATA>` prompt isolation, `SolHttpClient` SSRF/HTTPS/SHA-256 `DownloadWorker`, `SOL_BACKUP_V3` streaming AES-256-GCM backups, and `SolMemoryOptimizer`.
- **M6 (Voice Pipeline & Accessibility):** `VoskVoiceEngine` offline ASR, audio focus & AEC/NS handling, end-pointing & wake-word timeout controls, and `TtsManager` system TTS routing.
- **N1–N5 (User-Approved Offline Extensions):**
  - **N1:** User-confirmed web search via `Jsoup` (`WebSearchSkill`, no API keys).
  - **N2:** Screen-off `MicForegroundService` (`FOREGROUND_SERVICE_MICROPHONE`).
  - **N3:** Self-contained Persian Piper ONNX TTS engine (`onnxruntime-android` `arm64-v8a`, `PersianPhonemizer`, `PiperConfig` SHA-256 check).
  - **N4:** Continuous post-wake listening mode and audio cue controls.
  - **N5:** Optional local owner speaker verification (`SpeakerEmbedder`, `SpeakerVerifier`, `OwnerProfile` via `EncryptedFile`, `InactiveFailSafe` when unconfigured).
- **M5 (Offline Skills & Quick Surfaces):** Offline timer & stopwatch, permissionless flashlight toggle, Jalali-to-Gregorian calendar event insert, `SafeCalculator`, notes/to-do lists, media/settings/app controls, Quick Settings tile, and home-screen widget.
- **M4 (Hybrid NLU, Dialogue & Online Learning):** `StatisticalIntentClassifier` (TF-IDF + Multinomial LR), `HybridNluEngine`, multi-turn `DialogueManager` with slot filling, clarification choices, and `user_utterance_examples` adaptation.
- **M3 (Persian Normalization & Evaluation Harness):** `PersianTextNormalizer`, `ColloquialLexicon`, and golden evaluation suite.
- **M1–M2 (Reliability, Security & Persistence Foundation):** Room v1–v6 migrations, `ExactAlarmScheduler` + `BootReceiver`, `WorkManager` workers, `ConfirmationManager` communication gates, and honest privacy/capability auditing.
