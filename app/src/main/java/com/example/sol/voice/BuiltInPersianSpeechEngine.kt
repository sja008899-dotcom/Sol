package com.example.sol.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.VoicePersona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Self-contained 100% offline built-in Persian speech synthesizer using AudioTrack +
 * Persian G2P (numbers, English tech words, core Persian lexicon, and syllable rules) +
 * a 3-formant cascade Klatt-style resonator engine.
 *
 * Ensures Sol ALWAYS speaks Persian audibly even on phones where Android's system TTS
 * does not have a Persian voice pack installed.
 */
class BuiltInPersianSpeechEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeFlag = AtomicBoolean(false)

    @Volatile
    private var playJob: Job? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        persona: VoicePersona = VoicePersona.FEMALE_CALM,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ) {
        stop()
        val normalizedWords = PersianSpeechTextNormalizer.prepareWords(text)
        if (normalizedWords.isEmpty()) {
            onDone()
            return
        }

        val phonemes = PersianG2P.wordsToPhonemes(normalizedWords)
        if (phonemes.isEmpty()) {
            onDone()
            return
        }

        activeFlag.set(true)
        onStart()

        playJob = scope.launch {
            var track: AudioTrack? = null
            try {
                val basePitchHz = when (persona) {
                    VoicePersona.FEMALE_CALM -> 192f
                    VoicePersona.MALE_FORMAL -> 114f
                    VoicePersona.NEUTRAL_FRIENDLY -> 154f
                }
                val isQuestion = text.trim().endsWith("؟") || text.trim().endsWith("?")
                val pcm = FormantSynthesizer.renderPhonemes(
                    phonemes = phonemes,
                    baseF0 = basePitchHz,
                    rate = speechRate.coerceIn(0.65f, 1.45f),
                    risingIntonation = isQuestion,
                )
                if (!activeFlag.get() || pcm.isEmpty()) return@launch

                val minBufBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4096)

                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufBytes * 2)
                    .build()

                activeTrack = track
                if (track.state != AudioTrack.STATE_INITIALIZED) return@launch
                track.play()

                var offset = 0
                val chunk = 2048
                while (activeFlag.get() && offset < pcm.size) {
                    val count = minOf(chunk, pcm.size - offset)
                    val written = track.write(pcm, offset, count)
                    if (written <= 0) break
                    offset += written
                }
                if (activeFlag.get()) {
                    // Drain final buffer smoothly
                    runCatching { track.stop() }
                }
            } catch (_: Throwable) {
                // Safe fallback: never crash UI thread if audio device is busy
            } finally {
                activeFlag.set(false)
                runCatching {
                    track?.pause()
                    track?.flush()
                    track?.release()
                }
                if (activeTrack === track) activeTrack = null
                onDone()
            }
        }
    }

    fun stop() {
        activeFlag.set(false)
        playJob?.cancel()
        playJob = null
        runCatching {
            activeTrack?.pause()
            activeTrack?.flush()
            activeTrack?.release()
        }
        activeTrack = null
    }

    companion object {
        const val SAMPLE_RATE = 22050
    }
}

internal object PersianSpeechTextNormalizer {

    private val englishPhoneticReplacements = listOf(
        "android" to "اندروید",
        "vosk" to "واسک",
        "pdf" to "پی دی اف",
        "epub" to "ای پاب",
        "html" to "اچ تی ام ال",
        "bm25" to "بی ام بیست و پنج",
        "ram" to "رم",
        "wi-fi" to "وای فای",
        "wifi" to "وای فای",
        "gps" to "جی پی اس",
        "sms" to "اس ام اس",
        "slm" to "اس ال ام",
        "rag" to "رگ",
        "alarmmanager" to "آلارم منیجر",
        "doze" to "دوز",
        "room" to "روم",
        "solbackup" to "سول بکاپ",
        "sol" to "سول",
        "v2" to "نسخه دو",
        "ok" to "اوکی",
    )

    fun prepareWords(rawText: String): List<String> {
        var text = rawText
            .replace(Regex("https?://\\S+"), " لینک اینترنتی ")
            .replace("•", " ")
            .replace("*", " ")
            .replace("#", " ")
            .replace("«", " ")
            .replace("»", " ")
            .replace("(", " ، ")
            .replace(")", " ")
            .replace("[", " ")
            .replace("]", " ")
            .replace("؟", " ، ")
            .replace("?", " ، ")
            .replace("!", " ، ")
            .replace(".", " ، ")
            .replace(":", " ، ")
            .replace("\n", " ، ")

        text = PersianNormalizer.cleanText(text)
        englishPhoneticReplacements.forEach { (eng, fa) ->
            text = text.replace(Regex("\\b$eng\\b", RegexOption.IGNORE_CASE), fa)
        }

        // Convert numbers (e.g., 1404, 25, 9) into spoken Persian words
        text = Regex("\\d+").replace(text) { m ->
            val n = m.value.toLongOrNull() ?: return@replace m.value
            numberToPersianWords(n)
        }

        // Strip any remaining non-Persian/non-punctuation characters
        text = text.replace(Regex("[^آ-یءئؤةکگچپژ،\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return emptyList()
        // Cap spoken length per utterance for 4GB RAM & pleasant UX (~180 words max)
        return text.split(' ').filter { it.isNotBlank() }.take(180)
    }

    fun numberToPersianWords(num: Long): String {
        if (num == 0L) return "صفر"
        if (num < 0L) return "منفی " + numberToPersianWords(-num)
        if (num > 999999L) return num.toString().map { digitWord(it) }.joinToString(" ")

        val ones = arrayOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
        val teens = arrayOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
        val tens = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
        val hundreds = arrayOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")

        fun underThousand(n: Int): String {
            val h = n / 100
            val rem = n % 100
            val parts = mutableListOf<String>()
            if (h > 0) parts += hundreds[h]
            if (rem in 10..19) {
                parts += teens[rem - 10]
            } else {
                val t = rem / 10
                val o = rem % 10
                if (t > 0) parts += tens[t]
                if (o > 0) parts += ones[o]
            }
            return parts.joinToString(" و ")
        }

        val thousands = (num / 1000L).toInt()
        val rem = (num % 1000L).toInt()
        val parts = mutableListOf<String>()
        if (thousands > 0) {
            parts += if (thousands == 1) "هزار" else "${underThousand(thousands)} هزار"
        }
        if (rem > 0) {
            parts += underThousand(rem)
        }
        return parts.joinToString(" و ")
    }

    private fun digitWord(c: Char): String = when (c) {
        '0' -> "صفر"
        '1' -> "یک"
        '2' -> "دو"
        '3' -> "سه"
        '4' -> "چهار"
        '5' -> "پنج"
        '6' -> "شش"
        '7' -> "هفت"
        '8' -> "هشت"
        '9' -> "نه"
        else -> ""
    }
}

internal enum class PhKind { VOWEL, NASAL, LIQUID, FRICATIVE_VOICED, FRICATIVE_UNVOICED, STOP_VOICED, STOP_UNVOICED, PAUSE }

internal data class PhonemeUnit(
    val code: String,
    val kind: PhKind,
    val durationMs: Int,
    val f1: Float,
    val f2: Float,
    val f3: Float,
)

internal object PersianG2P {

    private val phonemeSpecs = mapOf(
        // Vowels
        "aa" to PhonemeUnit("aa", PhKind.VOWEL, 125, 720f, 1140f, 2500f),
        "a" to PhonemeUnit("a", PhKind.VOWEL, 85, 650f, 1560f, 2550f),
        "e" to PhonemeUnit("e", PhKind.VOWEL, 85, 480f, 1940f, 2600f),
        "i" to PhonemeUnit("i", PhKind.VOWEL, 110, 310f, 2280f, 2980f),
        "o" to PhonemeUnit("o", PhKind.VOWEL, 90, 450f, 920f, 2400f),
        "u" to PhonemeUnit("u", PhKind.VOWEL, 115, 320f, 760f, 2300f),
        // Nasals & Liquids
        "m" to PhonemeUnit("m", PhKind.NASAL, 72, 280f, 950f, 2200f),
        "n" to PhonemeUnit("n", PhKind.NASAL, 70, 290f, 1500f, 2500f),
        "l" to PhonemeUnit("l", PhKind.LIQUID, 68, 360f, 1300f, 2650f),
        "r" to PhonemeUnit("r", PhKind.LIQUID, 65, 420f, 1380f, 2100f),
        "y" to PhonemeUnit("y", PhKind.LIQUID, 65, 310f, 2200f, 2900f),
        "v" to PhonemeUnit("v", PhKind.FRICATIVE_VOICED, 70, 340f, 1050f, 2350f),
        // Fricatives
        "s" to PhonemeUnit("s", PhKind.FRICATIVE_UNVOICED, 82, 400f, 3800f, 5200f),
        "sh" to PhonemeUnit("sh", PhKind.FRICATIVE_UNVOICED, 88, 380f, 2400f, 3400f),
        "f" to PhonemeUnit("f", PhKind.FRICATIVE_UNVOICED, 75, 450f, 2100f, 3900f),
        "kh" to PhonemeUnit("kh", PhKind.FRICATIVE_UNVOICED, 82, 550f, 1450f, 2700f),
        "h" to PhonemeUnit("h", PhKind.FRICATIVE_UNVOICED, 62, 600f, 1600f, 2600f),
        "z" to PhonemeUnit("z", PhKind.FRICATIVE_VOICED, 76, 360f, 3200f, 4600f),
        "zh" to PhonemeUnit("zh", PhKind.FRICATIVE_VOICED, 80, 360f, 2200f, 3200f),
        "gh" to PhonemeUnit("gh", PhKind.FRICATIVE_VOICED, 74, 480f, 1200f, 2400f),
        // Stops
        "p" to PhonemeUnit("p", PhKind.STOP_UNVOICED, 72, 350f, 1000f, 2200f),
        "t" to PhonemeUnit("t", PhKind.STOP_UNVOICED, 72, 350f, 1800f, 2750f),
        "k" to PhonemeUnit("k", PhKind.STOP_UNVOICED, 75, 350f, 1950f, 2700f),
        "ch" to PhonemeUnit("ch", PhKind.STOP_UNVOICED, 85, 360f, 2300f, 3200f),
        "b" to PhonemeUnit("b", PhKind.STOP_VOICED, 68, 320f, 980f, 2200f),
        "d" to PhonemeUnit("d", PhKind.STOP_VOICED, 68, 320f, 1750f, 2700f),
        "g" to PhonemeUnit("g", PhKind.STOP_VOICED, 70, 320f, 1900f, 2650f),
        "j" to PhonemeUnit("j", PhKind.STOP_VOICED, 78, 340f, 2200f, 3000f),
        // Pauses
        "pause_short" to PhonemeUnit("pause_short", PhKind.PAUSE, 95, 0f, 0f, 0f),
        "pause_word" to PhonemeUnit("pause_word", PhKind.PAUSE, 35, 0f, 0f, 0f),
    )

    // Core Persian lexicon with exact vowelization so Sol's voice replies are clear
    private val lexicon: Map<String, List<String>> = mapOf(
        "سلام" to ph("s a l aa m"),
        "من" to ph("m a n"),
        "سول" to ph("s o l"),
        "هستم" to ph("h a s t a m"),
        "دستیار" to ph("d a s t y aa r"),
        "شخصی" to ph("sh a kh s i"),
        "شما" to ph("sh o m aa"),
        "بله" to ph("b a l e"),
        "نه" to ph("n a"),
        "خیر" to ph("kh e y r"),
        "تایید" to ph("t a a y i d"),
        "لغو" to ph("l a gh v"),
        "شد" to ph("sh o d"),
        "انجام" to ph("a n j aa m"),
        "آماده" to ph("aa m aa d e"),
        "آماده‌ام" to ph("aa m aa d e a m"),
        "ام" to ph("a m"),
        "است" to ph("a s t"),
        "هست" to ph("h a s t"),
        "در" to ph("d a r"),
        "به" to ph("b e"),
        "از" to ph("a z"),
        "با" to ph("b aa"),
        "برای" to ph("b a r aa y e"),
        "این" to ph("i n"),
        "آن" to ph("aa n"),
        "که" to ph("k e"),
        "را" to ph("r aa"),
        "و" to ph("v a"),
        "یا" to ph("y aa"),
        "چه" to ph("ch e"),
        "چطور" to ph("ch e t o r"),
        "چقدر" to ph("ch e gh a d r"),
        "کجا" to ph("k o j aa"),
        "امروز" to ph("e m r u z"),
        "فردا" to ph("f a r d aa"),
        "پسفردا" to ph("p a s f a r d aa"),
        "ساعت" to ph("s aa a t"),
        "دقیقه" to ph("d a gh i gh e"),
        "روز" to ph("r u z"),
        "برج" to ph("b o r j"),
        "ماه" to ph("m aa h"),
        "سال" to ph("s aa l"),
        "کبیسه" to ph("k a b i s e"),
        "عادی" to ph("aa d i"),
        "شنبه" to ph("sh a n b e"),
        "یکشنبه" to ph("y e k sh a n b e"),
        "دوشنبه" to ph("d o sh a n b e"),
        "سهشنبه" to ph("s e sh a n b e"),
        "چهارشنبه" to ph("ch a h aa r sh a n b e"),
        "پنجشنبه" to ph("p a n j sh a n b e"),
        "جمعه" to ph("j o m e"),
        "فروردین" to ph("f a r v a r d i n"),
        "اردیبهشت" to ph("o r d i b e h e sh t"),
        "خرداد" to ph("kh o r d aa d"),
        "تیر" to ph("t i r"),
        "مرداد" to ph("m o r d aa d"),
        "شهریور" to ph("sh a h r i v a r"),
        "مهر" to ph("m e h r"),
        "آبان" to ph("aa b aa n"),
        "آذر" to ph("aa z a r"),
        "دی" to ph("d e y"),
        "بهمن" to ph("b a h m a n"),
        "اسفند" to ph("e s f a n d"),
        "اینترنت" to ph("i n t e r n e t"),
        "دانلود" to ph("d aa n l o d"),
        "دریافت" to ph("d a r y aa f t"),
        "پیدا" to ph("p e y d aa"),
        "کتاب" to ph("k e t aa b"),
        "نقش" to ph("n a gh sh"),
        "حافظه" to ph("h aa f e z e"),
        "ذخیره" to ph("z a kh i r e"),
        "یاد" to ph("y aa d"),
        "گرفتم" to ph("g e r e f t a m"),
        "یادآوری" to ph("y aa d aa v a r i"),
        "یادآور" to ph("y aa d aa v a r"),
        "تنظیم" to ph("t a n z i m"),
        "تنظیمات" to ph("t a n z i m aa t"),
        "تماس" to ph("t a m aa s"),
        "زنگ" to ph("z a n g"),
        "پیام" to ph("p a y aa m"),
        "پیامک" to ph("p a y aa m a k"),
        "ارسال" to ph("e r s aa l"),
        "مخاطب" to ph("m o kh aa t a b"),
        "شماره" to ph("sh o m aa r e"),
        "موقعیت" to ph("m o gh e i y a t"),
        "فعلی" to ph("f e l i"),
        "شرکت" to ph("sh e r k a t"),
        "دفتر" to ph("d a f t a r"),
        "خانه" to ph("kh aa n e"),
        "منزل" to ph("m a n z e l"),
        "آدرس" to ph("aa d r e s"),
        "باتری" to ph("b aa t r i"),
        "درصد" to ph("d a r s a d"),
        "صدا" to ph("s e d aa"),
        "صدای" to ph("s e d aa y e"),
        "افزایش" to ph("a f z aa y e sh"),
        "کاهش" to ph("k aa h e sh"),
        "یافت" to ph("y aa f t"),
        "باز" to ph("b aa z"),
        "برنامه" to ph("b a r n aa m e"),
        "فهرست" to ph("f e h r e s t"),
        "قابلیت" to ph("gh aa b e l i y a t"),
        "پسزمینه" to ph("p a s z a m i n e"),
        "پشتیبان" to ph("p o sh t i b aa n"),
        "رمز" to ph("r a m z"),
        "گوشی" to ph("g u sh i"),
        "لطفاً" to ph("l o t f a n"),
        "لطفا" to ph("l o t f a n"),
        "بفرمایید" to ph("b e f a r m aa y i d"),
        "بگویید" to ph("b e g u y i d"),
        "بگو" to ph("b e g u"),
        "فرمان" to ph("f a r m aa n"),
        "کار" to ph("k aa r"),
        "حالت" to ph("h aa l a t"),
        "عمومی" to ph("o m u m i"),
        "ثبت" to ph("s a b t"),
        "نشده" to ph("n a sh o d e"),
        "موفقیت" to ph("m o v a f f a gh i y a t"),
        "ناموفق" to ph("n aa m o v a f f a gh"),
        "خطا" to ph("kh a t aa"),
        "کاربر" to ph("k aa r b a r"),
        "فایل" to ph("f aa y l"),
        "صفحه" to ph("s a f h e"),
        "وب" to ph("v e b"),
        "لینک" to ph("l i n k"),
        "اینترنتی" to ph("i n t e r n e t i"),
        "یک" to ph("y e k"),
        "دو" to ph("d o"),
        "سه" to ph("s e"),
        "چهار" to ph("ch a h aa r"),
        "پنج" to ph("p a n j"),
        "شش" to ph("sh e sh"),
        "هفت" to ph("h a f t"),
        "هشت" to ph("h a sh t"),
        "نه" to ph("n o h"),
        "ده" to ph("d a h"),
        "بیست" to ph("b i s t"),
        "سی" to ph("s i"),
        "چهل" to ph("ch e h e l"),
        "پنجاه" to ph("p a n j aa h"),
        "شصت" to ph("sh a s t"),
        "هفتاد" to ph("h a f t aa d"),
        "هشتاد" to ph("h a sh t aa d"),
        "نود" to ph("n a v a d"),
        "صد" to ph("s a d"),
        "هزار" to ph("h a z aa r"),
        "خوب" to ph("kh u b"),
        "مرسی" to ph("m e r s i"),
        "ممنون" to ph("m a m n u n"),
        "باشه" to ph("b aa sh e"),
        "متوجه" to ph("m o t a v a j j e h"),
        "نشدم" to ph("n a sh o d a m"),
        "دقیق" to ph("d a gh i gh"),
        "درست" to ph("d o r o s t"),
        "موضوع" to ph("m o z u"),
        "درخواست" to ph("d a r kh aa s t"),
        "جستجو" to ph("j o s t o j u"),
        "اطلاعات" to ph("e t t e l aa aa t"),
        "حالا" to ph("h aa l aa"),
        "همه" to ph("h a m e"),
        "نیست" to ph("n i s t"),
        "هستیم" to ph("h a s t i m"),
        "می‌توانید" to ph("m i t a v aa n i d"),
        "میتوانید" to ph("m i t a v aa n i d"),
        "وارد" to ph("v aa r e d"),
        "کنید" to ph("k o n i d"),
        "بزنید" to ph("b e z a n i d"),
        "کن" to ph("k o n"),
        "شدید" to ph("sh o d i d"),
    )

    private fun ph(str: String): List<String> = str.split(' ').filter { it.isNotBlank() }

    private fun lookupWordPhonemes(clean: String): List<String>? {
        lexicon[clean]?.let { return it }
        val suffixRules = listOf(
            "های" to ph("h aa y e"),
            "ها" to ph("h aa"),
            "ام" to ph("a m"),
            "ات" to ph("a t"),
            "اش" to ph("a sh"),
            "ی" to ph("i"),
        )
        for ((suffix, sufPh) in suffixRules) {
            if (clean.length > suffix.length + 1 && clean.endsWith(suffix)) {
                val stem = clean.removeSuffix(suffix)
                lexicon[stem]?.let { return it + sufPh }
            }
        }
        return null
    }

    fun wordsToPhonemes(words: List<String>): List<PhonemeUnit> {
        val units = mutableListOf<PhonemeUnit>()
        for (rawWord in words) {
            if (rawWord == "،") {
                units += phonemeSpecs.getValue("pause_short")
                continue
            }
            val clean = PersianNormalizer.normalize(rawWord).replace(" ", "")
            if (clean.isBlank()) continue

            val codes = lookupWordPhonemes(clean) ?: transliterateUnknownPersianWord(clean)
            for (code in codes) {
                phonemeSpecs[code]?.let { units += it }
            }
            units += phonemeSpecs.getValue("pause_word")
        }
        return units
    }

    private fun transliterateUnknownPersianWord(word: String): List<String> {
        val raw = mutableListOf<String>()
        word.forEachIndexed { idx, ch ->
            when (ch) {
                'آ' -> raw += "aa"
                'ا', 'أ', 'إ' -> raw += if (idx == 0) "a" else "aa"
                'ب' -> raw += "b"
                'پ' -> raw += "p"
                'ت', 'ط' -> raw += "t"
                'ث', 'س', 'ص' -> raw += "s"
                'ج' -> raw += "j"
                'چ' -> raw += "ch"
                'ح', 'ه' -> if (idx == word.lastIndex && word.length >= 2) raw += "e" else raw += "h"
                'خ' -> raw += "kh"
                'د' -> raw += "d"
                'ذ', 'ز', 'ض', 'ظ' -> raw += "z"
                'ر' -> raw += "r"
                'ژ' -> raw += "zh"
                'ش' -> raw += "sh"
                'غ', 'ق' -> raw += "gh"
                'ف' -> raw += "f"
                'ک' -> raw += "k"
                'گ' -> raw += "g"
                'ل' -> raw += "l"
                'م' -> raw += "m"
                'ن' -> raw += "n"
                'و' -> raw += if (idx == 0) "v" else "u"
                'ی' -> raw += if (idx == 0) "y" else "i"
                'ع', 'ء', 'ئ' -> raw += "a"
            }
        }

        // Insert epenthetic short vowel ('a'/'e') between consecutive consonants so syllables stay pronounceable
        val vowelSet = setOf("aa", "a", "e", "i", "o", "u")
        val syllabified = mutableListOf<String>()
        var consecutiveConsonants = 0
        for (i in raw.indices) {
            val c = raw[i]
            if (c in vowelSet) {
                syllabified += c
                consecutiveConsonants = 0
            } else {
                if (consecutiveConsonants >= 1 && i < raw.lastIndex) {
                    syllabified += "a"
                    consecutiveConsonants = 0
                }
                syllabified += c
                consecutiveConsonants++
            }
        }
        if (syllabified.isNotEmpty() && syllabified.none { it in vowelSet }) {
            syllabified.add(minOf(1, syllabified.size), "a")
        }
        return syllabified
    }
}

internal object FormantSynthesizer {

    private class Resonator {
        private var b0 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun configure(freqHz: Float, bwHz: Float, sampleRate: Float) {
            if (freqHz <= 20f || freqHz >= sampleRate * 0.45f) {
                b0 = 1f; a1 = 0f; a2 = 0f
                return
            }
            val r = exp((-PI * bwHz / sampleRate).toDouble()).toFloat()
            val omega = (2.0 * PI * freqHz / sampleRate)
            a1 = -2f * r * cos(omega).toFloat()
            a2 = r * r
            b0 = 1f + a1 + a2
        }

        fun process(x: Float): Float {
            val y = b0 * x - a1 * y1 - a2 * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    fun renderPhonemes(
        phonemes: List<PhonemeUnit>,
        baseF0: Float,
        rate: Float,
        risingIntonation: Boolean,
    ): ShortArray {
        val sr = BuiltInPersianSpeechEngine.SAMPLE_RATE.toFloat()
        val totalSamplesEstimate = phonemes.sumOf { ((it.durationMs / rate) * sr / 1000f).toInt().coerceAtLeast(160) }
        val out = FloatArray(totalSamplesEstimate + 512)

        val r1 = Resonator()
        val r2 = Resonator()
        val r3 = Resonator()
        var phase = 0f
        var cursor = 0
        val baseFadeSamples = (0.005f * sr).toInt().coerceAtLeast(1)
        val rng = Random(42)

        phonemes.forEachIndexed { idx, ph ->
            val samples = ((ph.durationMs / rate) * sr / 1000f).toInt().coerceAtLeast(160)
            if (cursor + samples > out.size) return@forEachIndexed
            val fadeSamples = minOf(baseFadeSamples, samples / 2).coerceAtLeast(1)

            if (ph.kind == PhKind.PAUSE) {
                cursor += samples
                return@forEachIndexed
            }

            val progress = idx.toFloat() / phonemes.size.coerceAtLeast(1)
            val pitchSlope = if (risingIntonation && progress > 0.72f) {
                1.0f + (progress - 0.72f) * 0.55f
            } else {
                1.04f - 0.14f * progress
            }
            val f0 = baseF0 * pitchSlope

            val prev = phonemes.getOrNull(idx - 1)?.takeIf { it.kind != PhKind.PAUSE } ?: ph
            val next = phonemes.getOrNull(idx + 1)?.takeIf { it.kind != PhKind.PAUSE } ?: ph

            for (i in 0 until samples) {
                val t = i.toFloat() / samples
                // Coarticulate F1/F2/F3 smoothly between previous -> current -> next phoneme
                val curF1 = coarticulate(prev.f1, ph.f1, next.f1, t)
                val curF2 = coarticulate(prev.f2, ph.f2, next.f2, t)
                val curF3 = coarticulate(prev.f3, ph.f3, next.f3, t)

                if (i % 16 == 0) {
                    val bwScale = if (ph.kind == PhKind.VOWEL) 1.0f else 1.6f
                    r1.configure(curF1, 68f * bwScale, sr)
                    r2.configure(curF2, 96f * bwScale, sr)
                    r3.configure(curF3, 145f * bwScale, sr)
                }

                phase += f0 / sr
                if (phase >= 1f) phase -= 1f

                // Rosenberg glottal pulse wave
                val glottal = when {
                    phase < 0.40f -> 0.5f * (1f - cos((PI * phase / 0.40f).toDouble()).toFloat())
                    phase < 0.58f -> cos((PI * (phase - 0.40f) / 0.36f).toDouble()).toFloat()
                    else -> 0f
                } * 2f - 0.35f

                val white = (rng.nextFloat() * 2f - 1f)

                val source = when (ph.kind) {
                    PhKind.VOWEL -> glottal * 0.92f
                    PhKind.LIQUID -> glottal * 0.65f
                    PhKind.NASAL -> glottal * 0.45f
                    PhKind.FRICATIVE_VOICED -> glottal * 0.45f + white * 0.25f
                    PhKind.FRICATIVE_UNVOICED -> white * 0.38f
                    PhKind.STOP_VOICED -> if (t < 0.55f) glottal * 0.16f else glottal * 0.55f + white * 0.22f
                    PhKind.STOP_UNVOICED -> if (t < 0.55f) 0f else white * 0.42f
                    PhKind.PAUSE -> 0f
                }

                var shaped = if (ph.kind == PhKind.FRICATIVE_UNVOICED || ph.kind == PhKind.STOP_UNVOICED) {
                    r2.process(source) * 0.65f + r3.process(source) * 0.35f
                } else {
                    val y = r1.process(source)
                    val z = r2.process(y)
                    r3.process(z) * 0.28f + z * 0.72f
                }

                // Smooth phoneme edge envelope to prevent pops
                var env = 1.0f
                if (i < fadeSamples) env = i.toFloat() / fadeSamples
                else if (samples - i < fadeSamples) env = (samples - i).toFloat() / fadeSamples

                val gain = when (ph.kind) {
                    PhKind.VOWEL -> 0.85f
                    PhKind.LIQUID -> 0.65f
                    PhKind.NASAL -> 0.55f
                    PhKind.FRICATIVE_VOICED -> 0.50f
                    PhKind.FRICATIVE_UNVOICED -> 0.36f
                    PhKind.STOP_VOICED, PhKind.STOP_UNVOICED -> 0.48f
                    PhKind.PAUSE -> 0f
                }

                out[cursor + i] = shaped * env * gain
            }
            cursor += samples
        }

        // Normalize safely to 16-bit PCM without clipping
        var peak = 0.001f
        for (i in 0 until cursor) {
            val a = kotlin.math.abs(out[i])
            if (a > peak) peak = a
        }
        val normScale = (24000f / peak).coerceAtMost(28000f)
        val result = ShortArray(cursor)
        for (i in 0 until cursor) {
            val v = (out[i] * normScale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[i] = v.toShort()
        }
        return result
    }

    private fun coarticulate(prev: Float, cur: Float, next: Float, t: Float): Float {
        return when {
            t < 0.22f -> {
                val k = t / 0.22f
                prev * (1f - k) * 0.45f + cur * (0.55f + 0.45f * k)
            }
            t > 0.78f -> {
                val k = (t - 0.78f) / 0.22f
                cur * (1f - 0.45f * k) + next * (0.45f * k)
            }
            else -> cur
        }
    }
}
