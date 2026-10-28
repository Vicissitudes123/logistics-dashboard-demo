"""Strict portable metadata format shared with Anime Shelf Android v1–v4.

Never copy local settings wholesale into a portable backup. Login sessions,
media addresses, download jobs and local file grants belong to this device.
"""
from __future__ import annotations
import copy
import json
import math
import re
import time
from urllib.parse import urlsplit

FORMAT = 'anime-shelf-library'
VERSION = 4
MAX_BYTES = 4 * 1024 * 1024
MAX_EPISODES = 20000
MAX_DURATION = 7 * 24 * 60 * 60 * 1000
THEMES = ('blue', 'purple', 'teal', 'green', 'orange', 'pink')
DEFAULT_SETTINGS = dict(theme='blue', speed=1.0, holdSpeed=2.0, controlsTimeoutMs=3500,
    autoNext=False, rememberQuality=True, preferredQuality=None, wifiOnlyDownloads=True,
    libraryFilter='all', gestureMask=31, seekSeconds=180)
DEFAULT_SKIP = dict(enabled=False, introSeconds=0, outroSeconds=0)

class BackupError(ValueError):
    pass

def _require(condition, message='备份字段无效，未导入任何数据'):
    if not condition:
        raise BackupError(message)

def _keys(value, keys):
    _require(isinstance(value, dict) and set(value) == set(keys), '备份含缺失或不支持的字段')

def _string(value, limit, empty=True):
    _require(isinstance(value, str) and len(value.encode('utf-16-le')) // 2 <= limit
             and (empty or bool(value.strip())) and all(ord(c) >= 32 and ord(c) != 127 for c in value),
             '备份文字格式无效')
    return value

def _id(value, empty=False):
    _string(value, 128, empty)
    _require((empty and value == '') or re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', value))
    return value

def _int(value, minimum, maximum):
    _require(type(value) is int and minimum <= value <= maximum, '备份整数格式无效')
    return value

def _bool(value):
    _require(type(value) is bool, '备份开关格式无效')
    return value

def safe_cover_url(value):
    if not isinstance(value, str) or not value or len(value) > 2048:
        return ''
    try:
        u = urlsplit(value)
        if u.scheme.lower() != 'https' or not re.fullmatch(r'i[0-9]+\.hdslb\.com', u.hostname or '', re.I):
            return ''
        if u.username or u.password or u.query or u.fragment or u.port not in (None, 443):
            return ''
        if '?' in value or '#' in value or not re.fullmatch(r'/bfs/[a-z0-9/_-]+\.(jpg|jpeg|png|webp|avif)(@[a-z0-9_.-]+)?', u.path, re.I):
            return ''
        return 'https://' + u.hostname.lower() + u.path
    except (ValueError, UnicodeError):
        return ''

def profile(quality):
    """Safe qn/codec identity only; never retain a signed URL or audio ID."""
    if not isinstance(quality, dict):
        return None
    qn = quality.get('qn', 0)
    codec = quality.get('codec')
    if codec is None:
        parts = str(quality.get('id', '')).split(':')
        if len(parts) != 3 or not parts[2] or parts[0] != str(qn):
            return None
        codec = parts[1]
    if type(qn) is not int or not 1 <= qn <= 10000 or not isinstance(codec, str):
        return None
    if codec != 'progressive':
        if not re.fullmatch(r'[0-9]{1,4}', codec) or not 1 <= int(codec) <= 1000:
            return None
        codec = str(int(codec))
    return {'qn': qn, 'codec': codec}

def _profile(value):
    _keys(value, ('qn', 'codec'))
    _require(profile(value) is not None, '默认清晰度编码无效')

def _skip(value):
    _keys(value, DEFAULT_SKIP)
    _bool(value['enabled'])
    _int(value['introSeconds'], 0, 600)
    _int(value['outroSeconds'], 0, 600)

def _settings(value, version):
    allowed = set(DEFAULT_SETTINGS)
    if version < 4:
        allowed -= {'gestureMask', 'seekSeconds'}
    if version == 1:
        allowed -= {'wifiOnlyDownloads', 'libraryFilter'}
    _keys(value, allowed)
    _require(value['theme'] in THEMES)
    for key in ('speed', 'holdSpeed'):
        _require(type(value[key]) in (int, float) and math.isfinite(value[key]))
    _require(.25 <= value['speed'] <= 3 and value['holdSpeed'] in (1.5, 2, 2.5, 3))
    _int(value['controlsTimeoutMs'], 0, 8000)
    _require(value['controlsTimeoutMs'] in (0, 3500, 5000, 8000))
    _bool(value['autoNext']); _bool(value['rememberQuality'])
    if value['preferredQuality'] is not None:
        _profile(value['preferredQuality'])
    if version >= 2:
        _bool(value['wifiOnlyDownloads'])
        _require(value['libraryFilter'] in ('all', 'unfinished', 'watched', 'offline'))
    if version >= 4:
        _int(value['gestureMask'], 0, 31)
        _int(value['seekSeconds'], 90, 300)
        _require(value['seekSeconds'] in (90, 180, 300))

def _unique_object(pairs):
    out = {}
    for key, value in pairs:
        _require(key not in out, '备份含重复字段')
        out[key] = value
    return out

def inspect_backup(text):
    """Validate the entire document before returning any data eligible for import."""
    try:
        _require(isinstance(text, str) and 0 < len(text.encode('utf-8')) <= MAX_BYTES, '备份为空或超过 4 MiB 上限')
        depth, quoted, escaped = 0, False, False
        for c in text:
            if quoted:
                if escaped: escaped = False
                elif c == '\\': escaped = True
                elif c == '"': quoted = False
            elif c == '"': quoted = True
            elif c in '[{':
                depth += 1
                _require(depth <= 12, '备份结构嵌套过深')
            elif c in ']}':
                depth -= 1
                _require(depth >= 0)
        _require(not quoted and depth == 0)
        doc = json.loads(text, object_pairs_hook=_unique_object, parse_constant=lambda _: (_ for _ in ()).throw(BackupError('备份数字无效')))
        _keys(doc, ('format', 'version', 'exportedAt', 'lastSeries', 'series', 'progress', 'settings'))
        _require(doc['format'] == FORMAT, '不是番匣片库备份')
        version = _int(doc['version'], 1, VERSION)
        latest = int(time.time() * 1000) + 86400000
        _int(doc['exportedAt'], 0, latest); _id(doc['lastSeries'], True)
        _require(isinstance(doc['series'], list) and len(doc['series']) <= 1000)
        owners, locals_, ids, count, local_count = {}, set(), set(), 0, 0
        for series in doc['series']:
            fields = {'id', 'title', 'coverUrl', 'pinned', 'hidden', 'watched', 'lastEpisodeId', 'episodes'}
            if version >= 2: fields.add('skip')
            if version >= 3: fields.add('playback')
            _keys(series, fields)
            sid = _id(series['id']); _require(sid not in ids, '备份含重复番剧 ID'); ids.add(sid)
            _string(series['title'], 512, False)
            cover = _string(series['coverUrl'], 2048)
            _require(not cover or cover == safe_cover_url(cover), '封面地址不是允许的公共静态图片')
            _bool(series['pinned']); _bool(series['hidden']); _require(series['watched'] in ('auto', 'yes', 'no'))
            if version >= 2: _skip(series['skip'])
            if version >= 3 and series['playback'] is not None:
                _keys(series['playback'], ('offline', 'profile')); _bool(series['playback']['offline']); _profile(series['playback']['profile'])
            _id(series['lastEpisodeId'], True)
            _require(isinstance(series['episodes'], list))
            count += len(series['episodes']); _require(count <= MAX_EPISODES, '备份剧集数量超过上限')
            own = set()
            for ep in series['episodes']:
                _keys(ep, ('id', 'title', 'index', 'local', 'bvid', 'aid', 'cid', 'bangumi'))
                eid = _id(ep['id']); local = _bool(ep['local'])
                _require(eid not in owners or (local and eid in locals_), '备份含重复在线剧集 ID')
                owners[eid] = sid; own.add(eid)
                if local: locals_.add(eid); local_count += 1
                _require(not eid.startswith('local-') or local)
                _string(ep['title'], 1024); _int(ep['index'], 0, 100000)
                bvid = _string(ep['bvid'], 32); _require(not bvid or re.fullmatch(r'BV[A-Za-z0-9]{10}', bvid))
                _int(ep['aid'], 0, 9007199254740991); _int(ep['cid'], 0, 9007199254740991); _bool(ep['bangumi'])
                _require(not local or (not bvid and ep['aid'] == 0 and ep['cid'] == 0 and not ep['bangumi']))
            _require(not series['lastEpisodeId'] or series['lastEpisodeId'] in own)
        _require(not doc['lastSeries'] or doc['lastSeries'] in ids)
        _require(isinstance(doc['progress'], list) and len(doc['progress']) <= MAX_EPISODES)
        recorded = set()
        for p in doc['progress']:
            _keys(p, ('episodeId', 'positionMs', 'durationMs', 'updatedAt', 'completed'))
            eid = _id(p['episodeId']); _require(eid in owners and eid not in recorded); recorded.add(eid)
            pos = _int(p['positionMs'], 0, MAX_DURATION); dur = _int(p['durationMs'], 0, MAX_DURATION)
            _require(not dur or pos <= dur); _int(p['updatedAt'], 1, latest); _bool(p['completed'])
            _require(not p['completed'] or (pos > 0 and dur > 0 and pos >= dur - 1000))
        _settings(doc['settings'], version)
        return {'document': doc, 'seriesCount': len(ids), 'episodeCount': count, 'progressCount': len(recorded),
                'skippedLocalEpisodes': local_count, 'wifiOnlyDownloads': doc['settings'].get('wifiOnlyDownloads'),
                'summary': f'{len(ids)} 部番剧、{count} 集、{len(recorded)} 条观看记录；本地视频需重新导入。'}
    except BackupError:
        raise
    except (ValueError, TypeError, KeyError, OverflowError, UnicodeError, RecursionError):
        raise BackupError('备份格式损坏或字段不完整，未导入任何数据') from None

def export_document(data):
    items, progress_rows, recorded = [], [], set()
    for raw in data.get('series', []):
        episodes = []
        for ep in raw.get('episodes', []):
            local = bool(ep.get('localUri')) or str(ep['id']).startswith('local-')
            episodes.append(dict(id=ep['id'], title=ep.get('title', ''), index=ep.get('index', 0), local=local,
                bvid='' if local else ep.get('bvid', ''), aid=0 if local else ep.get('aid', 0),
                cid=0 if local else ep.get('cid', 0), bangumi=False if local else ep.get('bangumi', False)))
            p = data.get('progress', {}).get(ep['id'], {})
            if p.get('updatedAt', 0) > 0 and ep['id'] not in recorded:
                recorded.add(ep['id'])
                progress_rows.append(dict(episodeId=ep['id'], positionMs=p.get('positionMs', 0), durationMs=p.get('durationMs', 0),
                    updatedAt=p['updatedAt'], completed=bool(p.get('completed')) and p.get('positionMs', 0) > 0
                    and p.get('durationMs', 0) > 0 and p['positionMs'] >= p['durationMs'] - 1000))
        playback = raw.get('playback'); safe_playback = None
        if isinstance(playback, dict) and profile(playback.get('profile')):
            safe_playback = {'offline': bool(playback.get('offline')), 'profile': profile(playback['profile'])}
        skip = raw.get('skip', DEFAULT_SKIP)
        items.append(dict(id=raw['id'], title=raw['title'], coverUrl=safe_cover_url(raw.get('coverUrl', '')),
            pinned=bool(raw.get('pinned')), hidden=bool(raw.get('hidden')), watched=raw.get('watched', 'auto'),
            skip={k: skip.get(k, v) for k, v in DEFAULT_SKIP.items()}, playback=safe_playback,
            lastEpisodeId=raw.get('lastEpisodeId', '') if raw.get('lastEpisodeId', '') in {e['id'] for e in episodes} else '', episodes=episodes))
    settings = {k: copy.deepcopy(data.get('settings', {}).get(k, v)) for k, v in DEFAULT_SETTINGS.items()}
    settings['preferredQuality'] = profile(settings['preferredQuality'])
    doc = dict(format=FORMAT, version=VERSION, exportedAt=int(time.time() * 1000),
        lastSeries=data.get('lastSeries', '') if data.get('lastSeries') in {s['id'] for s in items} else '',
        series=items, progress=progress_rows, settings=settings)
    text = json.dumps(doc, ensure_ascii=False, separators=(',', ':'))
    inspect_backup(text)
    return text
