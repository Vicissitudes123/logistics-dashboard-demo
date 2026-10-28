"""Durable device-local catalogue and portable Android-compatible backups."""
from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import threading
import time
import unicodedata
from .backup import DEFAULT_SETTINGS, DEFAULT_SKIP, BackupError, export_document, inspect_backup, profile, safe_cover_url

class Store:
    def __init__(self, path: Path):
        path = Path(path)
        self.path = path if path.suffix.lower() == '.json' else path / 'library.json'
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.data = {'schemaVersion': 1, 'series': [], 'progress': {}, 'settings': copy.deepcopy(DEFAULT_SETTINGS), 'lastSeries': ''}
        if self.path.exists():
            try:
                parsed = json.loads(self.path.read_text(encoding='utf-8'))
                if not isinstance(parsed, dict) or not isinstance(parsed.get('series'), list) or not isinstance(parsed.get('progress'), dict) or not isinstance(parsed.get('settings'), dict):
                    raise ValueError()
                self.data.update(parsed)
                self.data['settings'] = {**copy.deepcopy(DEFAULT_SETTINGS), **parsed['settings']}
            except (ValueError, UnicodeError, OSError):
                raise ValueError('本机片库无法读取，原文件已保留；请从备份恢复或检查磁盘。') from None

    @property
    def settings(self):
        return self.data['settings']

    def save(self):
        with self.lock:
            content = json.dumps(self.data, ensure_ascii=False, indent=2, allow_nan=False)
            temporary = self.path.with_name(self.path.name + '.tmp')
            try:
                with temporary.open('w', encoding='utf-8', newline='\n') as output:
                    output.write(content + '\n'); output.flush(); os.fsync(output.fileno())
                os.replace(temporary, self.path)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise

    def series(self):
        with self.lock:
            return self.data['series']

    def find_series(self, series_id):
        return next((s for s in self.series() if s['id'] == series_id), None)

    def episode(self, episode_id):
        return next((ep for s in self.series() for ep in s.get('episodes', []) if ep['id'] == episode_id), None)

    def save_series(self, series):
        """Add/refresh without removing old episodes, downloads or watch history."""
        if not isinstance(series, dict) or not series.get('id') or not series.get('title') or not isinstance(series.get('episodes'), list):
            raise ValueError('番剧信息不完整')
        with self.lock:
            incoming = copy.deepcopy(series)
            sid = incoming['id']; existing = self.find_series(sid)
            owners = {ep['id']: s['id'] for s in self.series() for ep in s.get('episodes', [])}
            for ep in incoming['episodes']:
                if not ep.get('id') or ep.get('index', 0) < 0:
                    raise ValueError('剧集信息不完整')
                if ep['id'] in owners and owners[ep['id']] != sid and not ep.get('localUri'):
                    raise ValueError('剧集归属与本机片库冲突')
                ep.update(seriesId=sid, seriesTitle=existing['title'] if existing else incoming['title'])
                for key, default in {'title': '', 'bvid': '', 'aid': 0, 'cid': 0, 'bangumi': False, 'localUri': ''}.items(): ep.setdefault(key, default)
            if existing:
                previous = {ep['id']: ep for ep in existing['episodes']}
                added = [ep for ep in incoming['episodes'] if ep['id'] not in previous]
                existing['episodes'].extend(added)
                if incoming.get('coverUrl'): existing['coverUrl'] = safe_cover_url(incoming['coverUrl'])
                existing['hidden'] = False
                existing['newEpisodes'] = existing.get('newEpisodes', 0) + len(added)
                target = existing
            else:
                target = incoming
                target.setdefault('pinned', False); target.setdefault('hidden', False); target.setdefault('watched', 'auto')
                target.setdefault('skip', copy.deepcopy(DEFAULT_SKIP)); target.setdefault('playback', None); target.setdefault('lastEpisodeId', '')
                target['coverUrl'] = safe_cover_url(target.get('coverUrl', ''))
                target['episodes'] = list({ep['id']: ep for ep in target['episodes']}.values())
                self.data['series'].append(target)
            target['episodes'].sort(key=lambda ep: (ep.get('index', 0), ep['id']))
            self.save()
            return target

    def progress(self, episode_id):
        with self.lock:
            return copy.deepcopy(self.data['progress'].get(episode_id,
                {'positionMs': 0, 'durationMs': 0, 'updatedAt': 0, 'completed': False}))

    def save_progress(self, episode, position_ms, duration_ms):
        if not episode or position_ms < 0:
            return
        with self.lock:
            old = self.progress(episode['id'])
            duration = int(duration_ms) if duration_ms > 0 else old['durationMs']
            position = min(int(position_ms), duration) if duration else int(position_ms)
            self.data['progress'][episode['id']] = dict(positionMs=position, durationMs=duration,
                updatedAt=int(time.time() * 1000), completed=position > 0 and duration > 0 and position >= duration - 1000)
            series = self.find_series(episode['seriesId'])
            if series and any(ep['id'] == episode['id'] for ep in series['episodes']):
                series['lastEpisodeId'] = episode['id']; self.data['lastSeries'] = series['id']
            self.save()

    def last_episode(self, series_id):
        series = self.find_series(series_id)
        return series.get('lastEpisodeId', '') if series else ''

    def _flag(self, series_id, key, value):
        with self.lock:
            series = self.find_series(series_id)
            if series is None: raise ValueError('番剧不存在')
            series[key] = value; self.save()

    def set_pinned(self, series_id, value): self._flag(series_id, 'pinned', bool(value))
    def hide_series(self, series_id, value=True): self._flag(series_id, 'hidden', bool(value))
    def restore_series(self, series_id): self.hide_series(series_id, False)
    def set_watched(self, series_id, value):
        if value not in ('auto', 'yes', 'no'): raise ValueError('看完标记无效')
        self._flag(series_id, 'watched', value)

    def is_watched(self, series):
        value = series.get('watched', 'auto')
        return value == 'yes' or (value == 'auto' and bool(series.get('episodes')) and all(self.progress(ep['id'])['completed'] for ep in series['episodes']))

    def set_playback_preference(self, series_id, offline, quality):
        safe = profile(quality)
        if safe is None: raise ValueError('播放画质格式无效')
        self._flag(series_id, 'playback', {'offline': bool(offline), 'profile': safe})
        if self.settings.get('rememberQuality', True):
            self.settings['preferredQuality'] = safe; self.save()

    def playback_preference(self, series_id):
        series = self.find_series(series_id)
        return copy.deepcopy(series.get('playback')) if series else None

    def visible_series(self, query=''):
        def normalize(text): return ' '.join(unicodedata.normalize('NFKC', str(text)).casefold().split())
        terms = normalize(query).split()
        values = [s for s in self.series() if not s.get('hidden') and all(t in normalize(s['title']) for t in terms)]
        recent = self.data.get('lastSeries')
        return sorted(values, key=lambda s: (0 if s['id'] == recent else 1 if s.get('pinned') else 2))

    def reset_settings(self):
        self.data['settings'] = copy.deepcopy(DEFAULT_SETTINGS); self.save()

    def export_backup(self):
        with self.lock: return export_document(self.data)

    @staticmethod
    def inspect_backup(text):
        return inspect_backup(text)

    def import_backup(self, text):
        snapshot = inspect_backup(text)
        incoming = snapshot['document']
        with self.lock:
            # No mutation of live memory or disk until every ownership check is complete.
            data = copy.deepcopy(self.data)
            by_series = {s['id']: s for s in data['series']}
            owners = {ep['id']: s['id'] for s in data['series'] for ep in s['episodes']}
            by_episode = {ep['id']: ep for s in data['series'] for ep in s['episodes']}
            eligible, flags = set(), {}
            counts = dict(addedSeries=0, mergedSeries=0, importedProgress=0, keptNewerProgress=0,
                          skippedLocalEpisodes=snapshot['skippedLocalEpisodes'])
            for item in incoming['series']:
                sid = item['id']; online = []
                for ep in item['episodes']:
                    if ep['local']: continue
                    if ep['id'] in owners and owners[ep['id']] != sid:
                        raise BackupError('备份剧集与本机番剧归属冲突，未导入任何数据')
                    if by_episode.get(ep['id'], {}).get('localUri'):
                        raise BackupError('备份在线视频与本地文件身份冲突，未导入任何数据')
                    online.append({k: v for k, v in ep.items() if k != 'local'})
                    eligible.add(ep['id'])
                if not online and any(ep['local'] for ep in item['episodes']): continue
                target = by_series.get(sid)
                if target is None:
                    target = dict(id=sid, title=item['title'], coverUrl=item['coverUrl'], episodes=[], lastEpisodeId='',
                                  pinned=False, hidden=False, watched='auto', skip=copy.deepcopy(DEFAULT_SKIP), playback=None)
                    by_series[sid] = target; data['series'].append(target); counts['addedSeries'] += 1
                else:
                    counts['mergedSeries'] += 1
                    if not target.get('coverUrl'): target['coverUrl'] = item['coverUrl']
                for ep in online:
                    if ep['id'] not in by_episode:
                        ep.update(seriesId=sid, seriesTitle=target['title'], localUri='')
                        target['episodes'].append(ep); by_episode[ep['id']] = ep; owners[ep['id']] = sid
                target['episodes'].sort(key=lambda e: (e['index'], e['id']))
                for field in ('pinned', 'hidden', 'watched', 'skip', 'playback'):
                    if field in item: target[field] = copy.deepcopy(item[field])
                flags[sid] = item
            for record in incoming['progress']:
                eid = record['episodeId']
                if eid not in eligible: continue
                if data['progress'].get(eid, {}).get('updatedAt', 0) >= record['updatedAt']:
                    counts['keptNewerProgress'] += 1; continue
                data['progress'][eid] = {k: v for k, v in record.items() if k != 'episodeId'}; counts['importedProgress'] += 1
            times = {}
            for series in data['series']:
                previous = series.get('lastEpisodeId', '')
                candidates = [previous, flags.get(series['id'], {}).get('lastEpisodeId', '')] + [e['id'] for e in series['episodes']]
                last, stamp = '', 0
                for eid in candidates:
                    t = data['progress'].get(eid, {}).get('updatedAt', 0) if owners.get(eid) == series['id'] else 0
                    if t > stamp: last, stamp = eid, t
                if stamp: series['lastEpisodeId'] = last; times[series['id']] = stamp
            recent = data.get('lastSeries', '')
            for sid in [incoming['lastSeries']] + list(times):
                if times.get(sid, 0) > times.get(recent, 0): recent = sid
            if times.get(recent, 0): data['lastSeries'] = recent
            data['settings'].update(copy.deepcopy(incoming['settings']))
            previous_data = self.data; self.data = data
            try: self.save()
            except BaseException: self.data = previous_data; raise
            counts['summary'] = f"新增 {counts['addedSeries']} 部，合并 {counts['mergedSeries']} 部；导入 {counts['importedProgress']} 条进度，保留 {counts['keptNewerProgress']} 条本机较新进度。"
            return counts
