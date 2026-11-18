package com.example.sol.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.sol.contacts.ResolvedContact

/**
 * Generates transparent Sol communication scripts so Sol NEVER conceals its identity.
 */
class CallScriptManager {
    fun buildTransparentMessage(
        ownerName: String,
        contact: ResolvedContact,
        body: String,
        confirmedLocation: String = "",
    ): String {
        val sender = ownerName.trim().ifBlank { "صاحب گوشی" }
        val customNote = contact.callScriptNote.trim()
        val locNote = if (confirmedLocation.isNotBlank() && body.contains("موقعیت")) {
            " (موقعیت تأییدشده: $confirmedLocation)"
        } else {
            ""
        }
        val intro = "سلام ${contact.name} عزیز، من Sol دستیار شخصی $sender هستم."
        val core = if (body.isNotBlank()) body.trim() else "درخواست تماس از طرف $sender."
        val suffix = if (customNote.isNotBlank()) "\n($customNote)" else ""
        return "$intro $core$locNote$suffix"
    }

    fun buildCallScriptPreview(
        ownerName: String,
        contact: ResolvedContact,
        message: String?,
    ): String {
        val sender = ownerName.trim().ifBlank { "صاحب گوشی" }
        val payload = message?.takeIf { it.isNotBlank() } ?: "تماس مستقیم"
        return "معرفی پیشنهادی Sol هنگام برقراری تماس:\n«سلام، من Sol دستیار شخصی $sender هستم؛ موضوع: $payload»"
    }
}

/**
 * Launches the Android system dialer via zero-permission ACTION_DIAL after user confirmation.
 */
class CallManager(private val context: Context) {
    fun openDialer(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

/**
 * Launches the system SMS composer via zero-permission ACTION_SENDTO (smsto:) after user confirmation.
 */
class MessageManager(private val context: Context) {
    fun openSmsComposer(phoneNumber: String, messageBody: String): Boolean {
        if (phoneNumber.isBlank()) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phoneNumber)}")).apply {
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
