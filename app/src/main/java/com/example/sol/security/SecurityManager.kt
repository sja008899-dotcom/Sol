package com.example.sol.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.sol.memory.HistoryRetentionMode
import com.example.sol.voice.ActiveAsrBackend

data class SecurityAuditItem(
    val titleFa: String,
    val detailFa: String,
    val isCompliant: Boolean,
)

class PermissionManager(private val context: Context) {
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}

class PrivacyManager(private val permissionManager: PermissionManager) {
    fun getSecurityAudit(
        alwaysConfirmCommunication: Boolean,
        activeAsrBackend: ActiveAsrBackend = ActiveAsrBackend.ANDROID_SPEECH_RECOGNIZER,
        voskInstalled: Boolean = false,
        piperTtsReady: Boolean = true,
        historyRetentionMode: HistoryRetentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
        privateModeEnabled: Boolean = false,
        offlineStrictMode: Boolean = false,
        lastNetworkEventFa: String = "",
        speakerIdCustomUnverified: Boolean = false,
    ): List<SecurityAuditItem> {
        val asrOffline = activeAsrBackend == ActiveAsrBackend.VOSK_OFFLINE
        val asrDetail = when {
            asrOffline && offlineStrictMode ->
                "موتور گفتار ۱۰۰٪ آفلاین Vosk فعال است و در حالت Offline Strict هیچ دسترسی سیستم/ابری مجاز نیست."
            asrOffline ->
                "موتور گفتار ۱۰۰٪ آفلاین Vosk فعال است؛ صدای شما از گوشی خارج نمی‌شود."
            voskInstalled ->
                "مدل Vosk نصب است اما اکنون تشخیص گفتار سیستم انتخاب شده است؛ در صورت نبود بستهٔ آفلاین سیستم، صوت ممکن است به سرور سرویس گفتار گوشی ارسال شود."
            else ->
                "تشخیص گفتار سیستم اندروید فعال است (با EXTRA_PREFER_OFFLINE). روی بیشتر گوشی‌ها اگر بستهٔ آفلاین فارسی سیستم نصب نباشد، سرویس گفتار سیستم ممکن است صدا را برای پردازش به اینترنت بفرستد. برای ۱۰۰٪ آفلاین، مدل Vosk را نصب کنید."
        }

        val ttsDetail = if (piperTtsReady) {
            "سنتز گفتار ۱۰۰٪ مستقل و آفلاین Sol (موتور داخلی Piper ONNX) فعال است و هیچ وابستگی به موتورهای صدای گوشی یا اینترنت ندارد."
        } else {
            "موتور گفتار داخلی و مستقل Piper ONNX هنوز نصب نشده است؛ با اولین اتصال به اینترنت (در صورت خاموش‌بودن Offline Strict) به‌صورت خودکار و تأییدشده با SHA-256 دانلود می‌شود."
        }

        val retentionDetail = if (privateModeEnabled) {
            "حالت خصوصی فعال است: هیچ فرمان یا پاسخی در جدول سوابق Room ذخیره نمی‌شود."
        } else {
            "سیاست نگهداری سوابق: «${historyRetentionMode.labelFa}» • امکان پاک‌کردن یک‌ضرب کل داده‌ها در تب سوابق فعال است."
        }

        val networkDetail = buildString {
            if (offlineStrictMode) {
                append("حالت آفلاین سخت‌گیرانه (Offline Strict) فعال است: هیچ درخواست خروجی HTTPS، DNS یا تشخیص گفتار سیستمی انجام نمی‌شود.")
            } else {
                append("هیچ تحلیل‌گر یا سرور ابری در Sol وجود ندارد؛ دریافت کتاب/صفحهٔ وب یا فایل مدل فقط با درخواست/تأیید کاربر، از HTTPS تأییدشده و بدون دسترسی به شبکه‌های محلی انجام می‌شود.")
            }
            if (lastNetworkEventFa.isNotBlank()) {
                append("\nآخرین رویداد شبکه: ")
                append(lastNetworkEventFa)
            }
        }

        val n5Detail = if (speakerIdCustomUnverified) {
            "شناسایی صدای مالک (N5) فقط یک سیگنال کمکی است و جایگزین تأیید صریح تماس/پیامک نمی‌شود. ⚠️ مدل گوینده فعلی سفارشی (تأییدنشده ناشر) است."
        } else {
            "شناسایی صدای مالک (N5) فقط سیگنال کمکی احراز است و تماس و پیامک هرگز بدون تأیید صریح کاربر ارسال نمی‌شوند."
        }

        return listOf(
            SecurityAuditItem(
                titleFa = if (asrOffline) {
                    "تشخیص گفتار (ASR): ۱۰۰٪ آفلاین"
                } else {
                    "تشخیص گفتار (ASR): موتور سیستم (احتمال نیاز به اینترنت)"
                },
                detailFa = asrDetail,
                isCompliant = asrOffline,
            ),
            SecurityAuditItem(
                titleFa = if (piperTtsReady) {
                    "سنتز گفتار (TTS): خودکفا و آفلاین (Piper ONNX)"
                } else {
                    "سنتز گفتار (TTS): در انتظار دریافت مدل داخلی Piper"
                },
                detailFa = ttsDetail,
                isCompliant = piperTtsReady,
            ),
            SecurityAuditItem(
                titleFa = if (offlineStrictMode) {
                    "سیاست شبکه: حالت آفلاین سخت‌گیرانه فعال است"
                } else {
                    "دانلودها و دسترسی وب با تأیید صریح HTTPS"
                },
                detailFa = networkDetail,
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "نگهداری سوابق، حالت خصوصی و حذف کامل",
                detailFa = retentionDetail,
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "تأیید اجباری تماس و پیامک + نقش کمکی N5",
                detailFa = if (alwaysConfirmCommunication) {
                    "$n5Detail هویت Sol نیز همیشه شفاف اعلام می‌شود."
                } else {
                    n5Detail
                },
                isCompliant = !speakerIdCustomUnverified,
            ),
            SecurityAuditItem(
                titleFa = "تفکیک موقعیت فعلی و پشتیبان ابری سیستم",
                detailFa = "پشتیبان‌گیری خودکار گوگل و انتقال ابری سیستم غیرفعال (exclude کامل) است؛ موقعیت فعلی فقط با ثبت دستی کاربر نگهداری می‌شود.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "مجوزهای فعال دستگاه",
                detailFa = "میکروفون: ${if (permissionManager.hasMicrophonePermission()) "فعال" else "در انتظار اجازه"} • مخاطبین: ${if (permissionManager.hasContactsPermission()) "فعال" else "فقط حافظه Sol"} • اعلان: ${if (permissionManager.hasNotificationPermission()) "فعال" else "غیرفعال"}",
                isCompliant = true,
            ),
        )
    }
}
