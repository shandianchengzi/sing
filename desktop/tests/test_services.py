from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import services


def test_self_hosted_protocol_upload_poll_download_cleanup_and_cache(tmp_path):
    job = 'a' * 32
    seen = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            assert self.headers['Authorization'] == 'Bearer secret'
            seen.append(self.path)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"service":"sing-separation","api_version":1}' if self.path == '/health'
                else b'{"status":"succeeded"}' if not self.path.endswith('/result') else b'fake-m4a')
        def do_POST(self):
            assert self.headers['Authorization'] == 'Bearer secret'
            assert self.headers['Content-Type'] == 'application/octet-stream'
            body = b''
            while True:
                size = int(self.rfile.readline().strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.read(2)
            assert body == b'source-audio'
            seen.append('upload')
            self.send_response(200)
            self.end_headers()
            self.wfile.write(('{"id":"' + job + '"}').encode())
        def do_DELETE(self):
            seen.append('delete')
            self.send_response(200)
            self.end_headers()
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        source = tmp_path / 'source.wav'
        source.write_bytes(b'source-audio')
        base = f'http://127.0.0.1:{server.server_port}'
        assert '正常' in services.health(base, 'secret')
        cache = tmp_path / 'cache'
        cache.mkdir()
        result = services.separate(source, base, 'secret', cache, threading.Event(), lambda _: None)
        assert result.read_bytes() == b'fake-m4a'
        assert seen == ['/health', 'upload', '/jobs/' + job, '/jobs/' + job + '/result', 'delete']
        services.separate(source, base, 'secret', cache, threading.Event(), lambda _: None)
        assert seen.count('upload') == 1
    finally:
        server.shutdown()
        server.server_close()
