package com.example.sol.brain.skills

import android.content.Context
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.brain.BrainExecutionResult
import com.example.sol.brain.LearningEngine
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SolAction
import com.example.sol.calendar.ExactAlarmScheduler
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.device.DeviceController
import com.example.sol.knowledge.BookRagEngine
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.knowledge.RankedBookChunk
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.PreferencesRepository
import com.example.sol.memory.SolRoleEntity
import com.example.sol.widget.SolWidgetStateMirror

class CalendarAndBackgroundSkill(
    private val backgroundCoordinator: BackgroundTaskCoordinator,
    private val exactAlarmScheduler: ExactAlarmScheduler,
) : SolSkill {
    override val supportedActions = setOf(
        SolAction.TIME_DATE,
        SolAction.SCHEDULE_REMINDER,
        SolAction.BACKGROUND_CONTROL,
    )

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        return when (command.action) {
            SolAction.TIME_DATE -> BrainExecutionResult(
                replyText = JalaliCalendar.answerCalendarQuestion(command.rawText),
                parsedCommand = command,
            )
            SolAction.SCHEDULE_REMINDER -> {
                val schedule = command.jalaliSchedule
                if (schedule != null) {
                    val title = if (schedule.isMessageReminder && !schedule.contactHint.isNullOrBlank()) {
                        "یادآور پیام به ${schedule.contactHint}"
                    } else {
                        "یادآور دقیق شمسی Sol"
                    }
                    val outcome = exactAlarmScheduler.scheduleJalaliAlarm(
                        triggerAtMillis = schedule.triggerAtMillis,
                        titleFa = title,
                        messageBody = schedule.reminderMessage,
                        targetContact = schedule.contactHint.orEmpty(),
                    )
                    val modeNote = if (outcome.isExactGranted) {
                        "با دقت ثانیه‌ای (AlarmManager + Doze)"
                    } else {
                        "با احترام به Doze (برای دقت دقیق‌تر در تنظیمات «دسترسی هشدار دقیق» را فعال کنید)"
                    }
                    val contactNote = if (!schedule.contactHint.isNullOrBlank()) {
                        "\nمخاطب هدف: ${schedule.contactHint}"
                    } else {
                        ""
                    }
                    BrainExecutionResult(
                        replyText = "یادآور شما برای «${outcome.jalaliDateTimeFa}» تنظیم شد ($modeNote).$contactNote\nمتن: «${schedule.reminderMessage}»",
                        parsedCommand = command,
                    )
                } else {
                    val msg = command.message.orEmpty().ifBlank { "یادآور Sol" }
                    val outcome = backgroundCoordinator.scheduleReminder(msg, command.delayMinutes.coerceAtLeast(1L))
                    val modeNote = if (outcome.isExactGranted) "با دقت ثانیه‌ای (AlarmManager)" else "با احترام به Doze"
                    BrainExecutionResult(
                        replyText = "یادآور شما برای ${command.delayMinutes.coerceAtLeast(1L)} دقیقه دیگر (${outcome.jalaliDateTimeFa}) تنظیم شد ($modeNote): «$msg».",
                        parsedCommand = command,
                    )
                }
            }
            SolAction.BACKGROUND_CONTROL -> {
                val jobId = command.backgroundJobId ?: "bg_memory_backup"
                val reply = backgroundCoordinator.triggerImmediateBackgroundJob(jobId)
                BrainExecutionResult(replyText = reply, parsedCommand = command)
            }
            else -> BrainExecutionResult(replyText = "", parsedCommand = command)
        }
    }
}

class KnowledgeAndRoleSkill(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
    private val bookRagEngine: BookRagEngine,
    private val localSlmEngine: LocalSlmEngine,
) : SolSkill {
    override val supportedActions = setOf(
        SolAction.WEB_BOOK_LEARN_ROLE,
        SolAction.TEACH_ROLE_VOICE,
        SolAction.CONSULT_ROLE_BOOK,
    )

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val prefs = preferencesRepository.getSnapshot()
        return when (command.action) {
            SolAction.WEB_BOOK_LEARN_ROLE -> {
                val query = command.bookQuery.orEmpty().trim()
                val reply = if (query.isBlank()) {
                    "باشه، آماده‌ام از اینترنت دریافت کنم. لطفاً نام کتاب، موضوع یا آدرس صفحهٔ اینترنتی مورد نظرتان را در کادر وارد کنید."
                } else {
                    "درخواست جست‌وجو و دریافت «$query» آماده شد؛ می‌توانید از اینترنت دریافت کنید یا فایل PDF / EPUB / TXT انتخاب کنید و با توضیحات صوتی به Sol آموزش دهید."
                }
                BrainExecutionResult(
                    replyText = reply,
                    parsedCommand = command,
                    pendingTask = PendingSolTask.InternetBookRoleFetch(
                        bookQuery = query,
                        requestedRoleHint = command.roleName.orEmpty(),
                        runInBackground = command.backgroundJobId != null,
                    ),
                )
            }
            SolAction.TEACH_ROLE_VOICE -> {
                val roleName = command.roleName?.ifBlank { "دستیار تخصصی" } ?: "دستیار تخصصی"
                val latestBook = knowledgeRepository.getAllBooksOnce().firstOrNull()
                knowledgeRepository.saveRole(
                    roleName = roleName,
                    instructions = command.message.orEmpty(),
                    linkedBookTitle = latestBook?.title.orEmpty(),
                    isActive = true,
                )
                SolWidgetStateMirror.saveAndRefresh(
                    context = context,
                    confirmedLocation = prefs.confirmedCurrentLocation,
                    activeRoleName = roleName,
                )
                val bookSuffix = if (latestBook != null) " و به کتاب «${latestBook.title}» متصل شد" else ""
                BrainExecutionResult(
                    replyText = "نقش جدید «$roleName» با دستور شما ذخیره$bookSuffix و فعال شد (ویجت صفحه اصلی نیز به‌روزرسانی شد).",
                    parsedCommand = command,
                )
            }
            SolAction.CONSULT_ROLE_BOOK -> {
                val rag = bookRagEngine.consultOffline(query = command.rawText)
                val canRunNeuralSlm = prefs.slmSynthesisEnabled && localSlmEngine.hasInstalledModel(prefs.slmModelPath)
                if (canRunNeuralSlm && (rag.topChunks.isNotEmpty() || rag.activeRole != null)) {
                    val sysPrompt = buildSlmSystemInstruction(
                        rag.activeRole,
                        rag.topChunks,
                        rag.referencedBook?.voiceNotes.orEmpty(),
                    )
                    BrainExecutionResult(
                        replyText = "",
                        parsedCommand = command,
                        speakReply = false,
                        pendingTask = PendingSolTask.RunLocalSlmChat(
                            userPrompt = command.rawText,
                            systemInstruction = sysPrompt,
                            fallbackReplyText = rag.answerText,
                            hasBookGrounding = rag.topChunks.isNotEmpty(),
                        ),
                    )
                } else {
                    BrainExecutionResult(replyText = rag.answerText, parsedCommand = command)
                }
            }
            else -> BrainExecutionResult(replyText = "", parsedCommand = command)
        }
    }

    internal suspend fun buildSlmSystemInstruction(
        activeRole: SolRoleEntity?,
        groundedChunks: List<RankedBookChunk>,
        voiceNotes: String,
    ): String {
        val prefs = preferencesRepository.getSnapshot()
        val highlights = memoryRepository.getAllOnce().take(8).map { m ->
            if (m.kind == "alias") "${m.keyText.removePrefix("alias:")} یعنی ${m.valueText}" else m.valueText
        }
        return LocalSlmEngine.buildSystemInstruction(
            ownerName = prefs.ownerName,
            confirmedLocation = prefs.confirmedCurrentLocation,
            activeRoleName = activeRole?.roleName,
            activeRoleInstructions = activeRole?.instructions,
            memoryHighlights = highlights,
            groundedBookChunks = groundedChunks,
            voiceNotes = voiceNotes,
        )
    }
}

class DeviceAndCommunicationSkill(
    private val learningEngine: LearningEngine,
    private val deviceController: DeviceController,
    private val preferencesRepository: PreferencesRepository,
) : SolSkill {
    override val supportedActions = setOf(
        SolAction.CALL,
        SolAction.SEND_SMS,
        SolAction.MULTI_STEP_NOTIFY,
        SolAction.DEVICE_STATUS,
        SolAction.ADJUST_VOLUME,
        SolAction.OPEN_SETTINGS,
        SolAction.OPEN_APP,
        SolAction.RUN_ROUTINE,
    )

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val prefs = preferencesRepository.getSnapshot()
        return when (command.action) {
            SolAction.CALL -> {
                val contact = command.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "با چه کسی تماس بگیرم؟ مثلاً: با علی تماس بگیر.",
                        parsedCommand = command,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(command.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "در حال آماده‌سازی تماس با $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                        parsedCommand = command,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = false,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = command.locationReference,
                            isMultiStepReport = false,
                        ),
                    )
                }
            }
            SolAction.SEND_SMS -> {
                val contact = command.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "به چه کسی پیام بدهم؟ مثلاً: به علی پیام بده فردا جلسه داریم.",
                        parsedCommand = command,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(command.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "در حال آماده‌سازی پیامک برای $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                        parsedCommand = command,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = command.locationReference,
                            isMultiStepReport = false,
                        ),
                    )
                }
            }
            SolAction.MULTI_STEP_NOTIFY -> {
                val contact = command.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "نام مخاطب را برای اجرای فرمان چندمرحله‌ای مشخص کن.",
                        parsedCommand = command,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(command.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "اجرای فرمان چندمرحله‌ای برای $contact آغاز شد…",
                        parsedCommand = command,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = command.locationReference,
                            isMultiStepReport = true,
                        ),
                    )
                }
            }
            SolAction.DEVICE_STATUS -> BrainExecutionResult(
                replyText = "وضعیت دستگاه: ${deviceController.buildDeviceSummaryFa()}",
                parsedCommand = command,
            )
            SolAction.ADJUST_VOLUME -> {
                val status = deviceController.adjustMediaVolume(command.volumeDelta)
                val dir = if (command.volumeDelta > 0) "افزایش" else "کاهش"
                BrainExecutionResult(
                    replyText = "صدای رسانه $dir یافت (${status.mediaCurrent} از ${status.mediaMax}).",
                    parsedCommand = command,
                )
            }
            SolAction.OPEN_SETTINGS -> {
                val sc = deviceController.settingsController
                val (opened, label) = when (command.settingTarget) {
                    "WIFI" -> sc.openWifiSettings() to "تنظیمات وای‌فای"
                    "LOCATION" -> sc.openLocationSettings() to "تنظیمات مکان"
                    "BLUETOOTH" -> sc.openBluetoothSettings() to "تنظیمات بلوتوث"
                    "SOUND" -> sc.openSoundSettings() to "تنظیمات صدا"
                    else -> sc.openBatterySettings() to "تنظیمات باتری"
                }
                val reply = if (opened) "$label باز شد." else "باز کردن $label در این دستگاه ممکن نشد."
                BrainExecutionResult(replyText = reply, parsedCommand = command)
            }
            SolAction.OPEN_APP -> {
                val target = command.targetApp.orEmpty()
                val launched = if (target.isNotBlank()) deviceController.appLauncher.launchAppByQuery(target) else null
                val reply = if (launched != null) {
                    "برنامهٔ «$launched» باز شد."
                } else {
                    "برنامهٔ «$target» در فهرست برنامه‌های قابل‌اجرا پیدا نشد."
                }
                BrainExecutionResult(replyText = reply, parsedCommand = command)
            }
            SolAction.RUN_ROUTINE -> {
                val bat = deviceController.getBatteryStatus()
                val loc = prefs.confirmedCurrentLocation.ifBlank { "ثبت‌نشده" }
                val todayJalali = JalaliCalendar.fromMillis().formatFullFa()
                val routineLabel = if (command.routineName == "NIGHT") "شب" else "کار"
                BrainExecutionResult(
                    replyText = "روتین «$routineLabel» ($todayJalali) اجرا شد:\n• موقعیت فعلی تأییدشده: $loc\n• باتری: ${bat.levelPercent}٪\n• موتور BM25 و حافظه محلی: آماده",
                    parsedCommand = command,
                )
            }
            else -> BrainExecutionResult(replyText = "", parsedCommand = command)
        }
    }
}

class UnknownFallbackSkill(
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val preferencesRepository: PreferencesRepository,
    private val bookRagEngine: BookRagEngine,
    private val localSlmEngine: LocalSlmEngine,
    private val knowledgeSkill: KnowledgeAndRoleSkill,
) : SolSkill {
    override val supportedActions = setOf(SolAction.UNKNOWN)

    override suspend fun execute(command: ParsedCommand): BrainExecutionResult {
        val rawInput = command.rawText
        val prefs = preferencesRepository.getSnapshot()

        val (resolved, matched) = learningEngine.resolveAliasesInText(rawInput)
        if (matched.isNotEmpty()) {
            val pairs = matched.joinToString("، ") { "«${it.first}» = «${it.second}»" }
            return BrainExecutionResult(
                replyText = "طبق یادگیری قبلی ($pairs)، برداشت من این است: «$resolved».",
                parsedCommand = command,
            )
        }

        val queryTokens = bookRagEngine.tokenize(rawInput)
        if (queryTokens.isNotEmpty()) {
            val normRaw = PersianNormalizer.normalize(rawInput)
            val matchedPerson = learningEngine.findMatchingPersonHint(normRaw)
            if (matchedPerson != null) {
                val phonePart = if (matchedPerson.phone.isNotBlank()) " — شماره: ${matchedPerson.phone}" else ""
                val relPart = if (matchedPerson.relation.isNotBlank()) " (${matchedPerson.relation})" else ""
                return BrainExecutionResult(
                    replyText = "مخاطب «${matchedPerson.name}»$relPart$phonePart در حافظه ثبت شده است.",
                    parsedCommand = command.copy(
                        contact = matchedPerson.name,
                        phone = matchedPerson.phone.takeIf { it.isNotBlank() } ?: command.phone,
                    ),
                )
            }

            val matchedLocation = locationRepository.getAllOnce().firstOrNull { loc ->
                normRaw.contains(loc.normalizedAlias) ||
                    normRaw.contains(PersianNormalizer.normalize(loc.fullName))
            }
            if (matchedLocation != null) {
                val addrPart = if (matchedLocation.addressNote.isNotBlank()) "\nنشانی: ${matchedLocation.addressNote}" else ""
                return BrainExecutionResult(
                    replyText = "مکان «${matchedLocation.alias}» همان «${matchedLocation.fullName}» است.$addrPart",
                    parsedCommand = command,
                )
            }

            val matchedFact = memoryRepository.getAllOnce().firstOrNull { fact ->
                val factNorm = PersianNormalizer.normalize("${fact.keyText} ${fact.valueText}")
                queryTokens.any { token -> token.length >= 3 && factNorm.contains(token) }
            }
            if (matchedFact != null) {
                return BrainExecutionResult(
                    replyText = "در حافظهٔ شما ثبت شده: ${matchedFact.valueText}",
                    parsedCommand = command,
                )
            }
        }

        val rag = bookRagEngine.consultOffline(query = rawInput)
        val hasInstalledSlm = localSlmEngine.hasInstalledModel(prefs.slmModelPath)
        val canRunNeuralSlm = prefs.slmSynthesisEnabled && hasInstalledSlm

        return when {
            canRunNeuralSlm -> {
                val sysPrompt = knowledgeSkill.buildSlmSystemInstruction(
                    activeRole = rag.activeRole,
                    groundedChunks = rag.topChunks,
                    voiceNotes = rag.referencedBook?.voiceNotes.orEmpty(),
                )
                val fallbackText = if (rag.topChunks.isNotEmpty() || rag.activeRole != null) rag.answerText else ""
                BrainExecutionResult(
                    replyText = "",
                    parsedCommand = command,
                    speakReply = false,
                    pendingTask = PendingSolTask.RunLocalSlmChat(
                        userPrompt = rawInput,
                        systemInstruction = sysPrompt,
                        fallbackReplyText = fallbackText,
                        hasBookGrounding = rag.topChunks.isNotEmpty(),
                    ),
                )
            }
            rag.topChunks.isNotEmpty() || rag.activeRole != null -> BrainExecutionResult(
                replyText = rag.answerText,
                parsedCommand = command,
            )
            hasInstalledSlm -> BrainExecutionResult(
                replyText = "این مورد در حافظه یا کتاب‌ها پیدا نشد. فایل مدل زبانی آفلاین روی گوشی نصب است؛ برای گفت‌وگوی آزاد، در «تنظیمات ← بخش ۳» سوئیچ مدل زبانی آفلاین (فاز ۳) را روشن کنید.",
                parsedCommand = command,
            )
            else -> BrainExecutionResult(
                replyText = "متوجه نشدم و در حافظه یا کتاب‌ها هم پیدا نشد (هنوز فایل مدل زبانی آفلاین .litertlm نصب نشده است).\n" +
                    "می‌توانید یکی از این نمونه‌ها را امتحان کنید:\n" +
                    "• «امروز چندم برج است؟»\n" +
                    "• «فردا ساعت ۸ صبح یادآوری کن جلسه دارم»\n" +
                    "• «به علی تماس بگیر»",
                parsedCommand = command,
            )
        }
    }
}
