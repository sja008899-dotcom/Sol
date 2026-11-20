package com.example.sol.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.PersonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ContactSource(val labelFa: String) {
    DEVICE("دفترچه تلفن"),
    MEMORY("حافظه شخصی Sol"),
    BOTH("دفترچه و حافظه Sol"),
}

data class ResolvedContact(
    val id: String,
    val name: String,
    val phone: String,
    val source: ContactSource,
    val relation: String = "",
    val callScriptNote: String = "",
)

sealed interface ContactResolutionResult {
    data class Resolved(
        val contact: ResolvedContact,
        val matchReason: String,
    ) : ContactResolutionResult

    data class Ambiguous(
        val query: String,
        val candidates: List<ResolvedContact>,
    ) : ContactResolutionResult

    data class NotFound(
        val query: String,
    ) : ContactResolutionResult
}

class ContactResolver {

    companion object {
        private val RELATION_SYNONYMS: List<Set<String>> = listOf(
            setOf("مامان", "مادر", "مادرم", "والده"),
            setOf("بابا", "پدر", "پدرم"),
            setOf("همسر", "همسرم", "خانم", "خانمم", "شوهر", "شوهرم"),
            setOf("داداش", "برادر", "برادرم", "اخوی"),
            setOf("ابجی", "آبجی", "خواهر", "خواهرم"),
            setOf("پسر", "پسرم"),
            setOf("دختر", "دخترم"),
            setOf("مدیر", "رییس", "رئیس"),
            setOf("همکار", "همکارم"),
        ).map { group -> group.map { PersianNormalizer.normalize(it) }.toSet() }

        fun levenshteinDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        curr[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost,
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }
    }

    fun resolve(query: String, contacts: List<ResolvedContact>): List<ResolvedContact> {
        return when (val res = resolveStructured(query, contacts)) {
            is ContactResolutionResult.Resolved -> listOf(res.contact)
            is ContactResolutionResult.Ambiguous -> res.candidates
            is ContactResolutionResult.NotFound -> emptyList()
        }
    }

    fun resolveStructured(query: String, contacts: List<ResolvedContact>): ContactResolutionResult {
        val normQuery = PersianNormalizer.normalize(query)
        if (normQuery.isBlank()) {
            return if (contacts.size == 1) {
                ContactResolutionResult.Resolved(contacts.first(), "single_contact")
            } else if (contacts.size > 1) {
                ContactResolutionResult.Ambiguous(query, contacts)
            } else {
                ContactResolutionResult.NotFound(query)
            }
        }

        // Tier 1: Exact normalized full name
        val exact = contacts.filter { PersianNormalizer.normalize(it.name) == normQuery }
        if (exact.size == 1) return ContactResolutionResult.Resolved(exact.first(), "exact_name")
        if (exact.size > 1) return ContactResolutionResult.Ambiguous(query, exact)

        // Tier 2: Phone digits match
        val queryDigits = normQuery.filter { it.isDigit() }
        if (queryDigits.length >= 4) {
            val phoneMatches = contacts.filter {
                val cDigits = it.phone.filter { ch -> ch.isDigit() }
                cDigits.isNotBlank() && (cDigits.endsWith(queryDigits) || cDigits.contains(queryDigits))
            }
            if (phoneMatches.size == 1) return ContactResolutionResult.Resolved(phoneMatches.first(), "phone")
            if (phoneMatches.size > 1) return ContactResolutionResult.Ambiguous(query, phoneMatches)
        }

        // Tier 3: Relation / nickname synonyms ("مامان" <-> relation "مادر")
        val relationGroup = RELATION_SYNONYMS.firstOrNull { normQuery in it }
        if (relationGroup != null) {
            val relMatches = contacts.filter { c ->
                val relTokens = PersianNormalizer.normalize(c.relation).split(' ').filter { it.isNotBlank() }
                val nameTokens = PersianNormalizer.normalize(c.name).split(' ').filter { it.isNotBlank() }
                (relTokens + nameTokens).any { it in relationGroup }
            }
            if (relMatches.size == 1) return ContactResolutionResult.Resolved(relMatches.first(), "relation")
            if (relMatches.size > 1) return ContactResolutionResult.Ambiguous(query, relMatches)
        }

        // Tier 4: Exact token / prefix match
        val queryTokens = normQuery.split(' ').filter { it.isNotBlank() }
        val tokenMatches = contacts.filter { c ->
            val normName = PersianNormalizer.normalize(c.name)
            val normRel = PersianNormalizer.normalize(c.relation)
            val nameTokens = normName.split(' ').filter { it.isNotBlank() }
            normName.startsWith(normQuery) ||
                queryTokens.all { qt -> nameTokens.any { nt -> nt == qt || nt.startsWith(qt) } } ||
                (normRel.isNotBlank() && normRel.contains(normQuery))
        }
        if (tokenMatches.size == 1) return ContactResolutionResult.Resolved(tokenMatches.first(), "prefix_token")
        if (tokenMatches.size > 1) return ContactResolutionResult.Ambiguous(query, tokenMatches)

        // Tier 5: Substring containment (excluding trivial 1-char)
        if (normQuery.length >= 2) {
            val subMatches = contacts.filter { c ->
                val normName = PersianNormalizer.normalize(c.name)
                normName.contains(normQuery) || (normName.length >= 3 && normQuery.contains(normName))
            }
            if (subMatches.size == 1) return ContactResolutionResult.Resolved(subMatches.first(), "substring")
            if (subMatches.size > 1) return ContactResolutionResult.Ambiguous(query, subMatches)
        }

        // Tier 6: Bounded edit distance <= 1 ONLY for queries and name tokens of length >= 4
        if (normQuery.length >= 4) {
            val fuzzyMatches = contacts.filter { c ->
                val normName = PersianNormalizer.normalize(c.name)
                val nameTokens = normName.split(' ').filter { it.length >= 4 }
                (normName.length >= 4 && levenshteinDistance(normQuery, normName) <= 1) ||
                    queryTokens.filter { it.length >= 4 }.any { qt ->
                        nameTokens.any { nt -> levenshteinDistance(qt, nt) <= 1 }
                    }
            }
            if (fuzzyMatches.size == 1) return ContactResolutionResult.Resolved(fuzzyMatches.first(), "fuzzy_levenshtein")
            if (fuzzyMatches.size > 1) return ContactResolutionResult.Ambiguous(query, fuzzyMatches)
        }

        return ContactResolutionResult.NotFound(query)
    }
}

class ContactManager(
    private val context: Context,
    private val personRepository: PersonRepository,
    private val resolver: ContactResolver = ContactResolver(),
) {
    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadMergedContacts(): List<ResolvedContact> = withContext(Dispatchers.IO) {
        val memoryPeople = personRepository.getAllOnce()
        val deviceContacts = if (hasContactsPermission()) queryDeviceContacts() else emptyList()

        val merged = linkedMapOf<String, ResolvedContact>()

        // Index memory people first
        for (p in memoryPeople) {
            val key = PersianNormalizer.normalize(p.name)
            merged[key] = ResolvedContact(
                id = "mem_${p.id}",
                name = p.name,
                phone = p.phone,
                source = ContactSource.MEMORY,
                relation = p.relation,
                callScriptNote = p.callScriptNote,
            )
        }

        // Merge device contacts
        for (dc in deviceContacts) {
            val key = PersianNormalizer.normalize(dc.name)
            val existing = merged[key]
            if (existing != null) {
                merged[key] = existing.copy(
                    phone = existing.phone.ifBlank { dc.phone },
                    source = ContactSource.BOTH,
                )
            } else {
                merged[key] = dc
            }
        }

        merged.values.sortedBy { it.name }
    }

    suspend fun findMatchingContacts(query: String): List<ResolvedContact> {
        val all = loadMergedContacts()
        return resolver.resolve(query, all)
    }

    suspend fun resolveContact(query: String): ContactResolutionResult {
        val all = loadMergedContacts()
        return resolver.resolveStructured(query, all)
    }

    suspend fun saveContactToMemory(
        name: String,
        phone: String,
        relation: String = "",
        callScriptNote: String = "",
    ) {
        personRepository.savePerson(
            name = name,
            phone = phone,
            relation = relation,
            callScriptNote = callScriptNote,
        )
    }

    private fun queryDeviceContacts(): List<ResolvedContact> {
        val result = mutableListOf<ResolvedContact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val seen = HashSet<String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1)?.trim() ?: continue
                    val number = cursor.getString(2)?.trim() ?: continue
                    val dedupKey = "${PersianNormalizer.normalize(name)}|$number"
                    if (seen.add(dedupKey)) {
                        result.add(
                            ResolvedContact(
                                id = "dev_${id}_${number.hashCode()}",
                                name = name,
                                phone = number,
                                source = ContactSource.DEVICE,
                            )
                        )
                    }
                }
            }
        }
        return result
    }
}
