package com.example

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.sol.calendar.BootReceiver
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.memory.ScheduledAlarmEntity
import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class M8CoreAndCalendarTests {

    @Test
    fun `table-driven PersianTextNormalizer covers digits ZWNJ Arabic variants tatweel and suffixes`() {
        data class NormCase(
            val raw: String,
            val expectedNormalized: String,
            val expectedCleanDisplay: String,
        )

        val table = listOf(
            NormCase(
                raw = "سَلَامْ كِتَابِ شازدۀ كوچولو",
                expectedNormalized = "سلام کتاب شازده کوچولو",
                expectedCleanDisplay = "سَلَامْ کِتَابِ شازدۀ کوچولو",
            ),
            NormCase(
                raw = "ساعت ۱۴:۳۰ می‌روم كلاس",
                expectedNormalized = "ساعت 14:30 می روم کلاس",
                expectedCleanDisplay = "ساعت 14:30 می‌روم کلاس",
            ),
            NormCase(
                raw = "شمارهٔ ١٢٣٬۴۵۶ و تـــــست",
                expectedNormalized = "شماره 123٬456 و تست",
                expectedCleanDisplay = "شمارهٔ 123٬456 و تـــــست",
            ),
            NormCase(
                raw = "به علي زنگ بزن؟!",
                expectedNormalized = "به علی زنگ بزن?!",
                expectedCleanDisplay = "به علی زنگ بزن؟!",
            ),
        )

        for (case in table) {
            assertEquals(
                "normalizeForMatching failed for '${case.raw}'",
                case.expectedNormalized,
                PersianTextNormalizer.normalizeForMatching(case.raw),
            )
            assertEquals(
                "cleanDisplayText failed for '${case.raw}'",
                case.expectedCleanDisplay,
                PersianTextNormalizer.cleanDisplayText(case.raw),
            )
        }

        val suffixTable = listOf(
            "کتابهایشان" to "کتاب",
            "یادآورها" to "یادآور",
            "گوشی" to "گوشی",
            "علی" to "علی",
            "ماهی" to "ماهی",
            "خوب" to "خوب",
        )
        for ((input, expectedStem) in suffixTable) {
            assertEquals("stripSuffix($input)", expectedStem, PersianTextNormalizer.stripSuffix(input))
        }
    }

    @Test
    fun `table-driven PersianNumbers covers spoken words fractions durations and TTS prep`() {
        val wordToNumberTable = listOf(
            "صفر" to 0.0,
            "یک" to 1.0,
            "پانزده" to 15.0,
            "بیست و پنج" to 25.0,
            "هزار و سیصد و چهل" to 1340.0,
            "نیم" to 0.5,
            "ربع" to 0.25,
            "سه ربع" to 0.75,
            "یک و نیم" to 1.5,
            "دو میلیون و سیصد" to 2_000_300.0,
            "کلمه نامربوط" to null,
        )
        for ((words, expected) in wordToNumberTable) {
            val actual = PersianNumbers.wordsToNumber(words)
            if (expected == null) {
                assertNull("Expected null for '$words'", actual)
            } else {
                assertNotNull("Expected non-null for '$words'", actual)
                assertEquals("wordsToNumber('$words')", expected, actual!!, 1e-4)
            }
        }

        val numberToWordTable = listOf(
            0L to "صفر",
            7L to "هفت",
            25L to "بیست و پنج",
            1340L to "هزار و سیصد و چهل",
        )
        for ((num, expectedWords) in numberToWordTable) {
            assertEquals("numberToWords($num)", expectedWords, PersianNumbers.numberToWords(num))
        }

        val durationTable = listOf(
            "نیم ساعت دیگر" to 30L,
            "سه ربع ساعت دیگر" to 45L,
            "یک ساعت و نیم دیگر" to 90L,
            "بیست و پنج دقیقه دیگه" to 25L,
            "بدون عدد" to null,
        )
        for ((phrase, expectedMinutes) in durationTable) {
            assertEquals(
                "parseSpokenDurationMinutes('$phrase')",
                expectedMinutes,
                PersianNumbers.parseSpokenDurationMinutes(phrase),
            )
        }

        val ttsPrepared = PersianNumbers.prepareTextForTts("ساعت 09:30 و 50% و 1404/01/02 • 09123456789")
        assertTrue(ttsPrepared.contains("نه و نیم"))
        assertTrue(ttsPrepared.contains("پنجاه درصد"))
        assertTrue(ttsPrepared.contains("دو فروردین هزار و چهارصد و چهار"))
        assertTrue(ttsPrepared.contains("09123456789"))
    }

    @Test
    fun `JalaliCalendar verifies leap years Esfand 29-30 boundaries month lengths and 200-year round-trip`() {
        val leapYears = listOf(1399, 1403, 1408)
        for (y in leapYears) {
            assertTrue("Expected Jalali year $y to be leap", JalaliCalendar.isJalaliLeapYear(y))
            assertEquals("Esfand in leap year $y must have 30 days", 30, JalaliCalendar.daysInJalaliMonth(y, 12))
        }

        val nonLeapYears = listOf(1400, 1401, 1402, 1404, 1405, 1406, 1407)
        for (y in nonLeapYears) {
            assertFalse("Expected Jalali year $y NOT to be leap", JalaliCalendar.isJalaliLeapYear(y))
            assertEquals("Esfand in non-leap year $y must have 29 days", 29, JalaliCalendar.daysInJalaliMonth(y, 12))
        }

        for (m in 1..6) assertEquals(31, JalaliCalendar.daysInJalaliMonth(1404, m))
        for (m in 7..11) assertEquals(30, JalaliCalendar.daysInJalaliMonth(1404, m))

        // Esfand 29/30 and Farvardin 1 boundaries around 1403/1404/1405
        val boundaryPairs = listOf(
            Triple(1403, 12, 30) to Triple(2025, 3, 20),
            Triple(1404, 1, 1) to Triple(2025, 3, 21),
            Triple(1404, 12, 29) to Triple(2026, 3, 20),
            Triple(1405, 1, 1) to Triple(2026, 3, 21),
        )
        for ((jalali, gregorian) in boundaryPairs) {
            val g = JalaliCalendar.jalaliToGregorian(jalali.first, jalali.second, jalali.third)
            assertEquals("jalaliToGregorian($jalali)", gregorian, g)
            val j = JalaliCalendar.gregorianToJalali(g.first, g.second, g.third)
            assertEquals("gregorianToJalali($g)", jalali, Triple(j.year, j.month, j.day))
        }

        // Exhaustive daily round-trip bijection over 1300..1500 (~73,400 days)
        var verifiedDays = 0
        for (jy in 1300..1500) {
            for (jm in 1..12) {
                val maxDay = JalaliCalendar.daysInJalaliMonth(jy, jm)
                for (jd in 1..maxDay) {
                    val (gy, gm, gd) = JalaliCalendar.jalaliToGregorian(jy, jm, jd)
                    val roundTrip = JalaliCalendar.gregorianToJalali(gy, gm, gd)
                    assertEquals(
                        "Round-trip failed for $jy/$jm/$jd -> $gy/$gm/$gd",
                        Triple(jy, jm, jd),
                        Triple(roundTrip.year, roundTrip.month, roundTrip.day),
                    )
                    verifiedDays++
                }
            }
        }
        assertTrue("Expected >73,000 days verified, got $verifiedDays", verifiedDays > 73_000)
    }

    @Test
    fun `BootReceiver re-registers future alarms in ShadowAlarmManager after ACTION_BOOT_COMPLETED`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val repo = app.container.knowledgeRepository
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = Shadows.shadowOf(am)

        // Reset DB and existing alarms cleanly
        repo.getAllAlarmsOnce().forEach { repo.deleteScheduledAlarm(it.id) }
        val beforeCount = shadowAlarm.scheduledAlarms.size

        val now = System.currentTimeMillis()
        val future1 = repo.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = "یادآور اول بعد از ریستارت",
                messageBody = "پیام اول",
                triggerAtMillis = now + 180_000L,
                jalaliLabel = "۱۴۰۴/۰۱/۰۵ - ۱۰:۰۰",
                isExact = true,
                isTriggered = false,
            )
        )
        val future2 = repo.insertAlarmWithUniqueRequestCode(
            ScheduledAlarmEntity(
                requestCode = 0,
                titleFa = "یادآور دوم بعد از ریستارت",
                messageBody = "پیام دوم",
                triggerAtMillis = now + 360_000L,
                jalaliLabel = "۱۴۰۴/۰۱/۰۵ - ۱۰:۰۳",
                isExact = true,
                isTriggered = false,
            )
        )
        assertNotEquals(future1.requestCode, future2.requestCode)

        // Trigger BOOT_COMPLETED broadcast and verify alarms are scheduled into ShadowAlarmManager
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        var scheduledTriggerTimes = emptySet<Long>()
        for (attempt in 0 until 25) {
            scheduledTriggerTimes = shadowAlarm.scheduledAlarms.map { it.triggerAtTime }.toSet()
            if (scheduledTriggerTimes.contains(future1.triggerAtMillis) &&
                scheduledTriggerTimes.contains(future2.triggerAtMillis)
            ) {
                break
            }
            delay(100)
        }

        val afterCount = shadowAlarm.scheduledAlarms.size
        assertTrue(
            "Expected BootReceiver to schedule 2 future alarms (before=$beforeCount, after=$afterCount)",
            afterCount >= beforeCount + 2,
        )
        assertTrue(scheduledTriggerTimes.contains(future1.triggerAtMillis))
        assertTrue(scheduledTriggerTimes.contains(future2.triggerAtMillis))
    }

    @Test
    fun `command evaluation latency benchmark meets 150ms p95 budget`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as SolApplication
        val c = app.container

        val sampleCommands = listOf(
            "سلام خوبی؟",
            "امروز چندم برج است؟",
            "تایمر ۱۰ دقیقه",
            "۲۵ درصد ۸۰۰ چند میشه؟",
            "وضعیت گوشی و باتری چطوره؟",
            "تنظیمات وای‌فای را باز کن",
            "یاد بگیر جلسه هفتگی شنبه ساعت ۱۰ صبح است",
            "به علی پیام بده ساعت ۵ جلسه داریم",
            "چراغ قوه را روشن کن",
            "فهرست قابلیت‌ها",
        )

        // Warm up lazy initialization & classifier once
        c.hybridNluEngine.evaluateSync(sampleCommands.first())

        val latenciesMs = mutableListOf<Double>()
        repeat(5) {
            for (cmd in sampleCommands) {
                val elapsedNs = measureNanoTime {
                    val result = c.commandBrain.processDialogue(cmd)
                    assertTrue(result.replyText.isNotBlank() || result.pendingTask != null)
                    c.dialogueManager.clearPendingState()
                }
                latenciesMs.add(elapsedNs / 1_000_000.0)
            }
        }
        latenciesMs.sort()
        val p50 = latenciesMs[(latenciesMs.size * 0.50).toInt()]
        val p95 = latenciesMs[((latenciesMs.size - 1) * 0.95).toInt()]
        val maxMs = latenciesMs.last()
        println(
            String.format(
                Locale.US,
                "SOL COMMAND BENCHMARK (n=%d): p50=%.2fms, p95=%.2fms, max=%.2fms",
                latenciesMs.size,
                p50,
                p95,
                maxMs,
            )
        )
        assertTrue("Expected command latency p95 < 150ms, measured ${p95}ms", p95 < 150.0)
    }
}
