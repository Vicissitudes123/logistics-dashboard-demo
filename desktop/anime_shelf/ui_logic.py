"""Small platform-neutral policies used by the desktop UI and its regression tests."""
from __future__ import annotations
import math
import re
import unicodedata
from typing import Iterable

THEMES = {
    "blue": ("蓝色", "#8db5ff"), "purple": ("紫色", "#b19aff"),
    "pink": ("粉色", "#f49dc1"), "green": ("绿色", "#83d7b5"),
    "orange": ("橙色", "#efb677"), "teal": ("青色", "#78d6df"),
}


def normalized(value: str) -> str:
    return unicodedata.normalize("NFKC", str(value)).casefold()


def matching_title(title: str, query: str) -> bool:
    return all(part in normalized(title) for part in normalized(query).split())


def ordered_episodes(series: dict) -> list[dict]:
    return sorted(series.get("episodes", []), key=lambda e: (int(e.get("index", 0)), e.get("id", "")))


def profile_of(quality: dict | None) -> dict | None:
    if not quality:
        return None
    if "codec" in quality:
        return {"qn": int(quality.get("qn", 0)), "codec": str(quality["codec"])}
    parts = str(quality.get("id", "")).split(":")
    return {"qn": int(quality.get("qn", 0)), "codec": parts[1] if len(parts) >= 2 else "progressive"}


def matching_quality(qualities: Iterable[dict], preferred: dict | None) -> dict | None:
    profile = profile_of(preferred)
    return next((q for q in qualities if profile_of(q) == profile), None) if profile else None


def watched(series: dict, progress) -> bool:
    override = series.get("watched", "auto")
    if override != "auto":
        return override == "yes"
    episodes = series.get("episodes", [])
    return bool(episodes) and all(progress(e["id"]).get("completed", False) for e in episodes)


def library_groups(series: list[dict], progress, last_series: str, query: str = "", mode: str = "all", offline_ids=()) -> list[tuple[str, list[dict]]]:
    offline_ids = set(offline_ids)
    visible = [s for s in series if not s.get("hidden") and matching_title(s.get("title", ""), query)]
    if mode == "watched": visible = [s for s in visible if watched(s, progress)]
    elif mode == "unfinished": visible = [s for s in visible if not watched(s, progress)]
    elif mode == "offline": visible = [s for s in visible if any(e.get("id") in offline_ids for e in s.get("episodes", []))]
    latest = lambda s: max((progress(e["id"]).get("updatedAt", 0) for e in s.get("episodes", [])), default=0)
    recent = next((s for s in visible if s.get("id") == last_series and latest(s)), None)
    if not recent:
        played = [s for s in visible if latest(s)]
        recent = max(played, key=latest) if played else None
    remaining = [s for s in visible if s is not recent]
    pinned = sorted((s for s in remaining if s.get("pinned")), key=lambda s: normalized(s.get("title", "")))
    others = sorted((s for s in remaining if not s.get("pinned")), key=lambda s: normalized(s.get("title", "")))
    return [(title, items) for title, items in [("最近观看", [recent] if recent else []), ("置顶番剧", pinned), ("其他番剧", others)] if items]


def format_time(milliseconds: int) -> str:
    seconds = max(0, int(milliseconds or 0)) // 1000
    if seconds >= 3600: return f"{seconds // 3600}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


def size_text(value: int | float | None) -> str:
    if value is None or value < 0: return "大小未知"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1000 or unit == "TB": return f"{value:.0f} {unit}" if unit in ("B", "KB") else f"{value:.1f} {unit}"
        value /= 1000
    return "大小未知"


def estimate_bytes(quality: dict) -> tuple[int, bool]:
    video = max(0, int(quality.get("videoBytes", 0)))
    audio = max(0, int(quality.get("audioBytes", 0)))
    has_audio = bool(quality.get("audioUrl"))
    if video and (not has_audio or audio): return video + audio, True
    duration = int(quality.get("durationMs", 0)); video_rate = int(quality.get("bandwidth", 0)); audio_rate = int(quality.get("audioBandwidth", 0))
    if duration <= 0 or video_rate <= 0 or (has_audio and audio_rate <= 0): return -1, False
    return math.ceil((video_rate + (audio_rate if has_audio else 0)) * duration / 8000), False


def aggregate_progress(items: list[dict]) -> tuple[int, int, int | None]:
    downloaded = sum(max(0, int(x.get("bytesDownloaded", 0))) for x in items)
    known = all(int(x.get("totalBytes", -1)) > 0 or x.get("complete") for x in items)
    if not items: return 0, 0, 0
    if not known: return downloaded, -1, None
    total = sum(int(x.get("totalBytes", 0)) if int(x.get("totalBytes", -1)) > 0 else max(0, int(x.get("bytesDownloaded", 0))) for x in items)
    percent = 100 if all(x.get("complete") for x in items) else min(99, round(downloaded * 100 / total)) if total else 0
    return downloaded, total, percent


def safe_error(error: Exception | str) -> str:
    text = str(error) or "操作未完成，请稍后重试。"
    text = re.sub(r"https?://\S+", "[媒体地址已隐藏]", text)
    text = re.sub(r"(?i)(SESSDATA|bili_jct|cookie|token|access[_-]?key)[=:]\s*[^\s;,]+", r"\1=[已隐藏]", text)
    return text[:600]
