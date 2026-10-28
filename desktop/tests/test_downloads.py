import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError

from anime_shelf.downloads import (DownloadManager, DownloadError, PAUSE_BYTES, RESUME_BYTES,
                                   HASH_BLOCK, download_key, media_headers, estimate_bytes)


def episode(index=1):
    return {"id": f"ep{index}", "seriesId": "ss1", "seriesTitle": "测试番剧", "index": index,
            "title": f"第 {index} 集", "localUri": ""}


def quality(base="https://media.example.test", video="/video", audio="/audio"):
    return {"id": "80:7:30280", "qn": 80, "label": "1080P · AVC", "videoUrl": base + video,
            "audioUrl": base + audio if audio else "", "headers": {"Cookie": "NEVER_SEND", "Authorization": "NEVER_SEND"}}


def wait_until(predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError("timed out waiting for download state")


class Response(io.BytesIO):
    def __init__(self, payload, url, status=200, headers=None):
        super().__init__(payload)
        self.status, self.url = status, url
        self.headers = headers or {"Content-Length": str(len(payload)), "Content-Type": "video/mp4"}
    def geturl(self): return self.url
    def getcode(self): return self.status


class DownloadUnitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.managers = []
    def tearDown(self):
        for manager in self.managers: manager.close()
        self.temp.cleanup()
    def manager(self, **kwargs):
        manager = DownloadManager(self.root, **kwargs)
        self.managers.append(manager)
        return manager

    def test_identity_ignores_expiring_tokens_but_not_resource_path_or_codec(self):
        q = quality(); q["videoUrl"] += "?token=one"
        first = download_key(episode(), q)
        q["videoUrl"] = "https://mirror.example.test/video?token=two"
        self.assertEqual(first, download_key(episode(), q))
        q["videoUrl"] = "https://mirror.example.test/new-video?token=two"
        self.assertNotEqual(first, download_key(episode(), q))
        q["id"] = "80:12:30280"
        self.assertNotEqual(first, download_key(episode(), q))
        headers = media_headers(q)
        self.assertNotIn("Cookie", headers); self.assertNotIn("Authorization", headers)
        self.assertIn("User-Agent", headers); self.assertIn("Referer", headers)

    def test_batch_is_validated_before_any_task_is_committed(self):
        manager = self.manager()
        bad = quality(); bad["videoUrl"] = "file:///private/video"
        with self.assertRaises(DownloadError): manager.enqueue_many([(episode(), quality()), (episode(2), bad)])
        self.assertEqual([], manager.snapshot())
        manager.enqueue_many([(episode(), quality()), (episode(2), quality())])
        reopened = DownloadManager(self.root)
        self.managers.append(reopened)
        self.assertEqual(2, len(reopened.snapshot()))

    def test_resume_one_after_global_pause_preserves_other_user_pauses_across_restart(self):
        manager = self.manager(free_space=lambda: 2_000_000_000)
        a, b = manager.enqueue_many([(episode(), quality()), (episode(2), quality())])
        manager.pause_all(); manager.resume(a)
        states = {row["key"]: row for row in manager.snapshot()}
        self.assertFalse(states[a]["paused"]); self.assertTrue(states[b]["paused"])
        manager.close()
        reopened = self.manager(free_space=lambda: 2_000_000_000)
        states = {row["key"]: row for row in reopened.snapshot()}
        self.assertTrue(states[b]["paused"]); self.assertFalse(states[a]["paused"])

    def test_unknown_network_blocks_before_open_and_disabling_wifi_allows_transfer(self):
        calls = []
        def opener(request, timeout):
            calls.append(request)
            return Response(b"data", request.full_url)
        manager = self.manager(opener=opener, free_space=lambda: 2_000_000_000)
        manager.enqueue(episode(), quality(audio="")); manager.start()
        time.sleep(.15)
        self.assertEqual([], calls); self.assertEqual("waiting_network", manager.snapshot()[0]["status"])
        manager.configure(wifi_only=False)
        wait_until(lambda: manager.snapshot()[0]["complete"])
        self.assertEqual(1, len(calls))
        self.assertNotIn("Cookie", dict(calls[0].header_items()))

    def test_storage_pause_is_sticky_and_resume_does_not_erase_other_pause(self):
        free = [100_000_000]
        manager = self.manager(free_space=lambda: free[0], network_allowed=lambda: True)
        a, b = manager.enqueue_many([(episode(), quality()), (episode(2), quality())])
        manager.pause(b)
        manager.start(); wait_until(lambda: manager.storage_paused)
        free[0] = PAUSE_BYTES + 1
        with self.assertRaises(DownloadError): manager.resume(a)
        manager.close()
        reopened = self.manager(free_space=lambda: free[0], network_allowed=lambda: True)
        self.assertTrue(reopened.storage_paused)
        free[0] = RESUME_BYTES
        reopened.resume(a)
        self.assertFalse(reopened.storage_paused)
        self.assertTrue(next(row for row in reopened.snapshot() if row["key"] == b)["paused"])

    def test_estimates_do_not_claim_unknown_audio_size_is_known(self):
        q = quality(); q["videoBytes"] = 100
        self.assertEqual((None, False), estimate_bytes(q))
        q["audioBytes"] = 10
        self.assertEqual((110, False), estimate_bytes(q))
        q["audioBytes"] = 0; q.update(durationMs=8000, bandwidth=100, audioBandwidth=10)
        self.assertEqual((110, True), estimate_bytes(q))

    def test_windows_busy_file_deletion_is_persisted_and_retried_after_restart(self):
        from unittest.mock import patch
        manager = self.manager()
        key = manager.enqueue(episode(), quality(audio=""))
        storage = manager._tasks[key]["storage"]
        path = manager.media_root / storage
        with patch("anime_shelf.downloads.shutil.rmtree", side_effect=PermissionError("file busy")):
            manager.remove(key)
        self.assertEqual([], manager.snapshot())
        self.assertTrue(path.exists())
        self.assertEqual(1, manager.pending_delete_count)
        self.assertEqual([storage], json.loads(manager.journal.read_text())["pendingDeletes"])
        manager.close()
        reopened = self.manager()
        self.assertEqual(1, reopened.pending_delete_count)
        reopened.start()
        wait_until(lambda: reopened.pending_delete_count == 0)
        self.assertFalse(path.exists())
        self.assertEqual([], json.loads(reopened.journal.read_text())["pendingDeletes"])

    def test_cancellation_during_read_cannot_resurrect_deleted_task(self):
        entered, release = threading.Event(), threading.Event()
        class Slow(Response):
            def read(self, size=-1):
                entered.set(); release.wait(3)
                return super().read(size)
        manager = self.manager(opener=lambda req, timeout: Slow(b"content", req.full_url),
                               network_allowed=lambda: True, free_space=lambda: 2_000_000_000)
        key = manager.enqueue(episode(), quality(audio="")); manager.start()
        self.assertTrue(entered.wait(3)); manager.remove(key); release.set()
        wait_until(lambda: not manager._active)
        self.assertEqual([], manager.snapshot())
        self.assertEqual([], list(manager.media_root.iterdir()))
        reopened = self.manager(); self.assertEqual([], reopened.snapshot())


class DownloadHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.video = (bytes(range(256)) * (HASH_BLOCK // 256 + 32)) * 3
        cls.audio = b"audio-track" * 10000
        cls.requests = []
        cls.lock = threading.Lock()
        outer = cls
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                with outer.lock:
                    outer.requests.append((self.path, dict(self.headers)))
                if self.path.startswith("/denied"):
                    self.send_error(403); return
                data = outer.audio if self.path.startswith("/audio") else outer.video
                requested = self.headers.get("Range", "bytes=0-").split("=", 1)[1]
                first, last = requested.split("-", 1)
                begin = int(first); end = int(last) if last else len(data) - 1
                if self.path.startswith("/ignore"):
                    begin, end = 0, len(data) - 1
                    self.send_response(200)
                else:
                    self.send_response(206)
                    reported_begin = begin + 1 if self.path.startswith("/wrong") else begin
                    self.send_header("Content-Range", f"bytes {reported_begin}-{end}/{len(data)}")
                body = data[begin:end + 1]
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("ETag", '"test-media-v1"')
                self.end_headers()
                try: self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError): pass
            def log_message(self, *args): pass
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(2)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.manager = DownloadManager(Path(self.temp.name), network_allowed=lambda: True, free_space=lambda: 2_000_000_000)
        with self.lock: self.requests.clear()

    def tearDown(self):
        self.manager.close(); self.temp.cleanup()

    def completed(self):
        wait_until(lambda: self.manager.snapshot()[0]["complete"])
        wait_until(lambda: not self.manager._active)
        return self.manager.snapshot()[0]

    def test_both_tracks_complete_and_offline_paths_survive_restart(self):
        key = self.manager.enqueue(episode(), quality(self.base)); self.manager.start()
        item = self.completed()
        self.assertEqual(len(self.video) + len(self.audio), item["bytesDownloaded"])
        self.assertEqual(100, item["percent"])
        self.assertEqual(self.video, Path(item["videoPath"]).read_bytes())
        self.assertEqual(self.audio, Path(item["audioPath"]).read_bytes())
        self.assertEqual([], [h for _, h in self.requests if "Cookie" in h or "Authorization" in h])
        self.manager.close()
        self.manager = DownloadManager(Path(self.temp.name), opener=lambda *a, **k: self.fail("offline must not open network"))
        offline = self.manager.get_complete(episode()["id"])
        self.assertEqual(key, offline["key"]); self.assertTrue(self.manager.inspect(key)["ok"])

    def test_resume_range_appends_only_correct_bytes(self):
        q = quality(self.base, audio=""); key = self.manager.enqueue(episode(), q)
        task = self.manager._tasks[key]
        self.manager._path(task, "video").write_bytes(self.video[:100000])
        self.manager.start(); item = self.completed()
        self.assertEqual(self.video, Path(item["videoPath"]).read_bytes())
        self.assertEqual("bytes=100000-", self.requests[0][1]["Range"])

    def test_range_ignored_uses_full_replacement_without_duplicate_prefix(self):
        q = quality(self.base, video="/ignore", audio=""); key = self.manager.enqueue(episode(), q)
        self.manager._path(self.manager._tasks[key], "video").write_bytes(self.video[:999])
        self.manager.start(); item = self.completed()
        self.assertEqual(self.video, Path(item["videoPath"]).read_bytes())
        self.assertEqual(len(self.video), item["totalBytes"])

    def test_bad_range_never_changes_partial_bytes_and_error_hides_url(self):
        q = quality(self.base, video="/wrong?token=SECRET", audio=""); key = self.manager.enqueue(episode(), q)
        path = self.manager._path(self.manager._tasks[key], "video"); path.write_bytes(b"unchanged")
        self.manager.start(); wait_until(lambda: self.manager.snapshot()[0]["status"] == "failed")
        self.assertEqual(b"unchanged", path.read_bytes())
        self.assertNotIn("SECRET", self.manager.snapshot()[0]["error"])
        self.assertNotIn("http", self.manager.snapshot()[0]["error"].lower())

    def test_missing_audio_never_reports_completed(self):
        q = quality(self.base, audio="/denied"); self.manager.enqueue(episode(), q); self.manager.start()
        wait_until(lambda: self.manager.snapshot()[0]["status"] == "failed")
        item = self.manager.snapshot()[0]
        self.assertFalse(item["complete"]); self.assertIsNone(item["percent"])
        self.assertIsNone(self.manager.get_complete(episode()["id"]))

    def test_same_length_corruption_is_repaired_by_range_and_preserves_good_audio(self):
        key = self.manager.enqueue(episode(), quality(self.base)); self.manager.start(); item = self.completed()
        path = Path(item["videoPath"]); audio_path = Path(item["audioPath"])
        audio_mtime = audio_path.stat().st_mtime_ns
        with path.open("r+b") as stream: stream.seek(HASH_BLOCK + 5); stream.write(b"BROKEN")
        report = self.manager.inspect(key)
        self.assertFalse(report["ok"])
        self.assertEqual([[HASH_BLOCK, 2 * HASH_BLOCK - 1]], report["tracks"][0]["changedRanges"])
        self.assertTrue(self.manager.snapshot()[0]["needsRepair"])
        with self.lock: self.requests.clear()
        self.manager.repair(key); item = self.completed()
        self.assertEqual(self.video, Path(item["videoPath"]).read_bytes())
        self.assertEqual(audio_mtime, audio_path.stat().st_mtime_ns)
        self.assertEqual(1, len(self.requests))
        self.assertEqual(f"bytes={HASH_BLOCK}-{2 * HASH_BLOCK - 1}", self.requests[0][1]["Range"])
        self.assertTrue(self.manager.inspect(key)["ok"])

    def test_deleted_file_can_be_repaired_without_discarding_audio(self):
        key = self.manager.enqueue(episode(), quality(self.base)); self.manager.start(); item = self.completed()
        Path(item["videoPath"]).unlink()
        report = self.manager.inspect(key); self.assertFalse(report["ok"])
        audio_mtime = Path(item["audioPath"]).stat().st_mtime_ns
        self.manager.repair(key); restored = self.completed()
        self.assertEqual(self.video, Path(restored["videoPath"]).read_bytes())
        self.assertEqual(audio_mtime, Path(restored["audioPath"]).stat().st_mtime_ns)

    def test_refreshed_resource_path_never_reuses_old_partial_file(self):
        old = quality(self.base, video="/denied", audio="")
        new = quality(self.base, video="/video", audio="")
        self.manager.resolver = lambda ep, q: new
        key = self.manager.enqueue(episode(), old)
        path = self.manager._path(self.manager._tasks[key], "video"); path.write_bytes(b"old data")
        self.manager.start()
        wait_until(lambda: len(self.manager.snapshot()) == 1 and self.manager.snapshot()[0]["complete"])
        item = self.manager.snapshot()[0]
        self.assertNotEqual(key, item["key"])
        self.assertEqual(self.video, Path(item["videoPath"]).read_bytes())
        self.assertFalse(path.exists())


if __name__ == "__main__": unittest.main()
