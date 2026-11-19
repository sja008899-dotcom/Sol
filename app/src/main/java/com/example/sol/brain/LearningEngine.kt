package com.example.sol.brain

import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.PersonRepository

class LearningEngine(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    private val locationRepository: LocationRepository,
) {
    suspend fun teachAlias(alias: String, meaning: String): String {
        val cleanAlias = alias.trim()
        val cleanMeaning = meaning.trim()
        memoryRepository.remember(
            key = "alias:$cleanAlias",
            value = cleanMeaning,
            kind = "alias",
        )
        // Also index in locations if it sounds like a place
        val norm = PersianNormalizer.normalize(cleanAlias)
        if (norm in setOf("دفتر", "شرکت", "خانه", "خونه", "منزل", "کارگاه", "مغازه")) {
            locationRepository.saveLocation(alias = cleanAlias, fullName = cleanMeaning)
        }
        return "یاد گرفتم. از این به بعد هر وقت بگویی «$cleanAlias»، منظورم «$cleanMeaning» است."
    }

    suspend fun teachFact(fact: String): String {
        memoryRepository.remember(
            key = "fact:${System.currentTimeMillis()}",
            value = fact.trim(),
            kind = "fact",
        )
        return "این نکته را در حافظهٔ محلی ذخیره کردم: $fact"
    }

    suspend fun teachPerson(name: String, phone: String, relation: String): String {
        personRepository.savePerson(
            name = name,
            phone = phone,
            relation = relation,
        )
        val relLabel = if (relation.isNotBlank()) " ($relation)" else ""
        return "اطلاعات $name$relLabel با شماره $phone در حافظهٔ اشخاص Sol ذخیره شد."
    }

    suspend fun teachLocation(alias: String, fullName: String): String {
        locationRepository.saveLocation(alias = alias, fullName = fullName)
        return "مکان «$alias» به عنوان «$fullName» در مکان‌های ذخیره‌شده ثبت شد (جدا از موقعیت فعلی)."
    }

    suspend fun forget(target: String): String {
        val deletedAlias = memoryRepository.forgetByKey("alias:$target")
        if (deletedAlias) return "قانون «$target» از حافظه حذف شد."
        val all = memoryRepository.getAllOnce()
        val normTarget = PersianNormalizer.normalize(target)
        val match = all.firstOrNull {
            PersianNormalizer.normalize(it.valueText).contains(normTarget) ||
                PersianNormalizer.normalize(it.keyText).contains(normTarget)
        }
        return if (match != null && memoryRepository.forgetById(match.id)) {
            "مورد «${match.valueText}» از حافظه حذف شد."
        } else {
            "این عبارت را در حافظه پیدا نکردم."
        }
    }

    suspend fun findMatchingPersonHint(normQuery: String): PersonEntity? {
        if (normQuery.isBlank()) return null
        val persons = personRepository.getAllOnce()
        return persons.firstOrNull { p ->
            val pNorm = PersianNormalizer.normalize(p.name)
            pNorm.length >= 2 && normQuery.contains(pNorm)
        }
    }

    suspend fun resolveAliasesInText(input: String): Pair<String, List<Pair<String, String>>> {
        val all = memoryRepository.getAllOnce().filter { it.kind == "alias" }
        var resolved = input
        val matched = mutableListOf<Pair<String, String>>()
        for (item in all) {
            val alias = item.keyText.removePrefix("alias:")
            if (alias.isNotBlank() && PersianNormalizer.normalize(input).contains(PersianNormalizer.normalize(alias))) {
                matched.add(alias to item.valueText)
                resolved = resolved.replace(alias, item.valueText)
            }
        }
        return resolved to matched
    }
}
