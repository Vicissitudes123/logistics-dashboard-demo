import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from anime_shelf.store import Store
from anime_shelf.backup import BackupError, inspect_backup
from test_store import series

class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.store = Store(Path(self.temp.name) / 'library.json')
        s = self.store.save_series(series()); self.store.save_progress(s['episodes'][0], 42000, 300000)
        self.store.data['progress']['ep1']['updatedAt'] = 1000

    def test_v4_roundtrip_includes_phone_schema_and_excludes_secrets(self):
        self.store.settings.update(gestureMask=5, seekSeconds=90, theme='pink')
        self.store.set_playback_preference('ss1', True, {'qn': 125, 'id': '125:12:30280', 'videoUrl': 'SECRET_MEDIA_URL'})
        self.store.data['session'] = 'SECRET_COOKIE'; self.store.data['downloads'] = [{'videoUrl': 'SECRET_SIGNED_URL'}]
        self.store.settings['privatePath'] = 'SECRET_LOCAL_PATH'
        text = self.store.export_backup(); snapshot = inspect_backup(text)
        self.assertEqual(4, snapshot['document']['version']); self.assertNotIn('SECRET_', text)
        self.assertEqual({'qn': 125, 'codec': '12'}, snapshot['document']['settings']['preferredQuality'])
        new = Store(Path(self.temp.name) / 'new.json'); result = new.import_backup(text)
        self.assertEqual(1, result['addedSeries']); self.assertEqual(42000, new.progress('ep1')['positionMs'])
        self.assertEqual('ep1', new.last_episode('ss1')); self.assertEqual(5, new.settings['gestureMask']); self.assertEqual(90, new.settings['seekSeconds'])

    def test_newer_local_progress_and_names_win_while_backup_flags_apply(self):
        source = json.loads(self.store.export_backup()); source['series'][0]['title'] = '旧名称'; source['series'][0]['pinned'] = True
        self.store.data['progress']['ep1'].update(positionMs=120000, updatedAt=2000)
        self.store.data['downloads'] = ['retain']
        result = self.store.import_backup(json.dumps(source))
        self.assertEqual(1, result['keptNewerProgress']); self.assertEqual(120000, self.store.progress('ep1')['positionMs'])
        self.assertEqual('擅长捉弄的高木同学', self.store.find_series('ss1')['title']); self.assertTrue(self.store.find_series('ss1')['pinned'])
        self.assertEqual(['retain'], self.store.data['downloads'])

    def test_older_versions_retain_newer_device_settings(self):
        current = json.loads(self.store.export_backup())
        for version in (1, 2, 3):
            old = copy.deepcopy(current); old['version'] = version
            old['settings'].pop('gestureMask'); old['settings'].pop('seekSeconds')
            if version < 3:
                for s in old['series']: s.pop('playback')
            if version == 1:
                old['settings'].pop('wifiOnlyDownloads'); old['settings'].pop('libraryFilter')
                for s in old['series']: s.pop('skip')
            self.store.settings.update(gestureMask=0, seekSeconds=300, wifiOnlyDownloads=False)
            self.store.find_series('ss1')['playback'] = {'offline': True, 'profile': {'qn': 125, 'codec': '12'}}
            self.store.import_backup(json.dumps(old))
            self.assertEqual(0, self.store.settings['gestureMask']); self.assertEqual(300, self.store.settings['seekSeconds'])
            if version < 3: self.assertTrue(self.store.playback_preference('ss1')['offline'])
            if version == 1: self.assertFalse(self.store.settings['wifiOnlyDownloads'])

    def test_local_uris_and_their_history_are_never_restored_on_another_device(self):
        local = series('local-folder', ('local-file',)); ep = local['episodes'][0]
        ep.update(localUri='file:///C:/SECRET_LOCAL_VIDEO.mp4', bvid='', aid=0, cid=0, bangumi=False)
        self.store.save_series(local); self.store.save_progress(ep, 10000, 300000)
        text = self.store.export_backup(); self.assertNotIn('SECRET_LOCAL_VIDEO', text)
        other = Store(Path(self.temp.name) / 'other.json'); result = other.import_backup(text)
        self.assertEqual(1, result['skippedLocalEpisodes']); self.assertIsNone(other.find_series('local-folder'))
        self.assertEqual(0, other.progress('local-file')['updatedAt'])

    def test_invalid_or_secret_fields_and_duplicate_keys_are_atomic_failures(self):
        doc = json.loads(self.store.export_backup()); bad = []
        x = copy.deepcopy(doc); x['cookie'] = 'secret'; bad.append(json.dumps(x))
        x = copy.deepcopy(doc); x['series'][0]['episodes'][0]['localUri'] = 'file:///secret'; bad.append(json.dumps(x))
        x = copy.deepcopy(doc); x['settings']['gestureMask'] = True; bad.append(json.dumps(x))
        x = copy.deepcopy(doc); x['progress'][0]['positionMs'] = 900000; bad.append(json.dumps(x))
        x = copy.deepcopy(doc); x['series'][0]['coverUrl'] += '?secret=1'; bad.append(json.dumps(x))
        bad += ['{"format":"duplicate",' + self.store.export_backup()[1:], self.store.export_backup() + '{}']
        before = copy.deepcopy(self.store.data); self.store.save(); raw = self.store.path.read_bytes()
        for text in bad:
            with self.assertRaises(BackupError): self.store.import_backup(text)
            self.assertEqual(before, self.store.data); self.assertEqual(raw, self.store.path.read_bytes())

    def test_ownership_conflict_does_not_partially_import(self):
        doc = json.loads(self.store.export_backup()); doc['series'][0]['id'] = 'ss2'; doc['lastSeries'] = 'ss2'
        before = copy.deepcopy(self.store.data)
        with self.assertRaises(BackupError): self.store.import_backup(json.dumps(doc))
        self.assertEqual(before, self.store.data)

    def test_failed_import_write_rolls_back_in_memory_as_well_as_disk(self):
        doc = json.loads(self.store.export_backup()); doc['settings']['theme'] = 'pink'
        before = copy.deepcopy(self.store.data); self.store.save(); raw = self.store.path.read_bytes()
        with patch('anime_shelf.store.os.replace', side_effect=OSError('fixture disk full')):
            with self.assertRaises(OSError): self.store.import_backup(json.dumps(doc))
        self.assertEqual(before, self.store.data); self.assertEqual(raw, self.store.path.read_bytes())

    def test_limits_and_nesting_rejected_before_merge(self):
        for text in (' ' * (4 * 1024 * 1024 + 1), '[' * 13 + ']' * 13):
            with self.assertRaises(BackupError): inspect_backup(text)

if __name__ == '__main__': unittest.main()
