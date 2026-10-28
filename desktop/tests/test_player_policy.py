import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from anime_shelf.player_policy import (ALL, SEEK, Gesture, PlaybackSourceError,
    candidates, format_time, media_proxy, media_url, public_headers,
    safe_error, settings_values, source_plan)
from anime_shelf.preview import Budget


class SourcePolicyTests(unittest.TestCase):
    def test_media_candidates_preserve_signatures_dedupe_cap_and_reject_injection(self):
        value = 'https://cdn.example/a.m4s?sign=%2Bprivate%2F'
        self.assertEqual(media_url(value), value)
        self.assertEqual(media_url('//cdn.example/a'), 'https://cdn.example/a')
        bad = ['http://cdn.example/a', 'file:///tmp/a', 'https://u:p@cdn.example/a',
               'https://cdn.example/a\r\nCookie: x', 'https://cdn.example/a#fragment', 'https://cdn.example:99999/a']
        for entry in bad:
            self.assertEqual(media_url(entry), '', entry)
        found = candidates(value, [value, *bad, *[f'https://cdn.example/{i}' for i in range(10)]])
        self.assertEqual(len(found), 5)
        self.assertEqual(found[0], value)

    def test_public_identity_does_not_accept_credentials_from_quality(self):
        headers = public_headers({'headers': {'Cookie': 'secret', 'Authorization': 'Bearer private', 'User-Agent': 'wrong'}})
        self.assertNotIn('Cookie', headers)
        self.assertNotIn('Authorization', headers)
        self.assertIn('Windows NT', headers['User-Agent'])
        self.assertEqual(headers['Referer'], 'https://www.bilibili.com/')

    def test_online_dual_track_and_progressive_sources_are_distinct(self):
        quality = {'id': '80:7:30280', 'videoUrl': 'https://cdn.example/v', 'audioUrl': 'https://cdn.example/a'}
        plan = source_plan({'id': 'ep1'}, quality)
        self.assertFalse(plan.offline)
        self.assertEqual(plan.audios, ('https://cdn.example/a',))
        progressive = source_plan({'id': 'ep1'}, {'videoUrl': 'https://cdn.example/p.mp4'})
        self.assertFalse(progressive.audios)
        with self.assertRaises(PlaybackSourceError):
            source_plan({'id': 'ep1'}, {'videoUrl': 'https://cdn.example/v', 'audioUrl': 'file:///private'})

    def test_offline_requires_both_local_files_and_never_uses_remote_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            video = Path(directory) / 'video'; audio = Path(directory) / 'audio'
            video.write_bytes(b'video'); audio.write_bytes(b'audio')
            quality = {'videoUrl': 'https://cdn.example/v', 'audioUrl': 'https://cdn.example/a'}
            task = {'complete': True, 'videoPath': str(video), 'audioPath': str(audio), 'quality': quality}
            plan = source_plan({'id': 'ep'}, quality, task)
            self.assertTrue(plan.offline)
            self.assertFalse(plan.headers)
            self.assertEqual(plan.videos, (str(video.resolve()),))
            self.assertEqual(plan.audios, (str(audio.resolve()),))
            audio.unlink()
            with self.assertRaises(PlaybackSourceError):
                source_plan({'id': 'ep'}, quality, task)
            task['audioPath'] = 'https://cdn.example/a'
            with self.assertRaises(PlaybackSourceError):
                source_plan({'id': 'ep'}, quality, task)
            task['complete'] = False
            with self.assertRaises(PlaybackSourceError):
                source_plan({'id': 'ep'}, quality, task)

    def test_android_content_grants_and_empty_local_files_are_not_portable(self):
        with self.assertRaises(PlaybackSourceError):
            source_plan({'id': 'local', 'localUri': 'content://phone/123'}, {})
        with tempfile.TemporaryDirectory() as directory:
            empty = Path(directory) / 'empty'; empty.touch()
            with self.assertRaises(PlaybackSourceError):
                source_plan({'localPath': str(empty)}, {})
            empty.write_bytes(b'x')
            self.assertTrue(source_plan({'localPath': str(empty)}, {}).offline)

    def test_offline_unc_paths_are_rejected_before_any_file_probe(self):
        from anime_shelf.player_policy import local_path
        with patch('pathlib.Path.is_file', side_effect=AssertionError('Network stat must not run')):
            for path in ('//server/share/video.mp4', '\\\\server\\share\\video.mp4', 'file://remote/share/video.mp4'):
                with self.assertRaises(PlaybackSourceError): local_path(path)

    def test_local_playlists_cannot_turn_offline_file_playback_into_network_streams(self):
        with tempfile.TemporaryDirectory() as folder:
            for name, content in [('list.m3u8', b'#EXTM3U\nhttps://remote/video'),
                                  ('disguised.mp4', b'#EXTM3U\nhttps://remote/video'),
                                  ('playlist.txt', b'https://remote/video')]:
                path = Path(folder) / name; path.write_bytes(content)
                with self.assertRaises(PlaybackSourceError): source_plan({'localPath': str(path)}, {})

    def test_error_messages_never_contain_signed_urls_or_credentials(self):
        self.assertIn('HTTP 403', safe_error(RuntimeError('HTTP error 403 https://cdn.example/x?token=SECRET')))
        for value in [RuntimeError('https://cdn.example/?token=SECRET Cookie: PRIVATE'), RuntimeError('decoder failed')]:
            message = safe_error(value)
            self.assertNotIn('SECRET', message)
            self.assertNotIn('PRIVATE', message)
            self.assertNotIn('https://', message)

    @patch('urllib.request.proxy_bypass', return_value=False)
    @patch('urllib.request.getproxies', return_value={'https': 'http://127.0.0.1:9876'})
    def test_media_proxy_matches_urllib_static_proxy_and_offline_never_uses_it(self, proxies, bypass):
        self.assertEqual(media_proxy('https://cdn.example/v'), 'http://127.0.0.1:9876')
        self.assertEqual(media_proxy('/local/video', offline=True), '')
        proxies.assert_called_once()
        bypass.return_value = True
        self.assertEqual(media_proxy('https://cdn.example/v'), '')


class GesturePolicyTests(unittest.TestCase):
    def gesture(self, mask=ALL, seconds=180, x=100):
        return Gesture(x, 150, 1000, 600, 120000, 600000, 50, 0, mask, seconds)

    def test_seek_only_commits_on_release_and_respects_sensitivity(self):
        for seconds in (90, 180, 300):
            g = self.gesture(seconds=seconds)
            mode, value = g.move(600, 150)
            self.assertEqual(mode, 'seek')
            self.assertEqual(value, 120000 + seconds * 500)
            self.assertEqual(g.finish(), value)
            self.assertIsNone(g.finish())

    def test_cancellation_and_disabled_axes_never_commit(self):
        g = self.gesture(); g.move(600, 150)
        self.assertIsNone(g.finish(cancelled=True))
        g = self.gesture(mask=ALL & ~SEEK)
        self.assertEqual(g.move(600, 150)[0], 'ignored')
        self.assertIsNone(g.finish())
        g = self.gesture(mask=SEEK)
        self.assertEqual(g.move(100, 40)[0], 'ignored')
        self.assertIsNone(g.finish())

    def test_vertical_halves_are_brightness_and_volume(self):
        g = self.gesture(x=100)
        mode, value = g.move(100, 40)
        self.assertEqual(mode, 'brightness'); self.assertGreater(value, 0)
        g = self.gesture(x=800)
        mode, value = g.move(800, 40)
        self.assertEqual(mode, 'volume'); self.assertGreater(value, 50)
        self.assertIsNone(g.finish())

    def test_invalid_coordinates_settings_and_time_are_bounded(self):
        g = self.gesture(); self.assertEqual(g.move(float('nan'), 150)[0], 'ignored')
        g = self.gesture(); self.assertEqual(g.move(-1000000, 150)[1], 0)
        g = self.gesture(); self.assertEqual(g.move(1000000, 150)[1], 600000)
        settings = settings_values({'speed': float('nan'), 'gestureMask': -1, 'seekSeconds': 999, 'holdSpeed': 99})
        self.assertEqual(settings['speed'], 1); self.assertEqual(settings['gestureMask'], ALL)
        self.assertEqual(settings['seekSeconds'], 180); self.assertEqual(settings['holdSpeed'], 2)
        self.assertEqual(format_time(3661000), '1:01:01')


class PreviewBudgetTests(unittest.TestCase):
    def test_bounds_reads_opens_cancellation_and_deadline(self):
        state = {'cancelled': False}
        budget = Budget(lambda: state['cancelled'], max_bytes=100, max_opens=2, timeout=4)
        budget.opened(); budget.opened()
        with self.assertRaises(TimeoutError): budget.opened()
        self.assertEqual(budget.take(64), 64); self.assertEqual(budget.take(64), 36)
        with self.assertRaises(TimeoutError): budget.take(1)
        state['cancelled'] = True
        with self.assertRaises(TimeoutError): budget.check()
        deadline = Budget(lambda: False, timeout=0)
        with self.assertRaises(TimeoutError): deadline.check()


if __name__ == '__main__': unittest.main()
