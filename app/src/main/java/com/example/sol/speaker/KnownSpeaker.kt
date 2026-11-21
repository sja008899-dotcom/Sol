package com.example.sol.speaker

/**
 * Non-owner known speaker enrolled locally (e.g. family member or colleague).
 * Known speakers may chat conversationally, while privileged device/action commands require owner verification when N5 is active.
 */
data class KnownSpeaker(
    val id: Long,
    val displayName: String,
    val relation: String? = null,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownSpeaker) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
