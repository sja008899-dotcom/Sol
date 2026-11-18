package com.example.sol.brain

import com.example.sol.device.DeviceController
import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PreferencesRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandBrain(
    private val parser: IntentParser,
    private val learningEngine: LearningEngine,
    private val memoryRepository: MemoryRepository,
    private val locationRepository: LocationRepository,
    private val preferencesRepository: PreferencesRepository,
    private val deviceController: DeviceController,
) {
    suspend fun process(rawInput: String): BrainExecutionResult {
        val parsed = parser.parse(rawInput)
        val prefs = preferencesRepository.getSnapshot()

        return when (parsed.action) {
            SolAction.GREETING -> BrainExecutionResult(
                replyText = "سلام ${prefs.ownerName}. من Sol هستم و کاملاً آفلاین آماده‌ام. چه کاری انجام بدهم؟",
                parsedCommand = parsed,
            )

            SolAction.IDENTITY -> BrainExecutionResult(
                replyText = "من Sol هستم؛ دستیار شخصی آفلاین و قانون‌محور شما. تمام حافظه‌ام روی خود گوشی ذخیره می‌شود و هیچ داده‌ای به اینترنت نمی‌فرستم.",
                parsedCommand = parsed,
            )

            SolAction.HELP -> BrainExecutionResult(
                replyText = "نمونه فرمان‌های من:\n• هر وقت می‌گم دفتر منظورم شرکت کارنو صنعت ماکیان است\n• الان شرکت هستم (ثبت موقعیت فعلی)\n• با علی تماس بگیر / به علی پیام بده که...\n• وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن\n• وضعیت باتری چطوره؟ / وای‌فای را باز کن / صدا را زیاد کن",
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
                val reply = if (memories.isEmpty() && locations.isEmpty() && prefs.confirmedCurrentLocation.isBlank()) {
                    "هنوز چیزی در حافظهٔ شخصی ذخیره نشده است."
                } else {
                    buildString {
                        if (prefs.confirmedCurrentLocation.isNotBlank()) {
                            append("موقعیت فعلی تأییدشده: ${prefs.confirmedCurrentLocation}\n")
                        }
                        if (memories.isNotEmpty()) {
                            append("یادگیری‌ها و نکات:\n")
                            memories.take(15).forEach { m ->
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
                if (matched.isNotEmpty()) {
                    val pairs = matched.joinToString("، ") { "«${it.first}» = «${it.second}»" }
                    BrainExecutionResult(
                        replyText = "طبق یادگیری قبلی ($pairs)، برداشت من این است: «$resolved».\nمی‌توانید بگویید با چه کسی تماس بگیرم یا چه نکته‌ای را ذخیره کنم.",
                        parsedCommand = parsed,
                    )
                } else {
                    BrainExecutionResult(
                        replyText = "این فرمان را هنوز دقیق نشناختم. می‌توانید با «هر وقت می‌گم ... منظورم ... است» به من یاد بدهید یا «کمک» بنویسید.",
                        parsedCommand = parsed,
                    )
                }
            }
        }
    }
}
