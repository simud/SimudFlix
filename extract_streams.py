import requests
from bs4 import BeautifulSoup
import jmespath
import json
import re
from urllib.parse import urljoin, urlparse
import logging

# Configura il logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class StreamingCommunityExtractor:
    def __init__(self):
        self.main_url = "https://streamingunity.to"
        self.headers = {
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
            "X-Requested-With": "XMLHttpRequest",
            "X-Inertia": "true"
        }
        self.inertia_version = ""
        self.setup_headers()

    def setup_headers(self):
        """Configura i cookie e la versione Inertia."""
        try:
            response = requests.get(f"{self.main_url}/archive", headers=self.headers)
            response.raise_for_status()
            cookies = response.cookies.get_dict()
            self.headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in cookies.items())
            
            soup = BeautifulSoup(response.text, 'html.parser')
            data_page = soup.select_one("#app").get("data-page")
            self.inertia_version = json.loads(data_page)["version"]
            self.headers["X-Inertia-Version"] = self.inertia_version
            logger.info("Headers configurati con successo.")
        except Exception as e:
            logger.error(f"Errore durante la configurazione dei headers: {e}")
            raise

    def search(self, query):
        """Cerca titoli sul sito."""
        url = f"{self.main_url}/api/search"
        params = {"q": query}
        try:
            response = requests.get(url, headers=self.headers, params=params)
            response.raise_for_status()
            data = response.json()
            titles = jmespath.search("data[?type=='movie' || type=='tv'].{name: name, id: id, slug: slug, type: type}", data)
            return titles
        except Exception as e:
            logger.error(f"Errore durante la ricerca di '{query}': {e}")
            return []

    def get_actual_url(self, url):
        """Corregge l'URL se necessario."""
        parsed_main = urlparse(self.main_url)
        parsed_url = urlparse(url)
        if parsed_url.netloc != parsed_main.netloc:
            actual_url = url.replace(parsed_url.netloc, parsed_main.netloc)
            logger.info(f"URL corretto: {url} -> {actual_url}")
            return actual_url
        return url

    def load(self, title):
        """Carica i dettagli del titolo e restituisce l'URL del flusso."""
        url = f"{self.main_url}/titles/{title['id']}-{title['slug']}"
        actual_url = self.get_actual_url(url).replace(self.main_url, f"{self.main_url}/it")
        
        try:
            response = requests.get(actual_url, headers=self.headers)
            response.raise_for_status()
            props = json.loads(response.text)["props"]
            title_data = props["title"]
            
            if title_data["type"] == "tv":
                # Per le serie TV, prendiamo il primo episodio della prima stagione
                seasons = title_data.get("seasons", [])
                if not seasons:
                    logger.warning(f"Nessuna stagione trovata per {title['name']}")
                    return None
                season = seasons[0]
                episode_id = jmespath.search("episodes[0].id", props.get("loadedSeason", {}))
                if not episode_id:
                    logger.warning(f"Nessun episodio trovato per {title['name']}")
                    return None
                data_url = f"{self.main_url}/it/iframe/{title_data['id']}?episode_id={episode_id}&canPlayFHD=1"
            else:
                data_url = f"{self.main_url}/it/iframe/{title_data['id']}&canPlayFHD=1"
            
            return data_url
        except Exception as e:
            logger.error(f"Errore durante il caricamento di {title['name']}: {e}")
            return None

    def get_playlist_link(self, url):
        """Estrae il link del playlist M3U8."""
        try:
            response = requests.get(url, headers=self.headers)
            response.raise_for_status()
            soup = BeautifulSoup(response.text, 'html.parser')
            iframe_src = soup.select_one("iframe").get("src")
            
            # Estrai lo script con il masterPlaylist
            iframe_headers = self.headers.copy()
            iframe_headers.update({
                "Referer": self.main_url,
                "Sec-Fetch-Dest": "iframe",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-Site": "cross-site"
            })
            iframe_response = requests.get(iframe_src, headers=iframe_headers)
            iframe_response.raise_for_status()
            iframe_soup = BeautifulSoup(iframe_response.text, 'html.parser')
            scripts = iframe_soup.select("script")
            script = next(s for s in scripts if "masterPlaylist" in s.text).text
            
            # Sanitizza lo script per il parsing JSON
            script_json = re.sub(r'window\.(video|streams|masterPlaylist|canPlayFHD)', r'"\1"', script)
            script_json = re.sub(r'params', r'"params"', script_json)
            script_json = re.sub(r'url', r'"url"', script_json)
            script_json = re.sub(r'[,;]\s*}', r'}', script_json)
            script_json = re.sub(r'=', r':', script_json)
            script_json = re.sub(r'\\', r'', script_json)
            script_json = "{" + script_json.replace("'", '"').strip() + "}"
            script_data = json.loads(script_json)
            
            master_playlist = script_data["masterPlaylist"]
            params = f"token={master_playlist['params']['token']}&expires={master_playlist['params']['expires']}"
            playlist_url = master_playlist["url"]
            if "?b" in playlist_url:
                playlist_url = playlist_url.replace("?b:1", "?b=1") + "&" + params
            else:
                playlist_url = f"{playlist_url}?{params}"
            
            if script_data.get("canPlayFHD"):
                playlist_url += "&h=1"
                
            logger.info(f"Playlist URL estratto: {playlist_url}")
            return playlist_url
        except Exception as e:
            logger.error(f"Errore durante l'estrazione del playlist: {e}")
            return None

def generate_m3u(titles_streams, output_file="Simud.m3u"):
    """Genera il file M3U con i flussi video."""
    m3u_content = "#EXTM3U\n"
    for title, stream_url in titles_streams:
        if stream_url:
            m3u_content += f'#EXTINF:-1 tvg-name="{title}",{title}\n{stream_url}\n'
    
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(m3u_content)
    logger.info(f"File M3U generato: {output_file}")

def main():
    # Lista di 5 titoli Marvel da cercare
    marvel_titles = [
        "Avengers: Endgame",
        "Spider-Man: No Way Home",
        "Black Panther",
        "Thor: Ragnarok",
        "WandaVision"  # Serie TV
    ]
    
    extractor = StreamingCommunityExtractor()
    titles_streams = []
    
    for title_name in marvel_titles:
        logger.info(f"Cercando: {title_name}")
        search_results = extractor.search(title_name)
        
        # Prendi il primo risultato (più rilevante)
        if not search_results:
            logger.warning(f"Nessun risultato trovato per: {title_name}")
            continue
        
        title = search_results[0]
        logger.info(f"Trovato: {title['name']} ({title['type']})")
        
        # Carica i dettagli del titolo
        data_url = extractor.load(title)
        if not data_url:
            logger.warning(f"Impossibile ottenere l'URL del flusso per: {title['name']}")
            continue
        
        # Estrai il link del playlist M3U8
        stream_url = extractor.get_playlist_link(data_url)
        if stream_url:
            titles_streams.append((title['name'], stream_url))
        else:
            logger.warning(f"Impossibile estrarre il flusso per: {title['name']}")
    
    # Genera il file M3U
    generate_m3u(titles_streams)

if __name__ == "__main__":
    main()
