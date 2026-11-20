package com.example.sol.brain

data class NluConfig(
    val minCharNgram: Int = 2,
    val maxCharNgram: Int = 4,
    val kNearest: Int = 5,
    val knnWeight: Float = 0.65f,
    val centroidWeight: Float = 0.35f,
    val ruleConfidenceHigh: Float = 0.85f,
    val ruleConfidenceMedium: Float = 0.60f,
    val highScoreThreshold: Float = 0.62f,
    val highMarginThreshold: Float = 0.10f,
    val mediumScoreThreshold: Float = 0.40f,
    val userExampleBoost: Float = 2.2f,
)
