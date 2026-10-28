"""Bounded, cancellable real keyframe previews through the packaged FFmpeg.

Online FFmpeg sees only a nonce-protected loopback URL. A per-request proxy owns
public CDN headers, checks redirects and stops after 8 MiB/8 opens/4 seconds.
Offline previews are exclusively local file reads. Failure returns no image.
"""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError
import os
import secrets
import subprocess
import sys
import threading
import time
from .player_policy import media_url


class Budget:
    def __init__(self, cancelled, max_bytes=8 * 1024 * 1024, max_opens=8, timeout=4.0):
        self.remaining = max_bytes
        self.opens = max_opens
        self.deadline = time.monotonic() + timeout
        self.cancelled = cancelled
        self.lock = threading.Lock()

    def check(self):
        if self.cancelled() or time.monotonic() >= self.deadline:
            raise TimeoutError('预览已取消')

    def opened(self):
        with self.lock:
            self.check()
            if self.opens <= 0:
                raise TimeoutError('预览读取次数已达上限')
            self.opens -= 1

    def take(self, maximum):
        with self.lock:
            self.check()
            if self.remaining <= 0:
                raise TimeoutError('预览读取量已达上限')
            amount = min(maximum, self.remaining)
            self.remaining -= amount
            return amount


class _Redirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not media_url(newurl):
            raise HTTPError(req.full_url, 403, 'Invalid media redirect', {}, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def runtime_root() -> Path:
    return Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[1]))


def ffmpeg_path() -> Path | None:
    root = runtime_root()
    exe = 'ffmpeg.exe' if os.name == 'nt' else 'ffmpeg'
    for path in (root / 'runtime/ffmpeg' / exe, root / 'runtime/mpv' / exe):
        if path.is_file():
            return path
    return None


def extract_frame(plan, position_ms: int, cancelled) -> bytes | None:
    executable = ffmpeg_path()
    if executable is None or cancelled():
        return None
    server = None
    process = None
    budget = Budget(cancelled)
    try:
        source = plan.videos[0]
        if not plan.offline:
            token = '/' + secrets.token_urlsafe(24)
            opener = build_opener(_Redirect())
            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_args):
                    pass
                def do_GET(self):
                    if self.path != token:
                        self.send_error(404)
                        return
                    try:
                        budget.opened()
                        headers = dict(plan.headers)
                        value = self.headers.get('Range', '')
                        if value:
                            import re
                            if not re.fullmatch(r'bytes=\d+-\d*', value):
                                self.send_error(416)
                                return
                            headers['Range'] = value
                        with opener.open(Request(source, headers=headers), timeout=2.5) as response:
                            if response.status not in (200, 206):
                                self.send_error(502)
                                return
                            self.send_response(response.status)
                            for key in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges'):
                                if response.headers.get(key):
                                    self.send_header(key, response.headers[key])
                            self.send_header('Connection', 'close')
                            self.end_headers()
                            while True:
                                count = budget.take(64 * 1024)
                                block = response.read(count)
                                if not block:
                                    break
                                self.wfile.write(block)
                    except Exception:
                        self.close_connection = True
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.daemon_threads = True
            threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .05}, daemon=True).start()
            input_path = f'http://127.0.0.1:{server.server_port}{token}'
            protocols = 'http,tcp'
        else:
            input_path = str(Path(source).resolve())
            protocols = 'file'
        command = [str(executable), '-nostdin', '-hide_banner', '-loglevel', 'error',
                   '-protocol_whitelist', protocols, '-rw_timeout', '2500000',
                   '-ss', f'{max(0, position_ms) / 1000:.3f}', '-i', input_path,
                   '-an', '-sn', '-frames:v', '1', '-vf', 'scale=320:180:force_original_aspect_ratio=decrease',
                   '-threads', '1', '-f', 'image2pipe', '-vcodec', 'mjpeg', '-q:v', '5', 'pipe:1']
        flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=flags)
        while True:
            budget.check()
            try:
                output, _ = process.communicate(timeout=.08)
                return output if process.returncode == 0 and output.startswith(b'\xff\xd8') and len(output) <= 1024 * 1024 and not cancelled() else None
            except subprocess.TimeoutExpired:
                continue
    except Exception:
        return None
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.communicate()
        if server is not None:
            server.shutdown()
            server.server_close()


class PreviewWorker:
    """At most one active decoder; latest request supersedes any prior one."""
    def __init__(self):
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='seek-preview')
        self.generation = 0
        self.closed = False
        self.cache = {}
        self.lock = threading.Lock()

    def cancel(self):
        with self.lock:
            self.generation += 1

    def request(self, plan, position_ms, callback):
        with self.lock:
            if self.closed:
                return
            self.generation += 1
            generation = self.generation
        bucket = max(0, int(position_ms)) // 2000 * 2000
        key = (plan.identity, plan.offline, plan.videos[0], bucket)
        if key in self.cache:
            callback(generation, self.cache[key])
            return
        def cancelled():
            return self.closed or generation != self.generation
        def work():
            if cancelled():
                return
            image = extract_frame(plan, bucket, cancelled)
            if not cancelled():
                if image is not None:
                    self.cache[key] = image
                    while len(self.cache) > 24:
                        del self.cache[next(iter(self.cache))]
                callback(generation, image)
        self.executor.submit(work)

    def close(self):
        with self.lock:
            self.closed = True
            self.generation += 1
        self.executor.shutdown(wait=False, cancel_futures=True)
        self.cache.clear()
