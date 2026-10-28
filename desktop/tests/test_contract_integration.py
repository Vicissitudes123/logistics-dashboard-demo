"""Cross-module contracts exercised without Qt, a JVM, or live network access."""
from __future__ import annotations

import io
import json
from pathlib import Path
import tempfile
import unittest

from anime_shelf.backup import THEMES as PORTABLE_THEMES
from anime_shelf.downloads import DownloadManager
from anime_shelf.player_policy import source_plan
from anime_shelf.store import Store
from anime_shelf.ui_logic import THEMES as UI_THEMES, ordered_episodes


class MediaResponse(io.BytesIO):
    def __init__(self, data, url):
        super().__init__(data)
        self.status = 206
        self.headers = {'Content-Length': str(len(data)), 'Content-Range': f'bytes 0-{len(data)-1}/{len(data)}',
                        'Content-Type': 'application/octet-stream', 'ETag': '"fixture"'}
        self.url = url
    def geturl(self):
        return self.url


class ContractIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = Store(self.root / 'library.json')
        self.episodes = [dict(id=f'ep{n}', title=f'第 {n} 集', index=n, seriesId='ss1', seriesTitle='高木',
                              aid=100+n, cid=200+n, bvid='', bangumi=True, localUri='') for n in (1, 2, 10)]
        self.store.save_series({'id': 'ss1', 'title': '高木', 'episodes': self.episodes})
        self.requests = []
        def opener(request, timeout):
            self.requests.append(request)
            return MediaResponse(b'picture-fixture' if '/video/' in request.full_url else b'audio-fixture', request.full_url)
        self.manager = DownloadManager(self.root / 'offline', opener=opener, network_allowed=lambda: True,
                                       free_space=lambda: 2_000_000_000)
    def tearDown(self):
        self.manager.close()
        self.temp.cleanup()
    @staticmethod
    def quality(qn=80, codec='12'):
        return {'id': f'{qn}:{codec}:30280', 'qn': qn, 'label': '测试画质',
                'videoUrl': f'https://cdn.example/video/{qn}.m4s?token=expires',
                'audioUrl': 'https://cdn.example/audio/30280.m4s?token=expires',
                'headers': {'Cookie': 'SESSDATA=must-not-send', 'Authorization': 'must-not-send'}}
    def complete(self, qn=80, episode=None):
        episode = episode or self.episodes[0]
        key = self.manager.enqueue(episode, self.quality(qn))
        task = self.manager._tasks[key]
        self.manager._run(key, self.manager._generations[key], task['storage'])
        self.assertTrue(self.manager.get_complete(episode['id'])['complete'])
        return key
    def test_real_store_profile_selects_matching_offline_representation(self):
        self.complete(80)
        self.complete(125)
        self.store.set_playback_preference('ss1', True, self.quality(80))
        stored = self.store.playback_preference('ss1')['profile']
        self.assertEqual(stored, {'qn': 80, 'codec': '12'})
        selected = self.manager.get_complete('ep1', stored)
        self.assertEqual(selected['quality']['qn'], 80)
    def test_completed_pair_is_local_after_restart_and_requests_never_send_session(self):
        self.complete()
        for request in self.requests:
            self.assertIsNone(request.get_header('Cookie'))
            self.assertIsNone(request.get_header('Authorization'))
        calls = len(self.requests)
        reopened = DownloadManager(self.root / 'offline', opener=lambda *_a, **_k: self.fail('offline started network'))
        try:
            task = reopened.get_complete('ep1')
            plan = source_plan(self.episodes[0], task['quality'], task)
            self.assertTrue(plan.offline)
            self.assertEqual(plan.headers, {})
            self.assertEqual(len(plan.videos), 1)
            self.assertEqual(len(plan.audios), 1)
            self.assertTrue(Path(plan.videos[0]).is_file())
            self.assertTrue(Path(plan.audios[0]).is_file())
            self.assertEqual(len(self.requests), calls)
        finally:
            reopened.close()
    def test_missing_audio_never_qualifies_for_offline_playback(self):
        self.complete()
        task = self.manager.get_complete('ep1')
        Path(task['audioPath']).unlink()
        self.assertIsNone(self.manager.get_complete('ep1'))
        snapshot = self.manager.snapshot()[0]
        self.assertTrue(snapshot['needsRepair'])
        self.assertFalse(snapshot['complete'])
        self.assertEqual(snapshot['videoPath'], '')
    def test_offline_progress_round_trip_keeps_newer_local_progress_and_no_media_urls(self):
        self.complete()
        self.store.save_progress(self.episodes[0], 13000, 30000)
        self.store.set_playback_preference('ss1', True, self.quality())
        self.store.settings['cookie'] = 'must-not-export'
        backup = self.store.export_backup()
        for secret in ('cdn.example', 'token=expires', 'SESSDATA', 'must-not-export', 'localUri', 'videoPath'):
            self.assertNotIn(secret, backup)
        document = json.loads(backup)
        document['progress'][0]['updatedAt'] -= 10000
        document['progress'][0]['positionMs'] = 1000
        destination = Store(self.root / 'restored.json')
        destination.import_backup(backup)
        self.assertEqual(destination.last_episode('ss1'), 'ep1')
        self.assertEqual(destination.progress('ep1')['positionMs'], 13000)
        destination.import_backup(json.dumps(document))
        self.assertEqual(destination.progress('ep1')['positionMs'], 13000)
        self.assertTrue(destination.playback_preference('ss1')['offline'])
        self.assertEqual(len(destination.series()[0]['episodes']), 3)
    def test_every_ui_theme_exports_as_mobile_v4_backup(self):
        self.assertEqual(set(UI_THEMES), set(PORTABLE_THEMES))
        for theme in UI_THEMES:
            self.store.settings['theme'] = theme
            document = json.loads(self.store.export_backup())
            self.assertEqual(document['settings']['theme'], theme)
    def test_episode_order_is_numeric_after_backup_merge(self):
        backup = json.loads(self.store.export_backup())
        backup['series'][0]['episodes'].reverse()
        destination = Store(self.root / 'restored.json')
        destination.import_backup(json.dumps(backup))
        self.assertEqual([e['index'] for e in ordered_episodes(destination.series()[0])], [1, 2, 10])


if __name__ == '__main__':
    unittest.main()
