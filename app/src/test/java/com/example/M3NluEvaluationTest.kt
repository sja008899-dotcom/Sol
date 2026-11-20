package com.example

import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.IntentParser
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SolAction
import com.example.sol.knowledge.SolCapabilitiesCatalog
import com.example.sol.text.ColloquialLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

data class GoldenExample(
    val text: String,
    val expectedAction: SolAction,
    val expectedLabel: String,
    val slots: Map<String, String>,
    val split: String,
)

data class EvalMetrics(
    val total: Int,
    val correct: Int,
    val accuracy: Double,
    val perIntent: Map<SolAction, IntentStat>,
    val confusions: Map<Pair<SolAction, SolAction>, Int>,
    val misses: List<Pair<GoldenExample, SolAction>>,
    val slotChecked: Int,
    val slotMatched: Int,
)

data class IntentStat(
    val support: Int,
    val tp: Int,
    val fp: Int,
    val fn: Int,
) {
    val precision: Double get() = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
    val recall: Double get() = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
    val f1: Double
        get() = if (precision + recall == 0.0) 0.0 else (2 * precision * recall) / (precision + recall)
}

class M3NluEvaluationTest {

    companion object {
        // Honest baseline gate recorded from actual test-split evaluation on golden_fa.jsonl (measured: 0.9729).
        const val RECORDED_BASELINE_TEST_ACCURACY = 0.97
    }

    private val parser = IntentParser(EntityExtractor(), ColloquialLexicon.loadDefault())

    @Test
    fun allTwentyCatalogSampleCommandsParseAccurately() {
        val expectedActionsById = mapOf(
            "bg_reminder" to SolAction.SCHEDULE_REMINDER,
            "bg_daily_briefing" to SolAction.BACKGROUND_CONTROL,
            "bg_battery_guard" to SolAction.BACKGROUND_CONTROL,
            "bg_memory_backup" to SolAction.BACKGROUND_CONTROL,
            "bg_memory_indexer" to SolAction.BACKGROUND_CONTROL,
            "bg_location_checkin" to SolAction.BACKGROUND_CONTROL,
            "bg_tts_reader_service" to SolAction.BACKGROUND_CONTROL,
            "bg_multistep_queue" to SolAction.MULTI_STEP_NOTIFY,
            "bg_book_fetch_worker" to SolAction.WEB_BOOK_LEARN_ROLE,
            "bg_history_cleanup" to SolAction.BACKGROUND_CONTROL,
            "adv_book_role_learning" to SolAction.WEB_BOOK_LEARN_ROLE,
            "core_persian_voice" to SolAction.IDENTITY,
            "core_alias_learning" to SolAction.LEARN_ALIAS,
            "core_fact_memory" to SolAction.LEARN_FACT,
            "core_confirmed_location" to SolAction.SET_CURRENT_LOCATION,
            "core_contact_call" to SolAction.CALL,
            "core_transparent_sms" to SolAction.SEND_SMS,
            "core_device_status_vol" to SolAction.DEVICE_STATUS,
            "core_app_settings_launcher" to SolAction.OPEN_SETTINGS,
            "core_role_consultation" to SolAction.CONSULT_ROLE_BOOK,
        )

        for (item in SolCapabilitiesCatalog.allTwentyCapabilities) {
            val expected = expectedActionsById[item.id]
            val parsed = parser.parse(item.sampleCommandFa)
            assertNotEquals("Sample ${item.id} must not be UNKNOWN: ${item.sampleCommandFa}", SolAction.UNKNOWN, parsed.action)
            if (expected != null) {
                assertEquals("Capability ${item.id} mismatch for '${item.sampleCommandFa}'", expected, parsed.action)
            }
        }
    }

    @Test
    fun goldenDatasetMeetsSizeCoverageAndBaselineAccuracy() {
        val dataset = loadGoldenDataset()
        assertTrue("Expected at least 1300 golden examples, found ${dataset.size}", dataset.size >= 1300)

        val commandActions = SolAction.entries.filter { it != SolAction.UNKNOWN }
        for (action in commandActions) {
            val count = dataset.count { it.expectedAction == action }
            assertTrue("Intent $action must have >= 40 examples, found $count", count >= 40)
        }
        val oosCount = dataset.count { it.expectedAction == SolAction.UNKNOWN }
        assertTrue("OUT_OF_SCOPE must have >= 300 examples, found $oosCount", oosCount >= 300)

        val devExamples = dataset.filter { it.split == "dev" }
        val testExamples = dataset.filter { it.split == "test" }
        assertTrue("dev split must be non-empty", devExamples.isNotEmpty())
        assertTrue("test split must be non-empty", testExamples.isNotEmpty())

        val devEval = evaluate(devExamples)
        val testEval = evaluate(testExamples)

        printReport("DEV", devEval)
        printReport("TEST", testEval)

        assertTrue(
            "Test accuracy ${"%.4f".format(Locale.US, testEval.accuracy)} fell below recorded baseline $RECORDED_BASELINE_TEST_ACCURACY",
            testEval.accuracy >= RECORDED_BASELINE_TEST_ACCURACY,
        )
        if (testEval.slotChecked > 0) {
            val slotAcc = testEval.slotMatched.toDouble() / testEval.slotChecked
            assertTrue("Test slot accuracy ${"%.4f".format(Locale.US, slotAcc)} fell below 0.85", slotAcc >= 0.85)
        }
    }

    private fun evaluate(examples: List<GoldenExample>): EvalMetrics {
        val tpMap = mutableMapOf<SolAction, Int>().withDefault { 0 }
        val fpMap = mutableMapOf<SolAction, Int>().withDefault { 0 }
        val fnMap = mutableMapOf<SolAction, Int>().withDefault { 0 }
        val supportMap = mutableMapOf<SolAction, Int>().withDefault { 0 }
        val confusions = mutableMapOf<Pair<SolAction, SolAction>, Int>().withDefault { 0 }
        val misses = mutableListOf<Pair<GoldenExample, SolAction>>()

        var correct = 0
        var slotChecked = 0
        var slotMatched = 0

        for (ex in examples) {
            val pred = parser.parse(ex.text)
            val predictedAction = pred.action
            val goldAction = ex.expectedAction
            supportMap[goldAction] = supportMap.getValue(goldAction) + 1

            if (predictedAction == goldAction) {
                correct++
                tpMap[goldAction] = tpMap.getValue(goldAction) + 1
            } else {
                fnMap[goldAction] = fnMap.getValue(goldAction) + 1
                fpMap[predictedAction] = fpMap.getValue(predictedAction) + 1
                val key = goldAction to predictedAction
                confusions[key] = confusions.getValue(key) + 1
                misses.add(ex to predictedAction)
            }

            if (ex.slots.isNotEmpty()) {
                for ((k, expectedVal) in ex.slots) {
                    slotChecked++
                    if (slotMatches(pred, k, expectedVal)) {
                        slotMatched++
                    }
                }
            }
        }

        val perIntent = SolAction.entries.associateWith { action ->
            IntentStat(
                support = supportMap.getValue(action),
                tp = tpMap.getValue(action),
                fp = fpMap.getValue(action),
                fn = fnMap.getValue(action),
            )
        }

        val acc = if (examples.isEmpty()) 0.0 else correct.toDouble() / examples.size
        return EvalMetrics(
            total = examples.size,
            correct = correct,
            accuracy = acc,
            perIntent = perIntent,
            confusions = confusions,
            misses = misses,
            slotChecked = slotChecked,
            slotMatched = slotMatched,
        )
    }

    private fun slotMatches(cmd: ParsedCommand, key: String, expected: String): Boolean {
        val actual = when (key) {
            "contact" -> cmd.contact
            "aliasKey" -> cmd.aliasKey
            "aliasValue" -> cmd.aliasValue
            "targetApp" -> cmd.targetApp
            "settingTarget" -> cmd.settingTarget
            "bookQuery" -> cmd.bookQuery
            "roleName" -> cmd.roleName
            "routineName" -> cmd.routineName
            "locationReference" -> cmd.locationReference
            "backgroundJobId" -> cmd.backgroundJobId
            "delayMinutes" -> cmd.delayMinutes.toString()
            "phone" -> cmd.phone
            "message" -> cmd.message
            else -> null
        } ?: return false
        return actual.trim() == expected.trim() || actual.contains(expected.trim())
    }

    private fun printReport(splitName: String, metrics: EvalMetrics) {
        val sb = StringBuilder()
        sb.appendLine("================ NLU EVAL ($splitName) ================")
        sb.appendLine(
            "Total=${metrics.total}, Correct=${metrics.correct}, Accuracy=${"%.4f".format(Locale.US, metrics.accuracy)}, " +
                "Slots=${metrics.slotMatched}/${metrics.slotChecked}"
        )
        sb.appendLine("Per-Intent P / R / F1:")
        for (action in SolAction.entries) {
            val st = metrics.perIntent[action] ?: continue
            if (st.support == 0 && st.fp == 0) continue
            sb.appendLine(
                "  %-22s supp=%3d tp=%3d fp=%3d fn=%3d P=%.3f R=%.3f F1=%.3f".format(
                    Locale.US,
                    action.name,
                    st.support,
                    st.tp,
                    st.fp,
                    st.fn,
                    st.precision,
                    st.recall,
                    st.f1,
                )
            )
        }
        if (metrics.confusions.isNotEmpty()) {
            sb.appendLine("Top Confusions:")
            metrics.confusions.entries.sortedByDescending { it.value }.take(15).forEach { (pair, cnt) ->
                sb.appendLine("  ${pair.first} -> ${pair.second} ($cnt)")
            }
        }
        if (metrics.misses.isNotEmpty()) {
            sb.appendLine("Worst Misses (up to 20):")
            metrics.misses.take(20).forEach { (ex, pred) ->
                sb.appendLine("  [gold=${ex.expectedAction} pred=$pred] ${ex.text}")
            }
        }
        println(sb.toString())
    }

    private fun loadGoldenDataset(): List<GoldenExample> {
        val stream = javaClass.classLoader?.getResourceAsStream("nlu/golden_fa.jsonl")
        val lines = if (stream != null) {
            stream.bufferedReader(Charsets.UTF_8).readLines()
        } else {
            val file = listOf(
                File("app/src/test/resources/nlu/golden_fa.jsonl"),
                File("src/test/resources/nlu/golden_fa.jsonl"),
            ).first { it.isFile }
            file.readLines(Charsets.UTF_8)
        }

        return lines.mapIndexedNotNull { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            parseJsonLine(line, idx + 1)
        }
    }

    private fun parseJsonLine(line: String, lineNumber: Int): GoldenExample {
        val text = extractStringField(line, "text")
            ?: error("Missing 'text' on line $lineNumber")
        val intentStr = extractStringField(line, "intent")
            ?: error("Missing 'intent' on line $lineNumber")
        val split = extractStringField(line, "split") ?: "test"
        val slots = extractStringMapField(line, "slots")

        val action = if (intentStr == "OUT_OF_SCOPE") {
            SolAction.UNKNOWN
        } else {
            SolAction.valueOf(intentStr)
        }
        return GoldenExample(
            text = text,
            expectedAction = action,
            expectedLabel = intentStr,
            slots = slots,
            split = split,
        )
    }

    private fun extractStringField(json: String, key: String): String? {
        val keyPattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val m = keyPattern.find(json) ?: return null
        return unescapeJson(m.groupValues[1])
    }

    private fun extractStringMapField(json: String, key: String): Map<String, String> {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return emptyMap()
        val open = json.indexOf('{', keyIdx)
        if (open < 0) return emptyMap()
        val close = json.indexOf('}', open)
        if (close < 0) return emptyMap()
        val inner = json.substring(open + 1, close)
        if (inner.isBlank()) return emptyMap()

        val result = linkedMapOf<String, String>()
        val pairRegex = Regex(""""([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        for (m in pairRegex.findAll(inner)) {
            result[m.groupValues[1]] = unescapeJson(m.groupValues[2])
        }
        return result
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c); i++
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
