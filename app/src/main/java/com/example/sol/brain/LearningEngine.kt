package com.example.sol.brain

import com.example.sol.memory.LocationRepository
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.MemoryRepository
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.PersonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LearningEngine(
    private val memoryRepository: MemoryRepository,
    private val personRepository: PersonRepository,
    private val locationRepository: LocationRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private data class CachedAlias(
        val rawAlias: String,
        val normalizedAlias: String,
        val meaning: String,
    )

    @Volatile
    private var cachedAliases: List<CachedAlias>? = null

    @Volatile
    private var cachedPersons: List<Pair<PersonEntity, String>>? = null

    init {
        scope.launch {
            memoryRepository.allMemories.collectLatest { items ->
                cachedAliases = buildAliasCache(items)
            }
        }
        scope.launch {
            personRepository.allPersons.collectLatest { persons ->
                cachedPersons = buildPersonCache(persons)
            }
        }
    }

    fun invalidateCache() {
        cachedAliases = null
        cachedPersons = null
    }

    private fun buildAliasCache(items: List<MemoryFactEntity>): List<CachedAlias> =
        items.asSequence()
            .filter { it.kind == "alias" }
            .mapNotNull { item ->
                val raw = item.keyText.removePrefix("alias:").trim()
                val norm = PersianNormalizer.normalize(raw)
                if (raw.isBlank() || norm.isBlank()) null
                else CachedAlias(rawAlias = raw, normalizedAlias = norm, meaning = item.valueText)
            }
            .sortedByDescending { it.rawAlias.length }
            .toList()

    private fun buildPersonCache(persons: List<PersonEntity>): List<Pair<PersonEntity, String>> =
        persons.map { person ->
            person to PersianNormalizer.normalize(person.name)
        }

    private suspend fun getAliasesCached(): List<CachedAlias> {
        cachedAliases?.let { return it }
        return buildAliasCache(memoryRepository.getAllOnce()).also { cachedAliases = it }
    }

    private suspend fun getPersonsCached(): List<Pair<PersonEntity, String>> {
        cachedPersons?.let { return it }
        return buildPersonCache(personRepository.getAllOnce()).also { cachedPersons = it }
    }

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
        cachedAliases = buildAliasCache(memoryRepository.getAllOnce())
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
        cachedPersons = buildPersonCache(personRepository.getAllOnce())
        val relLabel = if (relation.isNotBlank()) " ($relation)" else ""
        return "اطلاعات $name$relLabel با شماره $phone در حافظهٔ اشخاص Sol ذخیره شد."
    }

    suspend fun teachLocation(alias: String, fullName: String): String {
        locationRepository.saveLocation(alias = alias, fullName = fullName)
        return "مکان «$alias» به عنوان «$fullName» در مکان‌های ذخیره‌شده ثبت شد (جدا از موقعیت فعلی)."
    }

    suspend fun forget(target: String): String {
        val deletedAlias = memoryRepository.forgetByKey("alias:$target")
        if (deletedAlias) {
            cachedAliases = null
            return "قانون «$target» از حافظه حذف شد."
        }
        val all = memoryRepository.getAllOnce()
        val normTarget = PersianNormalizer.normalize(target)
        val match = all.firstOrNull {
            PersianNormalizer.normalize(it.valueText).contains(normTarget) ||
                PersianNormalizer.normalize(it.keyText).contains(normTarget)
        }
        return if (match != null && memoryRepository.forgetById(match.id)) {
            cachedAliases = null
            "مورد «${match.valueText}» از حافظه حذف شد."
        } else {
            "این عبارت را در حافظه پیدا نکردم."
        }
    }

    suspend fun findMatchingPersonHint(normQuery: String): PersonEntity? {
        if (normQuery.isBlank()) return null
        val persons = getPersonsCached()
        return persons.firstOrNull { (_, pNorm) ->
            pNorm.length >= 2 && normQuery.contains(pNorm)
        }?.first
    }

    suspend fun resolveAliasesInText(input: String): Pair<String, List<Pair<String, String>>> {
        val aliases = getAliasesCached()
        if (aliases.isEmpty()) return input to emptyList()
        val normInput = PersianNormalizer.normalize(input)
        var resolved = input
        val matched = mutableListOf<Pair<String, String>>()
        for (entry in aliases) {
            if (normInput.contains(entry.normalizedAlias)) {
                matched.add(entry.rawAlias to entry.meaning)
                resolved = resolved.replace(entry.rawAlias, entry.meaning)
            }
        }
        return resolved to matched
    }
}
