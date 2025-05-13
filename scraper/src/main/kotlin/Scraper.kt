import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI

object Scraper {
    private const val MAIN_URL = "https://streamingunity.to"
    private val headers = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "X-Requested-With" to "XMLHttpRequest",
        "X-Inertia" to "true"
    )
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val client: HttpClient = HttpClient.newBuilder().build()

    data class SearchResult(val name: String, val id: Int, val slug: String, val type: String)
    data class TitleProps(val title: Map<String, Any>, val loadedSeason: Map<String, Any>?)
    data class Script(val masterPlaylist: MasterPlaylist, val canPlayFHD: Boolean)
    data class MasterPlaylist(val url: String, val params: Params)
    data class Params(val token: String, val expires: String)

    fun setupHeaders() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$MAIN_URL/archive"))
            .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val doc = Jsoup.parse(response.body())
        val dataPage = doc.selectFirst("#app")?.attr("data-page")
            ?: throw IllegalStateException("Impossibile trovare data-page")
        val version = objectMapper.readValue<Map<String, Any>>(dataPage)["version"] as String
        headers["X-Inertia-Version"] = version
        // Simula i cookie, se necessario
        headers["Cookie"] = response.headers().allValues("set-cookie").joinToString("; ")
        println("Headers configurati. Inertia Version: $version")
    }

    fun search(query: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$MAIN_URL/api/search?q=$encodedQuery"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val data = objectMapper.readValue<List<Map<String, Any>>>(response.body())
        return data.filter { it["type"] in listOf("movie", "tv") }.map {
            SearchResult(
                name = it["name"] as String,
                id = it["id"] as Int,
                slug = it["slug"] as String,
                type = it["type"] as String
            )
        }
    }

    fun load(title: SearchResult): String? {
        val url = "$MAIN_URL/it/titles/${title.id}-${title.slug}"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val props = objectMapper.readValue<Map<String, Any>>(response.body())["props"] as Map<String, Any>
        val titleData = props["title"] as Map<String, Any>

        return if (titleData["type"] == "tv") {
            val seasons = titleData["seasons"] as List<Map<String, Any>>
            if (seasons.isEmpty()) {
                println("Nessuna stagione trovata per ${title.name}")
                return null
            }
            val episodeId = (props["loadedSeason"] as Map<String, Any>)["episodes"]?.let {
                (it as List<Map<String, Any>>).firstOrNull()?.get("id")
            } as Int?
            if (episodeId == null) {
                println("Nessun episodio trovato per ${title.name}")
                return null
            }
            "$MAIN_URL/it/iframe/${titleData["id"]}?episode_id=$episodeId&canPlayFHD=1"
        } else {
            "$MAIN_URL/it/iframe/${titleData["id"]}&canPlayFHD=1"
        }
    }

    fun getPlaylistLink(url: String): String? {
        val iframeHeaders = headers.toMutableMap().apply {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            put("Referer", MAIN_URL)
            put("Sec-Fetch-Dest", "iframe")
            put("Sec-Fetch-Mode", "navigate")
            put("Sec-Fetch-Site", "cross-site")
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .headers(*iframeHeaders.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val doc = Jsoup.parse(response.body())
        val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return null

        val iframeRequest = HttpRequest.newBuilder()
            .uri(URI.create(iframeSrc))
            .headers(*iframeHeaders.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            .GET()
            .build()
        val iframeResponse = client.send(iframeRequest, HttpResponse.BodyHandlers.ofString())
        val iframeDoc = Jsoup.parse(iframeResponse.body())
        val script = iframeDoc.select("script").find { it.html().contains("masterPlaylist") }?.html()
            ?: return null

        val jsonStr = script.replace("window.video", "\"video\"")
            .replace("window.streams", "\"streams\"")
            .replace("window.masterPlaylist", "\"masterPlaylist\"")
            .replace("window.canPlayFHD", "\"canPlayFHD\"")
            .replace("params", "\"params\"")
            .replace("url", "\"url\"")
            .replace(",\t        }", "}")
            .replace(",\t            }", "}")
            .replace("'", "\"")
            .replace(";", ",")
            .replace("=", ":")
            .replace("\\", "")
            .let { "{" + it.trim() + "}" }

        val scriptObj = objectMapper.readValue<Script>(jsonStr)
        val masterPlaylist = scriptObj.masterPlaylist
        var playlistUrl = masterPlaylist.url
        val params = "token=${masterPlaylist.params.token}&expires=${masterPlaylist.params.expires}"

        playlistUrl = if ("?b" in playlistUrl) {
            playlistUrl.replace("?b:1", "?b=1") + "&$params"
        } else {
            "$playlistUrl?$params"
        }

        if (scriptObj.canPlayFHD) {
            playlistUrl += "&h=1"
        }

        println("Playlist URL estratto: $playlistUrl")
        return playlistUrl
    }

    @JvmStatic
    fun main(args: Array<String>) {
        setupHeaders()

        val marvelTitles = listOf(
            "Avengers: Endgame",
            "Spider-Man: No Way Home",
            "Black Panther",
            "Thor: Ragnarok",
            "WandaVision"
        )

        val streams = mutableListOf<Pair<String, String>>()

        marvelTitles.forEach { titleName ->
            println("Cercando: $titleName")
            val searchResults = search(titleName)
            if (searchResults.isEmpty()) {
                println("Nessun risultato trovato per: $titleName")
                return@forEach
            }

            val title = searchResults.first()
            println("Trovato: ${title.name} (${title.type})")

            val dataUrl = load(title)
            if (dataUrl == null) {
                println("Impossibile ottenere l'URL del flusso per: ${title.name}")
                return@forEach
            }

            val streamUrl = getPlaylistLink(dataUrl)
            if (streamUrl != null) {
                streams.add(title.name to streamUrl)
            } else {
                println("Impossibile estrarre il flusso per: ${title.name}")
            }
        }

        // Genera Simud.m3u
        val m3uContent = "#EXTM3U\n" + streams.joinToString("\n") { (name, url) ->
            "#EXTINF:-1 tvg-name=\"$name\",$name\n$url"
        }

        File("Simud.m3u").writeText(m3uContent)
        println("File M3U generato: Simud.m3u")
    }
}
