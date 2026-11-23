# Sol (سول) — دستیار هوشمند و زندگی‌یار کاملاً آفلاین اندروید

**Sol** یک دستیار صوتی و شخصی **آفلاین‌محور (Offline-First)** برای زبان فارسی (`fa-IR`، راست‌به‌چپ) است که با **Kotlin**، **Jetpack Compose**، **Room (FTS4)**، **DataStore**، **WorkManager**، **Vosk**، **PDFBox-Android**، **Jsoup**، **LiteRT-LM** و **ONNX Runtime (`arm64-v8a`)** ساخته شده است.  
همهٔ داده‌ها و پردازش‌ها روی خود گوشی انجام می‌شوند؛ هیچ سرور ابری، سیستم تحلیلگر (Analytics) یا تبلیغاتی وجود ندارد.

> **توضیح دربارهٔ انتشار در فروشگاه‌ها (پیش از عرضهٔ عمومی در Google Play):**  
> پیش از انتشار عمومی در Google Play لازم است `namespace` (`com.example`) و `applicationId` (`com.aistudio.solassistant.kpvmqr`) به شناسهٔ اختصاصی ناشر تغییر یابند و فرم‌های اظهارنامهٔ Google Play Console برای `FOREGROUND_SERVICE_MICROPHONE`، `FOREGROUND_SERVICE_MEDIA_PLAYBACK` و `SCHEDULE_EXACT_ALARM` تکمیل شوند.

---

## بخش ۱: راهنمای فارسی

### Sol چیست و چگونه کار می‌کند؟
Sol ترکیبی از موتور درک زبان طبیعی ترکیبی (**Hybrid NLU**: قواعد قطعی فارسی + دسته‌بندی آماری TF-IDF روی دستگاه)، مدیریت گفت‌وگوی چندمرحله‌ای (**DialogueManager** برای رفع ابهام، پرکردن اسلات‌های جاافتاده و یادگیری اصطلاحات شما)، **۲۹ مهارت ثبت‌شده (`SkillRegistry`)** و پایگاه‌دادهٔ رمزنگاری‌شدهٔ محلی است.

### نمونه‌های دستورات پشتیبانی‌شده
- **تقویم شمسی و تاریخ:** «امروز چندم برج است؟»، «۱۴۰۴/۰۱/۰۱ به میلادی چندم است؟»، «آیا ۱۴۰۳ سال کبیسه است؟»
- **یادآور دقیق (AlarmManager + بازیابی پس از ری‌استارت):** «فردا ساعت ۹ صبح یادآوری کن جلسه دارم»، «۱۰ دقیقه دیگر یادآوری کن داروها را بخورم»
- **تایمر و کرنومتر آفلاین:** «تایمر ۱۰ دقیقه»، «لغو تایمر»، «کرنومتر را شروع کن»، «کرنومتر را متوقف کن»
- **تماس، پیامک و اطلاع‌رسانی چندمرحله‌ای (همیشه با تأیید صریح):** «به علی زنگ بزن»، «به مریم پیام بده ساعت ۵ می‌رسم»، «وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن»
- **حافظهٔ شخصی، مخاطبین و مکان‌ها:** «یادت باشه رمز کمد ۱۲۳۴ است»، «هر وقت می‌گم دفتر منظورم شرکت کارنو است»، «الان سر کار هستم»، «حافظه‌ات را نشان بده»
- **یادداشت و لیست خرید:** «یادداشت کن شیر و نان بخرم»، «لیست خرید چیه؟»، «شیر خط بخوره»
- **ماشین‌حساب فارسی و کنترل دستگاه:** «دو ضرب در سه حساب کن»، «۲۵ درصد ۸۰۰»، «چراغ قوه را روشن کن»، «صدا را زیاد کن»، «تنظیمات باتری را باز کن»، «اپ تقویم را باز کن»، «وضعیت گوشی چطوره؟»
- **کتابخانهٔ آفلاین (FTS4 + BM25) و آموزش نقش:** «طبق نقشت درباره عادت‌های اتمی راهنماییم کن»، «نقش مشاور بساز»
- **جست‌وجوی وب و دریافت کتاب (فقط با تأیید صریح):** «در وب جستجو کن تقویم ۱۴۰۴»، «کتاب عادت‌های اتمی را از وب پیدا کن و به حافظه اضافه کن»
- **پشتیبان رمزنگاری‌شده و قابلیت‌ها:** «از حافظه در پس‌زمینه پشتیبان بگیر»، «فهرست قابلیت‌ها»، «چه کارهایی بلدی؟»

### مجوزها (`AndroidManifest.xml`) و دلیل هر مجوز
| مجوز | دلیل نیاز در برنامه | رفتار بدون مجوز / جایگزین |
|---|---|---|
| `RECORD_AUDIO` | دریافت فرمان صوتی (Vosk یا SpeechRecognizer) و ثبت اختیاری صدای مالک | در صورت نبود مجوز، وارد کردن متن همیشه بدون مشکل کار می‌کند. |
| `READ_CONTACTS` | یافتن شمارهٔ مخاطب هنگام آماده‌سازی تماس یا پیامک | بدون دسترسی به مخاطبین گوشی، از مخاطبین ذخیره‌شده در Sol استفاده می‌شود. |
| `POST_NOTIFICATIONS` | نمایش اعلان یادآورها و نوتیفیکیشن سرویس پس‌زمینه (اندروید ۱۳+) | در اندروید ۱۲ (`API 31`) بدون پاپ‌آپ فعال است. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | گوش‌دادن اختیاری با صفحه خاموش (N2) و پخش صوت TTS | فقط با درخواست صریح کاربر و رعایت محدودیت‌های اندروید ۱۲+ شروع می‌شود. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **فقط** برای جست‌وجوی وب، دانلود کتاب یا مدل پس از تأیید صریح کاربر | هیچ اتصال پنهان یا تله‌متری در پس‌زمینه انجام نمی‌شود. |
| `SCHEDULE_EXACT_ALARM` | اجرای به‌موقع یادآورهای شمسی حتی در حالت Doze | در صورت لغو مجوز توسط کاربر، به‌شکل ایمن به زمان‌بندی عادی تنزل می‌یابد و راهنمای تنظیمات را نشان می‌دهد. |
| `RECEIVE_BOOT_COMPLETED` | بازیابی خودکار یادآورهای ثبت‌شده پس از خاموش/روشن شدن گوشی (`BootReceiver`) | دریافت‌کنندهٔ پس‌زمینه برای ثبت مجدد آلارم‌ها. |

> نکته: کنترل چراغ‌قوه از طریق `CameraManager.setTorchMode` انجام می‌شود و مجوز `CAMERA` در مانیفست وجود ندارد.

### چه چیزهایی کاملاً آفلاین است و چه بخش‌هایی از شبکه استفاده می‌کند؟
- **۱۰۰٪ آفلاین:** تمام درک زبان طبیعی (قواعد + مدل TF-IDF)، گفت‌وگوی چندمرحله‌ای، تقویم شمسی، آلارم و تایمر، یادداشت/لیست خرید، ماشین‌حساب، چراغ‌قوه، جست‌وجو در حافظه و کتاب‌های نمایه‌شده (FTS4 + BM25)، مدل زبانی محلی (در صورت نصب)، Piper TTS و تأیید صدای مالک (در صورت نصب مدل ONNX)، و پشتیبان رمزنگاری‌شدهٔ V3.
- **استفاده از شبکه فقط با تأیید صریح کاربر:** جست‌وجوی وب (`SEARCH_WEB`)، دریافت صفحه یا کتاب از اینترنت (`BookWebFetcher`) و دانلود فایل مدل‌ها (`DownloadWorker`).
- **نکتهٔ صداقت دربارهٔ موتور سیستم:** اگر مدل آفلاین Vosk یا صدای آفلاین TTS نصب نباشد و از موتور پیش‌فرض سیستم اندروید استفاده شود، سرویس گفتار گوشی ممکن است طبق تنظیمات سیستم عامل صدا را به اینترنت بفرستد. وضعیت واقعی این مورد در صفحهٔ حریم خصوصی داخل برنامه اعلام می‌شود.

### نکات دانلود و وارد کردن مدل‌ها (Vosk / LiteRT-LM / Piper / Speaker ID)
- **سیاست ایمنی هش (`TODO(verify)`):** برای مدل‌های آنلاین در `ModelCatalog.kt` و `VoskVoiceEngine.kt` که هنوز هش SHA-256 رسمی آن‌ها به‌صورت مستقل تأیید نشده، مقدار `"TODO(verify)"` قرار دارد و `DownloadWorker` تا پیش از ثبت هش معتبر، دانلود را به‌صورت **Fail-Safe** متوقف می‌کند تا فایل تأییدنشده وارد گوشی نشود.
- **Piper TTS (`N3`):** در تنظیمات TTS می‌توانید فایل‌های `.onnx` و `.onnx.json` را انتخاب کنید؛ برنامه پیش از بارگذاری، هش SHA-256 را بررسی کرده و در صورت مغایرت بدون کرش خطای فارسی می‌دهد.
- **Speaker ID (`N5`):** در تنظیمات، گزینهٔ «ضبط صدای من» با فایل رمزنگاری‌شده (`EncryptedFile`) فعال می‌شود؛ در نبود مدل یا نمونهٔ صدا، سامانه در وضعیت `InactiveFailSafe` باقی می‌ماند.

### قالب فایل پشتیبان (`SOL_BACKUP_V3`)
فایل‌های پشتیبان با جریان پیوسته (`Streaming`) بدون بارگذاری کل کتاب‌ها در رم ساخته می‌شوند:
- هدر احراز هویت‌شده (`SOL_BACKUP_V3`) + کلید مشتق‌شده با **PBKDF2WithHmacSHA256 (۶۰۰٬۰۰۰ دور)** + نمک ۱۶ بایتی و IV دوازده بایتی تصادفی
- رمزنگاری **AES-256-GCM** (هدر به عنوان AAD) + فشرده‌سازی **GZIP** + بازگردانی اتمیک در تراکنش Room (در صورت رمز اشتباه یا دستکاری فایل، داده‌های فعلی دست‌نخورده باقی می‌مانند).

### محدودیت‌های شناخته‌شده در گوشی‌های ۴ گیگابایتی و اندروید ۱۲ (One UI Core 4.1)
1. **مدیریت باتری سامسونگ:** One UI Core ممکن است سرویس‌ها و یادآورها را بخواباند. از صفحهٔ **تنظیمات ← پایداری یادآور و سرویس (One UI)** برای افزودن Sol به **Never sleeping apps** و بررسی دسترسی هشدار دقیق استفاده کنید.
2. **محدودیت رم (~۲ گیگابایت رم آزاد):** مدل‌های سنگین (`Qwen2.5-1.5B`) روی گوشی‌های ۴ گیگابایتی مسدود یا غیرتوصیه‌شده‌اند (`Qwen3-0.6B INT4` گزینهٔ پیشنهادی است). برای صرفه‌جویی در رم، مدل Vosk در حالت بیکاری آزاد می‌شود.
3. **شروع سرویس میکروفون در اندروید ۱۲+ (`API 31`):** شروع سرویس Foreground از پس‌زمینه مجاز نیست؛ سرویس میکروفون فقط از تعامل مستقیم کاربر راه‌اندازی می‌شود.

### ساخت و اجرای تست‌ها
```bash
# اجرای بررسی کد، لنت، تست‌های واحد و Robolectric و پوشش کد JaCoCo
./gradlew :app:detekt :app:lintDebug :app:testDebugUnitTest :app:jacocoTestReport --console=plain

# ساخت APK دیباگ قابل نصب
./gradlew :app:assembleDebug --console=plain

# ساخت APK رلیز (نیازمند تنظیم متغیرهای KEYSTORE_PATH، STORE_PASSWORD و KEY_PASSWORD)
./gradlew :app:assembleRelease --console=plain
```

---

## Part 2: English Overview

### What Is Sol?
**Sol** is an offline-first Persian (`fa-IR`, RTL) voice and life assistant for Android (`minSdk = 26`, `targetSdk = 36`, `arm64-v8a`), designed specifically to run reliably on constrained **4 GB RAM / Android 12** smartphones (such as Samsung Galaxy A12 with One UI Core 4.1).

- **Hybrid NLU + Multi-turn Dialogue:** Combines pure-Kotlin Persian normalization (`PersianTextNormalizer`, `PersianNumbers`, `ColloquialLexicon`), deterministic intent matchers, an on-device TF-IDF Multinomial Logistic Regression classifier, slot-filling, contact/intent clarification, and online utterance teaching (`user_utterance_examples`).
- **29 Registered Skills (`SkillRegistry`):** Jalali calendar Q&A & exact alarms (`BootReceiver` reboot recovery), offline timer & stopwatch, permissionless flashlight (`CameraManager`), Persian math (`SafeCalculator`), notes & shopping lists, calls/SMS/multi-step notifications (strictly gated by explicit confirmation and identifying Sol), local Room memory & contacts, FTS4 + BM25 offline book RAG, optional lazy-loaded LiteRT-LM SLM, optional Piper ONNX TTS (N3), optional speaker verification (N5), optional screen-off mic service (N2), and user-confirmed Jsoup web search (N1).
- **Offline vs. Network:** All core intelligence, scheduling, RAG, and AES-256-GCM encrypted V3 backups operate 100% offline. Network access is restricted strictly to explicit user-confirmed web search, web book import, and SHA-256–verified model downloads (`SolHttpClient` + `DownloadWorker`, failing safe whenever a hash is `TODO(verify)`).
- **Build & Verification:**
  ```bash
  ./gradlew :app:detekt :app:lintDebug :app:testDebugUnitTest :app:jacocoTestReport :app:assembleDebug
  ```
- See `ARCHITECTURE.md`, `PRIVACY.md`, `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, and `LICENSE` for full technical and policy details.
