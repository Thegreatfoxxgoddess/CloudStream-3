package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.get
import com.lagradost.cloudstream3.network.text
import com.lagradost.cloudstream3.network.url
import com.lagradost.cloudstream3.utils.*
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

class AllMoviesForYouProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("series") -> TvType.TvSeries
                t.contains("movies") -> TvType.Movie
                else -> TvType.Movie
            }
        }
    }

    override val mainUrl: String
        get() = "https://allmoviesforyou.co"
    override val name: String
        get() = "AllMoviesForYou"
    override val supportedTypes: Set<TvType>
        get() = setOf(
            TvType.Movie,
            TvType.TvSeries
        )

    override fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val response = get(url).text
        val document = Jsoup.parse(response)

        val items = document.select("ul.MovieList > li > article > a")
        val returnValue = ArrayList<SearchResponse>()
        for (item in items) {
            val href = item.attr("href")
            val title = item.selectFirst("> h2.Title").text()
            val img = fixUrl(item.selectFirst("> div.Image > figure > img").attr("data-src"))
            val type = getType(href)
            if (type == TvType.Movie) {
                returnValue.add(MovieSearchResponse(title, href, this.name, type, img, null))
            } else if (type == TvType.TvSeries) {
                returnValue.add(TvSeriesSearchResponse(title, href, this.name, type, img, null, null))
            }
        }
        return returnValue
    }

    private fun getLink(document: Document): List<String>? {
        val list = ArrayList<String>()
        Regex("iframe src=\"(.*?)\"").find(document.html())?.groupValues?.get(1)?.let {
            list.add(it)
        }
        document.select("div.OptionBx")?.forEach { element ->
            val baseElement = element.selectFirst("> a.Button")
            val elementText = element.selectFirst("> p.AAIco-dns")?.text()
            if (elementText == "Streamhub" || elementText == "Dood") {
                baseElement?.attr("href")?.let { href ->
                    list.add(href)
                }
            }
        }

        return if (list.isEmpty()) null else list
    }

    override fun load(url: String): LoadResponse {
        val type = getType(url)

        val response = get(url).text
        val document = Jsoup.parse(response)

        val title = document.selectFirst("h1.Title").text()
        val descipt = document.selectFirst("div.Description > p").text()
        val rating =
            document.selectFirst("div.Vote > div.post-ratings > span")?.text()?.toFloatOrNull()?.times(10)?.toInt()
        val year = document.selectFirst("span.Date")?.text()
        val duration = document.selectFirst("span.Time").text()
        val backgroundPoster = fixUrl(document.selectFirst("div.Image > figure > img").attr("data-src"))

        if (type == TvType.TvSeries) {
            val list = ArrayList<Pair<Int, String>>()

            document.select("main > section.SeasonBx > div > div.Title > a").forEach { element ->
                val season = element.selectFirst("> span")?.text()?.toIntOrNull()
                val href = element.attr("href")
                if (season != null && season > 0 && !href.isNullOrBlank()) {
                    list.add(Pair(season, fixUrl(href)))
                }
            }
            if (list.isEmpty()) throw ErrorLoadingException("No Seasons Found")

            val episodeList = ArrayList<TvSeriesEpisode>()

            for (season in list) {
                val seasonResponse = get(season.second).text
                val seasonDocument = Jsoup.parse(seasonResponse)
                val episodes = seasonDocument.select("table > tbody > tr")
                if (episodes.isNotEmpty()) {
                    episodes.forEach { episode ->
                        val epNum = episode.selectFirst("> td > span.Num")?.text()?.toIntOrNull()
                        val poster = episode.selectFirst("> td.MvTbImg > a > img")?.attr("data-src")
                        val aName = episode.selectFirst("> td.MvTbTtl > a")
                        val name = aName.text()
                        val href = aName.attr("href")
                        val date = episode.selectFirst("> td.MvTbTtl > span")?.text()
                        episodeList.add(
                            TvSeriesEpisode(
                                name,
                                season.first,
                                epNum,
                                href,
                                if (poster != null) fixUrl(poster) else null,
                                date
                            )
                        )
                    }
                }
            }
            return TvSeriesLoadResponse(
                title,
                url,
                this.name,
                type,
                episodeList,
                backgroundPoster,
                year?.toIntOrNull(),
                descipt,
                null,
                null,
                rating
            )
        } else {
            val data = getLink(document)
                ?: throw ErrorLoadingException("No Links Found")

            return MovieLoadResponse(
                title,
                url,
                this.name,
                type,
                mapper.writeValueAsString(data.filter { it != "about:blank" }),
                backgroundPoster,
                year?.toIntOrNull(),
                descipt,
                null,
                rating,
                duration = duration
            )
        }
    }

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data == "about:blank") return false
        if (data.startsWith("$mainUrl/episode/")) {
            val response = get(data).text
            getLink(Jsoup.parse(response))?.let { links ->
                for (link in links) {
                    if (link == data) continue
                    loadLinks(link, isCasting, subtitleCallback, callback)
                }
                return true
            }
            return false
        } else if (data.startsWith(mainUrl) && data != mainUrl) {
            val realDataUrl = URLDecoder.decode(data, "UTF-8")
            if (data.contains("trdownload")) {
                val request = get(data)
                val requestUrl = request.url
                if (requestUrl.startsWith("https://streamhub.to/d/")) {
                    val buffer = Buffer()
                    val source = request.body?.source()
                    var html = ""
                    var tries = 0 // 20 tries = 163840 bytes = 0.16mb

                    while (source?.exhausted() == false && tries < 20) {
                        // 8192 = max size
                        source.read(buffer, 8192)
                        tries += 1
                        html += buffer.readUtf8()
                    }
                    getPostForm(request.url, html)?.let { form ->
                        val postDocument = Jsoup.parse(form)

                        postDocument.selectFirst("a.downloadbtn")?.attr("href")?.let { url ->
                            callback(ExtractorLink(this.name, this.name, url, mainUrl, Qualities.Unknown.value))
                        }
                    }
                } else if (requestUrl.startsWith("https://dood")) {
                    for (extractor in extractorApis) {
                        if (requestUrl.startsWith(extractor.mainUrl)) {
                            extractor.getSafeUrl(requestUrl)?.forEach { link ->
                                callback(link)
                            }
                            break
                        }
                    }
                } else {
                    callback(ExtractorLink(this.name, this.name, realDataUrl, mainUrl, Qualities.Unknown.value))
                }
                return true
            }
            val response = get(realDataUrl).text
            Regex("<iframe.*?src=\"(.*?)\"").find(response)?.groupValues?.get(1)?.let { url ->
                loadExtractor(url.trimStart(), realDataUrl, callback)
            }
            return true
        } else {
            val links = mapper.readValue<List<String>>(data)
            for (link in links) {
                loadLinks(link, isCasting, subtitleCallback, callback)
            }
            return true
        }
    }
}
