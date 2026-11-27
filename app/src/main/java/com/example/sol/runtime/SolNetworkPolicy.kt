package com.example.sol.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SolNetworkPolicy(val labelFa: String) {
    /** Zero outbound sockets, zero DNS lookups, and zero cloud/system ASR fallbacks. */
    OFFLINE_STRICT("آفلاین سخت‌گیرانه (بدون هیچ اتصال اینترنتی)"),

    /** Outbound HTTPS requests permitted only for explicit user-approved actions or self-contained setup. */
    USER_APPROVED_NETWORK("شبکه با درخواست یا تأیید کاربر"),
}

fun canUseNetwork(
    policy: SolNetworkPolicy,
    userConfirmedThisAction: Boolean,
): Boolean = when (policy) {
    SolNetworkPolicy.OFFLINE_STRICT -> false
    SolNetworkPolicy.USER_APPROVED_NETWORK -> userConfirmedThisAction
}

/**
 * Thread-safe coordinator tracking the active [SolNetworkPolicy] and lightweight,
 * zero-PII diagnostic summaries of allowed/blocked network checks for Privacy Audit visibility.
 */
class SolNetworkPolicyCoordinator(
    initialPolicy: SolNetworkPolicy = SolNetworkPolicy.USER_APPROVED_NETWORK,
) {
    private val _policy = MutableStateFlow(initialPolicy)
    val policy: StateFlow<SolNetworkPolicy> = _policy.asStateFlow()

    val currentPolicy: SolNetworkPolicy
        get() = _policy.value

    private val _lastNetworkEventFa = MutableStateFlow("هنوز هیچ درخواست شبکه‌ای ثبت نشده است.")
    val lastNetworkEventFa: StateFlow<String> = _lastNetworkEventFa.asStateFlow()

    private val _lastBlockedReasonFa = MutableStateFlow("")
    val lastBlockedReasonFa: StateFlow<String> = _lastBlockedReasonFa.asStateFlow()

    fun setPolicy(newPolicy: SolNetworkPolicy) {
        _policy.value = newPolicy
        if (newPolicy == SolNetworkPolicy.OFFLINE_STRICT) {
            _lastNetworkEventFa.value = "حالت آفلاین سخت‌گیرانه فعال شد (همهٔ درخواست‌های شبکه مسدود هستند)."
        } else {
            _lastBlockedReasonFa.value = ""
        }
    }

    fun isOfflineStrict(): Boolean = _policy.value == SolNetworkPolicy.OFFLINE_STRICT

    /**
     * Checks whether a network operation is permitted under the active policy, logging only
     * the sanitized category/host (never query strings, prompts, or user PII).
     */
    fun checkAndRecordRequest(
        categoryFa: String,
        host: String = "",
        userConfirmedThisAction: Boolean = true,
    ): Boolean {
        val active = _policy.value
        val hostPart = host.trim().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val timeStr = formatNowTime()
        return if (canUseNetwork(active, userConfirmedThisAction)) {
            _lastNetworkEventFa.value = "$timeStr — $categoryFa$hostPart"
            true
        } else {
            val reason = if (active == SolNetworkPolicy.OFFLINE_STRICT) {
                "درخواست «$categoryFa$hostPart» به دلیل فعال‌بودن حالت آفلاین سخت‌گیرانه مسدود شد."
            } else {
                "درخواست «$categoryFa$hostPart» نیازمند تأیید کاربر است."
            }
            _lastBlockedReasonFa.value = "$timeStr — $reason"
            _lastNetworkEventFa.value = "$timeStr — مسدودشد: $categoryFa$hostPart"
            false
        }
    }

    fun recordBlockedAction(actionFa: String) {
        val timeStr = formatNowTime()
        _lastBlockedReasonFa.value = "$timeStr — $actionFa"
        _lastNetworkEventFa.value = "$timeStr — مسدودشد: $actionFa"
    }

    private fun formatNowTime(): String = runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }.getOrDefault("--:--:--")
}
