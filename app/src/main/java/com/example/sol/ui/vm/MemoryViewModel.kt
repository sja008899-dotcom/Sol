package com.example.sol.ui.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SolApplication
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.PersonEntity
import com.example.sol.memory.SolRoleEntity
import com.example.sol.memory.UserUtteranceExampleEntity
import com.example.sol.ui.PendingBookFetchConfirmation
import com.example.sol.ui.VoiceRoleTeachingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as SolApplication).container

    val memories: StateFlow<List<MemoryFactEntity>> = c.memoryRepository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val persons: StateFlow<List<PersonEntity>> = c.personRepository.allPersons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<LocationEntity>> = c.locationRepository.allLocations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val books: StateFlow<List<BookKnowledgeEntity>> = c.knowledgeRepository.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val roles: StateFlow<List<SolRoleEntity>> = c.knowledgeRepository.roles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userUtteranceExamples: StateFlow<List<UserUtteranceExampleEntity>> = c.userUtteranceRepository.allExamples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingBookFetch = MutableStateFlow<PendingBookFetchConfirmation?>(null)
    val pendingBookFetch: StateFlow<PendingBookFetchConfirmation?> = _pendingBookFetch.asStateFlow()

    private val _isFetchingBook = MutableStateFlow(false)
    val isFetchingBook: StateFlow<Boolean> = _isFetchingBook.asStateFlow()

    private val _voiceRoleSession = MutableStateFlow<VoiceRoleTeachingSession?>(null)
    val voiceRoleSession: StateFlow<VoiceRoleTeachingSession?> = _voiceRoleSession.asStateFlow()

    init {
        viewModelScope.launch {
            c.uiBridge.events.collect { ev ->
                when (ev) {
                    is SolUiEvent.OpenBookFetchDialog -> {
                        _pendingBookFetch.value = PendingBookFetchConfirmation(
                            query = ev.query,
                            roleHint = ev.roleHint,
                            runInBackground = ev.runInBackground,
                        )
                    }
                    is SolUiEvent.ResetChatAfterWipe -> {
                        _pendingBookFetch.value = null
                        _voiceRoleSession.value = null
                    }
                    else -> Unit
                }
            }
        }
    }

    fun requestBookRoleStudio(defaultQuery: String = "", roleHint: String = "") {
        _pendingBookFetch.value = PendingBookFetchConfirmation(
            query = defaultQuery,
            roleHint = roleHint,
            runInBackground = false,
        )
    }

    fun updatePendingBookQuery(query: String, roleHint: String) {
        _pendingBookFetch.value = _pendingBookFetch.value?.copy(query = query, roleHint = roleHint)
    }

    fun dismissPendingBookFetch() {
        _pendingBookFetch.value = null
    }

    fun confirmInternetBookFetch(runInBackground: Boolean) {
        val req = _pendingBookFetch.value ?: return
        _pendingBookFetch.value = null

        if (runInBackground) {
            c.backgroundTaskCoordinator.enqueueBookFetchWorker(req.query)
            val msg = "دریافت کتاب «${req.query}» در پس‌زمینه زمان‌بندی شد؛ پس از دریافت در Room ذخیره و در موتور BM25 نمایه‌سازی می‌شود."
            c.uiBridge.postBotMessage(text = msg, actionBadge = "BG_BOOK", speakIfAutoSpeak = true)
            return
        }

        _isFetchingBook.value = true
        viewModelScope.launch {
            val result = c.bookWebFetcher.fetchBookOrWebpageFromInternet(req.query)
            _isFetchingBook.value = false
            result.fold(
                onSuccess = { fetched ->
                    val entity = BookKnowledgeEntity(
                        title = fetched.title,
                        author = fetched.author,
                        subjects = fetched.subjects,
                        summaryText = fetched.summaryText,
                        sourceType = fetched.sourceType,
                        sourceUrl = fetched.sourceUrl,
                    )
                    val savedId = c.knowledgeRepository.saveBook(entity)
                    val outcome = c.bookRagEngine.indexBookContentWithNotice(savedId, fetched.title, fetched.fullText)
                    val savedBook = entity.copy(id = savedId)
                    val noticeSuffix = if (outcome.wasTruncated) "\n${outcome.userNoticeFa}" else ""
                    val msg = "📘 منبع «${fetched.title}» اثر ${fetched.author} ذخیره و به ${outcome.chunksCount} بخش معنایی (BM25) تبدیل شد.$noticeSuffix\nاکنون می‌توانید با توضیحات صوتی خود، نقش جدید Sol را آموزش دهید."
                    c.uiBridge.postBotMessage(text = msg, actionBadge = "BOOK_SAVED", speakIfAutoSpeak = true)
                    val currentPrefs = c.preferencesRepository.getSnapshot()
                    c.historyRepository.recordIfAllowed(
                        userText = "دریافت کتاب/صفحه ${req.query}",
                        solText = msg,
                        intentAction = "WEB_BOOK_LEARN_ROLE",
                        retentionMode = currentPrefs.historyRetentionMode,
                        privateModeEnabled = currentPrefs.privateModeEnabled,
                    )
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = savedBook,
                        roleNameDraft = req.roleHint.ifBlank { "مشاور و راهنمای ${fetched.title}" },
                    )
                },
                onFailure = { err ->
                    val failMsg = err.message ?: "دریافت کتاب از اینترنت ناموفق بود."
                    c.uiBridge.postBotMessage(text = failMsg, actionBadge = "BOOK_ERROR")
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = null,
                        roleNameDraft = req.roleHint.ifBlank { "نقش مبتنی بر ${req.query}" },
                    )
                }
            )
        }
    }

    fun importLocalBookFile(uri: Uri, titleHint: String = "") {
        _isFetchingBook.value = true
        viewModelScope.launch {
            val result = c.bookWebFetcher.importLocalDocument(uri, titleHint)
            _isFetchingBook.value = false
            result.fold(
                onSuccess = { imported ->
                    val id = c.knowledgeRepository.saveBook(imported.entity)
                    val outcome = c.bookRagEngine.indexBookContentWithNotice(id, imported.entity.title, imported.fullText)
                    val saved = imported.entity.copy(id = id)
                    val noticeSuffix = if (outcome.wasTruncated) "\n${outcome.userNoticeFa}" else ""
                    val msg = "فایل «${saved.title}» به صورت آفلاین استخراج و به ${outcome.chunksCount} بخش برای جست‌وجوی BM25 نمایه‌سازی شد.$noticeSuffix"
                    c.uiBridge.postBotMessage(text = msg, actionBadge = "LOCAL_BOOK")
                    _voiceRoleSession.value = VoiceRoleTeachingSession(
                        book = saved,
                        roleNameDraft = "راهنمای ${saved.title}",
                    )
                },
                onFailure = { err ->
                    c.uiBridge.postBotMessage(text = err.message ?: "خواندن فایل ناموفق بود.", actionBadge = "BOOK_ERROR")
                }
            )
        }
    }

    fun openVoiceRoleTeachingForBook(book: BookKnowledgeEntity?) {
        _voiceRoleSession.value = VoiceRoleTeachingSession(
            book = book,
            roleNameDraft = if (book != null) "مشاور ${book.title}" else "دستیار تخصصی من",
        )
    }

    fun updateVoiceRoleDraft(roleName: String, explanation: String) {
        _voiceRoleSession.value = _voiceRoleSession.value?.copy(
            roleNameDraft = roleName,
            voiceExplanationDraft = explanation,
        )
    }

    fun listenVoiceExplanationForRole() {
        viewModelScope.launch {
            val prefs = c.preferencesRepository.getSnapshot()
            _voiceRoleSession.value = _voiceRoleSession.value?.copy(isListeningForExplanation = true)
            c.voiceEngine.startListening(
                preferVosk = prefs.voskOfflineEnabled,
                vadSilenceMs = prefs.vadSilenceTimeoutMs,
                maxDurationMs = prefs.maxVoiceListenSeconds * 1000L,
                playCue = prefs.voiceCueFeedbackEnabled,
            ) { spoken ->
                val current = _voiceRoleSession.value
                if (current != null) {
                    val merged = listOf(current.voiceExplanationDraft, spoken)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    _voiceRoleSession.value = current.copy(
                        voiceExplanationDraft = merged,
                        isListeningForExplanation = false,
                    )
                }
            }
        }
    }

    fun stopVoiceExplanationListening() {
        c.voiceEngine.stopListening()
        _voiceRoleSession.value = _voiceRoleSession.value?.copy(isListeningForExplanation = false)
    }

    fun saveVoiceRoleTeaching() {
        val session = _voiceRoleSession.value ?: return
        val roleName = session.roleNameDraft.trim().ifBlank { "نقش شخصی Sol" }
        val explanation = session.voiceExplanationDraft.trim()
        _voiceRoleSession.value = null

        viewModelScope.launch {
            session.book?.let { b ->
                if (explanation.isNotBlank()) {
                    c.knowledgeRepository.attachVoiceNoteToBook(b.id, explanation)
                    c.bookRagEngine.indexBookContentWithNotice(
                        b.id,
                        b.title,
                        "${b.summaryText}\nتوضیح صوتی کاربر: $explanation",
                    )
                }
            }
            c.knowledgeRepository.saveRole(
                roleName = roleName,
                instructions = explanation.ifBlank { "بهره‌گیری از نکات کتاب ${session.book?.title.orEmpty()}" },
                linkedBookTitle = session.book?.title.orEmpty(),
                isActive = true,
            )
            if (explanation.isNotBlank()) {
                c.memoryRepository.remember(
                    key = "role_note:${System.currentTimeMillis()}",
                    value = "نقش «$roleName»: $explanation",
                    kind = "instruction",
                )
            }
            val confirmText = "یاد گرفتم! نقش «$roleName» با توضیحات شما${session.book?.let { " و کتاب «${it.title}»" } ?: ""} ثبت، نمایه‌سازی و روی ویجت فعال شد."
            c.uiBridge.postBotMessage(text = confirmText, actionBadge = "ROLE_LEARNED", speakIfAutoSpeak = true)
            val currentPrefs = c.preferencesRepository.getSnapshot()
            c.historyRepository.recordIfAllowed(
                userText = "آموزش نقش $roleName",
                solText = confirmText,
                intentAction = "TEACH_ROLE_VOICE",
                retentionMode = currentPrefs.historyRetentionMode,
                privateModeEnabled = currentPrefs.privateModeEnabled,
            )
        }
    }

    fun dismissVoiceRoleSession() {
        c.voiceEngine.stopListening()
        _voiceRoleSession.value = null
    }

    fun addOrUpdateAlias(alias: String, meaning: String) {
        if (alias.isBlank() || meaning.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { c.learningEngine.teachAlias(alias, meaning) }
    }

    fun addFact(fact: String) {
        if (fact.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { c.learningEngine.teachFact(fact) }
    }

    fun updateMemoryItem(item: MemoryFactEntity, newValue: String) {
        if (newValue.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { c.memoryRepository.updateMemory(item.copy(valueText = newValue.trim())) }
    }

    fun deleteMemoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { c.memoryRepository.forgetById(id) }
    }

    fun deleteUserUtteranceExample(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            c.userUtteranceRepository.deleteExample(id)
            c.statisticalClassifier.removeUserExample(id)
            c.uiBridge.postBanner("مثال آموزشی از حافظه فراموش شد.")
        }
    }

    fun savePerson(name: String, phone: String, relation: String, callScriptNote: String, existingId: Long = 0) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            c.personRepository.savePerson(name, phone, relation, callScriptNote, existingId)
            c.uiBridge.triggerRefreshContacts()
        }
    }

    fun deletePerson(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            c.personRepository.deletePerson(id)
            c.uiBridge.triggerRefreshContacts()
        }
    }

    fun saveLocation(alias: String, fullName: String, addressNote: String, existingId: Long = 0) {
        if (alias.isBlank() || fullName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { c.locationRepository.saveLocation(alias, fullName, addressNote, existingId) }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { c.locationRepository.deleteLocation(id) }
    }

    fun toggleRoleActive(role: SolRoleEntity, active: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { c.knowledgeRepository.toggleRoleActive(role, active) }
    }

    fun deleteRole(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { c.knowledgeRepository.deleteRole(id) }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            c.knowledgeRepository.deleteBook(id)
            c.bookRagEngine.evictCache()
        }
    }
}
