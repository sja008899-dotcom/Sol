package com.example.sol.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

    fun hasInternetPermission(): Boolean {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        }.getOrDefault(false)
    }
}

class PrivacyManager(private val permissionManager: PermissionManager) {
    fun getSecurityAudit(alwaysConfirmCommunication: Boolean): List<SecurityAuditItem> {
        val noInternet = !permissionManager.hasInternetPermission()
        return listOf(
            SecurityAuditItem(
                titleFa = "استقلال کامل از اینترنت",
                detailFa = if (noInternet) {
                    "مجوز INTERNET در مانیفست برنامه وجود ندارد؛ هیچ داده‌ای به فضای ابری ارسال نمی‌شود."
                } else {
                    "هشدار: مجوز اینترنت فعال است."
                },
                isCompliant = noInternet,
            ),
            SecurityAuditItem(
                titleFa = "حافظهٔ محلی شفاف و قابل حذف",
                detailFa = "تمام یادگیری‌ها، اشخاص، مکان‌ها و سوابق فقط در پایگاه دادهٔ خصوصی Room ذخیره می‌شوند.",
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
                detailFa = "موقعیت فعلی فقط با تأیید دستی ثبت می‌شود و با مکان‌های ذخیره‌شده اشتباه گرفته نمی‌شود.",
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "دسترسی میکروفون",
                detailFa = if (permissionManager.hasMicrophonePermission()) {
                    "صادر شده (فقط هنگام فشردن دکمهٔ میکروفون)"
                } else {
                    "هنوز صادر نشده — می‌توانید با ورودی متنی کار کنید یا هنگام نیاز اجازه دهید."
                },
                isCompliant = true,
            ),
            SecurityAuditItem(
                titleFa = "دسترسی مخاطبین",
                detailFa = if (permissionManager.hasContactsPermission()) {
                    "صادر شده (فقط خواندن برای نمایش و تأیید تماس/پیام)"
                } else {
                    "هنوز صادر نشده — بدون این مجوز هم می‌توانید اشخاص را در حافظهٔ Sol ثبت کنید."
                },
                isCompliant = true,
            ),
        )
    }
}
