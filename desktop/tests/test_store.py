import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from anime_shelf.store import Store


def series(sid='ss1', episodes=('ep1', 'ep2')):
    return dict(id=sid, title='擅长捉弄的高木同学', coverUrl='https://i0.hdslb.com/bfs/bangumi/cover.jpg',
        episodes=[dict(id=eid, title='第 %s 集' % i, index=i, seriesId=sid, seriesTitle='擅长捉弄的高木同学',
                       bvid='', aid=i, cid=i, bangumi=True, localUri='') for i, eid in enumerate(episodes, 1)])

class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.store = Store(Path(self.temp.name) / 'library.json')

    def test_history_settings_and_flags_survive_restart_without_network(self):
        s = self.store.save_series(series()); self.store.save_progress(s['episodes'][1], 45000, 300000)
        self.store.set_pinned('ss1', True); self.store.settings['speed'] = 1.5; self.store.save()
        reopened = Store(self.store.path)
        self.assertEqual(45000, reopened.progress('ep2')['positionMs']); self.assertEqual('ep2', reopened.last_episode('ss1'))
        self.assertEqual('ss1', reopened.data['lastSeries']); self.assertEqual(1.5, reopened.settings['speed'])
        self.assertTrue(reopened.find_series('ss1')['pinned'])

    def test_refresh_keeps_missing_episodes_flags_and_progress(self):
        s = self.store.save_series(series()); self.store.save_progress(s['episodes'][0], 42000, 300000)
        self.store.set_pinned('ss1', True); self.store.hide_series('ss1'); self.store.set_watched('ss1', 'no')
        fresh = series(episodes=('ep2', 'ep3')); fresh['title'] = '远端新名称'
        self.store.save_series(fresh)
        current = self.store.find_series('ss1')
        self.assertEqual({'ep1', 'ep2', 'ep3'}, {e['id'] for e in current['episodes']})
        self.assertTrue(current['pinned']); self.assertFalse(current['hidden']); self.assertEqual('no', current['watched'])
        self.assertEqual('擅长捉弄的高木同学', current['title']); self.assertEqual(42000, self.store.progress('ep1')['positionMs'])

    def test_search_normalizes_fullwidth_case_and_words_without_duplicate_recent(self):
        self.store.save_series(series()); other = series('ss2', ('ep3',)); other['title'] = 'JOJO 不灭钻石'; self.store.save_series(other)
        self.store.set_pinned('ss1', True); self.store.save_progress(other['episodes'][0], 1000, 300000)
        self.assertEqual(['ss2', 'ss1'], [s['id'] for s in self.store.visible_series()])
        self.assertEqual(['ss2'], [s['id'] for s in self.store.visible_series('ｊｏｊｏ　钻石')])
        self.assertEqual(['ss1'], [s['id'] for s in self.store.visible_series('高木')])
        self.store.hide_series('ss2'); self.assertEqual([], self.store.visible_series('jojo'))

    def test_profile_persists_only_qn_and_codec_not_signed_media_urls(self):
        self.store.save_series(series())
        self.store.set_playback_preference('ss1', True, dict(qn=125, id='125:12:30280', videoUrl='https://private/token', headers={'Cookie': 'secret'}))
        preference = self.store.playback_preference('ss1')
        self.assertEqual({'offline': True, 'profile': {'qn': 125, 'codec': '12'}}, preference)
        self.assertNotIn('private', self.store.path.read_text())

    def test_progress_clamps_and_preserves_duration_when_player_reports_unknown(self):
        s = self.store.save_series(series()); ep = s['episodes'][0]
        self.store.save_progress(ep, 45000, 100000); self.store.save_progress(ep, 120000, -1)
        self.assertEqual(100000, self.store.progress('ep1')['positionMs']); self.assertTrue(self.store.progress('ep1')['completed'])
        self.store.save_progress(ep, 2000, 0); self.assertFalse(self.store.progress('ep1')['completed'])
        self.assertEqual(100000, self.store.progress('ep1')['durationMs'])

    def test_failed_atomic_replace_keeps_existing_file(self):
        self.store.save_series(series()); original = self.store.path.read_bytes()
        self.store.settings['speed'] = 2
        with patch('anime_shelf.store.os.replace', side_effect=OSError('fixture disk full')):
            with self.assertRaises(OSError): self.store.save()
        self.assertEqual(original, self.store.path.read_bytes()); self.assertFalse(self.store.path.with_name('library.json.tmp').exists())

    def test_reset_retains_library_flags_history_and_desktop_only_data(self):
        s = self.store.save_series(series()); self.store.save_progress(s['episodes'][0], 42000, 300000)
        self.store.set_pinned('ss1', True); self.store.hide_series('ss1'); self.store.settings['theme'] = 'pink'
        self.store.data['downloadJournal'] = ['fixture']; self.store.reset_settings()
        self.assertEqual('blue', self.store.settings['theme']); self.assertTrue(s['pinned']); self.assertTrue(s['hidden'])
        self.assertEqual(42000, self.store.progress('ep1')['positionMs']); self.assertEqual(['fixture'], self.store.data['downloadJournal'])

    def test_corrupt_local_state_is_not_overwritten_with_empty_library(self):
        self.store.path.write_text('{broken')
        with self.assertRaises(ValueError): Store(self.store.path)
        self.assertEqual('{broken', self.store.path.read_text())

if __name__ == '__main__': unittest.main()
