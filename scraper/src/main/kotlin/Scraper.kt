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
    data class Script(val masterPlaylist: MasterPlaylist, val canPlayFHD: Boolean)
    data class MasterPlaylist(val url: String, val params: Params)
    data class Params(val token: String, val expires: String)

    fun setupHeaders(): Boolean {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$MAIN_URL/archive"))
                .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            
            println("setupHeaders - HTTP Status: ${response.statusCode()}")
            println("setupHeaders - Response Headers: ${response.headers().map()}")
            println("setupHeaders - Response Body (truncated): ${response.body().take(1000)}")

            if (response.statusCode() != 200) {
                println("setupHeaders - Errore HTTP: ${response.statusCode()}")
                return false
            }

            val doc = Jsoup.parse(response.body())
            val dataPage = doc.selectFirst("#app")?.attr("data-page")
            if (dataPage == null) {
                println("setupHeaders - Errore: Impossibile trovare data-page. Verifica la struttura HTML di $MAIN_URL/archive")
                return false
            }

            val version = objectMapper.readValue<Map<String, Any>>(dataPage).get("version") as? String
            if (version == null) {
                println("setupHeaders - Errore: Impossibile estrarre X-Inertia-Version da data-page")
                return false
            }

            headers["X-Inertia-Version"] = version
            headers["Cookie"] = response.headers().allValues("set-cookie").joinToString("; ")
            println("setupHeaders - Headers configurati. Inertia Version: $version")
            return true
        } catch (e: Exception) {
            println("setupHeaders - Eccezione: ${e.javaClass.name} - ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun search(query: String): List<SearchResult> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$MAIN_URL/api/search?q=$encodedQuery"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            println("search - HTTP Status: ${response.statusCode()}")
            println("search - Response Body (truncated): ${response.body().take(500)}")
            
            if (response.statusCode() != 200) {
                println("search - Errore HTTP: ${response.statusCode()}")
                return emptyList()
            }

            val data = objectMapper.readValue<List<Map<String, Any>>>(response.body())
            return data.filter { it["type"] in listOf("movie", "tv") }.map {
                SearchResult(
                    name = it["name"] as String,
                    id = it["id"] as Int,
                    slug = it["slug"] as String,
                    type = it["type"] as String
                )
            }
        } catch (e: Exception) {
            println("search - Eccezione: ${e.javaClass.name} - ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun load(title: SearchResult): String? {
        try {
            val url = "$MAIN_URL/it/titles/${title.id}-${title.slug}"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .headers(*headers.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            println("load - HTTP Status: ${response.statusCode()}")
            println("load - Response Body (truncated): ${response.body().take(500)}")
            
            if (response.statusCode() != 200) {
                println("load - Errore HTTP: ${response.statusCode()}")
                return null
            }

            val props = objectMapper.readValue<Map<String, Any>>(response.body())
            val propsData = props["props"] as? Map<String, Any> ?: run {
                println("load - Errore: 'props' non è una mappa valida")
                return null
            }
            val titleMap = propsData["title"] as? Map<String, Any> ?: run {
                println("load - Errore: 'title' non è una mappa valida")
                return null
            }

            return if (titleMap["type"] == "tv") {
                val seasons = titleMap["seasons"] as? List<Map<String, Any>> ?: run {
                    println("load - Nessuna stagione trovata per ${title.name}")
                    return null
                }
                if (seasons.isEmpty()) {
                    println("load - Nessuna stagione trovata per ${title.name}")
                    return null
                }
                val loadedSeason = propsData["loadedSeason"] as? Map<String, Any> ?: run {
                    println("load - Errore: 'loadedSeason' non è una mappa valida")
                    return null
                }
                val episodes = loadedSeason["episodes"] as? List<Map<String, Any>> ?: run {
                    println("load - Errore: 'episodes' non è una lista valida")
                    return null
                }
                val episodeId = episodes.firstOrNull()?.get("id") as? Int ?: run {
                    println("load - Nessun episodio trovato per ${title.name}")
                    return null
                }
                "$MAIN_URL/it/iframe/${titleMap["id"]}?episode_id=$episodeId&canPlayFHD=1"
            } else {
                "$MAIN_URL/it/iframe/${titleMap["id"]}&canPlayFHD=1"
            }
        } catch (e: Exception) {
            println("load - Eccezione: ${e.javaClass.name} - ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    fun getPlaylistLink(url: String): String? {
        try {
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
            println("getPlaylistLink - HTTP Status: ${response.statusCode()}")
            println("getPlaylistLink - Response Body (truncated): ${response.body().take(500)}")
            
            if (response.statusCode() != 200) {
                println("getPlaylistLink - Errore HTTP: ${response.statusCode()}")
                return null
            }

            val doc = Jsoup.parse(response.body())
            val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: run {
                println("getPlaylistLink - Errore: Impossibile trovare iframe")
                return null
            }

            val iframeRequest = HttpRequest.newBuilder()
                .uri(URI.create(iframeSrc))
                .headers(*iframeHeaders.toList().flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
                .GET()
                .build()
            val iframeResponse = client.send(iframeRequest, HttpResponse.BodyHandlers.ofString())
            println("getPlaylistLink - Iframe HTTP Status: ${iframeResponse.statusCode()}")
            println("getPlaylistLink - Iframe Response Body (truncated): ${iframeResponse.body().take(500)}")
            
            if (iframeResponse.statusCode() != 200) {
                println("getPlaylistLink - Errore Iframe HTTP: ${iframeResponse.statusCode()}")
                return null
            }

            val iframeDoc = Jsoup.parse(iframeResponse.body())
            val script = iframeDoc.select("script").find { it.html().contains("masterPlaylist") }?.html()
                ?: run {
                    println("getPlaylistLink - Errore: Impossibile trovare script con masterPlaylist")
                    return null
                }

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

            println("getPlaylistLink - Playlist URL estratto: $playlistUrl")
            return playlistUrl
        } catch (e: Exception) {
            println("getPlaylistLink - Eccezione: ${e.javaClass.name} - ${e.message}")
            e.printStackTrace()
            return null
        }
    }
}

fun main(args: Array<String>) {
    try {
        if (!Scraper.setupHeaders()) {
            println("main - Errore: Impossibile configurare gli headers. Interruzione.")
            return
        }

        val marvelTitles = listOf(
            "Avengers: Endgame",
            "Spider-Man: No Way Home",
            "Black Panther",
            "Thor: Ragnarok",
            "WandaVision"
        )

        val streams = mutableListOf<Pair<String, String>>()

        marvelTitles.forEach { titleName ->
            println("main - Cercando: $titleName")
            val searchResults = Scraper.search(titleName)
            if (searchResults.isEmpty()) {
                println("main - Nessun risultato trovato per: $titleName")
                return@forEach
            }

            val title = searchResults.first()
            println("main - Trovato: ${title.name} (${title.type})")

            val dataUrl = Scraper.load(title)
            if (dataUrl == null) {
                println("main - Impossibile ottenere l'URL del flusso per: ${title.name}")
                return@forEach
            }

            val streamUrl = Scraper.getPlaylistLink(dataUrl)
            if (streamUrl != null) {
                streams.add(title.name to streamUrl)
            } else {
                println("main - Impossibile estrarre il flusso per: ${title.name}")
            }
        }

        // Genera Simud.m3u
        val m3uContent = "#EXTM3U\n" + streams.joinToString("\n") { (name, url) ->
            "#EXTINF:-1 tvg-name=\"$name\",$name\n$url"
        }

        File("Simud.m3u").writeText(m3uContent)
        println("main - File M3U generato: Simud.m3u")
    } catch (e: Exception) {
        println("main - Eccezione: ${e.javaClass.name} - ${e.message}")
        e.printStackTrace()
    }
}
