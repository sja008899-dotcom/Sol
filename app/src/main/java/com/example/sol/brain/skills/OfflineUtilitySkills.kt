package com.example.sol.brain.skills

import android.view.KeyEvent
import com.example.sol.brain.CalculationResult
import com.example.sol.brain.ParsedCommand
import com.example.sol.brain.SafeCalculator
import com.example.sol.brain.SlotValue
import com.example.sol.brain.SolAction
import com.example.sol.calendar.JalaliCalendar
import com.example.sol.calendar.TimerController
import com.example.sol.device.DeviceController
import com.example.sol.memory.NoteTodoRepository
import com.example.sol.memory.PersianNormalizer

class TimerStopwatchSkill(
    private val timerController: TimerController,
) : SolSkill {
    override val id = "skill_timer_stopwatch"
    override val titleFa = "تایمر و کرنومتر آفلاین"
    override val descriptionFa = "شمارش معکوس با AlarmManager و اعلان اختصاصی Sol و کرنومتر دقیق بدون نیاز به اپ ساعت."
    override val intents = setOf(SolAction.TIMER_STOPWATCH)
    override val sampleCommandsFa = listOf("تایمر ۱۰ دقیقه")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val mode = (cmd.slots["timer_mode"] as? SlotValue.Text)?.value ?: "TIMER"
        val op = (cmd.slots["timer_op"] as? SlotValue.Text)?.value ?: "START"

        if (mode == "STOPWATCH") {
            val snap = when (op) {
                "STOP" -> timerController.stopStopwatch()
                "RESET" -> timerController.resetStopwatch()
                "STATUS" -> timerController.getStopwatchStatus()
                else -> timerController.startStopwatch()
            }
            val reply = when (op) {
                "STOP" -> "کرنومتر متوقف شد — زمان ثبت‌شده: ${snap.formattedFa}"
                "RESET" -> "کرنومتر صفر شد (00:00.0)."
                "STATUS" -> if (snap.isRunning) {
                    "کرنومتر در حال اجراست: ${snap.formattedFa}"
                } else {
                    "کرنومتر متوقف است — زمان ذخیره شده: ${snap.formattedFa}"
                }
                else -> "کرنومتر آغاز شد (زمان فعلی: ${snap.formattedFa})."
            }
            return SkillResult(replyText = reply, parsedCommand = cmd)
        }

        val reply = when (op) {
            "CANCEL" -> {
                val cancelled = timerController.cancelCountdownTimer()
                if (cancelled) "تایمر فعال لغو شد." else "تایمر فعالی در حال اجرا نبود."
            }
            "STATUS" -> {
                val st = timerController.getCountdownStatus()
                if (st.isActive) {
                    "${st.labelFa}: ${TimerController.formatDurationFa(st.remainingSeconds)} باقی مانده است."
                } else {
                    "تایمر فعالی تنظیم نشده است."
                }
            }
            else -> {
                val durationSec = (cmd.slots["duration_seconds"] as? SlotValue.Number)?.value?.toLong() ?: 0L
                if (durationSec <= 0L) {
                    "لطفاً مدت تایمر را مشخص کنید (مثلاً: تایمر ۱۰ دقیقه یا تایمر ۴۵ ثانیه)."
                } else {
                    val label = cmd.message?.takeIf { it.isNotBlank() } ?: "تایمر Sol"
                    val res = timerController.startCountdownTimer(durationSec, label)
                    val modeNote = if (res.outcome.isExactGranted) {
                        "AlarmManager دقیق"
                    } else {
                        "هشدار زمان‌بندی‌شده"
                    }
                    "${res.labelFa} برای ${TimerController.formatDurationFa(res.durationSeconds)} تنظیم شد ($modeNote، پایان: ${res.outcome.jalaliDateTimeFa})."
                }
            }
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class FlashlightSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_flashlight"
    override val titleFa = "کنترل چراغ‌قوه (بدون نیاز به مجوز دوربین)"
    override val descriptionFa = "روشن و خاموش کردن مستقیم فلش پشت گوشی از طریق CameraManager."
    override val intents = setOf(SolAction.FLASHLIGHT)
    override val sampleCommandsFa = listOf("چراغ قوه را روشن کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val flag = (cmd.slots["torch_enabled"] as? SlotValue.Flag)?.enabled
        val res = deviceController.setFlashlight(flag)
        return SkillResult(replyText = res.messageFa, parsedCommand = cmd)
    }
}

class CalendarEventSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_calendar_event"
    override val titleFa = "افزودن رویداد شمسی به تقویم گوشی"
    override val descriptionFa = "تبدیل تاریخ شمسی به میلادی و باز کردن فرم ثبت رویداد تقویم بدون مجوز اضافه."
    override val intents = setOf(SolAction.CREATE_CALENDAR_EVENT)
    override val sampleCommandsFa = listOf("رویداد جلسه فردا ساعت ۱۰ به تقویم اضافه کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val title = cmd.message?.takeIf { it.isNotBlank() } ?: "رویداد Sol"
        val slotMillis = (cmd.slots["event_begin_ms"] as? SlotValue.Number)?.value?.toLong()
        val parsed = if (slotMillis != null) {
            slotMillis to JalaliCalendar.fromMillis(slotMillis)
        } else {
            JalaliCalendar.parseEventDateTimeRange(cmd.rawText) ?: run {
                val now = System.currentTimeMillis()
                val nextHour = ((now / 3_600_000L) + 1L) * 3_600_000L
                nextHour to JalaliCalendar.fromMillis(nextHour)
            }
        }
        val beginMillis = parsed.first
        val endMillis = beginMillis + 3_600_000L
        val opened = deviceController.openCalendarInsertEvent(
            title = title,
            beginMillis = beginMillis,
            endMillis = endMillis,
            description = "ثبت‌شده با دستیار Sol",
        )
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = beginMillis }
        val timeText = String.format(
            java.util.Locale.US,
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
        val reply = if (opened) {
            "فرم ثبت رویداد «$title» برای ${parsed.second.formatFullFa()} (${parsed.second.formatShortFa()}) ساعت $timeText در برنامهٔ تقویم باز شد."
        } else {
            "برنامهٔ تقویمی برای دریافت رویداد «$title» روی این گوشی پیدا نشد."
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class CalculatorSkill : SolSkill {
    override val id = "skill_calculator"
    override val titleFa = "ماشین‌حساب ایمن فارسی و عددی"
    override val descriptionFa = "محاسبهٔ عبارت‌های ریاضی و کلمات فارسی (مثل «دو ضرب در سه» و «۲۵ درصد ۸۰۰») بدون موتور اسکریپت."
    override val intents = setOf(SolAction.CALCULATOR)
    override val sampleCommandsFa = listOf("۲۵ درصد ۸۰۰ چند میشه؟")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val expr = cmd.message?.takeIf { it.isNotBlank() } ?: cmd.rawText
        val reply = when (val res = SafeCalculator.evaluate(expr)) {
            is CalculationResult.Success -> "نتیجه: ${res.formattedFa}"
            is CalculationResult.Failure -> res.reasonFa
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}

class NotesTodoSkill(
    private val noteTodoRepository: NoteTodoRepository,
) : SolSkill {
    override val id = "skill_notes_todo"
    override val titleFa = "یادداشت، لیست خرید و کارهای امروز"
    override val descriptionFa = "ذخیرهٔ یادداشت‌ها، فهرست خرید و کارهای روزانه در Room همراه با خواندن و علامت‌گذاری."
    override val intents = setOf(SolAction.NOTES_TODO)
    override val sampleCommandsFa = listOf("لیست خرید نان و شیر اضافه کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val kind = (cmd.slots["note_kind"] as? SlotValue.Text)?.value ?: "NOTE"
        val op = (cmd.slots["note_op"] as? SlotValue.Text)?.value ?: "LIST"
        val content = cmd.message.orEmpty().trim()
        val labelFa = kindLabelFa(kind)

        val reply = when (op) {
            "ADD" -> {
                if (content.isBlank()) {
                    formatListReply(kind, labelFa)
                } else {
                    noteTodoRepository.addItem(kind, content)
                    "در $labelFa ثبت شد: «$content»."
                }
            }
            "DONE" -> {
                val items = noteTodoRepository.getByKindOnce(kind).filter { !it.isDone }
                val normTarget = PersianNormalizer.normalize(content)
                val target = items.firstOrNull {
                    normTarget.isNotBlank() && PersianNormalizer.normalize(it.content).contains(normTarget)
                } ?: items.firstOrNull {
                    normTarget.isNotBlank() && normTarget.contains(PersianNormalizer.normalize(it.content))
                } ?: if (content.isBlank() && items.size == 1) items.first() else null

                if (target != null) {
                    noteTodoRepository.markDone(target.id, true)
                    "مورد «${target.content}» در $labelFa تیک خورد."
                } else if (items.isEmpty()) {
                    "مورد انجام‌نشده‌ای در $labelFa وجود ندارد."
                } else {
                    "مورد مطابق «$content» در $labelFa پیدا نشد."
                }
            }
            "DELETE" -> {
                val items = noteTodoRepository.getByKindOnce(kind).ifEmpty {
                    noteTodoRepository.getAllOnce()
                }
                val normTarget = PersianNormalizer.normalize(content)
                val target = items.firstOrNull {
                    normTarget.isNotBlank() && PersianNormalizer.normalize(it.content).contains(normTarget)
                } ?: items.firstOrNull {
                    normTarget.isNotBlank() && normTarget.contains(PersianNormalizer.normalize(it.content))
                }
                if (target != null) {
                    noteTodoRepository.deleteById(target.id)
                    "«${target.content}» از $labelFa حذف شد."
                } else {
                    "موردی برای حذف در $labelFa پیدا نشد."
                }
            }
            else -> formatListReply(kind, labelFa)
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }

    private suspend fun formatListReply(kind: String, labelFa: String): String {
        val items = noteTodoRepository.getByKindOnce(kind)
        if (items.isEmpty()) {
            return "هنوز موردی در $labelFa ثبت نشده است."
        }
        return buildString {
            append("$labelFa (${items.size} مورد):\n")
            items.take(15).forEachIndexed { idx, item ->
                val mark = if (item.isDone) "✓" else "○"
                append("${idx + 1}. [$mark] ${item.content}\n")
            }
        }.trim()
    }

    companion object {
        fun kindLabelFa(kind: String): String = when (kind) {
            "SHOPPING" -> "لیست خرید"
            "TODO" -> "کارهای امروز"
            else -> "یادداشت‌ها"
        }
    }
}

class MediaControlSkill(
    private val deviceController: DeviceController,
) : SolSkill {
    override val id = "skill_media_control"
    override val titleFa = "کنترل پخش موسیقی و رسانه"
    override val descriptionFa = "ارسال کلیدهای استاندارد پخش، توقف، آهنگ بعدی و قبلی از طریق AudioManager."
    override val intents = setOf(SolAction.MEDIA_CONTROL)
    override val sampleCommandsFa = listOf("آهنگ بعدی را پخش کن")
    override val sensitivity = Sensitivity.NONE
    override val requiredPermissions = emptyList<String>()

    override suspend fun execute(cmd: ParsedCommand, ctx: SkillContext): SkillResult {
        val op = (cmd.slots["media_op"] as? SlotValue.Text)?.value ?: "PLAY"
        val (keyCode, labelFa) = when (op) {
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT to "آهنگ بعدی"
            "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "آهنگ قبلی"
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "توقف پخش"
            else -> KeyEvent.KEYCODE_MEDIA_PLAY to "پخش موسیقی"
        }
        val dispatched = deviceController.dispatchMediaKey(keyCode)
        val reply = if (dispatched) {
            "فرمان «$labelFa» به پخش‌کنندهٔ فعال ارسال شد."
        } else {
            "ارسال فرمان رسانه در این دستگاه ممکن نشد."
        }
        return SkillResult(replyText = reply, parsedCommand = cmd)
    }
}
