package com.example.sol.runtime

import android.content.ComponentCallbacks2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Central RAM lifecycle coordinator for heavy on-device engines (Vosk ASR, Piper TTS,
 * CAM++ Speaker ID, LiteRT-LM SLM) targeting 4 GB RAM Android devices.
 *
 * Guarantees:
 * - Never executes heavy native load/unload on Main thread.
 * - Unloads idle models cleanly before loading SLM or when memory is tight.
 * - Never unloads an actively busy engine (active inference, TTS playback, or mic capture).
 * - Repeated unload calls are strictly idempotent.
 */
class ModelResourceCoordinator(
    private val availableRamMbProvider: () -> Long,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val resources = ConcurrentHashMap<SolModelKind, SolModelResource>()
    private val lastUsedMap = ConcurrentHashMap<SolModelKind, Long>()

    private val _diagnostics = MutableStateFlow<List<ModelResourceDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<ModelResourceDiagnostic>> = _diagnostics.asStateFlow()

    private val _lastFailureReasonFa = MutableStateFlow("")
    val lastFailureReasonFa: StateFlow<String> = _lastFailureReasonFa.asStateFlow()

    fun register(resource: SolModelResource) {
        resources[resource.kind] = resource
        refreshDiagnostics()
    }

    fun touchLastUsed(kind: SolModelKind) {
        lastUsedMap[kind] = clockMs()
        refreshDiagnostics()
    }

    /**
     * Prepares device RAM right before loading the heavy local SLM:
     * evicts idle Vosk ASR, Piper TTS, and Speaker ID models off the Main thread,
     * never interrupts busy engines, and fails fast if available RAM remains below [requiredRamMb].
     */
    suspend fun prepareBeforeSlmLoad(requiredRamMb: Long): ModelAcquireResult = mutex.withLock {
        val busyBlocker = evictIdlePeersLocked(excludeKind = SolModelKind.LOCAL_SLM)
        val availAfterMb = availableRamMbProvider()
        if (availAfterMb in 0 until requiredRamMb) {
            if (busyBlocker != null) {
                val msg = "موتور ${busyBlocker.labelFa} در حال اجراست و حافظهٔ آزاد (${availAfterMb}MB) برای SLM (${requiredRamMb}MB) کافی نیست."
                _lastFailureReasonFa.value = msg
                refreshDiagnostics()
                return@withLock ModelAcquireResult.Busy(busyBlocker, msg)
            }
            val msg = "حافظه RAM آزاد کافی نیست (${availAfterMb}MB موجود؛ حداقل ${requiredRamMb}MB برای SLM لازم است)."
            _lastFailureReasonFa.value = msg
            refreshDiagnostics()
            return@withLock ModelAcquireResult.InsufficientMemory(availAfterMb, requiredRamMb, msg)
        }
        refreshDiagnostics()
        ModelAcquireResult.Acquired
    }

    suspend fun acquireForUse(
        kind: SolModelKind,
        requiredRamMbOverride: Long? = null,
    ): ModelAcquireResult = mutex.withLock {
        val resource = resources[kind] ?: run {
            val msg = "منبع مدل ${kind.labelFa} ثبت نشده است."
            _lastFailureReasonFa.value = msg
            return@withLock ModelAcquireResult.Failed(msg)
        }

        if (resource.isLoaded()) {
            touchLastUsed(kind)
            return@withLock ModelAcquireResult.Acquired
        }

        val requiredMb = requiredRamMbOverride ?: resource.minRequiredRamMb
        val initialAvailMb = availableRamMbProvider()

        if (kind == SolModelKind.LOCAL_SLM || (initialAvailMb in 0 until requiredMb)) {
            val busyBlocker = evictIdlePeersLocked(excludeKind = kind)
            val availAfterMb = availableRamMbProvider()
            if (availAfterMb in 0 until requiredMb) {
                if (busyBlocker != null) {
                    val msg = "موتور ${busyBlocker.labelFa} هم‌اکنون مشغول است و آزادسازی خودکار RAM ممکن نشد."
                    _lastFailureReasonFa.value = msg
                    refreshDiagnostics()
                    return@withLock ModelAcquireResult.Busy(busyBlocker, msg)
                }
                val msg = "حافظه RAM آزاد (${availAfterMb}MB) کمتر از حداقل لازم (${requiredMb}MB) برای ${kind.labelFa} است."
                _lastFailureReasonFa.value = msg
                refreshDiagnostics()
                return@withLock ModelAcquireResult.InsufficientMemory(availAfterMb, requiredMb, msg)
            }
        }

        val loadRes = withContext(Dispatchers.IO) { resource.load() }
        return@withLock loadRes.fold(
            onSuccess = {
                _lastFailureReasonFa.value = ""
                touchLastUsed(kind)
                ModelAcquireResult.Acquired
            },
            onFailure = { err ->
                val msg = err.localizedMessage ?: "بارگذاری ${kind.labelFa} ناموفق بود."
                _lastFailureReasonFa.value = msg
                refreshDiagnostics()
                ModelAcquireResult.Failed(msg)
            },
        )
    }

    /**
     * Idempotent unload of a specific engine; refuses to disrupt an actively busy engine.
     * Returns true if an unload actually occurred, false if already unloaded or busy.
     */
    suspend fun unload(kind: SolModelKind): Boolean = mutex.withLock {
        val resource = resources[kind] ?: return@withLock false
        if (!resource.isLoaded()) {
            refreshDiagnostics()
            return@withLock false
        }
        if (resource.isBusy()) {
            refreshDiagnostics()
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            runCatching { resource.unload() }
        }
        refreshDiagnostics()
        true
    }

    suspend fun unloadAllIdle(): Int = mutex.withLock {
        var count = 0
        val sortedPeers = resources.values
            .filter { it.isLoaded() && !it.isBusy() }
            .sortedBy { lastUsedMap[it.kind] ?: 0L }
        for (res in sortedPeers) {
            withContext(Dispatchers.IO) {
                runCatching { res.unload() }
            }
            count++
        }
        refreshDiagnostics()
        count
    }

    suspend fun trimOnMemoryPressure(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            unloadAllIdle()
        }
    }

    fun snapshot(): List<ModelResourceDiagnostic> =
        SolModelKind.entries.mapNotNull { kind ->
            val res = resources[kind] ?: return@mapNotNull null
            ModelResourceDiagnostic(
                kind = kind,
                isLoaded = res.isLoaded(),
                isBusy = res.isBusy(),
                lastUsedAtMs = lastUsedMap[kind] ?: 0L,
            )
        }

    private suspend fun evictIdlePeersLocked(excludeKind: SolModelKind): SolModelKind? {
        var firstBusyPeer: SolModelKind? = null
        val candidates = resources.values
            .filter { it.kind != excludeKind && it.isLoaded() }
            .sortedBy { lastUsedMap[it.kind] ?: 0L }

        for (peer in candidates) {
            if (peer.isBusy()) {
                if (firstBusyPeer == null) firstBusyPeer = peer.kind
                continue
            }
            withContext(Dispatchers.IO) {
                runCatching { peer.unload() }
            }
        }
        return firstBusyPeer
    }

    private fun refreshDiagnostics() {
        _diagnostics.value = snapshot()
    }
}
