package com.example.sol.brain

import com.example.sol.text.ColloquialLexicon

class IntentParser(
    private val extractor: EntityExtractor = EntityExtractor(),
    private val lexicon: ColloquialLexicon = ColloquialLexicon.loadDefault(),
) {
    private val matchers: List<IntentMatcher> = listOf(
        ShowCapabilitiesMatcher(),
        WebBookLearnRoleMatcher(),
        SearchWebMatcher(),
        TeachRoleVoiceMatcher(),
        ConsultRoleBookMatcher(),
        CalculatorMatcher(),
        TimerStopwatchMatcher(),
        ScheduleReminderMatcher(),
        FlashlightMatcher(),
        CalendarEventMatcher(),
        BackgroundControlMatcher(),
        LearnAliasMatcher(),
        NotesTodoMatcher(),
        LearnFactMatcher(),
        ForgetMemoryMatcher(),
        ListMemoryMatcher(),
        MediaControlMatcher(),
        SavePersonMatcher(extractor),
        SetCurrentLocationMatcher(),
        GetCurrentLocationMatcher(),
        SaveLocationMatcher(),
        MultiStepNotifyMatcher(extractor),
        CallMatcher(extractor),
        SendSmsMatcher(extractor),
        DeviceStatusMatcher(),
        AdjustVolumeMatcher(),
        OpenSettingsMatcher(),
        RunRoutineMatcher(),
        OpenAppMatcher(),
        TimeDateMatcher(),
        IdentityMatcher(),
        HelpMatcher(),
        GreetingMatcher(),
    )

    fun parseRanked(raw: String): List<IntentCandidate> {
        val ctx = MatchContext.create(raw, lexicon)
        if (ctx.tokens.isEmpty() && ctx.normalized.isBlank()) return emptyList()

        val candidates = ArrayList<IntentCandidate>(4)
        for (matcher in matchers) {
            val result = matcher.match(ctx) ?: continue
            if (result.score > 0f) {
                val cmd = result.buildCommand(ctx, result)
                candidates.add(
                    IntentCandidate(
                        matcherId = matcher.id,
                        action = matcher.action,
                        score = result.score,
                        command = cmd,
                        evidence = result.evidence,
                    )
                )
            }
        }

        val priorityMap = matchers.associate { it.id to it.priority }
        return candidates.sortedWith(
            compareByDescending<IntentCandidate> { it.score }
                .thenByDescending { priorityMap[it.matcherId] ?: 0 }
        )
    }

    fun parse(raw: String): ParsedCommand {
        val ranked = parseRanked(raw)
        if (ranked.isNotEmpty()) {
            return ranked.first().command
        }
        val ctx = MatchContext.create(raw, lexicon)
        return ParsedCommand(
            action = SolAction.UNKNOWN,
            rawText = raw,
            normalizedText = ctx.normalized,
            requireConfirmation = false,
            confidence = 0.0f,
            matchedRuleId = null,
        )
    }
}
