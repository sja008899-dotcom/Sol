package com.example.sol.knowledge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.sol.memory.BookKnowledgeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FetchedBookResult(
    val title: String,
    val author: String,
    val subjects: String,
    val summaryText: String,
    val sourceType: String,
    val sourceUrl: String,
)

class BookWebFetcher(private val context: Context) {

    fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun fetchBookFromInternet(query: String): Result<FetchedBookResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("نام کتاب خالی است"))
        }
        if (!isNetworkConnected()) {
            return@withContext Result.failure(
                IllegalStateException("اتصال اینترنت برقرار نیست. می‌توانید فایل متنی کتاب را به‌صورت آفلاین وارد کنید یا یادداشت صوتی بدهید.")
            )
        }

        // Try Gutendex first for richer book summaries/subjects, then OpenLibrary
        val gutendex = runCatching { queryGutendex(query) }.getOrNull()
        if (gutendex != null) return@withContext Result.success(gutendex)

        val openLibrary = runCatching { queryOpenLibrary(query) }.getOrNull()
        if (openLibrary != null) return@withContext Result.success(openLibrary)

        Result.failure(
            IllegalStateException("کتابی با عنوان «$query» در پایگاه‌های عمومی پیدا نشد یا پاسخ دریافت نشد.")
        )
    }

    suspend fun importLocalTextBook(uri: Uri, fallbackTitle: String): Result<BookKnowledgeEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }?.trim().orEmpty()
                require(content.isNotBlank()) { "فایل انتخاب‌شده متنی ندارد." }
                val preview = content.take(2500)
                BookKnowledgeEntity(
                    title = fallbackTitle.ifBlank { "کتاب متنی واردشده" },
                    author = "فایل محلی کاربر",
                    subjects = "آفلاین",
                    summaryText = preview,
                    sourceType = "LOCAL_FILE",
                    sourceUrl = uri.toString(),
                )
            }
        }

    private fun queryGutendex(query: String): FetchedBookResult? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoint = "https://gutendex.com/books?search=$encoded"
        val jsonStr = httpGet(endpoint) ?: return null
        val root = JSONObject(jsonStr)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val first = results.getJSONObject(0)
        val title = first.optString("title").trim()
        if (title.isBlank()) return null

        val authorsArr = first.optJSONArray("authors")
        val author = buildList {
            if (authorsArr != null) {
                for (i in 0 until authorsArr.length()) {
                    val name = authorsArr.getJSONObject(i).optString("name").trim()
                    if (name.isNotBlank()) add(name)
                }
            }
        }.joinToString("، ").ifBlank { "نامشخص" }

        val subjectsArr = first.optJSONArray("subjects")
        val subjects = buildList {
            if (subjectsArr != null) {
                for (i in 0 until minOf(subjectsArr.length(), 4)) {
                    add(subjectsArr.getString(i))
                }
            }
        }.joinToString(" • ")

        val summariesArr = first.optJSONArray("summaries")
        val summaryFromApi = if (summariesArr != null && summariesArr.length() > 0) {
            summariesArr.getString(0)
        } else {
            ""
        }
        val bookId = first.optInt("id", 0)
        val url = if (bookId > 0) "https://www.gutenberg.org/ebooks/$bookId" else endpoint

        val summary = buildString {
            if (summaryFromApi.isNotBlank()) {
                append(summaryFromApi)
            } else {
                append("کتاب «$title» اثر $author از مخزن عمومی Gutendex دریافت شد.")
                if (subjects.isNotBlank()) append(" موضوعات: $subjects.")
            }
        }

        return FetchedBookResult(
            title = title,
            author = author,
            subjects = subjects,
            summaryText = summary,
            sourceType = "INTERNET_GUTENDEX",
            sourceUrl = url,
        )
    }

    private fun queryOpenLibrary(query: String): FetchedBookResult? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoint = "https://openlibrary.org/search.json?q=$encoded&limit=3"
        val jsonStr = httpGet(endpoint) ?: return null
        val root = JSONObject(jsonStr)
        val docs = root.optJSONArray("docs") ?: return null
        if (docs.length() == 0) return null

        val first = docs.getJSONObject(0)
        val title = first.optString("title").trim()
        if (title.isBlank()) return null

        val authorsArr = first.optJSONArray("author_name")
        val author = if (authorsArr != null && authorsArr.length() > 0) {
            authorsArr.getString(0)
        } else {
            "نامشخص"
        }

        val subjectsArr = first.optJSONArray("subject")
        val subjects = buildList {
            if (subjectsArr != null) {
                for (i in 0 until minOf(subjectsArr.length(), 4)) {
                    add(subjectsArr.getString(i))
                }
            }
        }.joinToString(" • ")

        val firstYear = first.optInt("first_publish_year", 0)
        val firstSentenceArr = first.optJSONArray("first_sentence")
        val firstSentence = if (firstSentenceArr != null && firstSentenceArr.length() > 0) {
            firstSentenceArr.getString(0)
        } else {
            ""
        }
        val workKey = first.optString("key")
        val url = if (workKey.isNotBlank()) "https://openlibrary.org$workKey" else endpoint

        val summary = buildString {
            append("کتاب «$title» اثر $author")
            if (firstYear > 0) append(" (نخستین انتشار: $firstYear)")
            append(" از Open Library دریافت شد.")
            if (subjects.isNotBlank()) append("\nمحورها: $subjects")
            if (firstSentence.isNotBlank()) append("\nگزیده آغازین: $firstSentence")
        }

        return FetchedBookResult(
            title = title,
            author = author,
            subjects = subjects,
            summaryText = summary,
            sourceType = "INTERNET_OPEN_LIBRARY",
            sourceUrl = url,
        )
    }

    private fun httpGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "SolOfflineLifeAssistant/1.0")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
