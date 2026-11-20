package com.example.sol.brain

import com.example.sol.text.PersianTextNormalizer

internal class MultiStepNotifyMatcher(
    private val extractor: EntityExtractor,
) : IntentMatcher {
    override val id = "matcher_multi_step_notify"
    override val action = SolAction.MULTI_STEP_NOTIFY
    override val priority = 66

    override fun match(ctx: MatchContext): MatchResult? {
        val hasNotifyVerb = ctx.hasAnyPhrase("اطلاع بده", "خبر بده", "گزارش کن")
        val hasMultiCue = ctx.hasAnyToken("وضعیت", "بعد", "گزارش", "سپس")
        if (!hasNotifyVerb || !hasMultiCue) return null

        val contact = extractor.extractContactForMessage(ctx.cleaned)
            ?: FALLBACK_CONTACT_REGEX.find(ctx.cleaned)?.groupValues?.getOrNull(1)?.trim()
        val locRef = extractor.extractLocationReference(ctx.normalized)
        val msg = extractor.extractMessageAfterSay(ctx.cleaned) ?: ctx.cleaned
        val steps = listOf(
            "یافتن اطلاعات مخاطب",
            "خواندن موقعیت فعلی تأییدشده",
            "تنظیم پیام با معرفی شفاف Sol",
            "دریافت تأیید کاربر",
            "اجرای اقدام ارتباطی مجاز",
            "ثبت گزارش نهایی در سوابق",
        )

        val slots = buildMap<String, SlotValue> {
            contact?.let { put("contact", SlotValue.Contact(it)) }
            put("message", SlotValue.Text(msg))
            locRef?.let { put("location", SlotValue.Location(it)) }
            put("steps", SlotValue.Steps(steps))
        }
        return MatchResult(score = 0.95f, slots = slots, evidence = listOf("multi_step_notify")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                contact = contact,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
                steps = steps,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val FALLBACK_CONTACT_REGEX = Regex("به\\s+(.+?)\\s+(?:اطلاع|خبر)")
    }
}

internal class CallMatcher(
    private val extractor: EntityExtractor,
) : IntentMatcher {
    override val id = "matcher_call"
    override val action = SolAction.CALL
    override val priority = 64

    override fun match(ctx: MatchContext): MatchResult? {
        val isCallPhrase = ctx.hasAnyPhrase("تماس بگیر", "زنگ بزن", "تماس با", "تلفن بزن", "تماس صوتی") ||
            (ctx.hasToken("زنگ") && ctx.hasAnyToken("بزن", "بزنم", "زدن") && !ctx.hasAnyToken("تفریح", "مدرسه", "خطر", "هشدار", "آلارم")) ||
            (ctx.hasAnyToken("شماره", "تلفن") && ctx.hasAnyToken("بگیر", "بگیرم") && !ctx.hasToken("پشتیبان"))

        if (!isCallPhrase) return null

        val contact = extractor.extractContactForCall(ctx.cleaned)
        val msg = extractor.extractMessageAfterSay(ctx.cleaned)
        val locRef = extractor.extractLocationReference(ctx.normalized)
        val phone = extractor.extractPhone(ctx.cleaned)

        val slots = buildMap<String, SlotValue> {
            contact?.let { put("contact", SlotValue.Contact(it, phone = phone)) }
            msg?.let { put("message", SlotValue.Text(it)) }
            locRef?.let { put("location", SlotValue.Location(it)) }
        }
        return MatchResult(score = 0.92f, slots = slots, evidence = listOf("call_phrase")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                contact = contact,
                phone = phone,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class SendSmsMatcher(
    private val extractor: EntityExtractor,
) : IntentMatcher {
    override val id = "matcher_send_sms"
    override val action = SolAction.SEND_SMS
    override val priority = 62

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("خطا", "ارور", "تبریک", "تسلیت")) return null

        val isSmsPhrase = ctx.hasAnyToken("پیامک", "اساماس") ||
            ctx.hasAnyPhrase("اس ام اس", "پیام بده", "پیام بفرست", "پیام ارسال کن", "اطلاع بده", "خبر بده") ||
            (ctx.hasToken("پیام") && ctx.hasAnyToken("بده", "بفرست", "بفرستم", "ارسال", "بنویس"))

        if (!isSmsPhrase) return null

        val contact = extractor.extractContactForMessage(ctx.cleaned)
        val msg = extractor.extractMessageAfterSay(ctx.cleaned)
        val locRef = extractor.extractLocationReference(ctx.normalized)

        val slots = buildMap<String, SlotValue> {
            contact?.let { put("contact", SlotValue.Contact(it)) }
            msg?.let { put("message", SlotValue.Text(it)) }
            locRef?.let { put("location", SlotValue.Location(it)) }
        }
        return MatchResult(score = 0.89f, slots = slots, evidence = listOf("sms_phrase")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                contact = contact,
                message = msg,
                locationReference = locRef,
                requireConfirmation = true,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class DeviceStatusMatcher : IntentMatcher {
    override val id = "matcher_device_status"
    override val action = SolAction.DEVICE_STATUS
    override val priority = 60

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("تنظیمات", "پس زمینه", "پایش", "شارژر", "کابل", "خرید", "قیمت")) return null
        if (ctx.hasPhrase("پس زمینه")) return null

        val hit = ctx.hasAnyPhrase("وضعیت گوشی", "وضعیت دستگاه", "وضعیت باتری", "درصد باتری", "میزان شارژ", "شارژ گوشی", "شارژ دستگاه") ||
            ctx.hasAllTokens("وضعیت", "دستگاه") ||
            ctx.hasAllTokens("وضعیت", "گوشی") ||
            (ctx.hasAnyToken("باتری", "شارژ") && !ctx.hasToken("ساعت"))

        if (!hit) return null
        return MatchResult(score = 0.88f, evidence = listOf("device_status")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class AdjustVolumeMatcher : IntentMatcher {
    override val id = "matcher_adjust_volume"
    override val action = SolAction.ADJUST_VOLUME
    override val priority = 58

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("تنظیمات", "چرا", "یخچال", "ماشین", "خیابان")) return null
        val hasVolumeNoun = ctx.hasAnyToken("صدا", "ولوم", "صدات", "صداتو", "صدای", "ولومو")
        val hasUpWord = ctx.hasAnyToken("زیاد", "زیادش", "بلند", "بلندتر", "بلندش", "بالا", "بالاتر", "افزایش", "بیشتر", "ضعیف", "ضعیفه")
        val hasDownWord = ctx.hasAnyToken("کم", "کمش", "کمتر", "ارام", "آرام", "آروم", "آرومتر", "آرومش", "ارامتر", "پایین", "پائین", "پایینتر", "کاهش")

        if (!hasVolumeNoun || (!hasUpWord && !hasDownWord)) return null

        val delta = if (hasUpWord) 2 else -2
        val slots = mapOf("volume_delta" to SlotValue.Number(delta.toDouble()))
        return MatchResult(score = 0.91f, slots = slots, evidence = listOf("volume_control")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                volumeDelta = delta,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class OpenSettingsMatcher : IntentMatcher {
    override val id = "matcher_open_settings"
    override val action = SolAction.OPEN_SETTINGS
    override val priority = 56

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasToken("رمز") || ctx.hasToken("نسخه") || ctx.hasPhrase("چه خبر")) return null

        val target = when {
            ctx.hasAnyPhrase("تنظیمات وایفای", "تنظیمات وای فای", "تنظیمات اینترنت") ||
                (ctx.hasAnyToken("وایفای", "wifi") && ctx.hasAnyToken("باز", "روشن", "خاموش", "تنظیمات", "وصل", "بزن")) -> "WIFI"
            ctx.hasAnyPhrase("تنظیمات مکان", "مکان یاب") ||
                (ctx.hasAnyToken("لوکیشن", "gps") && ctx.hasAnyToken("باز", "روشن", "خاموش", "تنظیمات", "بزن")) -> "LOCATION"
            ctx.hasToken("بلوتوث") && ctx.hasAnyToken("باز", "روشن", "خاموش", "تنظیمات", "بزن", "وصل") -> "BLUETOOTH"
            ctx.hasPhrase("تنظیمات صدا") -> "SOUND"
            ctx.hasPhrase("تنظیمات باتری") -> "BATTERY"
            ctx.hasToken("تنظیمات") && ctx.hasAnyToken("باز", "برو", "بزن", "گوشی") -> "WIFI"
            else -> null
        } ?: return null

        val slots = mapOf("setting_target" to SlotValue.Text(target))
        return MatchResult(score = 0.90f, slots = slots, evidence = listOf("settings:$target")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                settingTarget = target,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class RunRoutineMatcher : IntentMatcher {
    override val id = "matcher_run_routine"
    override val action = SolAction.RUN_ROUTINE
    override val priority = 54

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasPhrase("پس زمینه") || ctx.hasToken("صبحگاهی")) return null
        val hit = ctx.hasToken("روتین") ||
            ctx.hasAnyPhrase("حالت کار", "حالت شب", "حالت تمرکز")
        if (!hit) return null

        val routine = if (ctx.hasToken("شب")) "NIGHT" else "WORK"
        val slots = mapOf("routine_name" to SlotValue.Text(routine))
        return MatchResult(score = 0.88f, slots = slots, evidence = listOf("routine:$routine")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                routineName = routine,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class OpenAppMatcher : IntentMatcher {
    override val id = "matcher_open_app"
    override val action = SolAction.OPEN_APP
    override val priority = 52

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("تنظیمات", "لینک", "سایت")) return null
        if (URL_REGEX.containsMatchIn(ctx.cleaned)) return null

        val m = OPEN_APP_REGEX_1.find(ctx.cleaned) ?: OPEN_APP_REGEX_2.find(ctx.cleaned) ?: return null
        val candidateApp = m.groupValues[1].trim()
        val normApp = PersianTextNormalizer.normalizeForMatching(candidateApp)
        if (candidateApp.isBlank() || normApp in PHYSICAL_OBJECT_EXCLUSIONS) return null

        val slots = mapOf("target_app" to SlotValue.Text(candidateApp))
        return MatchResult(score = 0.87f, slots = slots, evidence = listOf("open_app:$candidateApp")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                targetApp = candidateApp,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val OPEN_APP_REGEX_1 = Regex("(?:برنامه|اپ|اپلیکیشن)?\\s*(.+?)\\s+(?:را\\s+|رو\\s+)?باز کن$")
        private val OPEN_APP_REGEX_2 = Regex("^باز کن\\s+(?:برنامه\\s+|اپ\\s+)?(.+)$")
        private val PHYSICAL_OBJECT_EXCLUSIONS = setOf(
            "در", "پنجره", "چشم", "چشماتو", "قفل", "کیف", "جعبه", "یخچال", "دهن", "کتاب",
        )
    }
}

internal class TimeDateMatcher : IntentMatcher {
    override val id = "matcher_time_date"
    override val action = SolAction.TIME_DATE
    override val priority = 50

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("دیوار", "مچی", "خوابیده", "گرونه", "گران", "انقضا", "انقضای", "جنگ", "ایران", "تمدن", "فاصله", "مسافت", "تعداد", "تولد", "بیمه")) return null

        val isTime = ctx.tokens == listOf("ساعت") ||
            ctx.hasAnyPhrase("ساعت چند است", "ساعت چنده", "ساعت چنه", "الان ساعت چنده", "الان ساعت چند است", "ساعت الان", "چه ساعتیه", "چه ساعتی است") ||
            (ctx.hasToken("ساعت") && ctx.hasAnyToken("چند", "چنده", "بگو") && !ctx.hasAnyToken("یادآوری", "یادم", "آلارم", "بذار", "هشدار", "فاصله"))

        val isDate = ctx.tokens == listOf("امروز") ||
            ctx.hasAnyPhrase(
                "تاریخ امروز", "امروز چندم", "امروز چندمه", "امروز چند", "امروز چه روزی",
                "امروز چه روز", "امروز چیه", "امروز چه روزیه", "چندم برج", "چندم ماه", "چندم ماهه",
                "چه روزی است", "ماه شمسی", "تقویم شمسی", "امسال سال کبیسه", "سال کبیسه است",
            ) ||
            ctx.hasAnyToken("چندشنبه", "چندمه", "شمسی") ||
            (ctx.hasToken("کبیسه") && !ctx.hasToken("روزهای")) ||
            (ctx.hasToken("تاریخ") && ctx.hasAnyToken("امروز", "روز", "چندم", "شمسی", "بگو", "چیه", "چنده"))

        if (!isTime && !isDate) return null

        val slots = mapOf("kind" to SlotValue.Text(if (isTime && !isDate) "time" else "date"))
        return MatchResult(score = 0.89f, slots = slots, evidence = listOf("time_or_date_query")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class IdentityMatcher : IntentMatcher {
    override val id = "matcher_identity"
    override val action = SolAction.IDENTITY
    override val priority = 48

    override fun match(ctx: MatchContext): MatchResult? {
        val hit = ctx.hasAnyPhrase(
            "اسم تو", "نام تو", "اسمت چیه", "اسمت چیست",
            "کی هستی", "تو کی هستی", "شما کی هستید", "چی هستی",
            "خودت را معرفی کن", "خودت رو معرفی کن", "خودتو معرفی کن", "معرفی کن", "معرفی کنی",
        ) || ctx.hasToken("چیستی")

        if (!hit) return null
        return MatchResult(score = 0.90f, evidence = listOf("identity_query")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class HelpMatcher : IntentMatcher {
    override val id = "matcher_help"
    override val action = SolAction.HELP
    override val priority = 46

    override fun match(ctx: MatchContext): MatchResult? {
        val hit = ctx.hasAnyToken("کمک", "راهنما", "راهنمایی", "کاربردت") ||
            ctx.hasAnyPhrase(
                "چکار می توانی", "چیکار میتونی", "چکار میتونی", "می توانی بکنی", "می توانی انجام",
                "چه کارهایی", "چی بلدی", "چطوری کار می کنی", "چطور کار می کنی", "چطور با تو کار کنم",
                "چجوری کمک می کنی", "چطور کار میکنی", "نمونه فرمان", "کمکم کن",
            )
        if (!hit) return null
        return MatchResult(score = 0.88f, evidence = listOf("help_query")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}

internal class GreetingMatcher : IntentMatcher {
    override val id = "matcher_greeting"
    override val action = SolAction.GREETING
    override val priority = 20

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyPhrase("کار می کنی", "استرس امتحان")) return null
        val norm = ctx.rawNormalized
        val hit = norm == "سلام" ||
            ctx.hasAnyToken("سلام", "درود", "خوبی", "چطوری", "مرسی", "ممنون", "متشکرم", "سپاسگزارم") ||
            ctx.hasAnyPhrase("صبح بخیر", "شب بخیر", "عصر بخیر", "خسته نباشی", "حال شما خوب است", "حال شما خوب هست", "حالت چطوره", "حال تو چطوره")

        if (!hit) return null
        val isShortGreeting = ctx.tokens.size <= 5
        val score = if (isShortGreeting) 0.78f else 0.45f
        return MatchResult(score = score, evidence = listOf("greeting")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }
}
