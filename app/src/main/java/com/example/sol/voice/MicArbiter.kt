package com.example.sol.voice

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

enum class MicOwner(val labelFa: String) {
    FOREGROUND_WAKE("شنود پس‌زمینه (صفحه خاموش)"),
    VOICE_COMMAND("شنود فرمان صوتی"),
    WAKE_WORD_UI("بیدارباش درون‌برنامه"),
    OWNER_ENROLLMENT("ضبط نمونه صدای مالک"),
    MIC_HEALTH_TEST("آزمایش سلامت میکروفون"),
}

/**
 * Coordinates single-owner microphone access across foreground wake listening,
 * interactive voice commands, owner speaker enrollment, and mic health checks.
 */
class MicArbiter(
    private val pauseForegroundMic: suspend () -> Unit = {},
    private val resumeForegroundMic: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val waitersCount = AtomicInteger(0)

    @Volatile
    private var currentOwner: MicOwner? = null

    @Volatile
    private var pausedForegroundForLease = false

    fun activeOwner(): MicOwner? = currentOwner

    suspend fun acquire(
        owner: MicOwner,
        pauseForegroundIfNeeded: Boolean = owner != MicOwner.FOREGROUND_WAKE,
    ): Boolean {
        waitersCount.incrementAndGet()
        try {
            if (!mutex.tryLock()) {
                if (pauseForegroundIfNeeded && currentOwner == MicOwner.FOREGROUND_WAKE) {
                    runCatching { pauseForegroundMic() }
                    pausedForegroundForLease = true
                }
                mutex.lock()
            }
            if (pauseForegroundIfNeeded) {
                runCatching { pauseForegroundMic() }
                pausedForegroundForLease = true
            }
            currentOwner = owner
            return true
        } finally {
            waitersCount.decrementAndGet()
        }
    }

    suspend fun release(
        owner: MicOwner,
        resumeForegroundIfPaused: Boolean = true,
    ) {
        val noQueuedWaiters = waitersCount.get() == 0
        val shouldResume = pausedForegroundForLease &&
            resumeForegroundIfPaused &&
            noQueuedWaiters &&
            owner != MicOwner.FOREGROUND_WAKE
        if (currentOwner == owner) {
            currentOwner = null
        }
        if (shouldResume) {
            pausedForegroundForLease = false
        }
        if (mutex.isLocked) {
            runCatching { mutex.unlock() }
        }
        if (shouldResume) {
            runCatching { resumeForegroundMic() }
        }
    }

    suspend fun <T> withMicLease(
        owner: MicOwner,
        resumeForegroundIfPaused: Boolean = true,
        block: suspend () -> T,
    ): T {
        acquire(owner = owner, pauseForegroundIfNeeded = owner != MicOwner.FOREGROUND_WAKE)
        return try {
            block()
        } finally {
            release(owner = owner, resumeForegroundIfPaused = resumeForegroundIfPaused)
        }
    }
}
