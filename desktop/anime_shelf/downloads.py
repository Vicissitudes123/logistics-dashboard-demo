"""Persistent, resumable desktop downloads. Only this module owns its media files.

No Qt dependency. Session cookies are never accepted as media request headers.
The caller owns confirmations, API authentication and the Windows network probe.
"""
from __future__ import annotations

from collections import deque
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from pathlib import Path
import hashlib
import json
import math
import os
import re
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MEDIA_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
REFERER = "https://www.bilibili.com/"
PAUSE_BYTES = 200_000_000
RESUME_BYTES = 300_000_000
HASH_BLOCK = 1024 * 1024
READ_BLOCK = 128 * 1024


class DownloadError(RuntimeError):
    """An authored, safe-to-display failure, never a raw signed URL."""


class _Stopped(Exception):
    pass


class _HTTPFailure(DownloadError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"媒体服务器返回 HTTP {status}，可重新解析后重试")


class _SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        _url(new_url)
        if request.full_url.startswith("https:") and not new_url.startswith("https:"):
            raise DownloadError("媒体地址重定向不安全")
        return super().redirect_request(request, fp, code, message, headers, new_url)


def _url(value):
    if not isinstance(value, str) or not value or len(value) > 16384 or any(ord(c) <= 32 or ord(c) == 127 for c in value):
        raise DownloadError("媒体地址无效，请重新解析")
    if value.startswith("//"):
        value = "https:" + value
    try:
        parts = urllib.parse.urlsplit(value)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.fragment:
            raise ValueError()
        if parts.port is not None and not 0 < parts.port < 65536:
            raise ValueError()
    except ValueError:
        raise DownloadError("媒体地址无效，请重新解析") from None
    return value


def candidates(quality, kind):
    result = []
    for value in [quality.get(kind + "Url", ""), *quality.get(kind + "Urls", [])]:
        if not value:
            continue
        try:
            normalized = _url(value)
        except DownloadError:
            continue
        if normalized not in result:
            result.append(normalized)
    return result[:5]


def media_headers(quality):
    result = {"User-Agent": MEDIA_UA, "Referer": REFERER, "Accept-Encoding": "identity"}
    allowed = {"user-agent": "User-Agent", "referer": "Referer", "origin": "Origin"}
    for name, value in quality.get("headers", {}).items():
        canonical = allowed.get(str(name).lower())
        if canonical and isinstance(value, str) and not any(ord(c) < 32 or ord(c) == 127 for c in value):
            result[canonical] = value
    return result


def download_key(episode, quality):
    video, audio = candidates(quality, "video"), candidates(quality, "audio")
    if not video or not str(episode.get("id", "")) or not str(quality.get("id", "")):
        raise DownloadError("缺少剧集或画质信息，请重新解析")
    identity = "|".join([str(episode["id"]), str(quality["id"]), urllib.parse.urlsplit(video[0]).path,
                         urllib.parse.urlsplit(audio[0]).path if audio else ""])
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def same_profile(first, second):
    def normalized(value):
        if not isinstance(value, dict):
            return None
        parts = str(value.get("id", "")).split(":")
        codec = str(value.get("codec", "") or (parts[1] if len(parts) > 1 else ""))
        try:
            qn = int(value.get("qn", 0) or 0)
        except (ValueError, TypeError):
            return None
        return (qn, codec) if qn > 0 and codec else None
    left, right = normalized(first), normalized(second)
    return left is not None and left == right


def estimate_bytes(quality):
    """Returns (bytes or None, estimated). This is never used as actual progress."""
    video = int(quality.get("videoBytes", 0) or 0)
    audio = int(quality.get("audioBytes", 0) or 0)
    has_audio = bool(candidates(quality, "audio"))
    if video > 0 and (not has_audio or audio > 0):
        return video + (audio if has_audio else 0), False
    duration = int(quality.get("durationMs", 0) or 0)
    vb, ab = int(quality.get("bandwidth", 0) or 0), int(quality.get("audioBandwidth", 0) or 0)
    if duration > 0 and vb > 0 and (not has_audio or ab > 0):
        return math.ceil((vb + (ab if has_audio else 0)) * duration / 8000), True
    return None, False


class DownloadManager:
    def __init__(self, root: Path, resolver=None, *, network_allowed=None, free_space=None,
                 opener=None, clock=None, wall_clock=None, max_workers=2):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.media_root = self.root / "media"
        self.media_root.mkdir(exist_ok=True)
        self.journal = self.root / "downloads.json"
        self.resolver = resolver
        self.network_allowed = network_allowed or (lambda: False)
        self.free_space = free_space or (lambda: shutil.disk_usage(self.root).free)
        self.clock = clock or time.monotonic
        self.wall_clock = wall_clock or time.time
        self.opener = opener or urllib.request.build_opener(_SafeRedirect()).open
        self.max_workers = max(1, min(3, int(max_workers)))
        self._lock = threading.RLock()
        self._condition = threading.Condition(self._lock)
        self._tasks = {}
        self._active = {}
        self._responses = {}
        self._samples = {}
        self._pending_deletes = set()
        self._last_delete_retry = -1e30
        self._generations = {}
        self._closed = False
        self._thread = None
        self._pool = None
        self._checked_space_at = -1e30
        self._last_save_at = -1e30
        self._global_paused = False
        self._space_paused = False
        self._wifi_only = True
        self._priority = ""
        self._load()

    def _load(self):
        if not self.journal.exists():
            return
        try:
            if self.journal.stat().st_size > 32 * 1024 * 1024:
                raise ValueError()
            data = json.loads(self.journal.read_text(encoding="utf-8"))
            if data.get("version") != 1 or not isinstance(data.get("tasks"), list):
                raise ValueError()
            self._global_paused = data.get("paused") is True
            self._space_paused = data.get("spacePaused") is True
            self._wifi_only = data.get("wifiOnly", True) is not False
            self._priority = str(data.get("priority", ""))
            self._pending_deletes = {name for name in data.get("pendingDeletes", [])
                                     if isinstance(name, str) and re.fullmatch(r"[a-f0-9]{64}-[a-f0-9]{32}", name)}
            for task in data["tasks"][:20000]:
                key = task["key"]
                if not re.fullmatch(r"[a-f0-9]{64}", key) or not re.fullmatch(key + r"-[a-f0-9]{32}", task["storage"]):
                    continue
                if download_key(task["episode"], task["quality"]) != key:
                    continue
                expected_kinds = ["video", "audio"] if candidates(task["quality"], "audio") else ["video"]
                if sorted(task.get("tracks", {})) != sorted(expected_kinds):
                    continue
                task["active"] = False
                self._tasks[key] = task
                self._generations[key] = 0
        except (OSError, ValueError, KeyError, TypeError, DownloadError):
            # Preserve damaged metadata for recovery rather than silently overwriting it.
            raise DownloadError("下载任务记录损坏。请保留 downloads.json 后联系反馈，不要删除离线文件。") from None
        if self._priority not in self._tasks:
            self._priority = ""

    def _save(self, force=True):
        now = self.clock()
        if not force and now - self._last_save_at < 1:
            return
        data = {"version": 1, "paused": self._global_paused, "spacePaused": self._space_paused,
                "wifiOnly": self._wifi_only, "priority": self._priority, "tasks": list(self._tasks.values()),
                "pendingDeletes": sorted(self._pending_deletes)}
        temporary = self.journal.with_suffix(".json.tmp")
        try:
            with temporary.open("w", encoding="utf-8") as output:
                json.dump(data, output, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.journal)
            self._last_save_at = now
        except OSError:
            raise DownloadError("无法保存下载任务，请检查磁盘空间和文件夹权限") from None

    def configure(self, wifi_only=True):
        with self._condition:
            self._wifi_only = bool(wifi_only)
            self._save()
            self._condition.notify_all()

    @property
    def wifi_only(self):
        with self._lock:
            return self._wifi_only

    @property
    def storage_paused(self):
        with self._lock:
            return self._space_paused

    @property
    def paused(self):
        with self._lock:
            return self._global_paused

    def available_bytes(self):
        try:
            return int(self.free_space())
        except (OSError, ValueError, TypeError):
            return -1

    def _space_ok(self):
        if self._space_paused:
            return False
        now = self.clock()
        if now - self._checked_space_at >= .5:
            self._checked_space_at = now
            available = self.available_bytes()
            if 0 <= available < PAUSE_BYTES:
                self._space_paused = True
                self._save()
        return not self._space_paused

    def _resume_space(self):
        if self._space_paused and self.available_bytes() < RESUME_BYTES:
            raise DownloadError("空间不足，请清理至至少 300 MB 可用空间后继续下载")
        if self._space_paused:
            self._space_paused = False
            self._checked_space_at = -1e30
        if not self._space_ok():
            raise DownloadError("存储保护已暂停下载，请清理至至少 300 MB 后继续")

    def _network_ok(self):
        if not self._wifi_only:
            return True
        try:
            return self.network_allowed() is True
        except Exception:
            return False

    def start(self):
        with self._condition:
            if self._closed:
                raise DownloadError("下载管理器已关闭")
            if self._thread is not None:
                return
            self._pool = ThreadPoolExecutor(max_workers=self.max_workers, thread_name_prefix="anime-download")
            self._thread = threading.Thread(target=self._schedule, name="anime-download-queue", daemon=True)
            self._thread.start()

    def _schedule(self):
        with self._condition:
            while not self._closed:
                if self.clock() - self._last_delete_retry >= 5:
                    self._last_delete_retry = self.clock()
                    for storage in list(self._pending_deletes):
                        self._purge(storage)
                if self._priority:
                    priority = self._tasks.get(self._priority)
                    if priority is None or priority.get("paused") or priority.get("error") or self._complete(priority):
                        self._priority = ""
                        self._save()
                if not self._global_paused and self._space_ok() and self._network_ok():
                    for key, task in list(self._tasks.items()):
                        if len(self._active) >= self.max_workers:
                            break
                        if key in self._active or task.get("paused") or task.get("error") or task.get("needsRepair") or self._complete(task):
                            continue
                        if self._priority and key != self._priority:
                            continue
                        generation = self._generations[key]
                        task["active"] = True
                        self._active[key] = generation
                        self._samples[key] = deque([(self.clock(), self._bytes(task))])
                        self._pool.submit(self._run, key, generation, task["storage"])
                self._condition.wait(.35)

    def enqueue(self, episode, quality):
        return self.enqueue_many([(episode, quality)])[0]

    def enqueue_many(self, items):
        """Validate the whole confirmation and commit one journal before dispatching any item."""
        prepared = []
        for episode, quality in items:
            if episode.get("localUri"):
                raise DownloadError("本地视频无需下载")
            prepared.append((download_key(episode, quality), deepcopy(episode), self._clean_quality(quality)))
        with self._condition:
            if self._closed:
                raise DownloadError("下载管理器已关闭")
            for key, _, _ in prepared:
                if self._tasks.get(key, {}).get("needsRepair"):
                    raise DownloadError("所选剧集有异常文件，请先检查或修复")
            before, generations = deepcopy(self._tasks), dict(self._generations)
            created = []
            try:
                for key, episode, quality in prepared:
                    existing = self._tasks.get(key)
                    if existing:
                        existing["quality"] = quality
                        existing["resolvedAt"] = self.wall_clock()
                        existing["error"] = ""
                        existing["errorKind"] = ""
                        continue
                    storage = key + "-" + uuid.uuid4().hex
                    kinds = ["video", "audio"] if candidates(quality, "audio") else ["video"]
                    task = {"key": key, "storage": storage, "episode": episode,
                            "quality": quality, "createdAt": self.wall_clock(),
                            "resolvedAt": self.wall_clock(), "paused": False, "active": False,
                            "error": "", "errorKind": "", "needsRepair": False,
                            "tracks": {kind: {"total": None, "complete": False, "sha256": "", "blocks": [],
                                               "etag": "", "lastModified": "", "repairRanges": []} for kind in kinds}}
                    (self.media_root / storage).mkdir()
                    created.append(storage)
                    self._tasks[key] = task
                    self._generations[key] = self._generations.get(key, 0) + 1
                self._save()
            except Exception:
                self._tasks, self._generations = before, generations
                for storage in created:
                    self._purge(storage)
                raise
            self._condition.notify_all()
            return [key for key, _, _ in prepared]

    @staticmethod
    def _clean_quality(quality):
        result = deepcopy(quality)
        result["headers"] = {k: v for k, v in media_headers(result).items() if k != "Accept-Encoding"}
        for kind in ("video", "audio"):
            urls = candidates(result, kind)
            result[kind + "Urls"] = urls
            result[kind + "Url"] = urls[0] if urls else ""
        return result

    def _task(self, key):
        task = self._tasks.get(key)
        if task is None:
            raise DownloadError("下载任务已移除")
        return task

    def _path(self, task, kind):
        return self.media_root / task["storage"] / (kind + ".m4s")

    def _complete(self, task):
        if task.get("needsRepair"):
            return False
        for kind, track in task["tracks"].items():
            if not track.get("complete") or not track.get("total"):
                return False
            try:
                if self._path(task, kind).stat().st_size != track["total"]:
                    task["needsRepair"] = True
                    task["error"] = "离线文件缺失或长度异常，请检查 / 修复"
                    return False
            except OSError:
                task["needsRepair"] = True
                task["error"] = "离线文件缺失，请检查 / 修复"
                return False
        return True

    def _bytes(self, task):
        result = 0
        for kind, track in task["tracks"].items():
            try:
                size = self._path(task, kind).stat().st_size
            except OSError:
                size = 0
            if track.get("total"):
                size = min(size, track["total"])
            ranges = track.get("repairRanges", [])
            if ranges and track.get("total"):
                size = max(0, track["total"] - sum(end - start + 1 for start, end in ranges))
                size += track.get("repairWritten", 0)
            result += size
        return result

    def snapshot(self):
        with self._lock:
            result = []
            network_ok = self._network_ok()
            for key, task in self._tasks.items():
                complete = self._complete(task)
                active = key in self._active and not task.get("paused") and not self._global_paused and not self._space_paused and network_ok
                if complete:
                    status, label = "completed", "已离线保存 · 可断网播放"
                elif task.get("needsRepair"):
                    status, label = "failed", "离线文件异常 · 可检查 / 修复"
                elif task.get("error"):
                    status, label = "failed", "下载失败 · 可重新解析重试"
                elif self._space_paused:
                    status, label = "paused", "空间不足 · 清理后点继续"
                elif task.get("paused") or self._global_paused:
                    status, label = "paused", "已暂停"
                elif not network_ok:
                    status, label = "waiting_network", "等待 Wi-Fi / 有线网络 · 当前网络未获允许"
                elif self._priority and key != self._priority:
                    status, label = "queued", "等待优先集下载"
                    active = False
                elif active:
                    status, label = "downloading", "正在下载"
                else:
                    status, label = "queued", "等待下载"
                downloaded = self._bytes(task)
                totals = [t.get("total") for t in task["tracks"].values()]
                total = sum(totals) if all(isinstance(n, int) and n > 0 for n in totals) else None
                samples = self._samples.get(key, [])
                speed = 0
                if active and len(samples) > 1:
                    elapsed = samples[-1][0] - samples[0][0]
                    if elapsed > 0:
                        speed = max(0, int((samples[-1][1] - samples[0][1]) / elapsed))
                percent = 100 if complete else min(99, int(downloaded * 100 / total)) if total else None
                result.append({"key": key, "episode": deepcopy(task["episode"]), "quality": deepcopy(task["quality"]),
                    "status": status, "label": label, "bytesDownloaded": downloaded, "totalBytes": total,
                    "speed": speed, "eta": math.ceil(max(0, total - downloaded) / speed) if total and speed > 0 else None,
                    "percent": percent, "complete": complete, "videoPath": str(self._path(task, "video")) if complete else "",
                    "audioPath": str(self._path(task, "audio")) if complete and "audio" in task["tracks"] else "",
                    "error": task.get("error", ""), "errorKind": task.get("errorKind", ""),
                    "paused": status == "paused", "priority": self._priority == key, "needsRepair": bool(task.get("needsRepair")),
                    "integrity": task.get("integrity", "尚未检查"), "storagePaused": self._space_paused})
            return result

    def get_complete(self, episodeid, profile=None):
        items = [item for item in self.snapshot() if item["episode"].get("id") == episodeid and item["complete"]]
        if not items:
            return None
        if profile:
            matching = [item for item in items if same_profile(item["quality"], profile)]
            if matching:
                items = matching
        return max(items, key=lambda item: int(item["quality"].get("qn", 0)))

    def pause(self, key):
        with self._condition:
            self._task(key)["paused"] = True
            if self._priority == key:
                self._priority = ""
            self._samples.pop(key, None)
            self._save()
            self._condition.notify_all()

    def resume(self, key):
        with self._condition:
            self._resume_space()
            task = self._task(key)
            if task.get("needsRepair"):
                raise DownloadError("文件异常，请使用修复功能")
            if self._global_paused:
                for other in self._tasks.values():
                    if other["key"] != key and not self._complete(other):
                        other["paused"] = True
                self._global_paused = False
            task["paused"] = False
            task["error"] = ""
            task["errorKind"] = ""
            self._save()
            self._condition.notify_all()

    def pause_all(self):
        with self._condition:
            self._global_paused = True
            self._samples.clear()
            self._save()
            self._condition.notify_all()

    def resume_all(self):
        with self._condition:
            self._resume_space()
            self._global_paused = False
            self._priority = ""
            for task in self._tasks.values():
                task["paused"] = False
                if not task.get("needsRepair"):
                    task["error"] = ""
                    task["errorKind"] = ""
            self._save()
            self._condition.notify_all()

    def prioritize(self, key):
        with self._condition:
            self.resume(key)
            self._priority = key
            self._save()
            self._condition.notify_all()

    def remove(self, key):
        with self._condition:
            task = self._task(key)
            storage = task["storage"]
            self._pending_deletes.add(storage)
            del self._tasks[key]
            self._generations[key] = self._generations.get(key, 0) + 1
            if self._priority == key:
                self._priority = ""
            self._samples.pop(key, None)
            self._save()
            self._condition.notify_all()
        self._purge(storage)

    @property
    def pending_delete_count(self):
        with self._lock:
            return len(self._pending_deletes)

    def _purge(self, storage):
        if not re.fullmatch(r"[a-f0-9]{64}-[a-f0-9]{32}", storage):
            return False
        with self._lock:
            # Never remove a current task, even if metadata was externally malformed.
            if any(task["storage"] == storage for task in self._tasks.values()):
                return False
        try:
            shutil.rmtree(self.media_root / storage)
        except FileNotFoundError:
            pass
        except OSError:
            return False  # Persisted tombstone retries after Windows releases the handle.
        with self._lock:
            if storage in self._pending_deletes:
                self._pending_deletes.discard(storage)
                try:
                    self._save()
                except DownloadError:
                    self._pending_deletes.add(storage)
                    return False
        return True

    def _check(self, key, generation):
        with self._lock:
            if self._closed or key not in self._tasks or self._generations.get(key) != generation:
                raise _Stopped()
            task = self._tasks[key]
            if self._global_paused or task.get("paused") or not self._space_ok() or not self._network_ok() or (self._priority and self._priority != key):
                raise _Stopped()
            return task

    def _run(self, key, generation, storage):
        try:
            task = self._check(key, generation)
            if self.resolver and self.wall_clock() - task.get("resolvedAt", 0) >= 600:
                if not self._refresh(key, generation):
                    return
            for kind in list(task["tracks"]):
                self._check(key, generation)
                if task["tracks"][kind].get("complete"):
                    continue
                try:
                    self._transfer(key, generation, kind)
                except _HTTPFailure as error:
                    if error.status not in (401, 403, 410) or not self.resolver:
                        raise
                    if not self._refresh(key, generation):
                        return
                    self._transfer(key, generation, kind)
            with self._condition:
                task = self._check(key, generation)
                task["needsRepair"] = False
                task["error"] = ""
                task["errorKind"] = ""
                task["integrity"] = "已建立本机校验记录"
                self._save()
        except _Stopped:
            pass
        except Exception as error:
            with self._condition:
                if key in self._tasks and self._generations.get(key) == generation:
                    if isinstance(error, OSError) and getattr(error, "errno", None) in (28, 112):
                        self._space_paused = True
                    else:
                        self._tasks[key]["error"] = str(error) if isinstance(error, DownloadError) else "下载未完成，请检查网络、磁盘或重新解析后重试"
                        self._tasks[key]["errorKind"] = "http" if isinstance(error, _HTTPFailure) else "transfer"
                    try:
                        self._save()
                    except DownloadError:
                        self._space_paused = True
        finally:
            with self._condition:
                self._active.pop(key, None)
                self._samples.pop(key, None)
                response = self._responses.pop((key, generation), None)
                exists = key in self._tasks and self._tasks[key]["storage"] == storage
                if exists:
                    self._tasks[key]["active"] = False
                self._condition.notify_all()
            if response:
                try:
                    response.close()
                except Exception:
                    pass
            if not exists:
                self._purge(storage)

    def _refresh(self, key, generation):
        task = self._check(key, generation)
        try:
            refreshed = self.resolver(deepcopy(task["episode"]), deepcopy(task["quality"]))
        except Exception:
            raise DownloadError("重新解析失败，请检查登录或网络后重试") from None
        if isinstance(refreshed, list):
            refreshed = next((q for q in refreshed if same_profile(q, task["quality"])), None)
        if not isinstance(refreshed, dict) or not same_profile(refreshed, task["quality"]):
            raise DownloadError("原画质暂时不可用，请重新选择")
        with self._condition:
            task = self._check(key, generation)
            new_key = download_key(task["episode"], refreshed)
            if new_key != key:
                episode = deepcopy(task["episode"])
                was_priority = self._priority == key
                self.remove(key)
                new_key = self.enqueue(episode, refreshed)
                if was_priority:
                    self._priority = new_key
                self._save()
                return False
            task["quality"] = self._clean_quality(refreshed)
            task["resolvedAt"] = self.wall_clock()
            self._save()
            return True

    def _transfer(self, key, generation, kind):
        task = self._check(key, generation)
        track = task["tracks"][kind]
        path = self._path(task, kind)
        path.parent.mkdir(exist_ok=True)
        failures = []
        for url in candidates(task["quality"], kind):
            self._check(key, generation)
            # A backup URL for another resource must never reuse this representation's bytes.
            if urllib.parse.urlsplit(url).path != urllib.parse.urlsplit(candidates(task["quality"], kind)[0]).path:
                continue
            try:
                while track.get("repairRanges"):
                    start, end = track["repairRanges"][0]
                    self._fetch_piece(key, generation, kind, url, start, end, repair=True)
                    with self._lock:
                        self._check(key, generation)
                        track["repairRanges"].pop(0)
                        track["repairWritten"] = 0
                        self._save()
                size = path.stat().st_size if path.exists() else 0
                if track.get("total") and size > track["total"]:
                    raise DownloadError("本地文件长度异常，请检查 / 修复")
                if not track.get("total") or size < track["total"]:
                    self._fetch_piece(key, generation, kind, url, size, None, repair=False)
                self._check(key, generation)
                size = path.stat().st_size
                if not track.get("total") or size != track["total"]:
                    raise DownloadError("媒体长度未完整接收，可继续下载")
                digest, blocks = self._hash_file(path, lambda: self._check(key, generation))
                with self._lock:
                    self._check(key, generation)
                    track.update(complete=True, sha256=digest, blocks=blocks, repairRanges=[], repairWritten=0)
                    self._save()
                return
            except _Stopped:
                raise
            except (DownloadError, OSError, urllib.error.URLError) as error:
                if isinstance(error, OSError) and getattr(error, "errno", None) in (28, 112):
                    raise
                failures.append(error)
                continue
        if not failures:
            raise DownloadError("没有可用的同一媒体资源地址，请重新解析")
        if all(isinstance(e, _HTTPFailure) for e in failures):
            raise failures[-1]
        last = failures[-1]
        if isinstance(last, DownloadError):
            raise last
        raise DownloadError("媒体连接未完成，可保留进度继续或重新解析重试") from None

    def _fetch_piece(self, key, generation, kind, url, start, end, repair):
        task = self._check(key, generation)
        track = task["tracks"][kind]
        headers = media_headers(task["quality"])
        headers["Range"] = f"bytes={start}-" + (str(end) if end is not None else "")
        if start > 0 and track.get("etag") and not track["etag"].startswith("W/"):
            headers["If-Range"] = track["etag"]
        elif start > 0 and track.get("lastModified"):
            headers["If-Range"] = track["lastModified"]
        request = urllib.request.Request(url, headers=headers)
        response = None
        try:
            try:
                response = self.opener(request, timeout=20)
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
                raise _HTTPFailure(status) from None
            with self._lock:
                self._check(key, generation)
                self._responses[(key, generation)] = response
            _url(response.geturl())
            status = getattr(response, "status", None) or response.getcode()
            if status not in (200, 206):
                raise _HTTPFailure(status)
            if "text/" in response.headers.get("Content-Type", "").lower() or "json" in response.headers.get("Content-Type", "").lower():
                raise DownloadError("服务器没有返回视频数据，请重新解析")
            encoding = response.headers.get("Content-Encoding", "identity").lower()
            if encoding not in ("", "identity"):
                raise DownloadError("媒体服务器返回了无法续传的压缩内容")
            length_header = response.headers.get("Content-Length")
            try:
                length = int(length_header) if length_header is not None else None
                if length is not None and length < 0:
                    raise ValueError()
            except ValueError:
                raise DownloadError("媒体服务器返回了无效长度") from None
            write_start = start
            replace_full = False
            if status == 206:
                match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+|\*)", response.headers.get("Content-Range", ""))
                if not match:
                    raise DownloadError("媒体续传响应缺少有效范围")
                begin, last = int(match[1]), int(match[2])
                total = int(match[3]) if match[3] != "*" else None
                expected = last - begin + 1
                if begin != start or last < begin or (end is not None and last != end) or (total is not None and last >= total):
                    raise DownloadError("媒体续传范围不一致，未拼接错误数据")
                if length is not None and length != expected:
                    raise DownloadError("媒体范围与长度不一致")
                if track.get("total") and total and track["total"] != total:
                    raise DownloadError("媒体文件长度已变化，请重新下载本集")
                previous_etag, next_etag = track.get("etag", ""), response.headers.get("ETag", "")
                if start and previous_etag and next_etag and previous_etag != next_etag:
                    raise DownloadError("媒体文件身份已变化，请重新下载本集")
                if total:
                    track["total"] = total
            else:
                # Range ignored or If-Range failed: use a separate full replacement, never append.
                write_start = 0
                expected = length
                replace_full = start > 0 or repair
                track["total"] = length
            track["etag"] = response.headers.get("ETag", "")
            track["lastModified"] = response.headers.get("Last-Modified", "")
            path = self._path(task, kind)
            target = path.with_suffix(".replacement") if replace_full else path
            mode = "r+b" if target.exists() and write_start else "w+b"
            received = 0
            with target.open(mode) as output:
                output.seek(write_start)
                while expected is None or received < expected:
                    self._check(key, generation)
                    data = response.read(READ_BLOCK if expected is None else min(READ_BLOCK, expected - received))
                    if not data:
                        break
                    with self._lock:
                        self._check(key, generation)
                        output.write(data)
                        output.flush()
                        received += len(data)
                        if repair:
                            track["repairWritten"] = min(received, end - start + 1)
                        samples = self._samples.get(key)
                        if samples is not None:
                            now = self.clock()
                            samples.append((now, self._bytes(task)))
                            while len(samples) > 2 and now - samples[0][0] > 5:
                                samples.popleft()
                        self._save(force=False)
                if expected is not None and received != expected:
                    raise DownloadError("媒体传输中断，已保留可继续的进度")
                output.flush()
                os.fsync(output.fileno())
            with self._lock:
                self._check(key, generation)
                if status == 200 and expected is None:
                    if received <= 0:
                        raise DownloadError("媒体文件为空")
                    track["total"] = received
                if replace_full:
                    os.replace(target, path)
                    # A complete replacement repaired every range in this track.
                    if repair:
                        track["repairRanges"] = [[start, end]]
                self._save()
        finally:
            if response is not None:
                response.close()
            with self._lock:
                self._responses.pop((key, generation), None)

    @staticmethod
    def _hash_file(path, check):
        whole = hashlib.sha256()
        blocks = []
        with path.open("rb") as source:
            while True:
                check()
                block = source.read(HASH_BLOCK)
                if not block:
                    break
                whole.update(block)
                blocks.append(hashlib.sha256(block).hexdigest())
        return whole.hexdigest(), blocks

    def inspect(self, key):
        with self._lock:
            task = self._task(key)
            if key in self._active or not all(t.get("complete") for t in task["tracks"].values()):
                raise DownloadError("请等待本集下载完成后再检查")
            generation = self._generations[key]
            task = deepcopy(task)
        def check():
            with self._lock:
                if self._closed or key not in self._tasks or self._generations.get(key) != generation:
                    raise DownloadError("检查已取消，下载任务已改变")
        reports = []
        updates = {}
        for kind, track in task["tracks"].items():
            check()
            path = self._path(task, kind)
            expected = track.get("total")
            try:
                actual = path.stat().st_size
            except OSError:
                actual = 0
            missing = actual < (expected or 1)
            changed = []
            baseline_created = not bool(track.get("blocks"))
            whole = hashlib.sha256()
            blocks = []
            if path.exists():
                with path.open("rb") as source:
                    offset = 0
                    while True:
                        check()
                        block = source.read(HASH_BLOCK)
                        if not block:
                            break
                        digest = hashlib.sha256(block).hexdigest()
                        blocks.append(digest)
                        whole.update(block)
                        previous = track.get("blocks", [])
                        index = offset // HASH_BLOCK
                        if previous and (index >= len(previous) or digest != previous[index]):
                            changed.append([offset, min(offset + HASH_BLOCK, expected or actual) - 1])
                        offset += len(block)
            if missing and expected:
                changed.append([actual, expected - 1])
            # Merge overlapping tail/hash ranges and discard the extra bytes beyond expected.
            merged = []
            for begin, end in sorted(changed):
                if not expected or begin >= expected or begin > end:
                    continue
                end = min(end, expected - 1)
                if merged and begin <= merged[-1][1] + 1:
                    merged[-1][1] = max(end, merged[-1][1])
                else:
                    merged.append([begin, end])
            ok = bool(expected and expected == actual and not merged)
            if ok and track.get("sha256") and whole.hexdigest() != track["sha256"]:
                ok = False
                merged = [[0, expected - 1]]
            report = {"kind": kind, "ok": ok, "missing": missing, "changedRanges": merged,
                      "expectedBytes": expected, "actualBytes": actual, "baselineCreated": baseline_created and ok,
                      "message": "文件完整，已建立本机校验记录" if ok and baseline_created else "文件完整，与本机记录一致" if ok else "发现文件缺失、长度异常或内容变化"}
            reports.append(report)
            if ok:
                updates[kind] = {"sha256": whole.hexdigest(), "blocks": blocks}
        check()
        ok = all(report["ok"] for report in reports)
        with self._lock:
            check()
            current = self._tasks[key]
            for kind, values in updates.items():
                current["tracks"][kind].update(values)
            current["needsRepair"] = not ok
            current["integrity"] = "文件完整，与本机记录一致" if ok else "文件异常，需修复"
            current["error"] = "" if ok else "文件检查发现异常，请修复后再离线播放"
            self._save()
        return {"key": key, "ok": ok, "baselineCreated": any(r["baselineCreated"] for r in reports),
                "message": "\n".join(("画面：" if r["kind"] == "video" else "声音：") + r["message"] for r in reports), "tracks": reports}

    def repair(self, key, quality=None):
        report = self.inspect(key)
        with self._condition:
            task = self._task(key)
            if quality is not None:
                if not same_profile(task["quality"], quality):
                    raise DownloadError("修复需要原来的清晰度和编码")
                if download_key(task["episode"], quality) != key:
                    episode = deepcopy(task["episode"])
                    self.remove(key)
                    return self.enqueue(episode, quality)
                task["quality"] = self._clean_quality(quality)
                task["resolvedAt"] = self.wall_clock()
            if report["ok"]:
                return key
            for result in report["tracks"]:
                if result["ok"]:
                    continue
                kind = result["kind"]
                track = task["tracks"][kind]
                if not track.get("total"):
                    raise DownloadError("无法确定原文件大小，请删除此版本并重新下载")
                path = self._path(task, kind)
                if path.exists() and path.stat().st_size > track["total"]:
                    with path.open("r+b") as output:
                        output.truncate(track["total"])
                track["repairRanges"] = result["changedRanges"]
                track["repairWritten"] = 0
                track["complete"] = False
            task["needsRepair"] = False
            task["paused"] = False
            task["error"] = ""
            task["errorKind"] = ""
            task["integrity"] = "等待修复完成"
            self._generations[key] += 1
            self._save()
            self._condition.notify_all()
            return key

    def close(self):
        with self._condition:
            if self._closed:
                return
            self._closed = True
            responses = list(self._responses.values())
            self._save()
            self._condition.notify_all()
        for response in responses:
            try:
                response.close()
            except Exception:
                pass
        if self._thread:
            self._thread.join(timeout=3)
        if self._pool:
            self._pool.shutdown(wait=False, cancel_futures=True)
