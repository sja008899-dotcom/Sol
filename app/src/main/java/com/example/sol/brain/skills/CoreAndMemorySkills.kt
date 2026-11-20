package com.example.sol.brain.skills

import android.content.Context
import com.example.sol.brain.BrainExecutionResult
import com.example.sol.brain.LearningEngine
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.widget.SolWidgetStateMirror

class ConversationSkill(
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
) : SolSkill {
    override val supportedActions = setOf(
        SolAction.GREETING,
        SolAction.IDENTITY,
        SolAction.SHOW_CAPABILITIES,
        SolAction.HELP,
    )

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val prefs = preferencesRepository.getSnapshot()
        return when (command.action) {
            SolAction.GREETING -> {
                val activeRole = knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }
                val roleNote = if (activeRole != null) " (نقش فعال: ${activeRole.roleName})" else ""
                BrainExecutionResult(
                    replyText = "سلام ${prefs.ownerName}. من Sol هستم و آماده‌ام$roleNote. چه کاری انجام بدهم؟",
                    parsedCommand = command,
                )
            }
            SolAction.IDENTITY -> {
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
                BrainExecutionResult(
                    replyText = "من Sol هستم؛ دستیار شخصی آفلاین و قانون‌محور شما با تقویم شمسی دقیق، جست‌وجوی معنایی کتاب‌ها و پشتیبان رمزنگاری‌شده.$extra",
                    parsedCommand = command,
                )
            }
            SolAction.SHOW_CAPABILITIES -> BrainExecutionResult(
                replyText = "فهرست کامل ۲۰ قابلیت Sol (شامل ۱۰ اجرای پس‌زمینه، ۱ دستور پیشرفتهٔ اینترنت + کتاب + یادگیری نقش صوتی و ۹ قابلیت هوشمند) باز شد.",
                parsedCommand = command,
                pendingTask = PendingSolTask.OpenCapabilitiesCatalog(focusBackgroundOnly = false),
            )
            SolAction.HELP -> BrainExecutionResult(
                replyText = "نمونه فرمان‌های پیشرفته Sol v2:\n" +
                    "• «امروز چندم برج است؟» (تقویم شمسی واقعی)\n" +
                    "• «چهارشنبه ساعت ۹ صبح یادآوری کن جلسه دارم» (AlarmManager دقیق)\n" +
                    "• «دو روز دیگه به علی پیام یادآوری بفرست»\n" +
                    "• «طبق نقشت درباره عادت‌های اتمی راهنماییم کن» (جست‌وجوی BM25 روی کتاب‌ها)\n" +
                    "• «هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است»\n" +
                    "• «۲۰ قابلیت» برای مشاهده کل قابلیت‌ها و وظایف پس‌زمینه",
                parsedCommand = command,
            )
            else -> BrainExecutionResult(replyText = "", parsedCommand = command)
        }
    }
}

class MemoryAndLocationSkill(
    private val context: Context,
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
) : SolSkill {
    override val supportedActions = setOf(
        SolAction.LEARN_ALIAS,
        SolAction.LEARN_FACT,
        SolAction.FORGET_MEMORY,
        SolAction.LIST_MEMORY,
        SolAction.SAVE_PERSON,
        SolAction.SAVE_LOCATION,
        SolAction.SET_CURRENT_LOCATION,
        SolAction.GET_CURRENT_LOCATION,
    )

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val prefs = preferencesRepository.getSnapshot()
        return when (command.action) {
            SolAction.LEARN_ALIAS -> BrainExecutionResult(
                replyText = learningEngine.teachAlias(command.aliasKey.orEmpty(), command.aliasValue.orEmpty()),
                parsedCommand = command,
            )
            SolAction.LEARN_FACT -> {
                if (command.aliasKey == "owner_name" && !command.aliasValue.isNullOrBlank()) {
                    preferencesRepository.setOwnerName(command.aliasValue)
                    memoryRepository.remember("owner_name", command.aliasValue, kind = "fact")
                    BrainExecutionResult(
                        replyText = "خوشبختم ${command.aliasValue}! نام شما را به خاطر سپردم.",
                        parsedCommand = command,
                    )
                } else {
                    BrainExecutionResult(
                        replyText = learningEngine.teachFact(command.message.orEmpty()),
                        parsedCommand = command,
                    )
                }
            }
            SolAction.FORGET_MEMORY -> BrainExecutionResult(
                replyText = learningEngine.forget(command.aliasKey.orEmpty()),
                parsedCommand = command,
            )
            SolAction.SAVE_PERSON -> BrainExecutionResult(
                replyText = learningEngine.teachPerson(
                    name = command.contact.orEmpty(),
                    phone = command.phone.orEmpty(),
                    relation = command.relation.orEmpty(),
                ),
                parsedCommand = command,
            )
            SolAction.SAVE_LOCATION -> BrainExecutionResult(
                replyText = learningEngine.teachLocation(
                    alias = command.aliasKey.orEmpty(),
                    fullName = command.aliasValue.orEmpty(),
                ),
                parsedCommand = command,
            )
            SolAction.SET_CURRENT_LOCATION -> {
                val rawLoc = command.locationReference.orEmpty()
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
                BrainExecutionResult(
                    replyText = "موقعیت فعلی تأییدشدهٔ شما روی «$resolvedLoc» ثبت شد.",
                    parsedCommand = command,
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
                BrainExecutionResult(replyText = reply, parsedCommand = command)
            }
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
                BrainExecutionResult(replyText = reply, parsedCommand = command)
            }
            else -> BrainExecutionResult(replyText = "", parsedCommand = command)
        }
    }
}
