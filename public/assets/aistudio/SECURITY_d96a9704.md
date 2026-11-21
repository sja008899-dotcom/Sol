# Security

- گزارش آسیب‌پذیری: از GitHub «Security advisories» (گزارش خصوصی) استفاده کنید؛ نه Issue عمومی.
- اصول: متن SLM/RAG/وب هرگز دستور اجرایی نمی‌شود؛ خروجی SLM فقط از فهرست بستهٔ نیت‌ها پذیرفته می‌شود؛ ماشین‌حساب بدون eval است؛ دانلود مدل بدون هش تأییدشده رد می‌شود.
- کلید امضا و رمزها فقط در GitHub Secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) نگه داشته شوند؛ هرگز commit نشوند.
