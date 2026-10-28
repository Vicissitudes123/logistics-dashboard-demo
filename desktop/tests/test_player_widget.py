import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import tempfile
import unittest
from pathlib import Path

try:
    from PySide6.QtWidgets import QApplication
    from anime_shelf.player import PlayerWidget
    QT_AVAILABLE = True
except ImportError:
    QT_AVAILABLE = False


class FakeMedia:
    def __init__(self, **options):
        self.options = options
        self.values = {'duration': 600.0, 'track-list': [{'type': 'video'}, {'type': 'audio', 'external': True, 'id': 2}]}
        self.commands = []
        self.observers = {}
    def __getitem__(self, name):
        if name in ('duration', 'track-list', 'playlist', 'time-pos', 'video-params', 'video-out-params'):
            raise AttributeError('python-mpv item access reads options, not properties')
        return self.values.get(name)
    def _get_property(self, name): return self.values.get(name)
    def __setitem__(self, name, value): self.values[name] = value
    def observe_property(self, name, callback): self.observers[name] = callback
    def event_callback(self, *names): return lambda callback: callback
    def command(self, *args): self.commands.append(args)
    def terminate(self): self.terminated = True


@unittest.skipUnless(QT_AVAILABLE, 'Qt tests run in the packaged Windows CI environment')
class PlayerWidgetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.app = QApplication.instance() or QApplication([])
    def setUp(self):
        self.engine = None
        def factory(**options):
            self.engine = FakeMedia(**options)
            return self.engine
        self.player = PlayerWidget(media_factory=factory)
        self.player.resize(960, 540)
        self.errors = []; self.player.error.connect(self.errors.append)
        self.quality = {'id': '80:7:30280', 'label': '1080P', 'qn': 80, 'videoUrl': 'https://cdn.example/video', 'audioUrl': 'https://cdn.example/audio'}
    def tearDown(self): self.player.dispose(); self.player.deleteLater(); self.app.processEvents()
    def load(self, start=0, paused=False):
        self.assertTrue(self.player.load({'id': 'ep1', 'title': '第 1 集'}, self.quality, start_ms=start, paused=paused))
        self.player._native_event('start', 1)
        self.player._native_event('loaded', 1)
    def test_paired_sources_restore_position_pause_and_public_headers(self):
        self.load(42000, True)
        self.assertEqual(self.engine.values['audio-files'], [self.quality['audioUrl']])
        self.assertEqual(self.engine.values['http-header-fields'], ['Referer: https://www.bilibili.com/'])
        self.assertFalse(self.engine.values['cookies'])
        self.assertNotIn('file', self.engine.values['demuxer-lavf-o']['protocol_whitelist'].split(','))
        self.assertEqual(self.player.position_ms, 42000)
        self.assertEqual(self.player.duration_ms, 600000)
        self.assertFalse(self.player.is_playing)
        self.assertIn(('seek', 42.0, 'absolute+exact'), self.engine.commands)
        self.assertFalse(self.errors)
    def test_temporary_boost_restores_on_release_and_never_persists_speed(self):
        changes = []; self.player.speedChanged.connect(changes.append)
        self.load(); self.player.set_speed(.75)
        self.player._pointer_down(300, 200); self.player._start_boost()
        self.assertEqual(self.engine.values['speed'], 2)
        self.assertEqual(self.player.speed, .75)
        self.player._pointer_up(300, 200)
        self.assertEqual(self.engine.values['speed'], .75)
        self.assertEqual(changes, [.75])
        self.player._pointer_down(300, 200); self.player._start_boost(); self.player.cancel_interaction()
        self.assertEqual(self.engine.values['speed'], .75)
    def test_programmatic_seek_is_distinct_from_user_seek_and_lock_blocks_user(self):
        self.load(); moved = []; self.player.userSeeked.connect(moved.append)
        self.player.seek(42000); self.assertEqual(moved, [])
        self.player._user_seek(46000); self.assertEqual(moved, [46000])
        self.player.toggle_lock(); self.player._user_seek(50000)
        self.assertEqual(moved, [46000]); self.assertEqual(self.player.position_ms, 46000)
    def test_audio_failure_tries_its_backup_and_does_not_silently_play_silent_video(self):
        self.quality['audioUrls'] = [self.quality['audioUrl'], 'https://backup.example/audio']
        self.assertTrue(self.player.load({'id': 'ep'}, self.quality))
        self.engine.values['track-list'] = [{'type': 'video'}]
        self.player._native_event('start', 1)
        self.player._native_event('loaded', 1)
        self.assertEqual(self.engine.values['audio-files'], ['https://backup.example/audio'])
        self.player._native_event('start', 2)
        self.player._native_event('loaded', 2)
        self.assertTrue(self.errors); self.assertTrue(self.engine.values['pause'])
    def test_offline_missing_audio_does_not_start_a_network_player(self):
        with tempfile.TemporaryDirectory() as folder:
            video = Path(folder) / 'video'; video.write_bytes(b'fixture')
            task = {'complete': True, 'quality': self.quality, 'videoPath': str(video), 'audioPath': ''}
            self.assertFalse(self.player.load({'id': 'ep'}, self.quality, offline_task=task))
            self.assertIsNone(self.engine)
            self.assertTrue(self.errors)
    def test_stale_error_event_cannot_retry_a_replaced_playlist_entry(self):
        self.quality['videoUrls'] = [self.quality['videoUrl'], 'https://backup.example/video']
        self.load(); self.player._native_event('start', 22)
        before = len(self.engine.commands)
        self.player._native_event('end', {'entry': 21, 'reason': 4, 'error': -1})
        self.assertEqual(len(self.engine.commands), before)
        self.player._native_event('end', {'entry': 22, 'reason': 4, 'error': -1})
        self.assertGreater(len(self.engine.commands), before)
    def test_old_end_and_loaded_before_new_start_cannot_finish_or_retry_new_episode(self):
        self.load(); endings = []; self.player.ended.connect(lambda: endings.append(True))
        self.quality['videoUrls'] = [self.quality['videoUrl'], 'https://backup.example/video']
        self.assertTrue(self.player.load({'id': 'new'}, self.quality, start_ms=27000))
        before = len(self.engine.commands)
        self.player._native_event('end', {'entry': 1, 'reason': 0, 'error': 0})
        self.player._native_event('end', {'entry': 1, 'reason': 4, 'error': -1})
        self.player._native_event('start', 1); self.player._native_event('loaded', 1)
        self.assertEqual(endings, []); self.assertEqual(len(self.engine.commands), before)
        self.assertTrue(self.player._loading)
        self.player._native_event('start', 2); self.player._native_event('loaded', 2)
        self.assertEqual(self.player.position_ms, 27000); self.assertFalse(self.player._loading)

    def test_invalid_replacement_stops_old_media_without_publishing_old_progress(self):
        self.load(); self.player._position = 123000
        progress = []; self.player.progressChanged.connect(lambda position, duration: progress.append((position, duration)))
        self.assertFalse(self.player.load({'id': 'new'}, {}, start_ms=34000))
        self.assertEqual(progress, []); self.assertIsNone(self.player._plan)
        self.assertIn(('stop',), self.engine.commands)
        self.assertEqual(self.player.position_ms, 34000); self.assertFalse(self.player.is_playing)
        self.player._property_changed('time-pos', 125)
        self.assertEqual(self.player.position_ms, 34000)

    def test_eof_is_one_shot_and_input_hdr_is_not_claimed_as_confirmed_display_hdr(self):
        self.load(); endings = []; self.player.ended.connect(lambda: endings.append(True))
        self.engine.values['video-params'] = {'gamma': 'pq', 'primaries': 'bt.2020', 'w': 1920, 'h': 1080}
        info = self.player.video_information()
        self.assertTrue(info['inputHdr']); self.assertFalse(info['displayHdrConfirmed'])
        self.player._property_changed('eof-reached', True); self.player._finish()
        self.assertEqual(len(endings), 1); self.assertEqual(self.player.position_ms, self.player.duration_ms)


if __name__ == '__main__': unittest.main()
