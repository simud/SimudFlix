import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import com.github.Blatzar.NiceHttp.Request
import java.io.File

object Scraper {
    private const val MAIN_URL = "https://streamingunity.to"
    private val headers = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "X-Requested-With" to "XMLHttpRequest",
        "X-Inertia" to "true"
    )
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private var inertiaVersion: String? = null

    data class SearchResult(val name: String, val id: Int, val slug: String, val type: String)
    data class TitleProps(val title: Map<String, Any>, val loadedSeason: Map<String, Any>?)

    fun setupHeaders() {
        val response = Request.get("$MAIN_URL/it", headers).execute().body()
        val doc = Jsoup.parse(response)
        val dataPage = doc.selectFirst("#app")?.attr("data-page")
            ?: throw IllegalStateException("Impossibile trovare data-page")
        val version = objectMapper.readValue<Map<String, Any>>(dataPage)["version"] as String
        inertiaVersion = version
        headers["X-Inertia-Version"] = version
        println("Headers configurati. Inertia Version: $version")
    }

    fun search(query: String): List<SearchResult> {
        val url = "$MAIN_URL/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val response = Request.get(url, headers).execute().body()
        val data = objectMapper.readValue<List<Map<String, Any>>>(response)
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
        val response = Request.get(url, headers).execute().body()
        val props = objectMapper.readValue<Map<String, Any>>(response)["props"] as Map<String, Any>
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
        val response = Request.get(url, headers).execute().body()
        val doc = Jsoup.parse(response)
        val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: return null
        
        val iframeHeaders = headers.toMutableMap()
        iframeHeaders["Referer"] = MAIN_URL
        val iframeResponse = Request.get(iframeSrc, iframeHeaders).execute().body()
        val iframeDoc = Jsoup.parse(iframeResponse)
        val script = iframeDoc.select("script").first { it.html().contains("masterPlaylist") }.html()
        
        val jsonStr = script.replace(Regex("window\\.(video|streams|masterPlaylist|canPlayFHD)"), "\"$1\"")
            .replace("params", "\"params\"")
            .replace("url", "\"url\"")
            .replace("[,;]\\s*}", "}")
            .replace("=", ":")
            .replace("\\", "")
            .replace("'", "\"")
            .let { "{" + it.trim() + "}" }
        
        val data = objectMapper.readValue<Map<String, Any>>(jsonStr)
        val masterPlaylist = data["masterPlaylist"] as Map<String, Any>
        val params = masterPlaylist["params"] as Map<String, Any>
        var playlistUrl = masterPlaylist["url"] as String
        val queryParams = "token=${params["token"]}&expires=${params["expires"]}"
        
        playlistUrl = if ("?b" in playlistUrl) {
            playlistUrl.replace("?b:1", "?b=1") + "&$queryParams"
        } else {
            "$playlistUrl?$queryParams"
        }
        
        if (data["canPlayFHD"] as Boolean) {
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
