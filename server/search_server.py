"""Local YouTube search proxy. Run manually; API key stays in this process."""
import argparse
import json
import logging
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

LOG = logging.getLogger('SHSH_SEARCH')
LOCAL_PROPERTIES = Path(__file__).resolve().parents[1] / 'local.properties'


def youtube_api_key():
    # Explicit local file value wins; environment remains a fallback.
    try:
        for raw_line in LOCAL_PROPERTIES.read_text(encoding='utf-8').splitlines():
            line = raw_line.strip()
            if not line or line.startswith(('#', '!')):
                continue
            name, separator, value = line.partition('=')
            if separator and name.strip() == 'YOUTUBE_API_KEY' and value.strip():
                return value.strip()
    except FileNotFoundError:
        pass
    except (OSError, UnicodeError):
        LOG.warning('Could not read local.properties; using environment fallback')
    return os.environ.get('YOUTUBE_API_KEY', '').strip()



class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Default logging includes user search text; do not print it.

    def reply(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == '/health':
            self.reply(200, {'status': 'ok', 'youtube_key_configured': bool(youtube_api_key())})
            return
        if parsed.path != '/songs/search':
            self.reply(404, {'error': 'not_found'})
            return
        key = youtube_api_key()
        if not key:
            self.reply(503, {'error': 'youtube_key_missing'})
            return
        query = parse_qs(parsed.query).get('q', [''])[0].strip()
        if not 1 <= len(query) <= 120:
            self.reply(400, {'error': 'invalid_query'})
            return
        keyword = query if any(word in query.lower() for word in ('노래방', 'karaoke', ' mr')) else query + ' 노래방'
        params = urlencode(dict(part='snippet', type='video', q=keyword, maxResults=5,
                                videoEmbeddable='true', videoSyndicated='true',
                                relevanceLanguage='ko', regionCode='KR', safeSearch='moderate'))
        request = Request('https://www.googleapis.com/youtube/v3/search?' + params,
                          headers={'X-Goog-Api-Key': key, 'Accept': 'application/json'})
        LOG.info('YouTube search requested; query_chars=%d', len(query))
        try:
            with urlopen(request, timeout=12) as response:
                raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError('response_too_large')
            body = json.loads(raw)
            items = []
            for item in body.get('items', []):
                video_id = item.get('id', {}).get('videoId', '')
                snippet = item.get('snippet', {})
                if len(video_id) == 11:
                    items.append({'videoId': video_id, 'title': snippet.get('title', ''),
                                  'channelTitle': snippet.get('channelTitle', '')})
            LOG.info('Search finished; results=%d', len(items))
            self.reply(200, {'source': 'YouTube', 'items': items})
        except HTTPError as error:
            reason = 'youtube_error'
            try:
                details = json.loads(error.read(8192))
                reason = details.get('error', {}).get('errors', [{}])[0].get('reason', reason)
            except (ValueError, IndexError, TypeError):
                pass
            # Only known reason codes; never forward upstream bodies, headers or credentials.
            permitted = {'quotaExceeded', 'dailyLimitExceeded', 'accessNotConfigured', 'keyInvalid',
                         'ipRefererBlocked', 'forbidden', 'rateLimitExceeded'}
            reason = reason if reason in permitted else 'youtube_error'
            LOG.warning('YouTube search failed; status=%d, reason=%s', error.code, reason)
            self.reply(502, {'error': reason, 'upstreamStatus': error.code})
        except (URLError, TimeoutError, OSError, ValueError):
            LOG.warning('Search failed; network or response error')
            self.reply(502, {'error': 'network_or_response_error'})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Local-only YouTube search proxy for Pleos emulator')
    parser.add_argument('--port', type=int, default=8090)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(name)s %(levelname)s %(message)s')
    LOG.info('Local search server listening on 127.0.0.1:%d; key_configured=%s', args.port, bool(youtube_api_key()))
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
