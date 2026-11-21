# Architecture

```
صدا/متن → PersianText.normalize → IntentParser (قواعد، مرز کلمه)
        → UNKNOWN? → IntentClassifier (char n-gram TF-IDF kNN) → NluPolicy
              UseRule | RunIntent (فقط اقدام امن) | Clarify | Fallback
        → DialogueManager (اسلات‌های CONTACT/MESSAGE/REMINDER_TEXT/DURATION، TTL ۱۲۰ ثانیه)
        → CommandBrain.execute → SkillRegistry (Calculator/Timer/Flashlight) یا اقدام‌های قدیمی
        → RAG/SLM (اختیاری) → SpeechTextPreparer → PersianTTS
```
- `text/` متن و اعداد فارسی (JVM خالص، تست‌پذیر)
- `nlu/` طبقه‌بند، سیاست ایمنی، اسلات‌ها
- `dialogue/` وضعیت گفتگو
- `skills/` مهارت‌ها؛ هر نیت دقیقاً یک مهارت
- `knowledge/SlmIntentProposal` پیشنهاد SLM فقط با اعتبارسنجی سخت
- ارزیابی: `NluEvaluationTest`؛ داده با `tools/gen_nlu_data.py` تولید می‌شود (ساختگی).
