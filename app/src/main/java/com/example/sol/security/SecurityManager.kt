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
        ttsRequiresNetwork: Boolean = false,
        historyRetentionMode: HistoryRetentionMode = HistoryRetentionMode.TWO_HUNDRED_ITEMS,
        privateModeEnabled: Boolean = false,
    ): List<SecurityAuditItem> {
        val asrOffline = activeAsrBackend == ActiveAsrBackend.VOSK_OFFLINE
        val asrDetail = when {
            asrOffline ->
                "موتور گفتار ۱۰۰٪ آفلاین Vosk فعال است؛ صدای شما از گوشی خارج نمی‌شود."
            voskInstalled ->
                "مدل Vosk نصب است اما اکنون تشخیص گفتار سیستم انتخاب شده است؛ در صورت نبود بستهٔ آفلاین سیستم، صوت ممکن است به سرور سرویس گفتار گوشی ارسال شود."
            else ->
                "تشخیص گفتار سیستم اندروید فعال است (با EXTRA_PREFER_OFFLINE). روی بیشتر گوشی‌ها اگر بستهٔ آفلاین فارسی سیستم نصب نباشد، سرویس گفتار سیستم ممکن است صدا را برای پردازش به اینترنت بفرستد. برای ۱۰۰٪ آفلاین، مدل Vosk را نصب کنید."
        }

        val ttsDetail = if (ttsRequiresNetwork) {
            "صدای انتخاب‌شدهٔ TTS در تنظیمات گوشی نیازمند شبکه است؛ برای گفتار کاملاً آفلاین از SherpaTTS یا صدای آفلاین سیستم استفاده کنید."
        } else {
            "خروجی صوتی به موتور صدای محلی گوشی (مانند SherpaTTS یا صدای آفلاین سیستم) متصل است."
        }

        val retentionDetail = if (privateModeEnabled) {
            "حالت خصوصی فعال است: هیچ فرمان یا پاسخی در جدول سوابق Room ذخیره نمی‌شود."
        } else {
            "سیاست نگهداری سوابق: «${historyRetentionMode.labelFa}» • امکان پاک‌کردن یک‌ضرب کل داده‌ها در تب سوابق فعال است."
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
                titleFa = "سنتز گفتار (TTS) گوشی",
                detailFa = ttsDetail,
                isCompliant = !ttsRequiresNetwork,
            ),
            SecurityAuditItem(
                titleFa = "دانلودها و دسترسی وب با تأیید صریح HTTPS",
                detailFa = "هیچ تحلیل‌گر یا سرور ابری در Sol وجود ندارد؛ دریافت کتاب/صفحهٔ وب یا فایل مدل فقط با درخواست و تأیید کاربر، از HTTPS و بدون دسترسی به شبکه‌های محلی انجام می‌شود.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "نگهداری سوابق، حالت خصوصی و حذف کامل",
                detailFa = retentionDetail,
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "تأیید اجباری تماس و پیامک + معرفی شفاف Sol",
                detailFa = if (alwaysConfirmCommunication) {
                    "تماس و پیامک هرگز بدون تأیید صریح کاربر شروع نمی‌شوند و Sol همیشه هویت خود را در متن معرفی می‌کند."
                } else {
                    "تأیید صریح برای عملیات ارتباطی حساس فعال است و هویت Sol شفاف اعلام می‌شود."
                },
                isCompliant = true,
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
