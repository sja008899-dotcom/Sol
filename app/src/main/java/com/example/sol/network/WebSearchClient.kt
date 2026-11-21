package com.example.sol.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

interface SearchProvider {
    fun search(query: String, maxResults: Int = 5): List<WebSearchResult>
}

class DuckDuckGoSearchProvider(
    private val httpClient: SolHttpClient = SolHttpClient(),
) : SearchProvider {

    override fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val endpoint = "$SEARCH_BASE_URL?q=$encoded"
        val html = httpClient.fetchText(
            urlString = endpoint,
            maxBytes = MAX_SEARCH_HTML_BYTES,
            allowedHostSuffixes = ALLOWED_SEARCH_HOSTS,
        )
        return parseDuckDuckGoHtml(html, maxResults = maxResults.coerceIn(1, 10))
    }

    companion object {
        const val SEARCH_BASE_URL = "https://html.duckduckgo.com/html/"
        private const val MAX_SEARCH_HTML_BYTES = 2L * 1024L * 1024L
        private val ALLOWED_SEARCH_HOSTS = setOf("duckduckgo.com")

        fun parseDuckDuckGoHtml(
            html: String,
            maxResults: Int = 5,
            baseUrl: String = SEARCH_BASE_URL,
        ): List<WebSearchResult> {
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, baseUrl)
            val cards = doc.select(".result, .results_links, .web-result")
            val results = ArrayList<WebSearchResult>(maxResults)
            val seenUrls = HashSet<String>()

            if (cards.isNotEmpty()) {
                for (card in cards) {
                    val anchor = card.selectFirst("a.result__a")
                        ?: card.selectFirst("h2 a")
                        ?: continue
                    val title = anchor.text().trim()
                    val resolvedUrl = unwrapDuckDuckGoUrl(anchor.attr("href"))
                    if (title.isBlank() || resolvedUrl.isBlank() || !seenUrls.add(resolvedUrl)) continue
                    val snippet = card.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                    results.add(WebSearchResult(title = title, url = resolvedUrl, snippet = snippet))
                    if (results.size >= maxResults) return results
                }
            }

            // Fallback selector if card wrapper differs
            for (anchor in doc.select("a.result__a")) {
                val title = anchor.text().trim()
                val resolvedUrl = unwrapDuckDuckGoUrl(anchor.attr("href"))
                if (title.isBlank() || resolvedUrl.isBlank() || !seenUrls.add(resolvedUrl)) continue
                results.add(WebSearchResult(title = title, url = resolvedUrl, snippet = ""))
                if (results.size >= maxResults) break
            }
            return results
        }

        fun unwrapDuckDuckGoUrl(rawHref: String): String {
            val trimmed = rawHref.trim()
            if (trimmed.isBlank()) return ""
            val normalized = when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "https://duckduckgo.com$trimmed"
                else -> trimmed
            }
            val parsed = runCatching { URI(normalized) }.getOrNull() ?: return ""
            val query = parsed.rawQuery.orEmpty()
            val candidate = if (query.contains("uddg=")) {
                query.split('&')
                    .firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter("uddg=")
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    .orEmpty()
            } else {
                normalized
            }

            val finalUri = runCatching { URI(candidate) }.getOrNull() ?: return ""
            val scheme = finalUri.scheme?.lowercase().orEmpty()
            val host = finalUri.host.orEmpty().lowercase()
            if (scheme != "https" || host.isBlank()) return ""
            if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) {
                return ""
            }
            return candidate
        }
    }
}

class WebSearchClient(
    private val provider: SearchProvider = DuckDuckGoSearchProvider(),
) {
    suspend fun search(query: String, maxResults: Int = 5): Result<List<WebSearchResult>> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("عبارت جست‌وجو خالی است."))
            }
            try {
                Result.success(provider.search(clean, maxResults))
            } catch (e: SolNetworkException) {
                Result.failure(e)
            } catch (t: Throwable) {
                Result.failure(IllegalStateException("جست‌وجوی وب ناموفق بود: ${t.localizedMessage ?: "خطای شبکه"}", t))
            }
        }

    companion object {
        fun formatResultsFa(query: String, results: List<WebSearchResult>): String {
            if (results.isEmpty()) {
                return "نتیجه‌ای برای جست‌وجوی وب «$query» پیدا نشد."
            }
            return buildString {
                appendLine("نتایج جست‌وجوی وب (با تأیید شما) برای «$query»:")
                results.forEachIndexed { idx, item ->
                    val num = idx + 1
                    appendLine("$num. ${item.title}")
                    if (item.snippet.isNotBlank()) {
                        appendLine("   ${item.snippet}")
                    }
                    appendLine("   ${item.url}")
                }
            }.trim()
        }
    }
}
