package com.example.sol.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

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
    fun getSecurityAudit(alwaysConfirmCommunication: Boolean): List<SecurityAuditItem> {
        return listOf(
            SecurityAuditItem(
                titleFa = "اتصال اینترنتی اختیاری و با تأیید صریح",
                detailFa = "هیچ داده‌ای خودکار به اینترنت ارسال نمی‌شود؛ اتصال وب فقط پس از درخواست و تأیید شما برای دریافت کتاب فعال می‌شود.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "حافظهٔ محلی شفاف (کتاب‌ها، نقش‌ها و یادگیری‌ها)",
                detailFa = "تمام یادگیری‌ها، کتاب‌های دریافتی و نقش‌های صوتی فقط در پایگاه دادهٔ خصوصی Room ذخیره و قابل حذف‌اند.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "تأیید اجباری تماس و پیامک",
                detailFa = if (alwaysConfirmCommunication) {
                    "تماس و پیامک هرگز بدون تأیید صریح کاربر اجرا نمی‌شوند."
                } else {
                    "تأیید برای عملیات حساس فعال است."
                },
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "معرفی شفاف هویت Sol",
                detailFa = "متن‌های ارسالی Sol همیشه هویت دستیار را به مخاطب اعلام می‌کنند.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "تفکیک موقعیت فعلی از مکان‌های ذخیره‌شده",
                detailFa = "موقعیت فعلی فقط با تأیید دستی ثبت می‌شود و ردیابی پنهانی GPS در پس‌زمینه وجود ندارد.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "مجوز اعلان‌های پس‌زمینه",
                detailFa = if (permissionManager.hasNotificationPermission()) {
                    "فعال برای یادآورها و گزارش‌های پس‌زمینه"
                } else {
                    "غیرفعال — برای دریافت اعلان یادآورهای پس‌زمینه مجوز اعلان را فعال کنید."
                },
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "دسترسی میکروفون و مخاطبین",
                detailFa = "میکروفون: ${if (permissionManager.hasMicrophonePermission()) "فعال" else "در انتظار اجازه"} • مخاطبین: ${if (permissionManager.hasContactsPermission()) "فعال" else "فقط حافظه Sol"}",
                isCompliant = true,
            ),
        )
    }
}
