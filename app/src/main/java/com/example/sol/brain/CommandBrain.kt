package com.example.sol.brain

import android.content.Context
import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.calendar.ExactAlarmScheduler
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.device.DeviceController
import com.example.sol.knowledge.BookRagEngine
import com.example.sol.knowledge.LocalSlmEngine
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PreferencesRepository
import com.example.sol.widget.SolWidgetStateMirror

class CommandBrain(
    private val context: Context,
    private val parser: IntentParser,
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
    private val deviceController: DeviceController,
    private val backgroundCoordinator: BackgroundTaskCoordinator,
    private val bookRagEngine: BookRagEngine,
    private val localSlmEngine: LocalSlmEngine,
    private val exactAlarmScheduler: ExactAlarmScheduler,
) {
    suspend fun process(rawInput: String): BrainExecutionResult {
        val parsed = parser.parse(rawInput)
        val prefs = preferencesRepository.getSnapshot()

        return when (parsed.action) {
            SolAction.GREETING -> {
                val activeRole = knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }
                val roleNote = if (activeRole != null) {
                    " (نقش فعال: ${activeRole.roleName})"
                } else {
                    ""
                }
                BrainExecutionResult(
                    replyText = "سلام ${prefs.ownerName}. من Sol هستم و آماده‌ام$roleNote. چه کاری انجام بدهم؟",
                    parsedCommand = parsed,
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
                    parsedCommand = parsed,
                )
            }

            SolAction.SHOW_CAPABILITIES -> BrainExecutionResult(
                replyText = "فهرست کامل ۲۰ قابلیت Sol (شامل ۱۰ اجرای پس‌زمینه، ۱ دستور پیشرفتهٔ اینترنت + کتاب + یادگیری نقش صوتی و ۹ قابلیت هوشمند) باز شد.",
                parsedCommand = parsed,
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
                parsedCommand = parsed,
            )

            SolAction.TIME_DATE -> {
                val jalaliReply = JalaliCalendar.answerCalendarQuestion(parsed.rawText)
                BrainExecutionResult(
                    replyText = jalaliReply,
                    parsedCommand = parsed,
                )
            }

            SolAction.SCHEDULE_REMINDER -> {
                val schedule = parsed.jalaliSchedule
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
                        parsedCommand = parsed,
                    )
                } else {
                    val msg = parsed.message.orEmpty().ifBlank { "یادآور Sol" }
                    backgroundCoordinator.scheduleReminder(msg, parsed.delayMinutes)
                    knowledgeRepository.logBackgroundJob(
                        jobKey = "bg_reminder",
                        titleFa = "یادآور زمان‌بندی‌شده",
                        status = "SCHEDULED",
                        detailFa = "برای ${parsed.delayMinutes} دقیقه دیگر: $msg",
                    )
                    BrainExecutionResult(
                        replyText = "یادآور پس‌زمینه برای ${parsed.delayMinutes} دقیقه دیگر تنظیم شد: «$msg».",
                        parsedCommand = parsed,
                    )
                }
            }

            SolAction.BACKGROUND_CONTROL -> {
                val jobId = parsed.backgroundJobId ?: "bg_memory_backup"
                val reply = backgroundCoordinator.triggerImmediateBackgroundJob(jobId)
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.WEB_BOOK_LEARN_ROLE -> {
                val query = parsed.bookQuery.orEmpty().trim()
                val reply = if (query.isBlank()) {
                    "باشه، آماده‌ام از اینترنت دریافت کنم. لطفاً نام کتاب، موضوع یا آدرس صفحهٔ اینترنتی مورد نظرتان را در کادر وارد کنید."
                } else {
                    "درخواست جست‌وجو و دریافت «$query» آماده شد؛ می‌توانید از اینترنت دریافت کنید یا فایل PDF / EPUB / TXT انتخاب کنید و با توضیحات صوتی به Sol آموزش دهید."
                }
                BrainExecutionResult(
                    replyText = reply,
                    parsedCommand = parsed,
                    pendingTask = PendingSolTask.InternetBookRoleFetch(
                        bookQuery = query,
                        requestedRoleHint = parsed.roleName.orEmpty(),
                        runInBackground = parsed.backgroundJobId != null,
                    ),
                )
            }

            SolAction.TEACH_ROLE_VOICE -> {
                val roleName = parsed.roleName?.ifBlank { "دستیار تخصصی" } ?: "دستیار تخصصی"
                val latestBook = knowledgeRepository.getAllBooksOnce().firstOrNull()
                knowledgeRepository.saveRole(
                    roleName = roleName,
                    instructions = parsed.message.orEmpty(),
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
                    parsedCommand = parsed,
                )
            }

            SolAction.CONSULT_ROLE_BOOK -> {
                val rag = bookRagEngine.consultOffline(query = parsed.rawText)
                val canRunNeuralSlm = prefs.slmSynthesisEnabled && localSlmEngine.hasInstalledModel(prefs.slmModelPath)
                if (canRunNeuralSlm && (rag.topChunks.isNotEmpty() || rag.activeRole != null)) {
                    val sysPrompt = buildSlmSystemInstruction(rag.activeRole, rag.topChunks, rag.referencedBook?.voiceNotes.orEmpty())
                    BrainExecutionResult(
                        replyText = "",
                        parsedCommand = parsed,
                        speakReply = false,
                        pendingTask = PendingSolTask.RunLocalSlmChat(
                            userPrompt = parsed.rawText,
                            systemInstruction = sysPrompt,
                            fallbackReplyText = rag.answerText,
                            hasBookGrounding = rag.topChunks.isNotEmpty(),
                        ),
                    )
                } else {
                    BrainExecutionResult(replyText = rag.answerText, parsedCommand = parsed)
                }
            }

            SolAction.LEARN_ALIAS -> {
                val reply = learningEngine.teachAlias(
                    parsed.aliasKey.orEmpty(),
                    parsed.aliasValue.orEmpty(),
                )
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.LEARN_FACT -> {
                if (parsed.aliasKey == "owner_name" && !parsed.aliasValue.isNullOrBlank()) {
                    preferencesRepository.setOwnerName(parsed.aliasValue)
                    memoryRepository.remember("owner_name", parsed.aliasValue, kind = "fact")
                    return BrainExecutionResult(
                        replyText = "خوشبختم ${parsed.aliasValue}! نام شما را به خاطر سپردم.",
                        parsedCommand = parsed,
                    )
                }
                val reply = learningEngine.teachFact(parsed.message.orEmpty())
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.FORGET_MEMORY -> {
                val reply = learningEngine.forget(parsed.aliasKey.orEmpty())
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.SAVE_PERSON -> {
                val reply = learningEngine.teachPerson(
                    name = parsed.contact.orEmpty(),
                    phone = parsed.phone.orEmpty(),
                    relation = parsed.relation.orEmpty(),
                )
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.SAVE_LOCATION -> {
                val reply = learningEngine.teachLocation(
                    alias = parsed.aliasKey.orEmpty(),
                    fullName = parsed.aliasValue.orEmpty(),
                )
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.SET_CURRENT_LOCATION -> {
                val rawLoc = parsed.locationReference.orEmpty()
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
                    parsedCommand = parsed,
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
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
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
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.CALL -> {
                val contact = parsed.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "نام مخاطب را بگو؛ مثلاً: با علی تماس بگیر.",
                        parsedCommand = parsed,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(parsed.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "در حال آماده‌سازی تماس با $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                        parsedCommand = parsed,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = false,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = parsed.locationReference,
                            isMultiStepReport = false,
                        ),
                    )
                }
            }

            SolAction.SEND_SMS -> {
                val contact = parsed.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "نام گیرندهٔ پیامک را بگو؛ مثلاً: به علی پیام بده فردا جلسه داریم.",
                        parsedCommand = parsed,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(parsed.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "در حال آماده‌سازی پیامک برای $contact؛ می‌توانید «تأیید» یا «لغو» بگویید یا دکمه را بزنید.",
                        parsedCommand = parsed,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = parsed.locationReference,
                            isMultiStepReport = false,
                        ),
                    )
                }
            }

            SolAction.MULTI_STEP_NOTIFY -> {
                val contact = parsed.contact.orEmpty()
                if (contact.isBlank()) {
                    BrainExecutionResult(
                        replyText = "نام مخاطب را برای اجرای فرمان چندمرحله‌ای مشخص کن.",
                        parsedCommand = parsed,
                    )
                } else {
                    val (resolvedMsg, _) = learningEngine.resolveAliasesInText(parsed.message.orEmpty())
                    BrainExecutionResult(
                        replyText = "اجرای فرمان چندمرحله‌ای برای $contact آغاز شد…",
                        parsedCommand = parsed,
                        pendingTask = PendingSolTask.Communicate(
                            preferSms = true,
                            contactQuery = contact,
                            messageBody = resolvedMsg,
                            locationReference = parsed.locationReference,
                            isMultiStepReport = true,
                        ),
                    )
                }
            }

            SolAction.DEVICE_STATUS -> {
                val summary = deviceController.buildDeviceSummaryFa()
                BrainExecutionResult(replyText = "وضعیت دستگاه: $summary", parsedCommand = parsed)
            }

            SolAction.ADJUST_VOLUME -> {
                val status = deviceController.adjustMediaVolume(parsed.volumeDelta)
                val dir = if (parsed.volumeDelta > 0) "افزایش" else "کاهش"
                BrainExecutionResult(
                    replyText = "صدای رسانه $dir یافت (${status.mediaCurrent} از ${status.mediaMax}).",
                    parsedCommand = parsed,
                )
            }

            SolAction.OPEN_SETTINGS -> {
                val sc = deviceController.settingsController
                val (opened, label) = when (parsed.settingTarget) {
                    "WIFI" -> sc.openWifiSettings() to "تنظیمات وای‌فای"
                    "LOCATION" -> sc.openLocationSettings() to "تنظیمات مکان"
                    "BLUETOOTH" -> sc.openBluetoothSettings() to "تنظیمات بلوتوث"
                    "SOUND" -> sc.openSoundSettings() to "تنظیمات صدا"
                    else -> sc.openBatterySettings() to "تنظیمات باتری"
                }
                val reply = if (opened) "$label باز شد." else "باز کردن $label در این دستگاه ممکن نشد."
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.OPEN_APP -> {
                val target = parsed.targetApp.orEmpty()
                val launched = if (target.isNotBlank()) deviceController.appLauncher.launchAppByQuery(target) else null
                val reply = if (launched != null) {
                    "برنامهٔ «$launched» باز شد."
                } else {
                    "برنامهٔ «$target» در فهرست برنامه‌های قابل‌اجرا پیدا نشد."
                }
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.RUN_ROUTINE -> {
                val bat = deviceController.getBatteryStatus()
                val loc = prefs.confirmedCurrentLocation.ifBlank { "ثبت‌نشده" }
                val todayJalali = JalaliCalendar.fromMillis().formatFullFa()
                val routineLabel = if (parsed.routineName == "NIGHT") "شب" else "کار"
                BrainExecutionResult(
                    replyText = "روتین «$routineLabel» ($todayJalali) اجرا شد:\n• موقعیت فعلی تأییدشده: $loc\n• باتری: ${bat.levelPercent}٪\n• موتور BM25 و حافظه محلی: آماده",
                    parsedCommand = parsed,
                )
            }

            SolAction.UNKNOWN -> {
                val (resolved, matched) = learningEngine.resolveAliasesInText(rawInput)
                if (matched.isNotEmpty()) {
                    val pairs = matched.joinToString("، ") { "«${it.first}» = «${it.second}»" }
                    return BrainExecutionResult(
                        replyText = "طبق یادگیری قبلی ($pairs)، برداشت من این است: «$resolved».",
                        parsedCommand = parsed,
                    )
                }

                // Search saved personal facts, locations, or persons when user asks a question
                val queryTokens = bookRagEngine.tokenize(rawInput)
                if (queryTokens.isNotEmpty()) {
                    val normRaw = com.example.sol.memory.PersianNormalizer.normalize(rawInput)
                    val matchedPerson = learningEngine.findMatchingPersonHint(normRaw)
                    if (matchedPerson != null) {
                        val phonePart = if (matchedPerson.phone.isNotBlank()) " — شماره: ${matchedPerson.phone}" else ""
                        val relPart = if (matchedPerson.relation.isNotBlank()) " (${matchedPerson.relation})" else ""
                        return BrainExecutionResult(
                            replyText = "مخاطب «${matchedPerson.name}»$relPart$phonePart در حافظه ثبت شده است.",
                            parsedCommand = parsed,
                        )
                    }

                    val matchedLocation = locationRepository.getAllOnce().firstOrNull { loc ->
                        normRaw.contains(loc.normalizedAlias) ||
                            normRaw.contains(com.example.sol.memory.PersianNormalizer.normalize(loc.fullName))
                    }
                    if (matchedLocation != null) {
                        val addrPart = if (matchedLocation.addressNote.isNotBlank()) "\nنشانی: ${matchedLocation.addressNote}" else ""
                        return BrainExecutionResult(
                            replyText = "مکان «${matchedLocation.alias}» همان «${matchedLocation.fullName}» است.$addrPart",
                            parsedCommand = parsed,
                        )
                    }

                    val matchedFact = memoryRepository.getAllOnce().firstOrNull { fact ->
                        val factNorm = com.example.sol.memory.PersianNormalizer.normalize("${fact.keyText} ${fact.valueText}")
                        queryTokens.any { token -> token.length >= 3 && factNorm.contains(token) }
                    }
                    if (matchedFact != null) {
                        return BrainExecutionResult(
                            replyText = "در حافظهٔ شما ثبت شده: ${matchedFact.valueText}",
                            parsedCommand = parsed,
                        )
                    }
                }

                // Try on-device BM25 search over stored books + active role
                val rag = bookRagEngine.consultOffline(query = rawInput)
                val hasInstalledSlm = localSlmEngine.hasInstalledModel(prefs.slmModelPath)
                val canRunNeuralSlm = prefs.slmSynthesisEnabled && hasInstalledSlm

                when {
                    canRunNeuralSlm -> {
                        val sysPrompt = buildSlmSystemInstruction(
                            activeRole = rag.activeRole,
                            groundedChunks = rag.topChunks,
                            voiceNotes = rag.referencedBook?.voiceNotes.orEmpty(),
                        )
                        val fallbackText = if (rag.topChunks.isNotEmpty() || rag.activeRole != null) {
                            rag.answerText
                        } else {
                            ""
                        }
                        BrainExecutionResult(
                            replyText = "",
                            parsedCommand = parsed,
                            speakReply = false,
                            pendingTask = PendingSolTask.RunLocalSlmChat(
                                userPrompt = rawInput,
                                systemInstruction = sysPrompt,
                                fallbackReplyText = fallbackText,
                                hasBookGrounding = rag.topChunks.isNotEmpty(),
                            ),
                        )
                    }

                    rag.topChunks.isNotEmpty() || rag.activeRole != null -> {
                        BrainExecutionResult(
                            replyText = rag.answerText,
                            parsedCommand = parsed,
                        )
                    }

                    hasInstalledSlm -> {
                        BrainExecutionResult(
                            replyText = "این مورد در حافظه یا کتاب‌ها پیدا نشد. فایل مدل زبانی آفلاین روی گوشی نصب است؛ برای گفت‌وگوی آزاد، در «تنظیمات ← بخش ۳» سوئیچ مدل زبانی آفلاین (فاز ۳) را روشن کنید.",
                            parsedCommand = parsed,
                        )
                    }

                    else -> {
                        BrainExecutionResult(
                            replyText = "این مورد در فرمان‌ها، حافظهٔ شخصی و کتاب‌های شما پیدا نشد و هنوز فایل مدل زبانی آفلاین (فاز ۳) نصب نشده است.\n" +
                                "برای گفت‌وگوی آزاد، هم‌فکری و پاسخ‌گویی عصبی بدون اینترنت، در «تنظیمات ← بخش ۳» مدل Qwen3-0.6B (~۴۹۸ مگابایت) را دانلود یا فایل .litertlm انتخاب کنید.",
                            parsedCommand = parsed,
                        )
                    }
                }
            }
        }
    }

    private suspend fun buildSlmSystemInstruction(
        activeRole: com.example.sol.memory.SolRoleEntity?,
        groundedChunks: List<com.example.sol.knowledge.RankedBookChunk>,
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
