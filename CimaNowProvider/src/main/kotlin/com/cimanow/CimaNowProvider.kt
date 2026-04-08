package com.cimanow

import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import android.os.Handler
import android.util.Log
import kotlin.math.min
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.text.toIntOrNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.math.BigInteger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.javascript.Scriptable
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

import kotlin.text.RegexOption

class CimaNow : MainAPI() {
    override var name = "CimaNow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/category/الافلام/" to "الأفلام",
        "$mainUrl/category/المسلسلات/" to "المسلسلات",
        "$mainUrl/category/افلام-اجنبية/" to "أفلام أجنبية",
        "$mainUrl/category/مسلسلات-اجنبية/" to "مسلسلات أجنبية",
        "$mainUrl/category/افلام-عربية/" to "أفلام عربية",
        "$mainUrl/category/مسلسلات-عربية/" to "مسلسلات عربية",
        "$mainUrl/category/افلام-هندية/" to "أفلام هندية",
        "$mainUrl/category/افلام-تركية/" to "أفلام تركية",
        "$mainUrl/category/مسلسلات-تركية/" to "مسلسلات تركية"
    )

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = this.attr("abs:href")
        if (url.contains("javascript")) return null
        val posterUrl = select("img")?.attr("data-src")
        var title = select("li[aria-label=\"title\"]").html().replace(" <em>.*|\\\\n".toRegex(), "").replace("&nbsp;", "")
        val year = select("li[aria-label=\"year\"]").text().toIntOrNull()
        val tvType = if (url.contains("فيلم|مسرحية|حفلات".toRegex())) TvType.Movie else TvType.TvSeries

        val dubEl = select("li[aria-label=\"ribbon\"]:nth-child(2)").isNotEmpty()
        val dubStatus = if (dubEl) select("li[aria-label=\"ribbon\"]:nth-child(2)").text().contains("مدبلج")
        else select("li[aria-label=\"ribbon\"]:nth-child(1)").text().contains("مدبلج")
        if (dubStatus) title = "$title (مدبلج)"

        return newMovieSearchResponse(
            "$title ${select("li[aria-label=\"ribbon\"]:contains(الموسم)").text()}",
            url,
            tvType,
        ) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHomePage = request.data == mainUrl
        val url = if (isHomePage) {
            if (page > 1) return newHomePageResponse(emptyList())
            "$mainUrl/home"
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }

        val doc = app.get(url, headers = mapOf("user-agent" to "MONKE")).document

        return if (isHomePage) {
            val pages = doc.select("section:has(span):has(.owl-body)").mapNotNull { section ->
                val nameElement = section.selectFirst("span") ?: return@mapNotNull null
                val name = nameElement.ownText().trim()
                val categoryUrl = nameElement.selectFirst("a")?.attr("abs:href")

                if (name.contains("أختر وجهتك المفضلة|تم اضافته حديثاً".toRegex())) return@mapNotNull null

                val list = section.select(".owl-body a").mapNotNull { element ->
                    element.toSearchResponse()
                }

                if (list.isEmpty()) return@mapNotNull null
                HomePageList(name, list, isHorizontalImages = true)
            }
            newHomePageResponse(pages.filter { it.list.isNotEmpty() })
        } else {
            val items = doc.select("section[aria-label='posts'] article").mapNotNull {
                it.selectFirst("a")?.toSearchResponse()
            }
            val hasNext = doc.select("ul[aria-label=\"pagination\"] li.active + li").isNotEmpty()
            newHomePageResponse(request.name, items, hasNext = hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("section article[aria-label='post'] a").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val posterUrl = doc.select("meta[property=\"og:image\"]").attr("content")
        val year = doc.select("article ul:nth-child(1) li a").last()?.text()?.toIntOrNull()
        val title = doc.select("title").text().split(" | ")[0]
        val isMovie = title.contains("فيلم|حفلات|مسرحية".toRegex())
        val youtubeTrailer = doc.select("iframe")?.attr("src")
        val synopsis = doc.select("ul#details li:contains(لمحة) p").text()
        val tags = doc.select("article ul").first()?.select("li")?.map { it.text() }
        val recommendations = doc.select("ul#related li").mapNotNull { element ->
            newMovieSearchResponse(
                element.select("img:nth-child(2)").attr("alt"),
                element.select("a").attr("abs:href"),
                TvType.Movie,
            ) {
                this.posterUrl = element.select("img:nth-child(2)").attr("src")
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                addTrailer(youtubeTrailer)
            }
        } else {
            val seasons = doc.select("section[aria-label=\"seasons\"] ul li a").mapNotNull {
                Pair(it.attr("abs:href"), it.text().getIntFromText())
            }

            val episodes = if (seasons.isNotEmpty()) {
                seasons.apmap { (seasonUrl, seasonNum) ->
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select("ul#eps li a").mapNotNull { epEl ->
                        newEpisode(epEl.attr("abs:href")) {
                            this.name = epEl.select("img:nth-child(2)").attr("alt")
                            this.season = seasonNum
                            this.episode = epEl.select("em").text().toIntOrNull()
                            this.posterUrl = posterUrl
                        }
                    }
                }.flatten()
            } else {
                doc.select("ul#eps li a").mapNotNull { epEl ->
                    newEpisode(epEl.attr("abs:href")) {
                        this.name = epEl.select("img:nth-child(2)").attr("alt")
                        this.season = doc.select("span[aria-label=\"season-title\"]").text().getIntFromText()
                        this.episode = epEl.select("em").text().toIntOrNull()
                        this.posterUrl = posterUrl
                    }
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinctBy { it.name }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.year = year
                this.plot = synopsis
                this.recommendations = recommendations
                addTrailer(youtubeTrailer)
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val serverLogTag = "CimaNowDecode"

        val hideMyHtmlRegex = Regex("hide_my_HTML_\\s*=\\s*((?:'[^']*'|\"[^\"]*\")(?:\\s*\\+\\s*(?:'[^']*'|\"[^\"]*\"))*)\\s*;", RegexOption.DOT_MATCHES_ALL)
        val quoteExtractRegex = Regex("'([^']*)'|\"([^\"]*)\"")
        val serverRegex = Regex("<li[^>]+data-index=\"(\\d+)\"[^>]+data-id=\"(\\d+)\"[^>]*>([^<]+)</li>")
        val iframeSrcRegex = Regex("<iframe[^>]+src=\"([^\"]+)\"")

        val watchUrl = if (data.contains("/watching")) data else data.trimEnd('/') + "/watching/"

        try {
            val responseText = withContext(Dispatchers.IO) {
                app.get(
                    watchUrl,
                    referer = "https://rm.freex2line.online/2020/02/blog-post.html/",
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115 Mobile Safari/537.36")
                ).text
            }

            val hideString = hideMyHtmlRegex.find(responseText)?.groups?.get(1)?.value
                ?.let { rawGroup ->
                    quoteExtractRegex.findAll(rawGroup)
                        .map { it.groups[1]?.value ?: it.groups[2]?.value ?: "" }
                        .joinToString("")
                }

            if (hideString.isNullOrEmpty()) {
                Log.e(serverLogTag, "❌ فشل استخراج hide_my_HTML_")
                return@coroutineScope false
            }

            val decoded = StringBuilder()
            val numberBuilder = StringBuilder(10)
            hideString.splitToSequence('.').forEach { part ->
                if (part.isNotEmpty()) {
                    try {
                        val b64 = Base64.decode(part, Base64.DEFAULT)
                        numberBuilder.clear()
                        for (byte in b64) {
                            val char = byte.toInt().toChar()
                            if (char.isDigit()) numberBuilder.append(char)
                        }
                        if (numberBuilder.isNotEmpty()) {
                            val digits = numberBuilder.toString()
                            val codePoint = digits.toInt() - 87653
                            if (codePoint in 0..0x10FFFF) decoded.append(codePoint.toChar())
                        }
                    } catch (_: Exception) { }
                }
            }

            val servers = serverRegex.findAll(decoded.toString())
                .mapNotNull { match ->
                    val (index, id, name) = match.destructured
                    Triple(index, id, name.trim())
                }.toList()

            Log.i(serverLogTag, "✅ تم العثور على ${servers.size} سيرفرات")

            servers.map { (index, id, name) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(5000) {
                        try {
                            val switchUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$index&id=$id"
                            val serverResponse = app.get(
                                switchUrl,
                                referer = "https://cimanow.cc/",
                                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115 Mobile Safari/537.36")
                            ).text

                            Log.d(serverLogTag, "📥 [$name] استجابة السيرفر (أول 200): " +
                                    serverResponse.take(200).replace("\n", " "))

                            if (name.equals("Cima Now", ignoreCase = true)) {
                                val iframeUrl = iframeSrcRegex.find(serverResponse)?.groupValues?.get(1)
                                if (!iframeUrl.isNullOrEmpty()) {
                                    val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                                    Log.i(serverLogTag, "   ✅ [$name] تم العثور على iframe: $finalUrl")

                                    try {
                                        val iframeResponse = app.get(
                                            finalUrl,
                                            referer = finalUrl,
                                            headers = mapOf("User-Agent" to "Mozilla/5.0")
                                        ).text

                                        val regex = Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""")
                                        val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""

                                        regex.findAll(iframeResponse).forEach { match ->
                                            val quality = match.groupValues[1]
                                            val filePath = match.groupValues[2]
                                            val videoUrl = baseUrl + filePath

                                            val link = newExtractorLink(
                                                source = "CimaNow",
                                                name = "CimaNow $quality",
                                                url = videoUrl
                                            ).apply {
                                                this.quality = getQualityFromName(quality)
                                                this.referer = finalUrl
                                            }

                                            Log.i(serverLogTag, "   🎬 [$name] رابط $quality => $videoUrl")
                                            callback.invoke(link)
                                        }
                                    } catch (ex: Exception) {
                                        Log.e(serverLogTag, "   ❌ [$name] خطأ أثناء استخراج روابط iframe", ex)
                                    }
                                } else {
                                    Log.w(serverLogTag, "   ⚠️ [$name] لم يتم العثور على iframe داخل استجابة CimaNow")
                                }
                            } else {
                                val iframeUrl = iframeSrcRegex.find(serverResponse)?.groupValues?.get(1)
                                if (!iframeUrl.isNullOrEmpty()) {
                                    val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                                    Log.i(serverLogTag, "   ✅ [$name] تم العثور على رابط iframe: $finalUrl")

                                    Log.d(serverLogTag, "   - [$name] جاري استدعاء loadExtractor...")
                                    loadExtractor(finalUrl, "https://cimanow.cc/", subtitleCallback, callback)
                                } else {
                                    Log.w(serverLogTag, "   ⚠️ [$name] لم يتم العثور على رابط iframe")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(serverLogTag, "   ❌ [$name] حدث خطأ عام أثناء المعالجة", e)
                        }
                    } ?: Log.e(serverLogTag, "   ❌ [$name] فشل بسبب انتهاء المهلة (Timeout)")
                }
            }.awaitAll()

            return@coroutineScope true
        } catch (e: Exception) {
            Log.e(serverLogTag, "❌ خطأ عام في loadLinks", e)
            return@coroutineScope false
        }
    }
}
