package com.example.sol.brain

import com.example.sol.background.BackgroundTaskCoordinator
import com.example.sol.device.DeviceController
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.PreferencesRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandBrain(
    private val parser: IntentParser,
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val preferencesRepository: PreferencesRepository,
    private val deviceController: DeviceController,
    private val backgroundCoordinator: BackgroundTaskCoordinator,
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
                        append("\nتعداد کتاب‌های ذخیره‌شده در حافظه محلی: $booksCount")
                    }
                }
                BrainExecutionResult(
                    replyText = "من Sol هستم؛ دستیار شخصی آفلاین و قانون‌محور شما. فقط با اجازهٔ صریح شما به اینترنت می‌روم و پس از ذخیره در حافظه محلی کاملاً مستقل عمل می‌کنم.$extra",
                    parsedCommand = parsed,
                )
            }

            SolAction.SHOW_CAPABILITIES -> BrainExecutionResult(
                replyText = "فهرست کامل ۲۰ قابلیت Sol (شامل ۱۰ اجرای پس‌زمینه، ۱ دستور پیشرفتهٔ اینترنت + کتاب + یادگیری نقش صوتی و ۹ قابلیت هوشمند) باز شد.",
                parsedCommand = parsed,
                pendingTask = PendingSolTask.OpenCapabilitiesCatalog(focusBackgroundOnly = false),
            )

            SolAction.HELP -> BrainExecutionResult(
                replyText = "نمونه فرمان‌های Sol:\n• «۲۰ قابلیت» برای مشاهده کل ۱۰ اجرای پس‌زمینه و دستورهای پیشرفته\n• به اینترنت برو کتاب Atomic Habits را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن\n• ۵ دقیقه دیگر یادآوری کن با علی تماس بگیرم\n• از حافظه در پس‌زمینه پشتیبان بگیر\n• هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است\n• وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن",
                parsedCommand = parsed,
            )

            SolAction.TIME_DATE -> {
                val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                val dateStr = SimpleDateFormat("yyyy/MM/dd (EEEE)", Locale("fa", "IR")).format(Date())
                BrainExecutionResult(
                    replyText = "ساعت دستگاه $timeStr و تاریخ $dateStr است.",
                    parsedCommand = parsed,
                )
            }

            SolAction.SCHEDULE_REMINDER -> {
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

            SolAction.BACKGROUND_CONTROL -> {
                val jobId = parsed.backgroundJobId ?: "bg_memory_backup"
                val reply = backgroundCoordinator.triggerImmediateBackgroundJob(jobId)
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.WEB_BOOK_LEARN_ROLE -> {
                val query = parsed.bookQuery.orEmpty().ifBlank { "Little Prince" }
                BrainExecutionResult(
                    replyText = "درخواست جست‌وجوی کتاب «$query» در اینترنت آماده شد؛ لطفاً اتصال اختیاری به اینترنت را تأیید کنید تا بعد با توضیحات صوتی شما به حافظه و نقش Sol اضافه شود.",
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
                val bookSuffix = if (latestBook != null) " و به کتاب «${latestBook.title}» متصل شد" else ""
                BrainExecutionResult(
                    replyText = "نقش جدید «$roleName» با دستور شما ذخیره$bookSuffix و فعال شد.",
                    parsedCommand = parsed,
                )
            }

            SolAction.CONSULT_ROLE_BOOK -> {
                val roles = knowledgeRepository.getAllRolesOnce().filter { it.isActive }
                val books = knowledgeRepository.getAllBooksOnce()
                val reply = if (roles.isEmpty() && books.isEmpty()) {
                    "هنوز کتاب یا نقشی آموزش داده نشده است. می‌توانید بگویید: «به اینترنت برو کتاب ... را پیدا کن و با توضیحات صوتی من به حافظه و نقشت اضافه کن»."
                } else {
                    buildString {
                        if (roles.isNotEmpty()) {
                            val r = roles.first()
                            append("بر اساس نقش فعال «${r.roleName}»")
                            if (r.instructions.isNotBlank()) append(" (${r.instructions})")
                            append(":\n")
                        }
                        val normQuery = PersianNormalizer.normalize(parsed.rawText)
                        val matchedBook = books.firstOrNull {
                            normQuery.contains(PersianNormalizer.normalize(it.title))
                        } ?: books.firstOrNull()
                        if (matchedBook != null) {
                            append("📘 کتاب مرجع: ${matchedBook.title} (${matchedBook.author})\n")
                            append("خلاصه: ${matchedBook.summaryText.take(360)}\n")
                            if (matchedBook.voiceNotes.isNotBlank()) {
                                append("🎙 یادداشت‌های صوتی شما:\n• ${matchedBook.voiceNotes}")
                            }
                        }
                    }.trim()
                }
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.LEARN_ALIAS -> {
                val reply = learningEngine.teachAlias(
                    parsed.aliasKey.orEmpty(),
                    parsed.aliasValue.orEmpty(),
                )
                BrainExecutionResult(replyText = reply, parsedCommand = parsed)
            }

            SolAction.LEARN_FACT -> {
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
                        replyText = "در حال آماده‌سازی تماس با $contact؛ لطفاً تأیید کنید.",
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
                        replyText = "در حال آماده‌سازی پیامک برای $contact؛ لطفاً متن و شماره را تأیید کنید.",
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
                val routineLabel = if (parsed.routineName == "NIGHT") "شب" else "کار"
                BrainExecutionResult(
                    replyText = "روتین «$routineLabel» اجرا شد:\n• موقعیت فعلی تأییدشده: $loc\n• باتری: ${bat.levelPercent}٪\n• حافظهٔ محلی: فعال و آماده",
                    parsedCommand = parsed,
                )
            }

            SolAction.UNKNOWN -> {
                val (resolved, matched) = learningEngine.resolveAliasesInText(rawInput)
                val activeRole = knowledgeRepository.getAllRolesOnce().firstOrNull { it.isActive }
                val books = knowledgeRepository.getAllBooksOnce()
                val normIn = PersianNormalizer.normalize(rawInput)
                val bookMatch = books.firstOrNull {
                    normIn.contains(PersianNormalizer.normalize(it.title)) ||
                        (it.voiceNotes.isNotBlank() && PersianNormalizer.normalize(it.voiceNotes).split(" ").any { w -> w.length > 3 && normIn.contains(w) })
                }

                when {
                    matched.isNotEmpty() -> {
                        val pairs = matched.joinToString("، ") { "«${it.first}» = «${it.second}»" }
                        BrainExecutionResult(
                            replyText = "طبق یادگیری قبلی ($pairs)، برداشت من این است: «$resolved».",
                            parsedCommand = parsed,
                        )
                    }
                    bookMatch != null || activeRole != null -> {
                        val rolePrefix = if (activeRole != null) "در نقش «${activeRole.roleName}»" else "طبق دانش ذخیره‌شده"
                        val bookBody = if (bookMatch != null) {
                            "\n📘 مرجع «${bookMatch.title}»: ${bookMatch.summaryText.take(260)}" +
                                if (bookMatch.voiceNotes.isNotBlank()) "\n🎙 توضیح صوتی شما: ${bookMatch.voiceNotes}" else ""
                        } else {
                            "\nدستورالعمل نقش شما: ${activeRole?.instructions.orEmpty()}"
                        }
                        BrainExecutionResult(
                            replyText = "$rolePrefix پاسخ می‌دهم:$bookBody",
                            parsedCommand = parsed,
                        )
                    }
                    else -> BrainExecutionResult(
                        replyText = "این فرمان را هنوز دقیق نشناختم. می‌توانید «۲۰ قابلیت» را بزنید، یا با «هر وقت می‌گم ... منظورم ... است» یاد بدهید، یا کتابی را با توضیح صوتی به نقشم اضافه کنید.",
                        parsedCommand = parsed,
                    )
                }
            }
        }
    }
}
