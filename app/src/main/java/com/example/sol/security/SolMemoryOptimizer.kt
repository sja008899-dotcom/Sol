package com.example.sol.security

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RamUsageSnapshot(
    val totalDeviceRamMb: Long = 0L,
    val availableDeviceRamMb: Long = 0L,
    val appHeapUsedMb: Long = 0L,
    val isLowMemory: Boolean = false,
    val lastTrimActionFa: String = "آماده و بهینه برای دستگاه‌های ۴ گیگابایتی",
)

class SolMemoryOptimizer(private val context: Context) {

    private val _ramSnapshot = MutableStateFlow(captureSnapshot("بارگذاری تنبل سرویس‌ها فعال است"))
    val ramSnapshot: StateFlow<RamUsageSnapshot> = _ramSnapshot.asStateFlow()

    fun refresh(note: String? = null): RamUsageSnapshot {
        val snap = captureSnapshot(note ?: _ramSnapshot.value.lastTrimActionFa)
        _ramSnapshot.value = snap
        return snap
    }

    fun handleTrimMemory(
        level: Int,
        evictRagCache: () -> Unit,
        unloadVoskIfIdle: () -> Unit,
        releaseSlmCache: () -> Unit,
    ) {
        val actionLabel = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                evictRagCache()
                unloadVoskIfIdle()
                releaseSlmCache()
                "آزادسازی خودکار کش کتاب‌ها و مدل صوتی هنگام رفتن به پس‌زمینه"
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                evictRagCache()
                releaseSlmCache()
                "آزادسازی کش موقت در وضعیت فشار حافظه RAM"
            }
            else -> null
        }
        if (actionLabel != null) {
            _ramSnapshot.value = captureSnapshot(actionLabel)
        }
    }

    fun manualTrimNow(
        evictRagCache: () -> Unit,
        unloadVoskIfIdle: () -> Unit,
        releaseSlmCache: () -> Unit,
    ): RamUsageSnapshot {
        evictRagCache()
        unloadVoskIfIdle()
        releaseSlmCache()
        return refresh("آزادسازی دستی کش کتاب، مدل صوتی و حافظه انجام شد")
    }

    private fun captureSnapshot(note: String): RamUsageSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        return RamUsageSnapshot(
            totalDeviceRamMb = memInfo.totalMem / (1024L * 1024L),
            availableDeviceRamMb = memInfo.availMem / (1024L * 1024L),
            appHeapUsedMb = usedHeapMb,
            isLowMemory = memInfo.lowMemory,
            lastTrimActionFa = note,
        )
    }
}
