# Contributing to Sol (سول)

Thank you for helping improve **Sol**, an offline-first Persian (`fa-IR`, RTL) Android assistant. Before submitting changes, please review the project's architecture (`ARCHITECTURE.md`), privacy guarantees (`PRIVACY.md`), and verification gates below.

---

## 1. Adding a New Skill (`SolSkill`)

1. **Define the Intent Action** in `app/src/main/java/com/example/sol/brain/ParsedCommand.kt` (`SolAction` enum).
2. **Add Rule / Pattern Matching & Slot Extraction** in the appropriate matcher file under `app/src/main/java/com/example/sol/brain/`:
   - `DeviceAndAssistantMatchers.kt`
   - `KnowledgeAndScheduleMatchers.kt`
   - `MemoryAndLocationMatchers.kt`
   - `UtilitySkillsMatchers.kt`
   - Keep `IntentParser` and `EntityExtractor` pure Kotlin with zero Android `Context` dependencies whenever possible.
3. **Implement `SolSkill`** in `app/src/main/java/com/example/sol/brain/skills/`:
   - Specify accurate `id`, `titleFa`, `descriptionFa`, `sampleCommandsFa`, `sensitivity` (`Sensitivity.NONE` vs `Sensitivity.CONFIRM`), and `requiredPermissions`.
   - Never execute `CALL`, `SEND_SMS`, network fetch, or destructive actions without returning a `PendingSolTask` gated by confirmation.
4. **Register the Skill** in `SolAppContainer.skillRegistry` (`app/src/main/java/com/example/SolApplication.kt`).
   - `SolCapabilitiesCatalog` and `SkillRegistry` automatically surface registered skills in the in-app Capabilities dialog so privacy/capability claims stay literally true.
5. **Add Success & Failure Tests**:
   - Cover both normal execution and missing-slot / invalid-input / missing-permission failure paths in `app/src/test/java/com/example/`.

---

## 2. Adding NLU Training Data & Golden Evaluation Examples

Sol uses a **Hybrid NLU** (`IntentParser` rules + on-device TF-IDF Multinomial Logistic Regression classifier):

- **Training utterances:** `app/src/main/assets/nlu/intents_fa.jsonl`
  - Used to train the statistical classifier (`StatisticalIntentClassifier`).
- **Golden evaluation set (disjoint):** `app/src/test/resources/nlu/golden_fa.jsonl` (if present in test resources; see `M3NluEvaluationTest.kt` for split evaluation).
  - **Important:** Never copy evaluation/test-split sentences directly into `intents_fa.jsonl`; keep `train` and `test` splits strictly disjoint so `M3NluEvaluationTest` measures real generalization.
  - Cover colloquial spoken Persian variants (`colloquial_map.json`), Persian/Arabic/English digits, and regression pairs such as `وب`/`خوب`, `برو`/`بروم`, and `زنگ تفریح`.

Each JSONL line follows the NLU schema (`text`, `intent`, optional `slots`, and `split`).

---

## 3. Mandatory Local Checks Before Opening a PR

Run the exact CI verification suite locally:

```bash
./gradlew :app:detekt :app:lintDebug :app:testDebugUnitTest :app:jacocoTestReport --console=plain
./gradlew :app:assembleDebug --console=plain
```

All pull requests must keep:
- `:app:detekt` clean (0 violations)
- `:app:lintDebug` clean (0 errors)
- `:app:testDebugUnitTest` 100% green, including `M3NluEvaluationTest`, `M4StatisticalAndDialogueTests`, and `M8*Tests`
- Strict adherence to 4 GB RAM / Android 12 (`API 31`) safety rules: no `runBlocking` on the main thread, no `FallbackToDestructiveMigration` in release builds, and no fabricated hashes (`TODO(verify)` must fail safe).
