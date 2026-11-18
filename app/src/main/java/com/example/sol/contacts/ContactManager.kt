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

class ContactResolver {
    fun resolve(query: String, contacts: List<ResolvedContact>): List<ResolvedContact> {
        val normQuery = PersianNormalizer.normalize(query)
        if (normQuery.isBlank()) return contacts
        val exact = contacts.filter { PersianNormalizer.normalize(it.name) == normQuery }
        if (exact.isNotEmpty()) return exact
        return contacts.filter {
            val normName = PersianNormalizer.normalize(it.name)
            val normRel = PersianNormalizer.normalize(it.relation)
            normName.contains(normQuery) ||
                normQuery.contains(normName) ||
                (normRel.isNotBlank() && normRel.contains(normQuery)) ||
                it.phone.contains(normQuery)
        }
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
