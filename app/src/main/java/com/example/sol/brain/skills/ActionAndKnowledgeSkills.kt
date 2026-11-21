package com.example.sol.brain.skills

import android.content.Context
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.brain.LearningEngine
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.PendingSolTask
import com.example.sol.brain.SlotValue
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

class TimeDateQaSkill : SolSkill {
    override val id = "skill_time_date"
    override val titleFa = "پرسش‌وپاسخ تقویم شمسی، تبدیل تاریخ و کبیسه"
    override val descriptionFa = "تبدیل دقیق شمسی و میلادی، روز هفته، شمارش روزها تا تاریخ هدف و بررسی سال کبیسه."
    override val intents = setOf(SolAction.TIME_DATE)
    override val sampleCommandsFa = listOf("۱۴۰۴/۰۱/۰۱ به میلادی چندم است؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        return SkillResult(
            replyText = JalaliCalendar.answerCalendarQuestion(cmd.rawText),
            parsedCommand = cmd,
        )
    }
}

class ReminderAlarmSkill(
    private val backgroundCoordinator: BackgroundTaskCoordinator,
    private val exactAlarmScheduler: ExactAlarmScheduler,
) : SolSkill {
    override val id = "skill_reminder_alarm"
    override val titleFa = "یادآور زمان‌بندی‌شده دقیق (AlarmManager)"
    override val descriptionFa = "تنظیم یادآور شمسی با AlarmManager دقیق + بازیابی پس از راه‌اندازی مجدد گوشی."
    override val intents = setOf(SolAction.SCHEDULE_REMINDER)
    override val sampleCommandsFa = listOf("۱ دقیقه دیگر یادآوری کن وضعیت پروژه را بررسی کنم")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val schedule = cmd.jalaliSchedule
        return if (schedule != null) {
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
            SkillResult(
                replyText = "یادآور شما برای «${outcome.jalaliDateTimeFa}» تنظیم شد ($modeNote).$contactNote\nمتن: «${schedule.reminderMessage}»",
                parsedCommand = cmd,
            )
        } else {
            val msg = cmd.message.orEmpty().ifBlank { "یادآور Sol" }
            val outcome = backgroundCoordinator.scheduleReminder(msg, cmd.delayMinutes.coerceAtLeast(1L))
            val modeNote = if (outcome.isExactGranted) "با دقت ثانیه‌ای (AlarmManager)" else "با احترام به Doze"
            SkillResult(
                replyText = "یادآور شما برای ${cmd.delayMinutes.coerceAtLeast(1L)} دقیقه دیگر (${outcome.jalaliDateTimeFa}) تنظیم شد ($modeNote): «$msg».",
                parsedCommand = cmd,
            )
        }
    }
}

class BackgroundControlSkill(
    private val backgroundCoordinator: BackgroundTaskCoordinator,
) : SolSkill {
    override val id = "skill_background_control"
    override val titleFa = "اجرای وظایف پس‌زمینه (WorkManager و سرویس صوتی)"
    override val descriptionFa = "مدیریت پشتیبان حافظه، پایش باتری، مرتب‌سازی شاخص و روخوانی صوتی."
    override val intents = setOf(SolAction.BACKGROUND_CONTROL)
    override val sampleCommandsFa = listOf("از حافظه در پس‌زمینه پشتیبان بگیر")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val jobId = cmd.backgroundJobId ?: "bg_memory_backup"
        val reply = backgroundCoordinator.triggerImmediateBackgroundJob(jobId)
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class WebBookRoleSkill : SolSkill {
    override val id = "skill_web_book_role"
    override val titleFa = "اینترنت + دریافت کتاب + آموزش نقش"
    override val descriptionFa = "با تأیید شما کتاب یا صفحه را از اینترنت دریافت می‌کند و پس از نمایه‌سازی در BM25 برای آموزش نقش آماده می‌سازد."
    override val intents = setOf(SolAction.WEB_BOOK_LEARN_ROLE)
    override val sampleCommandsFa = listOf("به اینترنت برو کتاب عادت‌های اتمی را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن")
    override val sensitivity = Sensitivity.CONFIRM
    override val requiredPermissions = emptyList<String>()
    override val category = SkillCategory.ADVANCED_LEARNING

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val query = cmd.bookQuery.orEmpty().trim()
        val reply = if (query.isBlank()) {
            "باشه، آماده‌ام از اینترنت دریافت کنم. لطفاً نام کتاب، موضوع یا آدرس صفحهٔ اینترنتی مورد نظرتان را در کادر وارد کنید."
        } else {
            "درخواست جست‌وجو و دریافت «$query» آماده شد؛ می‌توانید از اینترنت دریافت کنید یا فایل PDF / EPUB / TXT انتخاب کنید و با توضیحات صوتی به Sol آموزش دهید."
        }
        return SkillResult(
            replyText = reply,
            parsedCommand = cmd,
            pendingTask = PendingSolTask.InternetBookRoleFetch(
                bookQuery = query,
                requestedRoleHint = cmd.roleName.orEmpty(),
                runInBackground = cmd.backgroundJobId != null,
            ),
        )
    }
}

class WebSearchSkill : SolSkill {
    override val id = "skill_web_search"
    override val titleFa = "جست‌وجوی وب با تأیید صریح (DuckDuckGo HTML)"
    override val descriptionFa = "جست‌وجوی اینترنتی بدون کلید API از طریق Jsoup؛ هرگز بدون تأیید کاربر به اینترنت متصل نمی‌شود."
    override val intents = setOf(SolAction.SEARCH_WEB)
    override val sampleCommandsFa = listOf("جستجوی وب قیمت سکه امروز")
    override val sensitivity = Sensitivity.CONFIRM
    override val requiredPermissions = emptyList<String>()
    override val category = SkillCategory.ADVANCED_LEARNING

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val query = cmd.searchQuery.orEmpty().trim()
        val reply = if (query.isBlank()) {
            "این عملیات از اینترنت استفاده می‌کند. لطفاً عبارت مورد نظر برای جست‌وجوی وب را در کادر وارد و تأیید کنید."
        } else {
            "این عملیات از اینترنت استفاده می‌کند. برای جست‌وجوی «$query» در وب، لطفاً در پنجرهٔ تأیید ادامه دهید یا لغو کنید."
        }
        return SkillResult(
            replyText = reply,
            parsedCommand = cmd,
            pendingTask = PendingSolTask.ConfirmWebSearch(query = query),
        )
    }
}

class TeachRoleVoiceSkill(
    private val context: Context,
    private val knowledgeRepository: KnowledgeRepository,
) : SolSkill {
    override val id = "skill_teach_role"
    override val titleFa = "آموزش نقش شخصی جدید به Sol"
    override val descriptionFa = "تعریف نقش تخصصی و اتصال به کتاب‌های نمایه‌شده در حافظهٔ Sol."
    override val intents = setOf(SolAction.TEACH_ROLE_VOICE)
    override val sampleCommandsFa = listOf("نقش تو مربی بهره‌وری باشد")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val roleName = cmd.roleName?.ifBlank { "دستیار تخصصی" } ?: "دستیار تخصصی"
        val latestBook = knowledgeRepository.getAllBooksOnce().firstOrNull()
        knowledgeRepository.saveRole(
            roleName = roleName,
            instructions = cmd.message.orEmpty(),
            linkedBookTitle = latestBook?.title.orEmpty(),
            isActive = true,
        )
        SolWidgetStateMirror.saveAndRefresh(
            context = context,
            confirmedLocation = ctx.preferences.confirmedCurrentLocation,
            activeRoleName = roleName,
        )
        val bookSuffix = if (latestBook != null) " و به کتاب «${latestBook.title}» متصل شد" else ""
        return SkillResult(
            replyText = "نقش جدید «$roleName» با دستور شما ذخیره$bookSuffix و فعال شد (ویجت صفحه اصلی نیز به‌روزرسانی شد).",
            parsedCommand = cmd,
        )
    }
}

class ConsultRoleBookSkill(
    private val memoryRepository: MemoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val bookRagEngine: BookRagEngine,
    private val localSlmEngine: LocalSlmEngine,
) : SolSkill {
    override val id = "skill_consult_role_book"
    override val titleFa = "مشاوره و پاسخ‌گویی طبق نقش‌ها و کتاب‌های آموخته‌شده"
    override val descriptionFa = "استفاده آفلاین از نقش و کتابی که با جست‌وجوی BM25 و توضیحات خودتان آموزش داده‌اید."
    override val intents = setOf(SolAction.CONSULT_ROLE_BOOK)
    override val sampleCommandsFa = listOf("طبق نقشت درباره برنامه‌ریزی و عادت‌ها راهنماییم کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val prefs = ctx.preferences
        val rag = bookRagEngine.consultOffline(query = cmd.rawText)
        val canRunNeuralSlm = prefs.slmSynthesisEnabled && localSlmEngine.hasInstalledModel(prefs.slmModelPath)
        return if (canRunNeuralSlm && (rag.topChunks.isNotEmpty() || rag.activeRole != null)) {
            val sysPrompt = buildSlmSystemInstruction(
                rag.activeRole,
                rag.topChunks,
                rag.referencedBook?.voiceNotes.orEmpty(),
            )
            SkillResult(
                replyText = "",
                parsedCommand = cmd,
                speakReply = false,
                pendingTask = PendingSolTask.RunLocalSlmChat(
                    userPrompt = cmd.rawText,
                    systemInstruction = sysPrompt,
                    fallbackReplyText = rag.answerText,
                    hasBookGrounding = rag.topChunks.isNotEmpty(),
                ),
            )
        } else {
            SkillResult(replyText = rag.answerText, parsedCommand = cmd)
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

class CallSkill(
    private val learningEngine: LearningEngine,
) : SolSkill {
    override val id = "skill_call"
    override val titleFa = "جست‌وجوی مخاطب و تماس با تأیید اجباری"
    override val descriptionFa = "نمایش نام، شماره و متن معرفی Sol پیش از باز کردن شماره‌گیر تلفن (بدون تماس خودکار)."
    override val intents = setOf(SolAction.CALL)
    override val sampleCommandsFa = listOf("با علی تماس بگیر و بگو بیاد شرکت")
    override val sensitivity = Sensitivity.CONFIRM
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val contact = cmd.contact.orEmpty()
        return if (contact.isBlank()) {
            SkillResult(
                replyText = "با چه کسی تماس بگیرم؟ مثلاً: با علی تماس بگیر.",
                parsedCommand = cmd,
            )
        } else {
            val (resolvedMsg, _) = learningEngine.resolveAliasesInText(cmd.message.orEmpty())
            SkillResult(
                replyText = "در حال آماده‌سازی تماس با $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                parsedCommand = cmd,
                pendingTask = PendingSolTask.Communicate(
                    preferSms = false,
                    contactQuery = contact,
                    messageBody = resolvedMsg,
                    locationReference = cmd.locationReference,
                    isMultiStepReport = false,
                ),
            )
        }
    }
}

class SmsAndNotifySkill(
    private val learningEngine: LearningEngine,
) : SolSkill {
    override val id = "skill_sms_notify"
    override val titleFa = "تنظیم پیامک و اطلاع‌رسانی با معرفی شفاف Sol"
    override val descriptionFa = "آماده‌سازی پیامک متنی با معرفی دستیار از طرف شما و باز کردن پیام‌رسان فقط پس از تأیید."
    override val intents = setOf(SolAction.SEND_SMS, SolAction.MULTI_STEP_NOTIFY)
    override val sampleCommandsFa = listOf("به علی پیام بده ساعت ۵ جلسه داریم")
    override val sensitivity = Sensitivity.CONFIRM
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val contact = cmd.contact.orEmpty()
        return when (cmd.action) {
            SolAction.SEND_SMS -> {
                if (contact.isBlank()) {
                    SkillResult(
                        replyText = "به چه کسی پیام بدهم؟ مثلاً: به علی پیام بده فردا جلسه داریم.",
                        parsedCommand = cmd,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(cmd.message.orEmpty())
                    SkillResult(
                        replyText = "در حال آماده‌سازی پیامک برای $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                        parsedCommand = cmd,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = cmd.locationReference,
                            isMultiStepReport = false,
                        ),
                    )
                }
            }
            SolAction.MULTI_STEP_NOTIFY -> {
                if (contact.isBlank()) {
                    SkillResult(
                        replyText = "نام مخاطب را برای اجرای فرمان چندمرحله‌ای مشخص کن.",
                        parsedCommand = cmd,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(cmd.message.orEmpty())
                    SkillResult(
                        replyText = "اجرای فرمان چندمرحله‌ای برای $contact آغاز شد…",
                        parsedCommand = cmd,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = cmd.locationReference,
                            isMultiStepReport = true,
                        ),
                    )
                }
            }
            else -> SkillResult(replyText = "", parsedCommand = cmd)
        }
    }
}

class DeviceStatusSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_device_status"
    override val titleFa = "گزارش باتری، زمان شارژ، رم و فضای ذخیره‌سازی"
    override val descriptionFa = "خواندن درصد شارژ، منبع برق، زمان تخمینی شارژ، حافظه آزاد رم و فضای خالی گوشی."
    override val intents = setOf(SolAction.DEVICE_STATUS)
    override val sampleCommandsFa = listOf("وضعیت گوشی و باتری چطوره؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        return SkillResult(
            replyText = "وضعیت دستگاه: ${deviceController.buildDeviceSummaryFa()}",
            parsedCommand = cmd,
        )
    }
}

class VolumeControlSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_volume_control"
    override val titleFa = "کنترل صدای رسانهٔ گوشی"
    override val descriptionFa = "افزایش یا کاهش مستقیم صدای پخش رسانهٔ دستگاه."
    override val intents = setOf(SolAction.ADJUST_VOLUME)
    override val sampleCommandsFa = listOf("صدای گوشی را زیاد کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val status = deviceController.adjustMediaVolume(cmd.volumeDelta)
        val dir = if (cmd.volumeDelta > 0) "افزایش" else "کاهش"
        return SkillResult(
            replyText = "صدای رسانه $dir یافت (${status.mediaCurrent} از ${status.mediaMax}).",
            parsedCommand = cmd,
        )
    }
}

class OpenSettingsSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_open_settings"
    override val titleFa = "باز کردن صادقانهٔ تنظیمات وای‌فای، بلوتوث، دیتا و مکان"
    override val descriptionFa = "باز کردن پنل/تنظیمات مجاز سیستم همراه با توضیح صادقانه دربارهٔ محدودیت‌های اندروید."
    override val intents = setOf(SolAction.OPEN_SETTINGS)
    override val sampleCommandsFa = listOf("تنظیمات وای‌فای را باز کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val sc = deviceController.settingsController
        val target = cmd.settingTarget ?: "WIFI"
        val toggleRequested = (cmd.slots["toggle_requested"] as? SlotValue.Flag)?.enabled == true

        val (opened, label) = when (target) {
            "WIFI" -> sc.openWifiSettings() to "تنظیمات وای‌فای"
            "DATA" -> sc.openMobileDataSettings() to "تنظیمات اینترنت موبایل"
            "LOCATION" -> sc.openLocationSettings() to "تنظیمات مکان"
            "BLUETOOTH" -> sc.openBluetoothSettings() to "تنظیمات بلوتوث"
            "AIRPLANE" -> sc.openAirplaneModeSettings() to "تنظیمات حالت هواپیما"
            "SOUND" -> sc.openSoundSettings() to "تنظیمات صدا"
            else -> sc.openBatterySettings() to "تنظیمات باتری"
        }

        val restrictedToggleFeature = when (target) {
            "WIFI" -> "وای‌فای"
            "DATA" -> "دیتای موبایل"
            "BLUETOOTH" -> "بلوتوث"
            "LOCATION" -> "مکان‌یاب (GPS)"
            "AIRPLANE" -> "حالت هواپیما"
            else -> null
        }

        val reply = when {
            restrictedToggleFeature != null && toggleRequested -> {
                if (opened) {
                    "اندروید اجازهٔ روشن یا خاموش کردن خودکار «$restrictedToggleFeature» را به برنامه‌ها نمی‌دهد؛ برای امنیت شما $label را باز کردم تا خودتان تغییر دهید."
                } else {
                    "اندروید اجازهٔ تغییر خودکار «$restrictedToggleFeature» را به برنامه‌ها نمی‌دهد و باز کردن $label در این دستگاه ممکن نشد."
                }
            }
            opened -> "$label باز شد."
            else -> "باز کردن $label در این دستگاه ممکن نشد."
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class OpenAppSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_open_app"
    override val titleFa = "باز کردن برنامه‌های نصب‌شده"
    override val descriptionFa = "جست‌وجوی نام فارسی/انگلیسی و باز کردن مستقیم برنامه‌های گوشی."
    override val intents = setOf(SolAction.OPEN_APP)
    override val sampleCommandsFa = listOf("ماشین حساب را باز کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val target = cmd.targetApp.orEmpty()
        val launched = if (target.isNotBlank()) deviceController.appLauncher.launchAppByQuery(target) else null
        val reply = if (launched != null) {
            "برنامهٔ «$launched» باز شد."
        } else {
            "برنامهٔ «$target» در فهرست برنامه‌های قابل‌اجرا پیدا نشد."
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class RoutineSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_run_routine"
    override val titleFa = "اجرای روتین کار یا شب"
    override val descriptionFa = "گزارش سریع وضعیت موقعیت فعلی، باتری و آمادگی موتور آفلاین."
    override val intents = setOf(SolAction.RUN_ROUTINE)
    override val sampleCommandsFa = listOf("روتین حالت کار را اجرا کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val bat = deviceController.getBatteryStatus()
        val loc = ctx.preferences.confirmedCurrentLocation.ifBlank { "ثبت‌نشده" }
        val todayJalali = JalaliCalendar.fromMillis().formatFullFa()
        val routineLabel = if (cmd.routineName == "NIGHT") "شب" else "کار"
        return SkillResult(
            replyText = "روتین «$routineLabel» ($todayJalali) اجرا شد:\n• موقعیت فعلی تأییدشده: $loc\n• باتری: ${bat.levelPercent}٪\n• موتور BM25 و حافظه محلی: آماده",
            parsedCommand = cmd,
        )
    }
}

class UnknownFallbackSkill(
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val bookRagEngine: BookRagEngine,
    private val localSlmEngine: LocalSlmEngine,
    private val consultSkill: ConsultRoleBookSkill,
) : SolSkill {
    override val id = "skill_unknown_fallback"
    override val titleFa = "پاسخ‌گوی هوشمند حافظه، کتاب و SLM محلی"
    override val descriptionFa = "جست‌وجوی مترادف‌ها، مخاطبین، نکات و کتاب‌های Room یا مدل زبانی آفلاین برای جملات آزاد."
    override val intents = setOf(SolAction.UNKNOWN)
    override val sampleCommandsFa = emptyList<String>()
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val rawInput = cmd.rawText
        val prefs = ctx.preferences

        val (resolved, matched) = learningEngine.resolveAliasesInText(rawInput)
        if (matched.isNotEmpty()) {
            val pairs = matched.joinToString("، ") { "«${it.first}» = «${it.second}»" }
            return SkillResult(
                replyText = "طبق یادگیری قبلی ($pairs)، برداشت من این است: «$resolved».",
                parsedCommand = cmd,
            )
        }

        val queryTokens = bookRagEngine.tokenize(rawInput)
        if (queryTokens.isNotEmpty()) {
            val normRaw = PersianNormalizer.normalize(rawInput)
            val matchedPerson = learningEngine.findMatchingPersonHint(normRaw)
            if (matchedPerson != null) {
                val phonePart = if (matchedPerson.phone.isNotBlank()) " — شماره: ${matchedPerson.phone}" else ""
                val relPart = if (matchedPerson.relation.isNotBlank()) " (${matchedPerson.relation})" else ""
                return SkillResult(
                    replyText = "مخاطب «${matchedPerson.name}»$relPart$phonePart در حافظه ثبت شده است.",
                    parsedCommand = cmd.copy(
                        contact = matchedPerson.name,
                        phone = matchedPerson.phone.takeIf { it.isNotBlank() } ?: cmd.phone,
                    ),
                )
            }

            val matchedLocation = locationRepository.getAllOnce().firstOrNull { loc ->
                normRaw.contains(loc.normalizedAlias) ||
                    normRaw.contains(PersianNormalizer.normalize(loc.fullName))
            }
            if (matchedLocation != null) {
                val addrPart = if (matchedLocation.addressNote.isNotBlank()) "\nنشانی: ${matchedLocation.addressNote}" else ""
                return SkillResult(
                    replyText = "مکان «${matchedLocation.alias}» همان «${matchedLocation.fullName}» است.$addrPart",
                    parsedCommand = cmd,
                )
            }

            val matchedFact = memoryRepository.getAllOnce().firstOrNull { fact ->
                val factNorm = PersianNormalizer.normalize("${fact.keyText} ${fact.valueText}")
                queryTokens.any { token -> token.length >= 3 && factNorm.contains(token) }
            }
            if (matchedFact != null) {
                return SkillResult(
                    replyText = "در حافظهٔ شما ثبت شده: ${matchedFact.valueText}",
                    parsedCommand = cmd,
                )
            }
        }

        val rag = bookRagEngine.consultOffline(query = rawInput)
        val hasInstalledSlm = localSlmEngine.hasInstalledModel(prefs.slmModelPath)
        val canRunNeuralSlm = prefs.slmSynthesisEnabled && hasInstalledSlm

        return when {
            canRunNeuralSlm -> {
                val sysPrompt = consultSkill.buildSlmSystemInstruction(
                    activeRole = rag.activeRole,
                    groundedChunks = rag.topChunks,
                    voiceNotes = rag.referencedBook?.voiceNotes.orEmpty(),
                )
                val fallbackText = if (rag.topChunks.isNotEmpty() || rag.activeRole != null) rag.answerText else ""
                SkillResult(
                    replyText = "",
                    parsedCommand = cmd,
                    speakReply = false,
                    pendingTask = PendingSolTask.RunLocalSlmChat(
                        userPrompt = rawInput,
                        systemInstruction = sysPrompt,
                        fallbackReplyText = fallbackText,
                        hasBookGrounding = rag.topChunks.isNotEmpty(),
                        allowIntentProposal = rag.topChunks.isEmpty() && rag.activeRole == null,
                    ),
                )
            }
            rag.topChunks.isNotEmpty() || rag.activeRole != null -> SkillResult(
                replyText = rag.answerText,
                parsedCommand = cmd,
            )
            hasInstalledSlm -> SkillResult(
                replyText = "این مورد در حافظه یا کتاب‌ها پیدا نشد. فایل مدل زبانی آفلاین روی گوشی نصب است؛ برای گفت‌وگوی آزاد، در «تنظیمات ← بخش ۳» سوئیچ مدل زبانی آفلاین (فاز ۳) را روشن کنید.",
                parsedCommand = cmd,
            )
            else -> SkillResult(
                replyText = "متوجه نشدم و در حافظه یا کتاب‌ها هم پیدا نشد (هنوز فایل مدل زبانی آفلاین .litertlm نصب نشده است).\n" +
                    "می‌توانید یکی از این نمونه‌ها را امتحان کنید:\n" +
                    "• «امروز چندم برج است؟»\n" +
                    "• «تایمر ۱۰ دقیقه» یا «۲۵ درصد ۸۰۰»\n" +
                    "• «فردا ساعت ۸ صبح یادآوری کن جلسه دارم»\n" +
                    "• «به علی تماس بگیر»",
                parsedCommand = cmd,
            )
        }
    }
}
