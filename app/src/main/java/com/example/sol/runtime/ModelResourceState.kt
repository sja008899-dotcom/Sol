package com.example.sol.runtime

enum class SolModelKind(val labelFa: String) {
    VOSK_ASR("تشخیص گفتار آفلاین Vosk"),
    PIPER_TTS("سنتز گفتار Piper"),
    SPEAKER_ID("شناسایی گوینده CAM++"),
    LOCAL_SLM("مدل زبانی محلی SLM"),
}

sealed interface ModelAcquireResult {
    data object Acquired : ModelAcquireResult

    data class InsufficientMemory(
        val availableMb: Long,
        val requiredMb: Long,
        val messageFa: String,
    ) : ModelAcquireResult

    data class Busy(
        val activeModel: SolModelKind,
        val messageFa: String,
    ) : ModelAcquireResult

    data class Failed(
        val reasonFa: String,
    ) : ModelAcquireResult
}

data class ModelResourceDiagnostic(
    val kind: SolModelKind,
    val isLoaded: Boolean,
    val isBusy: Boolean,
    val lastUsedAtMs: Long,
)

interface SolModelResource {
    val kind: SolModelKind
    val minRequiredRamMb: Long
    fun isLoaded(): Boolean
    fun isBusy(): Boolean
    suspend fun load(): Result<Unit>
    suspend fun unload()
}
