"""Platform-independent playback source and gesture policies.

No account cookies ever cross this module's media boundary.  An offline plan
contains file paths only, so a missing track cannot silently turn into a stream.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit, unquote
import math
import os
import re

MEDIA_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
            '(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36')
REFERER = 'https://www.bilibili.com/'
BRIGHTNESS, VOLUME, SEEK, HOLD, DOUBLE_TAP, ALL = 1, 2, 4, 8, 16, 31


class PlaybackSourceError(ValueError):
    pass


def media_url(value: object) -> str:
    if not isinstance(value, str) or not value or any(ord(c) <= 32 or ord(c) == 127 for c in value):
        return ''
    if value.startswith('//'):
        value = 'https:' + value
    try:
        parsed = urlsplit(value)
        if parsed.scheme.lower() != 'https' or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.fragment:
            return ''
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            return ''
    except ValueError:
        return ''
    return value


def candidates(primary: object, alternatives: object) -> tuple[str, ...]:
    values: list[str] = []
    for value in [primary, *(alternatives if isinstance(alternatives, (list, tuple)) else [])]:
        checked = media_url(value)
        if checked and checked not in values:
            values.append(checked)
        if len(values) >= 5:
            break
    return tuple(values)


def public_headers(quality: dict) -> dict[str, str]:
    # Use the same fixed request identity as the verified Android implementation.
    # In particular, do not accept Cookie/Authorization from serialized qualities.
    return {'User-Agent': MEDIA_UA, 'Referer': REFERER}


def local_path(value: object) -> str:
    if not isinstance(value, (str, Path)) or not value:
        raise PlaybackSourceError('本地视频文件不存在或授权已失效，请重新导入。')
    value = str(value)
    if value.startswith('file:'):
        parsed = urlsplit(value)
        if parsed.netloc not in ('', 'localhost'):
            raise PlaybackSourceError('不支持远程文件共享地址，请先保存到本机。')
        value = unquote(parsed.path)
        if re.match(r'^/[A-Za-z]:/', value):
            value = value[1:]
    elif '://' in value or value.startswith(('content:', 'http:', 'https:')):
        raise PlaybackSourceError('离线播放需要本机文件，不能使用网络地址。')
    if value.startswith(('\\\\', '//')):
        raise PlaybackSourceError('离线播放不能读取网络共享文件，请先保存到本机。')
    path = Path(value).expanduser()
    if os.name == 'nt':
        import ctypes
        # Check the drive before stat/open: a mapped network drive looks local to
        # pathlib but would violate a deliberately offline playback request.
        try:
            get_type = ctypes.WinDLL('kernel32', use_last_error=True).GetDriveTypeW
            get_type.argtypes = [ctypes.c_wchar_p]
            get_type.restype = ctypes.c_uint
            if get_type(str(path.absolute().anchor)) == 4:  # DRIVE_REMOTE
                raise PlaybackSourceError('离线播放不能读取映射网络盘，请先保存到本机。')
        except PlaybackSourceError:
            raise
        except Exception:
            raise PlaybackSourceError('无法确认本地文件所在磁盘，请重新选择本机文件。') from None
    if not path.is_file() or path.stat().st_size <= 0:
        raise PlaybackSourceError('离线文件缺失或为空，请检查文件或重新下载。')
    if path.suffix.lower() in ('.m3u', '.m3u8', '.pls', '.cue', '.strm', '.url', '.asx', '.xspf', '.ffconcat'):
        raise PlaybackSourceError('请导入实际视频文件，不支持通过离线播放打开网络播放列表。')
    with path.open('rb') as handle:
        prefix = handle.read(4096).lstrip(b'\xef\xbb\xbf \r\n\t').lower()
    if prefix.startswith((b'#extm3u', b'[playlist]', b'ffconcat version', b'http://', b'https://', b'<asx')) or (prefix.startswith(b'<?xml') and (b'<asx' in prefix or b'<playlist' in prefix)):
        raise PlaybackSourceError('该文件是播放列表，请改为导入实际视频文件。')
    return str(path.resolve())


@dataclass(frozen=True)
class SourcePlan:
    videos: tuple[str, ...]
    audios: tuple[str, ...]
    offline: bool
    headers: dict[str, str]
    identity: str


def source_plan(episode: dict, quality: dict | None, offline_task: dict | None = None) -> SourcePlan:
    quality = quality or {}
    identity = str(episode.get('id', '')) + ':' + str(quality.get('id', ''))
    if offline_task is not None:
        if not offline_task.get('complete'):
            raise PlaybackSourceError('画面和声音尚未完整下载，暂时不能离线播放。')
        video = local_path(offline_task.get('videoPath'))
        audio = offline_task.get('audioPath')
        saved_quality = offline_task.get('quality') or quality
        needs_audio = bool(saved_quality.get('audioUrl') or saved_quality.get('audioUrls'))
        if needs_audio and not audio:
            raise PlaybackSourceError('离线音轨缺失，请修复后再播放。')
        return SourcePlan((video,), (local_path(audio),) if audio else (), True, {}, identity)
    if episode.get('localUri') or episode.get('localPath'):
        return SourcePlan((local_path(episode.get('localPath') or episode.get('localUri')),), (), True, {}, identity)
    videos = candidates(quality.get('videoUrl'), quality.get('videoUrls'))
    audios = candidates(quality.get('audioUrl'), quality.get('audioUrls'))
    if not videos:
        raise PlaybackSourceError('没有可用播放地址，请重新选择清晰度。')
    if (quality.get('audioUrl') or quality.get('audioUrls')) and not audios:
        raise PlaybackSourceError('没有可用音轨地址，请重新解析这一集。')
    return SourcePlan(videos, audios, False, public_headers(quality), identity)


def bounded_number(value: object, fallback: float, low: float, high: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return fallback
    return min(high, max(low, number)) if math.isfinite(number) else fallback


def settings_values(settings: dict) -> dict:
    mask = settings.get('gestureMask', ALL)
    if type(mask) is not int or not 0 <= mask <= ALL:
        mask = ALL
    seconds = settings.get('seekSeconds', 180)
    if seconds not in (90, 180, 300):
        seconds = 180
    hold = settings.get('holdSpeed', 2)
    if hold not in (1.5, 2, 2.5, 3):
        hold = 2
    timeout = settings.get('controlsTimeoutMs', 3500)
    if timeout not in (0, 3500, 5000, 8000):
        timeout = 3500
    return dict(gestureMask=mask, seekSeconds=seconds, holdSpeed=float(hold),
                controlsTimeoutMs=timeout, speed=bounded_number(settings.get('speed'), 1, .25, 3))


@dataclass
class Gesture:
    x: float
    y: float
    width: float
    height: float
    position: int
    duration: int
    volume: float
    brightness: float
    mask: int = ALL
    seek_seconds: int = 180
    mode: str = 'pending'
    target: int = 0
    active: bool = True

    def move(self, x: float, y: float) -> tuple[str, float]:
        if not self.active or not all(math.isfinite(v) for v in (x, y)):
            self.active = False
            return 'ignored', 0
        dx, dy = x - self.x, y - self.y
        if self.mode == 'pending':
            if max(abs(dx), abs(dy)) < 10:
                return 'pending', 0
            bit = SEEK if abs(dx) >= abs(dy) else BRIGHTNESS if self.x < self.width / 2 else VOLUME
            if not self.mask & bit:
                self.mode, self.active = 'ignored', False
                return 'ignored', 0
            self.mode = 'seek' if bit == SEEK else 'brightness' if bit == BRIGHTNESS else 'volume'
            if self.mode == 'seek' and self.duration <= 0:
                self.mode = 'ignored'
                return 'ignored', 0
        if self.mode == 'seek':
            span = min(self.duration, self.seek_seconds * 1000)
            self.target = round(min(self.duration, max(0, self.position + dx / max(1, self.width) * span)))
            return self.mode, self.target
        if self.mode == 'volume':
            return self.mode, min(100, max(0, self.volume - dy / max(1, self.height * .6) * 100))
        if self.mode == 'brightness':
            return self.mode, min(100, max(-50, self.brightness - dy / max(1, self.height * .6) * 100))
        return self.mode, 0

    def finish(self, cancelled: bool = False) -> int | None:
        target = self.target if self.active and self.mode == 'seek' and not cancelled else None
        self.active = False
        return target


def format_time(milliseconds: int) -> str:
    seconds = max(0, int(milliseconds)) // 1000
    if seconds >= 3600:
        return f'{seconds // 3600}:{seconds // 60 % 60:02d}:{seconds % 60:02d}'
    return f'{seconds // 60:02d}:{seconds % 60:02d}'


def safe_error(error: object) -> str:
    # Extract only error classes/status codes. Native errors may contain signed URLs.
    text = str(error)
    code = re.search(r'(?i)\b(?:http(?: error)?(?: status)?\s*[:=]?\s*)([45]\d\d)\b', text)
    if code:
        return f'媒体请求失败（HTTP {code.group(1)}）。可刷新清晰度地址后重试。'
    if isinstance(error, PlaybackSourceError):
        return str(error)
    return '播放未能继续，请检查网络、本机文件或重新选择清晰度。'


def media_proxy(source: str, offline: bool = False) -> str:
    """Use the same static OS/environment proxy source as urllib's API client.

    Windows PAC/WPAD discovery is not implemented by urllib and is deliberately
    not advertised as supported. Proxy credentials never appear in diagnostics.
    """
    if offline:
        return ''
    from urllib.request import getproxies, proxy_bypass
    parsed = urlsplit(source)
    if parsed.hostname and proxy_bypass(parsed.hostname):
        return ''
    proxies = getproxies()
    value = proxies.get('https') or proxies.get('http') or ''
    if not value:
        return ''
    if '://' not in value:
        value = 'http://' + value
    try:
        proxy = urlsplit(value)
        if proxy.scheme not in ('http', 'https') or not proxy.hostname or any(ord(ch) < 32 for ch in value):
            raise ValueError()
        if proxy.port is not None and not 1 <= proxy.port <= 65535:
            raise ValueError()
    except ValueError:
        raise PlaybackSourceError('系统代理类型暂不支持，请使用静态 HTTP/HTTPS 代理后重试。') from None
    return value
