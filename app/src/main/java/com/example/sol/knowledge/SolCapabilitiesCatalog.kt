package com.example.sol.knowledge

import com.example.sol.brain.SolAction
import com.example.sol.brain.skills.Sensitivity
import com.example.sol.brain.skills.SkillCategory
import com.example.sol.brain.skills.SkillRegistry

enum class CapabilityCategory(val labelFa: String) {
    BACKGROUND("اجراهای پس‌زمینه (Background)"),
    ADVANCED_LEARNING("یادگیری پیشرفته: اینترنت + کتاب + نقش"),
    CORE_ASSISTANT("مهارت‌های هوشمند، حافظه و ابزارهای آفلاین"),
}

data class BackgroundJobDescriptor(
    val id: String,
    val titleFa: String,
    val descriptionFa: String,
    val sampleCommandFa: String,
    val backingSkillId: String,
)

data class SolCapabilityItem(
    val number: Int,
    val id: String,
    val category: CapabilityCategory,
    val titleFa: String,
    val descriptionFa: String,
    val sampleCommandFa: String,
    val backingSkillId: String,
    val sensitivity: Sensitivity = Sensitivity.NONE,
    val isBackgroundToggleable: Boolean = false,
)

object SolCapabilitiesCatalog {
    val backgroundJobs: List<BackgroundJobDescriptor> = listOf(
        BackgroundJobDescriptor(
            id = "bg_reminder",
            titleFa = "یادآور زمان‌بندی‌شده در پس‌زمینه",
            descriptionFa = "تنظیم یادآور با AlarmManager دقیق + BootReceiver (بازیابی خودکار پس از روشن‌شدن گوشی) که حتی با بسته‌شدن اپ اعلان فارسی نمایش می‌دهد.",
            sampleCommandFa = "۱ دقیقه دیگر یادآوری کن وضعیت پروژه را بررسی کنم",
            backingSkillId = "skill_reminder_alarm",
        ),
        BackgroundJobDescriptor(
            id = "bg_daily_briefing",
            titleFa = "روتین روزانه و گزارش صبحگاهی پس‌زمینه",
            descriptionFa = "اجرای دوره‌ای روزانه با WorkManager برای خلاصه باتری، موقعیت تأییدشده و نکات مهم.",
            sampleCommandFa = "روتین صبحگاهی پس‌زمینه را فعال کن",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_battery_guard",
            titleFa = "پایش هوشمند باتری در پس‌زمینه",
            descriptionFa = "بررسی دوره‌ای سطح شارژ و دما با WorkManager و ارسال هشدار در صورت کاهش شدید باتری.",
            sampleCommandFa = "پایش باتری در پس‌زمینه را اجرا کن",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_memory_backup",
            titleFa = "پشتیبان‌گیری خودکار حافظه در فضای خصوصی",
            descriptionFa = "تهیه فایل پشتیبان رمزنگاری‌شدهٔ محلی از حافظه، کتاب‌ها و نقش‌های Sol در حافظه خصوصی اپ.",
            sampleCommandFa = "از حافظه در پس‌زمینه پشتیبان بگیر",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_memory_indexer",
            titleFa = "مرتب‌سازی و نمایه‌سازی حافظه و نقش‌ها",
            descriptionFa = "به‌روزرسانی شاخص مترادف‌ها، اشخاص و کتاب‌های یادگرفته‌شده در پس‌زمینه.",
            sampleCommandFa = "حافظه را در پس‌زمینه مرتب کن",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_location_checkin",
            titleFa = "یادآور تأیید موقعیت فعلی",
            descriptionFa = "ارسال اعلان ملایم برای تأیید دستی موقعیت فعلی بدون ردیابی پنهانی GPS.",
            sampleCommandFa = "یادآور تأیید موقعیت را فعال کن",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_tts_reader_service",
            titleFa = "سرویس روخوانی صوتی کتاب و گزارش (Foreground Service)",
            descriptionFa = "پخش صوتی خلاصه کتاب یا گزارش حافظه با اعلان پایدار و دکمه توقف.",
            sampleCommandFa = "گزارش حافظه را با سرویس صوتی بخوان",
            backingSkillId = "skill_background_control",
        ),
        BackgroundJobDescriptor(
            id = "bg_multistep_queue",
            titleFa = "صف اجرای فرمان‌های چندمرحله‌ای",
            descriptionFa = "ادامهٔ پردازش مراحل و ثبت گزارش نتیجه در پایگاه داده پس از تأیید کاربر.",
            sampleCommandFa = "وضعیت شرکت را به علی اطلاع بده و بعد نتیجه را به من گزارش کن",
            backingSkillId = "skill_sms_notify",
        ),
        BackgroundJobDescriptor(
            id = "bg_book_fetch_worker",
            titleFa = "دریافت کتاب در پس‌زمینه پس از تأیید",
            descriptionFa = "واکشی کتاب از مخازن عمومی پس از اجازه کاربر و ذخیره آفلاین در Room.",
            sampleCommandFa = "در پس‌زمینه کتاب شازده کوچولو را دریافت کن",
            backingSkillId = "skill_web_book_role",
        ),
        BackgroundJobDescriptor(
            id = "bg_history_cleanup",
            titleFa = "نگهداری و هرس خودکار سوابق قدیمی",
            descriptionFa = "مدیریت حجم سوابق قدیمی در پس‌زمینه برای سبک نگه‌داشتن پایگاه داده.",
            sampleCommandFa = "سوابق قدیمی را در پس‌زمینه بهینه‌سازی کن",
            backingSkillId = "skill_background_control",
        ),
    )

    fun buildCatalog(skillRegistry: SkillRegistry): List<SolCapabilityItem> {
        val items = mutableListOf<SolCapabilityItem>()

        for (job in backgroundJobs) {
            val backingSkill = skillRegistry.getSkillById(job.backingSkillId)
                ?: error("Background job '${job.id}' references missing skill '${job.backingSkillId}'")
            items += SolCapabilityItem(
                number = items.size + 1,
                id = job.id,
                category = CapabilityCategory.BACKGROUND,
                titleFa = job.titleFa,
                descriptionFa = job.descriptionFa,
                sampleCommandFa = job.sampleCommandFa,
                backingSkillId = backingSkill.id,
                sensitivity = backingSkill.sensitivity,
                isBackgroundToggleable = true,
            )
        }

        val interactiveSkills = skillRegistry.allSkills()
            .filter { SolAction.UNKNOWN !in it.intents }
            .sortedBy { if (it.category == SkillCategory.ADVANCED_LEARNING) 0 else 1 }

        for (skill in interactiveSkills) {
            val mappedCategory = when (skill.category) {
                SkillCategory.BACKGROUND -> CapabilityCategory.BACKGROUND
                SkillCategory.ADVANCED_LEARNING -> CapabilityCategory.ADVANCED_LEARNING
                SkillCategory.CORE_ASSISTANT -> CapabilityCategory.CORE_ASSISTANT
            }
            items += SolCapabilityItem(
                number = items.size + 1,
                id = skill.id,
                category = mappedCategory,
                titleFa = skill.titleFa,
                descriptionFa = skill.descriptionFa,
                sampleCommandFa = skill.sampleCommandsFa.firstOrNull().orEmpty(),
                backingSkillId = skill.id,
                sensitivity = skill.sensitivity,
                isBackgroundToggleable = false,
            )
        }

        return items
    }
}
