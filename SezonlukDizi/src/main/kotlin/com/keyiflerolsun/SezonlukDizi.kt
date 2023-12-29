// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi6.com"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/diziler.asp?adi=${query}").document

        return document.select("div.afis a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it?.text()?.trim() }
        val rating      = document.selectFirst("div.dizipuani a div")?.text()?.trim()?.replace(",", ".").toRatingInt()
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actors_req  = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors      = actors_req.select("div.doubling div.ui").map {
            Actor(
                it.selectFirst("div.header")!!.text().trim(),
                fixUrlNull(it.selectFirst("img")?.attr("src"))
            )
        }


        val episodes_req = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes     = mutableListOf<Episode>()
        for (sezon in episodes_req.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val ep_name    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val ep_href    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val ep_episode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val ep_season  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(Episode(
                    data    = ep_href,
                    name    = ep_name,
                    season  = ep_season,
                    episode = ep_episode
                ))
            }
        }


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SZD", "data » ${data}")
        val document = app.get(data).document
        val bid      = document.selectFirst("div#dilsec")?.attr("data-id") ?: return false
        Log.d("SZD", "bid » ${bid}")

        val altyazi_response = app.post(
            "${mainUrl}/ajax/dataAlternatif2.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data    = mapOf(
                "bid" to bid,
                "dil" to "1"
            )
        ).parsedSafe<Kaynak>()
        altyazi_response?.takeIf { it.status == "success" }?.data?.forEach { veri ->
            Log.d("SZD", "dil»1 | veri.baslik » ${veri.baslik}")

            val veri_response = app.post(
                "${mainUrl}/ajax/dataEmbed.asp",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                data    = mapOf("id" to "${veri.id}")
            ).document

            var iframe = fixUrlNull(veri_response.selectFirst("iframe")?.attr("src")) ?: return@forEach
            Log.d("SZD", "dil»1 | iframe » ${iframe}")

            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { link ->
                callback.invoke(
                    ExtractorLink(
                        source        = "AltYazı - ${veri.baslik}",
                        name          = "AltYazı - ${veri.baslik}",
                        url           = link.url,
                        referer       = link.referer,
                        quality       = link.quality,
                        headers       = link.headers,
                        extractorData = link.extractorData,
                        type          = link.type
                    )
                )
            }
        }

        val dublaj_response = app.post(
            "${mainUrl}/ajax/dataAlternatif2.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data    = mapOf(
                "bid" to bid,
                "dil" to "0"
            )
        ).parsedSafe<Kaynak>()
        dublaj_response?.takeIf { it.status == "success" }?.data?.forEach { veri ->
            Log.d("SZD", "dil»0 | veri.baslik » ${veri.baslik}")

            val veri_response = app.post(
                "${mainUrl}/ajax/dataEmbed.asp",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                data    = mapOf("id" to "${veri.id}")
            ).document

            var iframe = fixUrlNull(veri_response.selectFirst("iframe")?.attr("src")) ?: return@forEach
            Log.d("SZD", "dil»0 | iframe » ${iframe}")

            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { link ->
                callback.invoke(
                    ExtractorLink(
                        source        = "Dublaj - ${veri.baslik}",
                        name          = "Dublaj - ${veri.baslik}",
                        url           = link.url,
                        referer       = link.referer,
                        quality       = link.quality,
                        headers       = link.headers,
                        extractorData = link.extractorData,
                        type          = link.type
                    )
                )
            }
        }

        return true
    }
}
