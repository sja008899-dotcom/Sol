package com.example.sol.knowledge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import com.example.sol.memory.BookKnowledgeEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

data class FetchedBookResult(
    val title: String,
    val author: String,
    val subjects: String,
    val summaryText: String,
    val fullText: String,
    val sourceType: String,
    val sourceUrl: String,
)

data class ImportedLocalBook(
    val entity: BookKnowledgeEntity,
    val fullText: String,
)

class BookWebFetcher(private val context: Context) {

    @Volatile
    private var pdfBoxInitialized = false

    fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun fetchBookOrWebpageFromInternet(queryOrUrl: String): Result<FetchedBookResult> =
        withContext(Dispatchers.IO) {
            val trimmed = queryOrUrl.trim()
            if (trimmed.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("نام کتاب یا نشانی وب خالی است"))
            }
            if (!isNetworkConnected()) {
                return@withContext Result.failure(
                    IllegalStateException("اتصال اینترنت برقرار نیست. می‌توانید فایل PDF / EPUB / TXT کتاب را به‌صورت آفلاین وارد کنید.")
                )
            }

            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val webRes = runCatching { fetchFullWebpage(trimmed) }.getOrNull()
                if (webRes != null) return@withContext Result.success(webRes)
                return@withContext Result.failure(
                    IllegalStateException("صفحهٔ وب «$trimmed» قابل دریافت یا خواندن نبود.")
                )
            }

            val hasPersianScript = trimmed.any { it in '\u0600'..'\u06FF' }
            if (hasPersianScript) {
                val faWiki = runCatching { queryWikipedia(trimmed, "fa") }.getOrNull()
                if (faWiki != null) return@withContext Result.success(faWiki)
            }

            val gutendex = runCatching { queryGutendex(trimmed) }.getOrNull()
            if (gutendex != null) return@withContext Result.success(gutendex)

            val openLibrary = runCatching { queryOpenLibrary(trimmed) }.getOrNull()
            if (openLibrary != null) return@withContext Result.success(openLibrary)

            val wikiFallback = runCatching {
                queryWikipedia(trimmed, if (hasPersianScript) "fa" else "en")
                    ?: queryWikipedia(trimmed, "en")
            }.getOrNull()
            if (wikiFallback != null) return@withContext Result.success(wikiFallback)

            Result.failure(
                IllegalStateException("موردی با عنوان «$trimmed» در مخازن عمومی پیدا نشد یا پاسخ دریافت نشد.")
            )
        }

    // Backwards-compatible wrapper used by BookFetchWorker
    suspend fun fetchBookFromInternet(query: String): Result<FetchedBookResult> =
        fetchBookOrWebpageFromInternet(query)

    suspend fun importLocalDocument(uri: Uri, fallbackTitle: String = ""): Result<ImportedLocalBook> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = resolveDisplayName(uri)
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val lowerName = displayName.lowercase()

                val extracted = when {
                    mime.contains("pdf") || lowerName.endsWith(".pdf") -> extractPdfText(uri)
                    mime.contains("epub") || lowerName.endsWith(".epub") -> extractEpubText(uri)
                    mime.contains("html") || lowerName.endsWith(".html") || lowerName.endsWith(".htm") ->
                        extractHtmlText(uri)
                    else -> extractPlainText(uri)
                }.trim()

                require(extracted.isNotBlank()) {
                    "متن قابل استخراجی در این فایل یافت نشد (اگر PDF اسکن‌شدهٔ تصویری است، نسخه متنی انتخاب کنید)."
                }

                val cleanTitle = fallbackTitle.ifBlank {
                    displayName.substringBeforeLast('.').ifBlank { "کتاب واردشده" }
                }
                val sourceTag = when {
                    lowerName.endsWith(".pdf") || mime.contains("pdf") -> "LOCAL_PDF"
                    lowerName.endsWith(".epub") || mime.contains("epub") -> "LOCAL_EPUB"
                    lowerName.endsWith(".html") || lowerName.endsWith(".htm") -> "LOCAL_HTML"
                    else -> "LOCAL_TXT"
                }

                val cappedFullText = extracted.take(350_000)
                val preview = cappedFullText.take(2000)

                ImportedLocalBook(
                    entity = BookKnowledgeEntity(
                        title = cleanTitle,
                        author = "فایل محلی کاربر ($sourceTag)",
                        subjects = "آفلاین • آمادهٔ جست‌وجوی BM25",
                        summaryText = preview,
                        sourceType = sourceTag,
                        sourceUrl = uri.toString(),
                    ),
                    fullText = cappedFullText,
                )
            }
        }

    private fun extractPdfText(uri: Uri): String {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, 120) // RAM-safe cap on 4GB devices
                }
                return stripper.getText(doc)
            }
        }
        return ""
    }

    private fun extractEpubText(uri: Uri): String {
        val chapters = mutableListOf<Pair<String, String>>()
        context.contentResolver.openInputStream(uri)?.use { rawStream ->
            ZipInputStream(rawStream).use { zis ->
                var entry = zis.nextEntry
                var totalChars = 0
                while (entry != null && totalChars < 350_000) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                        val bytes = zis.readBytes()
                        val html = bytes.toString(Charsets.UTF_8)
                        val doc = Jsoup.parse(html)
                        doc.select("script, style, nav").remove()
                        val text = doc.body()?.text().orEmpty().trim()
                        if (text.isNotBlank()) {
                            chapters += name to text
                            totalChars += text.length
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return chapters.sortedBy { it.first }.joinToString("\n\n") { it.second }
    }

    private fun extractHtmlText(uri: Uri): String {
        val html = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }.orEmpty()
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, footer, header").remove()
        return doc.body()?.text().orEmpty()
    }

    private fun extractPlainText(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }.orEmpty()
    }

    private fun fetchFullWebpage(urlString: String): FetchedBookResult? {
        val html = httpGet(urlString) ?: return null
        val doc = Jsoup.parse(html, urlString)
        val title = doc.title().trim().ifBlank { urlString }
        doc.select("script, style, nav, aside, footer, noscript").remove()
        val article = doc.select("article, main").text().trim()
        val bodyText = (article.ifBlank { doc.body()?.text().orEmpty() }).trim()
        if (bodyText.isBlank()) return null

        val cappedFull = bodyText.take(250_000)
        return FetchedBookResult(
            title = title,
            author = URL(urlString).host,
            subjects = "صفحهٔ وب ذخیره‌شده (آفلاین)",
            summaryText = cappedFull.take(2000),
            fullText = cappedFull,
            sourceType = "INTERNET_WEBPAGE",
            sourceUrl = urlString,
        )
    }

    private fun resolveDisplayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: ""
                }
            }
        }
        return uri.lastPathSegment.orEmpty()
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

        // Try downloading public-domain plain text snippet if available in Gutendex formats
        val formats = first.optJSONObject("formats")
        val txtUrl = formats?.optString("text/plain; charset=utf-8")
            ?.ifBlank { formats.optString("text/plain; charset=us-ascii") }
            ?.ifBlank { formats.optString("text/plain") }
            .orEmpty()
        val fetchedText = if (txtUrl.startsWith("https://")) {
            runCatching { httpGet(txtUrl, maxChars = 120_000) }.getOrNull().orEmpty()
        } else {
            ""
        }

        val summary = buildString {
            if (summaryFromApi.isNotBlank()) {
                append(summaryFromApi)
            } else {
                append("کتاب «$title» اثر $author از مخزن عمومی Gutendex دریافت شد.")
                if (subjects.isNotBlank()) append(" موضوعات: $subjects.")
            }
        }

        val fullBody = buildString {
            append(summary)
            if (fetchedText.isNotBlank()) {
                append("\n\n")
                append(fetchedText)
            }
        }

        return FetchedBookResult(
            title = title,
            author = author,
            subjects = subjects,
            summaryText = summary,
            fullText = fullBody,
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
            fullText = summary,
            sourceType = "INTERNET_OPEN_LIBRARY",
            sourceUrl = url,
        )
    }

    private fun queryWikipedia(query: String, lang: String): FetchedBookResult? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl =
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=1&format=json&utf8=1"
        val searchJson = httpGet(searchUrl) ?: return null
        val searchArr = JSONObject(searchJson).optJSONObject("query")?.optJSONArray("search") ?: return null
        if (searchArr.length() == 0) return null

        val hitTitle = searchArr.getJSONObject(0).optString("title").trim()
        if (hitTitle.isBlank()) return null

        val encodedTitle = URLEncoder.encode(hitTitle.replace(' ', '_'), "UTF-8")
        val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
        val sumJsonStr = httpGet(summaryUrl) ?: return null
        val sumObj = JSONObject(sumJsonStr)
        val extract = sumObj.optString("extract").trim()
        if (extract.isBlank()) return null

        // Also fetch longer plain-text intro/sections when available
        val fullExtractUrl =
            "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=18000&titles=$encodedTitle&format=json&utf8=1"
        val longerText = runCatching {
            val raw = httpGet(fullExtractUrl) ?: return@runCatching ""
            val pages = JSONObject(raw).optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching ""
            val firstKey = pages.keys().asSequence().firstOrNull() ?: return@runCatching ""
            pages.optJSONObject(firstKey)?.optString("extract").orEmpty().trim()
        }.getOrDefault("")

        val pageUrl = sumObj.optJSONObject("content_urls")
            ?.optJSONObject("desktop")
            ?.optString("page")
            ?.ifBlank { "https://$lang.wikipedia.org/wiki/$encodedTitle" }
            ?: "https://$lang.wikipedia.org/wiki/$encodedTitle"

        val finalFull = longerText.ifBlank { extract }
        return FetchedBookResult(
            title = hitTitle,
            author = if (lang == "fa") "دانشنامه ویکی‌پدیا فارسی" else "Wikipedia",
            subjects = if (lang == "fa") "محتوای دریافت‌شده از اینترنت (فارسی)" else "Wikipedia Article",
            summaryText = extract.take(1500),
            fullText = finalFull,
            sourceType = if (lang == "fa") "INTERNET_WIKIPEDIA_FA" else "INTERNET_WIKIPEDIA_EN",
            sourceUrl = pageUrl,
        )
    }

    private fun httpGet(urlString: String, maxChars: Int = 500_000): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 9000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SolOfflineLifeAssistant/2.0")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1 && sb.length < maxChars) {
                        val take = minOf(read, maxChars - sb.length)
                        sb.append(buf, 0, take)
                    }
                    sb.toString()
                }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
