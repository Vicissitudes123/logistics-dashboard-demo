"""Manual update discovery for the Windows distribution; never installs software."""
from __future__ import annotations
import json
import re
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = 'Vicissitudes123/logistics-dashboard-demo'
API_URL = 'https://api.github.com/repos/' + REPOSITORY + '/releases?per_page=30'
TAG_PREFIX = 'anime-shelf-desktop-v'
_RELEASE_PREFIX = 'https://github.com/' + REPOSITORY + '/releases/'
_VERSION = re.compile(r'^[0-9]+(?:\.[0-9]+){2,3}$')
_FIELDS = {'schemaVersion', 'packageName', 'versionName', 'platform', 'zipUrl', 'zipSha256', 'zipBytes', 'notes', 'sourceCommit'}


class UpdateError(Exception):
    pass


def version_tuple(value: str) -> tuple:
    if not isinstance(value, str) or not _VERSION.fullmatch(value) or len(value) > 40:
        raise UpdateError('更新版本号格式无效')
    return tuple(int(x) for x in value.split('.')) + (0,) * (4 - len(value.split('.')))


def _safe_text(value, maximum=1000):
    return isinstance(value, str) and 0 < len(value) <= maximum and all(ord(c) >= 32 and c not in '\u2028\u2029\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069' for c in value)


def validate_manifest(data, version, current):
    if not isinstance(data, dict) or set(data) != _FIELDS:
        raise UpdateError('更新信息字段不完整或含未知字段')
    if type(data['schemaVersion']) is not int or data['schemaVersion'] != 1 or data['packageName'] != 'com.animeshelf.desktop' or data['platform'] != 'windows-x64':
        raise UpdateError('更新信息不属于 Windows 桌面版')
    if data['versionName'] != version: raise UpdateError('更新版本与发布记录不一致')
    version_tuple(version)
    if not isinstance(data['sourceCommit'], str) or not re.fullmatch('[a-f0-9]{40}', data['sourceCommit']): raise UpdateError('更新来源无效')
    if not isinstance(data['zipSha256'], str) or not re.fullmatch('[a-fA-F0-9]{64}', data['zipSha256']): raise UpdateError('更新文件校验值无效')
    if type(data['zipBytes']) is not int or not 1 <= data['zipBytes'] <= 2 * 1024**3: raise UpdateError('更新文件大小无效')
    prefix = _RELEASE_PREFIX + 'download/' + TAG_PREFIX + version + '/'
    url = data['zipUrl']
    if not isinstance(url, str) or not url.startswith(prefix) or not re.fullmatch(r'[A-Za-z0-9._-]+\.zip', url[len(prefix):]):
        raise UpdateError('更新文件不在本项目的固定发布目录')
    notes = data['notes']
    if not isinstance(notes, list) or len(notes) > 32 or any(not _safe_text(n) for n in notes) or sum(map(len, notes)) > 12000:
        raise UpdateError('更新说明格式无效')
    return {'available': version_tuple(version) > version_tuple(current), 'version': version, 'notes': notes,
            'zipUrl': url, 'zipSha256': data['zipSha256'].lower(), 'zipBytes': data['zipBytes'],
            'releaseUrl': _RELEASE_PREFIX + 'tag/' + TAG_PREFIX + version}


class _PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        uri = urllib.parse.urlsplit(newurl)
        if uri.scheme != 'https' or uri.username or uri.password or uri.port not in (None, 443) or uri.hostname not in ('api.github.com', 'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'):
            raise UpdateError('更新服务器跳转地址无效')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _json(url, limit=1024*1024):
    try:
        # A fresh opener has no CookieProcessor; no B站 credentials enter these requests.
        opener = urllib.request.build_opener(_PublicRedirect())
        request = urllib.request.Request(url, headers={'User-Agent': 'AnimeShelf-Desktop-Update/1', 'Accept': 'application/json', 'Cache-Control': 'no-cache'})
        with opener.open(request, timeout=10) as response:
            raw = response.read(limit + 1)
        if len(raw) > limit: raise UpdateError('更新信息超过大小限制')
        def pairs(values):
            result = {}
            for key, value in values:
                if key in result: raise ValueError('duplicate')
                result[key] = value
            return result
        return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs)
    except UpdateError: raise
    except (OSError, ValueError, urllib.error.URLError): raise UpdateError('无法读取更新信息，请检查网络后重试') from None


def check_update(current_version='0.1.13.1', *, fetch=None):
    version_tuple(current_version)
    fetch = fetch or _json
    releases = fetch(API_URL)
    if not isinstance(releases, list): raise UpdateError('发布记录格式无效')
    candidates = []
    for release in releases:
        if not isinstance(release, dict) or release.get('draft') or release.get('prerelease'): continue
        tag = release.get('tag_name', '')
        if not isinstance(tag, str) or not tag.startswith(TAG_PREFIX): continue
        version = tag[len(TAG_PREFIX):]
        try: ordering = version_tuple(version)
        except UpdateError: continue
        candidates.append((ordering, version, release))
    if not candidates:
        return {'available': False, 'version': current_version, 'notes': ['当前没有更新的桌面版发布记录'], 'zipUrl': '', 'zipSha256': '', 'zipBytes': 0, 'releaseUrl': 'https://github.com/' + REPOSITORY + '/releases'}
    _, version, release = max(candidates, key=lambda x: x[0])
    expected = _RELEASE_PREFIX + 'download/' + TAG_PREFIX + version + '/desktop-release.json'
    assets = release.get('assets', [])
    if not isinstance(assets, list) or not any(isinstance(x, dict) and x.get('name') == 'desktop-release.json' and x.get('browser_download_url') == expected for x in assets):
        raise UpdateError('此发布缺少桌面版校验清单')
    return validate_manifest(fetch(expected), version, current_version)
