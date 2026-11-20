package com.example.sol.brain

internal class LearnAliasMatcher : IntentMatcher {
    override val id = "matcher_learn_alias"
    override val action = SolAction.LEARN_ALIAS
    override val priority = 82

    override fun match(ctx: MatchContext): MatchResult? {
        val m = ALIAS_REGEX.find(ctx.cleaned) ?: ALIAS_REGEX_2.find(ctx.cleaned) ?: return null
        val key = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        if (key.isBlank() || value.isBlank()) return null

        val slots = mapOf(
            "alias_key" to SlotValue.Text(key),
            "alias_value" to SlotValue.Text(value),
        )
        return MatchResult(score = 0.95f, slots = slots, evidence = listOf("alias_definition")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                aliasKey = key,
                aliasValue = value,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val ALIAS_REGEX = Regex(
            "(?:از این به بعد\\s+)?هر\\s*وقت\\s+(?:میگم|می‌گم|می\\s*گویم|گفتم)\\s+[«\"]?(.+?)[»\"]?[,،\\s]+(?:منظورم|یعنی)\\s+[«\"]?(.+?)[»\"]?(?:\\s+(?:است|هست))?$"
        )
        private val ALIAS_REGEX_2 = Regex(
            "^منظورم\\s+از\\s+[«\"]?(.+?)[»\"]?\\s+(?:همان\\s+)?[«\"]?(.+?)[»\"]?(?:\\s+(?:است|هست))?$"
        )
    }
}

internal class LearnFactMatcher : IntentMatcher {
    override val id = "matcher_learn_fact"
    override val action = SolAction.LEARN_FACT
    override val priority = 80

    override fun match(ctx: MatchContext): MatchResult? {
        val ownerNameMatch = OWNER_NAME_REGEX.find(ctx.cleaned)
        if (ownerNameMatch != null) {
            val ownerName = ownerNameMatch.groupValues[1].trim()
            val slots = mapOf(
                "alias_key" to SlotValue.Text("owner_name"),
                "alias_value" to SlotValue.Text(ownerName),
            )
            return MatchResult(score = 0.94f, slots = slots, evidence = listOf("owner_name_declaration")) { c, r ->
                ParsedCommand(
                    action = action,
                    rawText = c.rawText,
                    normalizedText = c.normalized,
                    aliasKey = "owner_name",
                    aliasValue = ownerName,
                    message = c.cleaned,
                    requireConfirmation = false,
                    slots = r.slots,
                    confidence = r.score,
                    matchedRuleId = id,
                )
            }
        }

        if (ctx.hasAnyPhrase("فراموش کن", "پاک کن")) return null
        val learnMatch = LEARN_FACT_REGEX.find(ctx.cleaned) ?: return null
        val fact = learnMatch.groupValues[1].trim()
        if (fact.isBlank()) return null

        val slots = mapOf("fact" to SlotValue.Text(fact))
        return MatchResult(score = 0.88f, slots = slots, evidence = listOf("learn_fact_prefix")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                message = fact,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val OWNER_NAME_REGEX = Regex("^(?:اسم|نام)\\s+من\\s+(.+?)\\s+(?:است|هست)$")
        private val LEARN_FACT_REGEX = Regex(
            "(?:اینو\\s+|این را\\s+)?(?:یاد بگیر|به خاطر بسپار|ذخیره کن|یادداشت کن|یادت باشه|یادت بمونه|بنویس)\\s*(?:که\\s+)?(.+)$"
        )
    }
}

internal class ForgetMemoryMatcher : IntentMatcher {
    override val id = "matcher_forget_memory"
    override val action = SolAction.FORGET_MEMORY
    override val priority = 78

    override fun match(ctx: MatchContext): MatchResult? {
        val hit = ctx.hasPhrase("فراموش کن") ||
            ctx.hasPhrase("یادت بره") ||
            (ctx.hasAnyToken("پاک", "حذف") && ctx.hasAnyToken("حافظه", "یادداشت", "نکته"))
        if (!hit) return null

        val target = ctx.cleaned
            .replace(STRIP_ACTION_REGEX, "")
            .trim()
            .replace(STRIP_TAIL_REGEX, "")
            .trim()
        val slots = if (target.isNotBlank()) mapOf("target" to SlotValue.Text(target)) else emptyMap()
        return MatchResult(score = 0.91f, slots = slots, evidence = listOf("forget_memory")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                aliasKey = target,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val STRIP_ACTION_REGEX = Regex("(?:از\\s+حافظه\\s+)?(?:فراموش کن|پاک کن|حذف کن|یادت بره)")
        private val STRIP_TAIL_REGEX = Regex("(?:را|رو)$")
    }
}

internal class ListMemoryMatcher : IntentMatcher {
    override val id = "matcher_list_memory"
    override val action = SolAction.LIST_MEMORY
    override val priority = 76

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("گوشی", "رم", "پر", "فضا", "خالی") && !ctx.hasAnyToken("یادداشت", "یادت")) return null
        if (ctx.hasPhrase("پس زمینه") || ctx.hasPhrase("سرویس صوتی")) return null

        val hit = ctx.hasAnyPhrase(
            "لیست حافظه",
            "فهرست حافظه",
            "مرور حافظه",
            "گزارش حافظه",
            "حافظه رو بگو",
            "حافظه را بگو",
            "حافظه را نشان بده",
            "توی حافظه",
            "در حافظه",
            "حافظه ت",
            "حافظه ات",
            "چی یادت",
            "چی یادته",
            "چیا بلدی",
            "چیا ذخیره",
            "چه چیزهایی ذخیره",
            "چه چیزهایی یادت",
        ) || (ctx.hasToken("حافظه") && ctx.hasAnyToken("بگو", "بخوان", "خواندن", "نشان", "نشون", "لیست", "فهرست", "مرور", "چی"))

        if (!hit) return null
        return MatchResult(score = 0.87f, evidence = listOf("list_memory")) { c, r ->
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

internal class SavePersonMatcher(
    private val extractor: EntityExtractor,
) : IntentMatcher {
    override val id = "matcher_save_person"
    override val action = SolAction.SAVE_PERSON
    override val priority = 74

    override fun match(ctx: MatchContext): MatchResult? {
        val phone = extractor.extractPhone(ctx.cleaned) ?: return null
        if (ctx.hasAnyToken("زنگ", "تماس", "پیام", "پیامک")) return null

        val candidateName = PERSON_NAME_REGEX.find(ctx.cleaned)?.groupValues?.getOrNull(1)
            ?.replace("همکار منه", "")
            ?.replace("دوست منه", "")
            ?.replace("شماره", "")
            ?.replace("است", "")
            ?.replace("ذخیره کن", "")
            ?.trim()
            .orEmpty()

        if (candidateName.isBlank()) return null

        val relation = when {
            ctx.hasToken("همکار") -> "همکار"
            ctx.hasToken("دوست") -> "دوست"
            ctx.hasAnyToken("خانواده", "برادر", "خواهر", "پدر", "مادر", "همسر") -> "خانواده"
            ctx.hasToken("مدیر") -> "مدیر"
            else -> ""
        }

        val slots = mapOf(
            "contact" to SlotValue.Contact(name = candidateName, phone = phone, relation = relation.ifBlank { null }),
        )
        return MatchResult(score = 0.90f, slots = slots, evidence = listOf("phone_with_name")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                contact = candidateName,
                phone = phone,
                relation = relation,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val PERSON_NAME_REGEX = Regex("(?:شماره\\s+)?([آ-یa-zA-Z\\s]{2,25})")
    }
}

internal class SetCurrentLocationMatcher : IntentMatcher {
    override val id = "matcher_set_current_location"
    override val action = SolAction.SET_CURRENT_LOCATION
    override val priority = 72

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("کجا", "کجاست", "کجام", "چیه", "چیست", "بگو", "بخوان", "نشان", "نشون", "چک", "کدام")) return null

        val m = SET_LOC_REGEX_1.find(ctx.cleaned)
            ?: SET_LOC_REGEX_2.find(ctx.cleaned)
            ?: SET_LOC_REGEX_3.find(ctx.cleaned)
            ?: return null

        val loc = m.groupValues[1].trim()
        if (loc.isBlank()) return null

        val slots = mapOf("location" to SlotValue.Location(reference = loc))
        return MatchResult(score = 0.91f, slots = slots, evidence = listOf("set_current_location")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                locationReference = loc,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val SET_LOC_REGEX_1 = Regex("^(?:من\\s+)?الان\\s+(?:در|تو|توی|به)?\\s*(.+?)\\s+(?:هستم|ام|رسیدم)$")
        private val SET_LOC_REGEX_2 = Regex("^(?:من\\s+)?(?:رسیدم|اومدم|آمدم)\\s+(?:به\\s+)?(.+)$")
        private val SET_LOC_REGEX_3 = Regex("^موقعیت(?:\\s+فعلی)?(?:\\s+من)?\\s+(?:الان\\s+)?(.+?)(?:\\s+(?:است|هست))?$")
    }
}

internal class GetCurrentLocationMatcher : IntentMatcher {
    override val id = "matcher_get_current_location"
    override val action = SolAction.GET_CURRENT_LOCATION
    override val priority = 70

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasToken("تنظیمات") || ctx.hasPhrase("تایید موقعیت") || ctx.hasPhrase("تأیید موقعیت")) return null
        val hit = ctx.hasAnyPhrase(
            "الان کجام",
            "الان کجا هستم",
            "کجا هستم",
            "موقعیت فعلی",
            "موقعیت من کجاست",
            "موقعیتم کجاست",
            "موقعیتم چیه",
            "موقعیتم چیست",
        ) || ctx.hasToken("موقعیتم")

        if (!hit) return null
        return MatchResult(score = 0.89f, evidence = listOf("get_current_location")) { c, r ->
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

internal class SaveLocationMatcher : IntentMatcher {
    override val id = "matcher_save_location"
    override val action = SolAction.SAVE_LOCATION
    override val priority = 68

    override fun match(ctx: MatchContext): MatchResult? {
        if (ctx.hasAnyToken("وضعیت", "کجاست", "چیه", "چیست", "کجا", "تنظیمات", "باز", "روشن", "خاموش", "یاب", "بگو")) return null
        val m = SAVE_LOC_REGEX.find(ctx.cleaned) ?: return null
        val key = m.groupValues[1].trim()
        val value = m.groupValues[2].trim()
        if (key.isBlank() || value.isBlank()) return null

        val slots = mapOf(
            "alias_key" to SlotValue.Text(key),
            "alias_value" to SlotValue.Text(value),
            "location" to SlotValue.Location(reference = key, resolvedName = value),
        )
        return MatchResult(score = 0.90f, slots = slots, evidence = listOf("save_location")) { c, r ->
            ParsedCommand(
                action = action,
                rawText = c.rawText,
                normalizedText = c.normalized,
                aliasKey = key,
                aliasValue = value,
                requireConfirmation = false,
                slots = r.slots,
                confidence = r.score,
                matchedRuleId = id,
            )
        }
    }

    companion object {
        private val SAVE_LOC_REGEX = Regex("(?:آدرس|نشانی|محل|مکان)\\s+(شرکت|دفتر|خانه|منزل|[آ-ی]+)\\s+(.+?)(?:\\s+(?:است|هست))?$")
    }
}
