package com.example.sol.knowledge

import com.example.sol.memory.BookChunkEntity
import com.example.sol.memory.BookKnowledgeEntity
import com.example.sol.memory.KnowledgeRepository
import com.example.sol.memory.PersianNormalizer
import com.example.sol.memory.SolRoleEntity
import kotlin.math.ln

data class RankedBookChunk(
    val chunk: BookChunkEntity,
    val score: Double,
    val matchedTerms: List<String>,
)

data class RagConsultationResult(
    val answerText: String,
    val topChunks: List<RankedBookChunk>,
    val referencedBook: BookKnowledgeEntity?,
    val activeRole: SolRoleEntity?,
)

class BookRagEngine(
    private val knowledgeRepository: KnowledgeRepository,
) {
    private val stopwords = setOf(
        "و", "در", "به", "از", "که", "را", "این", "آن", "است", "هست", "بود", "شد",
        "با", "برای", "تا", "بر", "یا", "اما", "اگر", "هم", "یک", "دو", "من", "تو",
        "او", "ما", "شما", "خود", "می", "های", "ها", "ای", "کن", "شود", "دارد",
        "the", "and", "of", "to", "in", "a", "is", "it", "for", "on", "that", "with", "as",
    )

    // Lightweight in-memory IDF cache evicted on onTrimMemory for 4GB RAM devices
    @Volatile
    private var cachedChunks: List<BookChunkEntity>? = null

    fun evictCache() {
        cachedChunks = null
    }

    fun tokenize(text: String): List<String> {
        val norm = PersianNormalizer.normalize(text)
        return norm
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopwords }
            .toList()
    }

    fun chunkText(
        bookId: Long,
        bookTitle: String,
        fullText: String,
        maxChars: Int = 480,
        overlapChars: Int = 80,
    ): List<BookChunkEntity> {
        val cleaned = fullText
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        val result = mutableListOf<BookChunkEntity>()
        var start = 0
        var index = 0
        val safeMax = maxChars.coerceAtLeast(180)
        val safeOverlap = overlapChars.coerceIn(0, safeMax / 3)

        while (start < cleaned.length && index < 600) {
            var end = (start + safeMax).coerceAtMost(cleaned.length)
            if (end < cleaned.length) {
                val sentenceBreak = cleaned.lastIndexOfAny(charArrayOf('.', '؟', '!', '۔', '\n'), end)
                if (sentenceBreak > start + safeMax / 2) {
                    end = sentenceBreak + 1
                } else {
                    val spaceBreak = cleaned.lastIndexOf(' ', end)
                    if (spaceBreak > start + safeMax / 2) {
                        end = spaceBreak
                    }
                }
            }
            val slice = cleaned.substring(start, end).trim()
            if (slice.isNotBlank()) {
                val tokens = tokenize(slice).distinct().joinToString(" ")
                result += BookChunkEntity(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    chunkIndex = index,
                    contentText = slice,
                    normalizedTokens = tokens,
                )
                index++
            }
            if (end >= cleaned.length) break
            val nextStart = (end - safeOverlap).coerceAtLeast(start + 1)
            start = nextStart
        }
        return result
    }

    suspend fun indexBookContent(bookId: Long, bookTitle: String, fullText: String): Int {
        val chunks = chunkText(bookId, bookTitle, fullText)
        knowledgeRepository.replaceChunksForBook(bookId, chunks)
        cachedChunks = null
        return chunks.size
    }

    suspend fun ensureChunksIndexed(books: List<BookKnowledgeEntity>): List<BookChunkEntity> {
        val existing = cachedChunks ?: knowledgeRepository.getAllChunksOnce().also {
            cachedChunks = it
        }
        if (books.isEmpty()) return existing

        val existingBookIds = existing.map { it.bookId }.toSet()
        var addedAny = false
        for (book in books) {
            if (book.id !in existingBookIds) {
                val combined = buildString {
                    append(book.summaryText)
                    if (book.voiceNotes.isNotBlank()) {
                        append("\nتوضیحات صوتی کاربر: ")
                        append(book.voiceNotes)
                    }
                }
                val chunks = chunkText(book.id, book.title, combined)
                if (chunks.isNotEmpty()) {
                    knowledgeRepository.replaceChunksForBook(book.id, chunks)
                    addedAny = true
                }
            }
        }
        return if (addedAny) {
            knowledgeRepository.getAllChunksOnce().also { cachedChunks = it }
        } else {
            existing
        }
    }

    fun rankChunksBm25(
        query: String,
        chunks: List<BookChunkEntity>,
        preferredBookTitle: String = "",
        topK: Int = 3,
    ): List<RankedBookChunk> {
        if (chunks.isEmpty()) return emptyList()
        val queryTokens = tokenize(query).distinct()
        if (queryTokens.isEmpty()) {
            val preferred = chunks.filter {
                preferredBookTitle.isNotBlank() && it.bookTitle.equals(preferredBookTitle, ignoreCase = true)
            }
            return (preferred.ifEmpty { chunks }).take(topK).map {
                RankedBookChunk(chunk = it, score = 0.5, matchedTerms = emptyList())
            }
        }

        val tokenizedDocs = chunks.map { chunk ->
            if (chunk.normalizedTokens.isNotBlank()) {
                chunk.normalizedTokens.split(' ').filter { it.isNotBlank() }
            } else {
                tokenize(chunk.contentText)
            }
        }
        val nDocs = chunks.size.toDouble()
        val avgDl = tokenizedDocs.sumOf { it.size }.toDouble() / nDocs.coerceAtLeast(1.0)
        val k1 = 1.5
        val b = 0.75

        val dfMap = mutableMapOf<String, Int>()
        for (term in queryTokens) {
            dfMap[term] = tokenizedDocs.count { docTokens -> term in docTokens }
        }

        val scored = chunks.mapIndexed { idx, chunk ->
            val docTokens = tokenizedDocs[idx]
            val dl = docTokens.size.toDouble().coerceAtLeast(1.0)
            var score = 0.0
            val matched = mutableListOf<String>()

            for (term in queryTokens) {
                val tf = docTokens.count { it == term }.toDouble()
                if (tf <= 0.0) continue
                matched += term
                val df = dfMap[term] ?: 0
                val idf = ln(((nDocs - df + 0.5) / (df + 0.5)) + 1.0)
                val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (dl / avgDl.coerceAtLeast(1.0))))
                score += idf * tfNorm
            }

            if (preferredBookTitle.isNotBlank() &&
                PersianNormalizer.normalize(chunk.bookTitle) == PersianNormalizer.normalize(preferredBookTitle)
            ) {
                score *= 1.25
            }

            RankedBookChunk(chunk = chunk, score = score, matchedTerms = matched)
        }

        return scored
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(topK)
    }

    suspend fun consultOffline(
        query: String,
    ): RagConsultationResult {
        val roles = knowledgeRepository.getAllRolesOnce().filter { it.isActive }
        val books = knowledgeRepository.getAllBooksOnce()
        val activeRole = roles.firstOrNull()

        if (roles.isEmpty() && books.isEmpty()) {
            return RagConsultationResult(
                answerText = "هنوز کتاب یا نقشی آموزش داده نشده است. می‌توانید PDF/EPUB/فایل متنی یا لینک کتاب را وارد کنید و با توضیحات صوتی به Sol آموزش دهید.",
                topChunks = emptyList(),
                referencedBook = null,
                activeRole = null,
            )
        }

        val chunks = ensureChunksIndexed(books)
        val normQuery = PersianNormalizer.normalize(query)
        val explicitBook = books.firstOrNull {
            normQuery.contains(PersianNormalizer.normalize(it.title))
        }
        val preferredTitle = explicitBook?.title ?: activeRole?.linkedBookTitle.orEmpty()
        val ranked = rankChunksBm25(query, chunks, preferredTitle, topK = 3)

        val referencedBook = explicitBook
            ?: ranked.firstOrNull()?.let { hit -> books.firstOrNull { it.id == hit.chunk.bookId } }
            ?: books.firstOrNull()

        val baseAnswer = buildString {
            if (activeRole != null) {
                append("🎭 در نقش فعال «${activeRole.roleName}»")
                if (activeRole.instructions.isNotBlank()) {
                    append(" (${activeRole.instructions.take(160)})")
                }
                append(":\n")
            }

            if (ranked.isNotEmpty()) {
                append("🔎 نتایج جست‌وجوی مستقیم از کتاب‌ها (BM25):\n")
                ranked.forEachIndexed { idx, hit ->
                    val scoreStr = String.format(java.util.Locale.US, "%.2f", hit.score)
                    append("${idx + 1}) از «${hit.chunk.bookTitle}» [بخش ${hit.chunk.chunkIndex + 1} • امتیاز $scoreStr]:\n")
                    append(hit.chunk.contentText.take(280))
                    if (hit.chunk.contentText.length > 280) append("…")
                    append("\n")
                }
            } else if (referencedBook != null) {
                append("📘 مرجع «${referencedBook.title}» (${referencedBook.author}):\n")
                append(referencedBook.summaryText.take(320))
                append("\n")
            }

            if (referencedBook != null && referencedBook.voiceNotes.isNotBlank()) {
                append("🎙 یادداشت‌های صوتی شما برای این کتاب:\n• ${referencedBook.voiceNotes}")
            }
        }.trim()

        return RagConsultationResult(
            answerText = baseAnswer,
            topChunks = ranked,
            referencedBook = referencedBook,
            activeRole = activeRole,
        )
    }
}
