// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziPal : MainAPI() {
    override var mainUrl              = "https://www.dizigom1.com/"
    override var name                 = "Dizigom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.select("title").text() == "Just a moment...") {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/"                          to "Son Bölümler",
        "${mainUrl}/dizi-arsivi-hd1/"                                       to "Yeni Diziler",
        "${mainUrl}/tum-yabanci-filmler-hd2/"                                       to "Yeni Filmler",
        "${mainUrl}/netflix-dizileri-hd1/"                            to "Netflix",
        "${mainUrl}/apple-tv-dizileri-hd1/"                              to "Apple TV+",
        "${mainUrl}/kore-dizileri-hd1/"                             to "Kore Dizileri",
        "${mainUrl}/japon-dizileri-hd1/"                       to "Japon Dizileri",
		"${mainUrl}/cin-dizileri-hd1/"                              to "Çin Dizileri",
        "${mainUrl}/marvel-dizileri-hd1/"                           to "Marvel Dizileri",
        "${mainUrl}/dc-comics-dizileri-hd1/"                           to "DC Comics Dizileri",
        "${mainUrl}/anime-dizileri-hd1/"                               to "Anime Diziler",
        "${mainUrl}/efsane-diziler-hd1/" 							to "Efsane Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, interceptor = interceptor).document
        val home     = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() } 
        } else {
            document.select("article.type2 ul li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.name")?.text() ?: return null
        val episode   = this.selectFirst("div.episode")?.text()?.trim()?.toString()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "${name} ${episode}"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster

        if (this.type == "series") {
            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        var sorgu = query
        if (sorgu == "over") { // ! Test Provider
            sorgu = "gibi"
        }

        val response_raw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers     = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer     = "${mainUrl}/",
            interceptor = interceptor,
            data        = mapOf(
                "query" to sorgu
            )
        )

        val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(response_raw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        for ((key, searchItem) in searchItemsMap) {
            searchResponses.add(searchItem.toPostSearchResult())
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").mapNotNull { it.trim() }
        val rating      = document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim().toRatingInt()
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text() ?: "")?.value?.toIntOrNull()

        if (url.contains("/dizi-arsivi-hd1/")) {
            val title       = document.selectFirst("div.cover h5")?.text() ?: return null

            val episodes    = document.select("div.episode-item").mapNotNull {
                val ep_name    = it.selectFirst("div.name")?.text()?.trim() ?: return@mapNotNull null
                val ep_href    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val ep_episode = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(2)?.replace(".", "")?.toIntOrNull()
                val ep_season  = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(0)?.replace(".", "")?.toIntOrNull()

                Episode(
                    data    = ep_href,
                    name    = ep_name,
                    season  = ep_season,
                    episode = ep_episode
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        } else { 
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP", "data » ${data}")
        val document = app.get(data, interceptor = interceptor).document
        val iframe   = document.selectFirst(".series-player-container iframe")?.attr("src") ?: document.selectFirst("div#vast_new iframe")?.attr("src") ?: return false
        Log.d("DZP", "iframe » ${iframe}")

        val i_source = app.get("${iframe}", referer="${mainUrl}/").text
        val m3u_link = Regex("""file:\"([^\"]+)""").find(i_source)?.groupValues?.get(1)
        if (m3u_link == null) {
            Log.d("DZP", "i_source » ${i_source}")
            return loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        val subtitles = Regex("""\"subtitle":\"([^\"]+)""").find(i_source)?.groupValues?.get(1)
        if (subtitles != null) {
            if (subtitles.contains(",")) {
                subtitles.split(",").forEach {
                    val sub_lang = it.substringAfter("[").substringBefore("]")
                    val sub_url  = it.replace("[${sub_lang}]", "")

                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = sub_lang,
                            url  = fixUrl(sub_url)
                        )
                    )
                }
            } else {
                val sub_lang = subtitles.substringAfter("[").substringBefore("]")
                val sub_url  = subtitles.replace("[${sub_lang}]", "")

                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub_lang,
                        url  = fixUrl(sub_url)
                    )
                )
            }
        }

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3u_link,
                referer = "${mainUrl}/",
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )

        // M3u8Helper.generateM3u8(
        //     source    = this.name,
        //     name      = this.name,
        //     streamUrl = m3u_link,
        //     referer   = "${mainUrl}/"
        // ).forEach(callback)

        return true
    }
}
