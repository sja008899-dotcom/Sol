package com.example.sol.brain.skills

import android.content.Context
import com.example.sol.brain.LearningEngine
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.widget.SolWidgetStateMirror

class GreetingSkill(
    private val knowledgeRepository: KnowledgeRepository,
) : SolSkill {
    override val id = "skill_greeting"
    override val titleFa = "احوال‌پرسی و پاسخ سریع فارسی"
    override val descriptionFa = "دریافت خوشامدگویی فارسی و نمایش نقش فعال Sol."
    override val intents = setOf(SolAction.GREETING)
    override val sampleCommandsFa = listOf("سلام خوبی؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val activeRole = knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }
        val roleNote = if (activeRole != null) " (نقش فعال: ${activeRole.roleName})" else ""
        return SkillResult(
            replyText = "سلام ${ctx.preferences.ownerName}. من Sol هستم و آماده‌ام$roleNote. چه کاری انجام بدهم؟",
            parsedCommand = cmd,
        )
    }
}

class IdentitySkill(
    private val knowledgeRepository: KnowledgeRepository,
) : SolSkill {
    override val id = "skill_identity"
    override val titleFa = "معرفی هویت و وضعیت دانش Sol"
    override val descriptionFa = "توضیح صادقانهٔ قابلیت‌های آفلاین، نقش‌های فعال و کتاب‌های نمایه‌شده."
    override val intents = setOf(SolAction.IDENTITY)
    override val sampleCommandsFa = listOf("سلام Sol، اسم تو چیه؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val roles = knowledgeRepository.getAllRolesOnce().filter { it.isActive }
        val booksCount = knowledgeRepository.getAllBooksOnce().size
        val extra = buildString {
            if (roles.isNotEmpty()) {
                append("\nنقش‌های فعال آموخته‌شده: ")
                append(roles.joinToString("، ") { it.roleName })
            }
            if (booksCount > 0) {
                append("\nتعداد کتاب‌های نمایه‌شده در موتور آفلاین BM25: $booksCount")
            }
        }
        return SkillResult(
            replyText = "من Sol هستم؛ دستیار شخصی آفلاین و قانون‌محور شما با تقویم شمسی دقیق، جست‌وجوی معنایی کتاب‌ها و پشتیبان رمزنگاری‌شده.$extra",
            parsedCommand = cmd,
        )
    }
}

class CapabilitiesSkill(
    private val catalogCountProvider: () -> Int,
) : SolSkill {
    override val id = "skill_capabilities"
    override val titleFa = "فهرست واقعی قابلیت‌ها و ابزارهای Sol"
    override val descriptionFa = "نمایش فهرست کامل و به‌روز از همهٔ مهارت‌ها و کارهای پس‌زمینه ثبت‌شده."
    override val intents = setOf(SolAction.SHOW_CAPABILITIES)
    override val sampleCommandsFa = listOf("فهرست قابلیت‌ها")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val total = catalogCountProvider()
        return SkillResult(
            replyText = "فهرست کامل $total قابلیت و ابزار واقعی Sol باز شد.",
            parsedCommand = cmd,
            pendingTask = PendingSolTask.OpenCapabilitiesCatalog(focusBackgroundOnly = false),
        )
    }
}

class HelpSkill : SolSkill {
    override val id = "skill_help"
    override val titleFa = "راهنما و نمونه فرمان‌های فارسی"
    override val descriptionFa = "ارائهٔ چند نمونه دستور عملی برای شروع کار با Sol."
    override val intents = setOf(SolAction.HELP)
    override val sampleCommandsFa = listOf("چه کارهایی بلدی؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        return SkillResult(
            replyText = "نمونه فرمان‌های Sol:\n" +
                "• «امروز چندم برج است؟» یا «۱۴۰۴/۰۱/۰۱ به میلادی» (تقویم شمسی واقعی)\n" +
                "• «تایمر ۱۰ دقیقه» یا «کرنومتر را شروع کن»\n" +
                "• «چراغ قوه را روشن کن» یا «دو ضرب در سه حساب کن»\n" +
                "• «چهارشنبه ساعت ۹ صبح یادآوری کن جلسه دارم» (AlarmManager دقیق)\n" +
                "• «یادداشت کن شیر بخرم» / «لیست خرید چیه؟»\n" +
                "• «طبق نقشت درباره عادت‌های اتمی راهنماییم کن» (جست‌وجوی BM25 روی کتاب‌ها)\n" +
                "• «فهرست قابلیت‌ها» برای مشاهدهٔ کل قابلیت‌ها و وظایف پس‌زمینه",
            parsedCommand = cmd,
        )
    }
}

class AliasMemorySkill(
    private val learningEngine: LearningEngine,
) : SolSkill {
    override val id = "skill_alias_memory"
    override val titleFa = "آموزش مترادف‌ها و قواعد شخصی"
    override val descriptionFa = "یادگیری نام مکان‌ها و اصطلاحات اختصاصی کاربر بدون نیاز به اینترنت."
    override val intents = setOf(SolAction.LEARN_ALIAS)
    override val sampleCommandsFa = listOf("هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        return SkillResult(
            replyText = learningEngine.teachAlias(cmd.aliasKey.orEmpty(), cmd.aliasValue.orEmpty()),
            parsedCommand = cmd,
        )
    }
}

class FactMemorySkill(
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
) : SolSkill {
    override val id = "skill_fact_memory"
    override val titleFa = "ذخیره، مرور و فراموشی نکات شخصی"
    override val descriptionFa = "ثبت دائمی نکات مهم در SQLite/Room، خواندن کل حافظه و حذف فوری با تأیید امن."
    override val intents = setOf(SolAction.LEARN_FACT, SolAction.FORGET_MEMORY, SolAction.LIST_MEMORY)
    override val sampleCommandsFa = listOf("یاد بگیر جلسه هفتگی شنبه ساعت ۱۰ صبح است")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val prefs = ctx.preferences
        return when (cmd.action) {
            SolAction.LEARN_FACT -> {
                if (cmd.aliasKey == "owner_name" && !cmd.aliasValue.isNullOrBlank()) {
                    preferencesRepository.setOwnerName(cmd.aliasValue)
                    memoryRepository.remember("owner_name", cmd.aliasValue, kind = "fact")
                    SkillResult(
                        replyText = "خوشبختم ${cmd.aliasValue}! نام شما را به خاطر سپردم.",
                        parsedCommand = cmd,
                    )
                } else {
                    SkillResult(
                        replyText = learningEngine.teachFact(cmd.message.orEmpty()),
                        parsedCommand = cmd,
                    )
                }
            }
            SolAction.FORGET_MEMORY -> SkillResult(
                replyText = learningEngine.forget(cmd.aliasKey.orEmpty()),
                parsedCommand = cmd,
            )
            SolAction.LIST_MEMORY -> {
                val memories = memoryRepository.getAllOnce()
                val locations = locationRepository.getAllOnce()
                val books = knowledgeRepository.getAllBooksOnce()
                val roles = knowledgeRepository.getAllRolesOnce()
                val reply = if (memories.isEmpty() && locations.isEmpty() && books.isEmpty() && roles.isEmpty() && prefs.confirmedCurrentLocation.isBlank()) {
                    "هنوز چیزی در حافظهٔ شخصی ذخیره نشده است."
                } else {
                    buildString {
                        if (prefs.confirmedCurrentLocation.isNotBlank()) {
                            append("موقعیت فعلی تأییدشده: ${prefs.confirmedCurrentLocation}\n")
                        }
                        if (roles.isNotEmpty()) {
                            append("نقش‌های Sol:\n")
                            roles.forEach { r -> append("• ${r.roleName} (${if (r.isActive) "فعال" else "غیرفعال"})\n") }
                        }
                        if (books.isNotEmpty()) {
                            append("کتاب‌های آموخته‌شده:\n")
                            books.take(5).forEach { b -> append("• ${b.title} — ${b.author}\n") }
                        }
                        if (memories.isNotEmpty()) {
                            append("یادگیری‌ها و نکات:\n")
                            memories.take(12).forEach { m ->
                                if (m.kind == "alias") {
                                    append("• «${m.keyText.removePrefix("alias:")}» ⟵ «${m.valueText}»\n")
                                } else {
                                    append("• ${m.valueText}\n")
                                }
                            }
                        }
                        if (locations.isNotEmpty()) {
                            append("مکان‌های ذخیره‌شده:\n")
                            locations.forEach { loc ->
                                append("• ${loc.alias}: ${loc.fullName}\n")
                            }
                        }
                    }.trim()
                }
                SkillResult(replyText = reply, parsedCommand = cmd)
            }
            else -> SkillResult(replyText = "", parsedCommand = cmd)
        }
    }
}

class PersonMemorySkill(
    private val learningEngine: LearningEngine,
) : SolSkill {
    override val id = "skill_person_memory"
    override val titleFa = "ثبت مخاطب و نسبت شخصی در حافظه"
    override val descriptionFa = "ذخیرهٔ شمارهٔ تلفن و نسبت افراد مهم در پایگاه دادهٔ محلی Room."
    override val intents = setOf(SolAction.SAVE_PERSON)
    override val sampleCommandsFa = listOf("شماره علی ۰۹۱۲۳۴۵۶۷۸۹ همکار منه ذخیره کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        return SkillResult(
            replyText = learningEngine.teachPerson(
                name = cmd.contact.orEmpty(),
                phone = cmd.phone.orEmpty(),
                relation = cmd.relation.orEmpty(),
            ),
            parsedCommand = cmd,
        )
    }
}

class LocationMemorySkill(
    private val context: Context,
    private val learningEngine: LearningEngine,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
) : SolSkill {
    override val id = "skill_location_memory"
    override val titleFa = "ثبت موقعیت فعلی جدا از مکان‌های ذخیره‌شده"
    override val descriptionFa = "جلوگیری از اشتباه شدن محل حضور فعلی با آدرس‌های ثابت ذخیره‌شده."
    override val intents = setOf(
        SolAction.SAVE_LOCATION,
        SolAction.SET_CURRENT_LOCATION,
        SolAction.GET_CURRENT_LOCATION,
    )
    override val sampleCommandsFa = listOf("الان شرکت هستم")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val prefs = ctx.preferences
        return when (cmd.action) {
            SolAction.SAVE_LOCATION -> SkillResult(
                replyText = learningEngine.teachLocation(
                    alias = cmd.aliasKey.orEmpty(),
                    fullName = cmd.aliasValue.orEmpty(),
                ),
                parsedCommand = cmd,
            )
            SolAction.SET_CURRENT_LOCATION -> {
                val rawLoc = cmd.locationReference.orEmpty()
                val (_, matchedAliases) = learningEngine.resolveAliasesInText(rawLoc)
                val resolvedLoc = matchedAliases.firstOrNull()?.second
                    ?: locationRepository.findLocation(rawLoc)?.fullName
                    ?: rawLoc
                preferencesRepository.confirmCurrentLocation(resolvedLoc)
                val activeRole = knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }?.roleName.orEmpty()
                SolWidgetStateMirror.saveAndRefresh(
                    context = context,
                    confirmedLocation = resolvedLoc,
                    activeRoleName = activeRole,
                )
                SkillResult(
                    replyText = "موقعیت فعلی تأییدشدهٔ شما روی «$resolvedLoc» ثبت شد.",
                    parsedCommand = cmd,
                )
            }
            SolAction.GET_CURRENT_LOCATION -> {
                val current = prefs.confirmedCurrentLocation
                val saved = locationRepository.getAllOnce()
                val reply = buildString {
                    if (current.isNotBlank()) {
                        append("موقعیت فعلی تأییدشدهٔ شما: «$current».")
                    } else {
                        append("هنوز موقعیت فعلی‌تان را تأیید نکرده‌اید (مثلاً بگویید: الان شرکت هستم).")
                    }
                    if (saved.isNotEmpty()) {
                        append("\nمکان‌های ذخیره‌شده: ")
                        append(saved.joinToString("، ") { "${it.alias} (${it.fullName})" })
                    }
                }
                SkillResult(replyText = reply, parsedCommand = cmd)
            }
            else -> SkillResult(replyText = "", parsedCommand = cmd)
        }
    }
}
