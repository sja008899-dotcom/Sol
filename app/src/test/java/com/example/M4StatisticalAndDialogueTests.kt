package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sol.brain.EntityExtractor
import com.example.sol.brain.HybridNluEngine
import com.example.sol.brain.NluDecision
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.contacts.ContactResolutionResult
import com.example.sol.contacts.ContactResolver
import com.example.sol.contacts.ResolvedContact
import com.example.sol.device.AppLauncher
import com.example.sol.device.InstalledAppShortcut
import com.example.sol.dialogue.DialogueManager
import com.example.sol.dialogue.PendingDialogueState
import com.example.sol.memory.PersonEntity
import com.example.sol.text.PersianTextNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M4StatisticalAndDialogueTests {

    @Test
    fun `intents_fa training set has zero overlap with golden_fa dev and test splits`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trainNorms = context.assets.open("nlu/intents_fa.jsonl")
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .mapNotNull { extractJsonText(it) }
            .map { PersianTextNormalizer.normalizeForMatching(it) }
            .toSet()

        assertTrue("Expected at least 250 training utterances in intents_fa.jsonl", trainNorms.size >= 250)

        val goldenStream = javaClass.classLoader?.getResourceAsStream("nlu/golden_fa.jsonl")
            ?: File("app/src/test/resources/nlu/golden_fa.jsonl")
                .takeIf { it.isFile }?.inputStream()
            ?: File("src/test/resources/nlu/golden_fa.jsonl").inputStream()

        val goldenNorms = goldenStream.bufferedReader(Charsets.UTF_8)
            .readLines()
            .mapNotNull { extractJsonText(it) }
            .map { PersianTextNormalizer.normalizeForMatching(it) }
            .toSet()

        val overlaps = trainNorms.intersect(goldenNorms)
        assertTrue(
            "Found ${overlaps.size} leaked normalized utterances between intents_fa.jsonl and golden_fa.jsonl: ${overlaps.take(5)}",
            overlaps.isEmpty(),
        )
    }

    @Test
    fun `statistical classifier builds under 200ms and hybrid nlu evaluates golden dev and test splits`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        c.statisticalClassifier.ensureInitialized { emptyList() }
        val buildMs = c.statisticalClassifier.lastBuildDurationMs
        assertTrue("Statistical classifier build time ${buildMs}ms exceeded 200ms target", buildMs < 200L)

        val goldenLines = (javaClass.classLoader?.getResourceAsStream("nlu/golden_fa.jsonl")
            ?: File("src/test/resources/nlu/golden_fa.jsonl").inputStream())
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val devEntries = mutableListOf<Pair<String, SolAction>>()
        val testEntries = mutableListOf<Pair<String, SolAction>>()
        for (line in goldenLines) {
            val text = extractJsonText(line) ?: continue
            val intentStr = extractJsonField(line, "intent") ?: continue
            val split = extractJsonField(line, "split") ?: "test"
            val action = if (intentStr == "OUT_OF_SCOPE") SolAction.UNKNOWN else SolAction.valueOf(intentStr)
            if (split == "dev") devEntries.add(text to action) else testEntries.add(text to action)
        }

        fun evalSplit(name: String, items: List<Pair<String, SolAction>>): Double {
            var directMatch = 0
            var clarifiedTotal = 0
            var top2ContainsGold = 0
            var oosRouted = 0
            val misses = mutableListOf<String>()

            for ((text, gold) in items) {
                when (val d = c.hybridNluEngine.evaluateSync(text)) {
                    is NluDecision.Execute -> {
                        if (d.command.action == gold) {
                            directMatch++
                        } else {
                            misses.add("[Execute gold=$gold pred=${d.command.action}(${d.source},${"%.2f".format(Locale.US, d.topScore)})] $text")
                        }
                    }
                    is NluDecision.ClarifyTopTwo -> {
                        clarifiedTotal++
                        if (d.options.any { it.action == gold }) {
                            top2ContainsGold++
                        } else {
                            misses.add("[Clarify gold=$gold opts=${d.options.map { it.action }}] $text")
                        }
                    }
                    is NluDecision.LowConfidence -> {
                        if (gold == SolAction.UNKNOWN) {
                            directMatch++
                            oosRouted++
                        } else {
                            misses.add("[LowConf gold=$gold score=${"%.2f".format(Locale.US, d.topScore)}] $text")
                        }
                    }
                }
            }
            val strictAcc = directMatch.toDouble() / items.size
            val top2WithClarifyAcc = (directMatch + top2ContainsGold).toDouble() / items.size
            println(
                "HYBRID NLU $name: total=${items.size}, strictMatch=$directMatch (${"%.4f".format(Locale.US, strictAcc)}), " +
                    "clarified=$clarifiedTotal (top2Hit=$top2ContainsGold), strictOrClarify=${"%.4f".format(Locale.US, top2WithClarifyAcc)}, buildMs=$buildMs"
            )
            misses.take(12).forEach { println("  MISS: $it") }
            return strictAcc
        }

        val devAcc = evalSplit("DEV", devEntries)
        val testAcc = evalSplit("TEST", testEntries)

        assertTrue("Hybrid DEV strict accuracy ${"%.4f".format(Locale.US, devAcc)} below 0.90", devAcc >= 0.90)
        assertTrue("Hybrid TEST strict accuracy ${"%.4f".format(Locale.US, testAcc)} below 0.95", testAcc >= 0.95)
    }

    @Test
    fun `slot filling handles persian dates phones fuzzy contacts and cached apps`() {
        val nowMillis = 1_742_544_000_000L // Deterministic anchor

        // 1. Relative and weekly Jalali phrases
        val p1 = JalaliCalendar.parseDateTimeSlot("۱۰ دقیقه دیگه", nowMillis)
        assertNotNull(p1)
        assertEquals(10, ((p1!!.triggerAtMillis - nowMillis) / 60_000L).toInt())

        val p2 = JalaliCalendar.parseDateTimeSlot("نیم ساعت دیگه", nowMillis)
        assertNotNull(p2)
        assertEquals(30, ((p2!!.triggerAtMillis - nowMillis) / 60_000L).toInt())

        val p3 = JalaliCalendar.parseDateTimeSlot("پس فردا", nowMillis)
        assertNotNull(p3)
        assertTrue(p3!!.triggerAtMillis > nowMillis)

        assertNotNull(JalaliCalendar.parseDateTimeSlot("آخر هفته", nowMillis))
        assertNotNull(JalaliCalendar.parseDateTimeSlot("شنبه آینده", nowMillis))
        assertNotNull(JalaliCalendar.parseDateTimeSlot("هفته آینده", nowMillis))
        assertEquals(Triple(2025, 3, 21), JalaliCalendar.jalaliToGregorian(1404, 1, 1))
        assertEquals(1404, JalaliCalendar.gregorianToJalali(2025, 3, 21).year)

        // 2. Iran E.164 phone normalization and dummy rejection
        val extractor = EntityExtractor()
        val valid1 = extractor.normalizeIranPhoneToE164("۰۹۱۲ ۳۴۵ ۶۷۸۹")
        assertTrue(valid1.isValid)
        assertEquals("+989123456789", valid1.e164)

        val valid2 = extractor.normalizeIranPhoneToE164("00989123456789")
        assertTrue(valid2.isValid)
        assertEquals("+989123456789", valid2.e164)

        assertFalse(extractor.normalizeIranPhoneToE164("09000000000").isValid)
        assertFalse(extractor.normalizeIranPhoneToE164("09123").isValid)

        // 3. Contact resolution: relation alias, ambiguity, bounded fuzzy (len >= 4 only)
        val contactResolver = ContactResolver()
        val resolvedContacts = listOf(
            ResolvedContact(id = "1", name = "زهرا احمدی", phone = "09121112233", source = com.example.sol.contacts.ContactSource.MEMORY, relation = "مادر"),
            ResolvedContact(id = "2", name = "علی رضایی", phone = "09122223344", source = com.example.sol.contacts.ContactSource.MEMORY, relation = "همکار"),
            ResolvedContact(id = "3", name = "علی کریمی", phone = "09123334455", source = com.example.sol.contacts.ContactSource.MEMORY, relation = "دوست"),
            ResolvedContact(id = "4", name = "محمد حسینی", phone = "09124445566", source = com.example.sol.contacts.ContactSource.MEMORY, relation = ""),
        )
        val momRes = contactResolver.resolveStructured("مامان", resolvedContacts)
        assertTrue(momRes is ContactResolutionResult.Resolved)
        assertEquals("زهرا احمدی", (momRes as ContactResolutionResult.Resolved).contact.name)

        val aliRes = contactResolver.resolveStructured("علی", resolvedContacts)
        assertTrue(aliRes is ContactResolutionResult.Ambiguous)
        assertEquals(2, (aliRes as ContactResolutionResult.Ambiguous).candidates.size)

        val fuzzyOk = contactResolver.resolveStructured("محمذ", resolvedContacts)
        assertTrue(fuzzyOk is ContactResolutionResult.Resolved)
        assertEquals("محمد حسینی", (fuzzyOk as ContactResolutionResult.Resolved).contact.name)

        // Length < 4 must NOT fuzzy-match false hits
        val shortNoFalseFuzzy = contactResolver.resolveStructured("علس", resolvedContacts)
        assertTrue(shortNoFalseFuzzy is ContactResolutionResult.NotFound)

        // 4. AppLauncher synonym & bounded fuzzy matching
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appLauncher = AppLauncher(context)
        val fakeApps = listOf(
            InstalledAppShortcut("WhatsApp", "com.whatsapp"),
            InstalledAppShortcut("Telegram", "org.telegram.messenger"),
            InstalledAppShortcut("Calculator", "com.android.calculator2"),
        )
        assertEquals("org.telegram.messenger", appLauncher.findBestAppMatch("تلگرام", fakeApps)?.packageName)
        assertEquals("com.whatsapp", appLauncher.findBestAppMatch("WhatsAp", fakeApps)?.packageName)
    }

    @Test
    fun `dialogue manager handles slots pronouns disambiguation confirmation and user teaching`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        // Clean state
        c.userUtteranceRepository.clearAll()
        c.statisticalClassifier.syncUserExamples(emptyList())
        c.dialogueManager.clearHistory()
        c.personRepository.savePerson(name = "علی رضایی", phone = "09121112233", relation = "همکار")
        c.personRepository.savePerson(name = "علی کریمی", phone = "09123334455", relation = "دوست")
        c.memoryRepository.remember(key = "alias:شرکت قدیمی", value = "آدرس قبلی", kind = "alias")

        // 1. Missing slot: reminder without time asks ONCE, then fills with next turn
        val remTurn1 = c.commandBrain.processDialogue("یادم بنداز دارو بخورم")
        assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.MissingSlot)
        assertTrue(remTurn1.replyText.contains("زمان"))

        val remTurn2 = c.commandBrain.processDialogue("۱۰ دقیقه دیگه")
        assertEquals(null, c.dialogueManager.currentPendingState())
        assertTrue(remTurn2.replyText.contains("تنظیم شد") || remTurn2.replyText.contains("یادآور"))

        // 2. Ambiguous contact disambiguation -> pick #2 -> asks missing SMS body -> confirm gate
        val smsTurn1 = c.commandBrain.processDialogue("به علی پیام بده")
        assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingContactChoice)
        assertTrue(smsTurn1.actionChoices.any { it.labelFa.contains("علی کریمی") })

        val smsTurn2 = c.commandBrain.processDialogue("۲")
        assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.MissingSlot)
        assertTrue(smsTurn2.replyText.contains("علی کریمی"))

        val smsTurn3 = c.commandBrain.processDialogue("رسیدم شرکت")
        assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
        assertTrue(smsTurn3.pendingTask is PendingSolTask.Communicate)

        // Cancel cleanly
        val cancelTurn = c.commandBrain.processDialogue("لغو")
        assertEquals(null, c.dialogueManager.currentPendingState())
        assertTrue(cancelTurn.replyText.contains("لغو شد"))

        // 3. Repeat command repeats Sol's last reply
        val repeatTurn = c.commandBrain.processDialogue("دوباره بگو")
        assertTrue(repeatTurn.replyText.contains("لغو"))

        // 4. Pronoun resolution over recent turns
        c.commandBrain.processDialogue("شماره علی کریمی چنده؟")
        val pronounTurn = c.commandBrain.processDialogue("به او پیام بده سلام")
        assertEquals("علی کریمی", pronounTurn.parsedCommand.contact)

        // 5. FORGET_MEMORY centralized confirmation: does NOT delete until confirmed
        val forgetPrompt = c.commandBrain.processDialogue("شرکت قدیمی را فراموش کن")
        assertTrue(c.dialogueManager.currentPendingState() is PendingDialogueState.AwaitingConfirmation)
        assertTrue(forgetPrompt.replyText.contains("مطمئن"))
        assertNotNull(c.memoryRepository.getAllOnce().firstOrNull { it.keyText.contains("شرکت قدیمی") })

        val forgetConfirmed = c.commandBrain.processDialogue("بله")
        assertEquals(null, c.dialogueManager.currentPendingState())
        assertTrue(forgetConfirmed.replyText.contains("حذف شد") || forgetConfirmed.replyText.contains("فراموش"))
        assertEquals(null, c.memoryRepository.getAllOnce().firstOrNull { it.keyText.contains("شرکت قدیمی") })

        // 6. User teaching correction persists to Room + boosts statistical classification + can be forgotten
        val novelText = "مقدار شارژ و وضعیت کلی گوشی الان چطور است"
        c.commandBrain.processDialogue(novelText)
        val teachTurn = c.commandBrain.processDialogue("نه، منظورم وضعیت باتری بود")
        assertTrue(teachTurn.taughtExampleSaved)

        val savedExamples = c.userUtteranceRepository.getAllOnce()
        assertTrue(savedExamples.any { it.intent == SolAction.DEVICE_STATUS.name })

        // Clean forget of taught example removes it from Room & classifier
        val savedId = savedExamples.first { it.intent == SolAction.DEVICE_STATUS.name }.id
        c.userUtteranceRepository.deleteExample(savedId)
        c.statisticalClassifier.removeUserExample(savedId)
        assertFalse(c.userUtteranceRepository.getAllOnce().any { it.id == savedId })
    }

    private fun extractJsonText(line: String): String? = extractJsonField(line, "text")

    private fun extractJsonField(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
        return m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
    }
}
