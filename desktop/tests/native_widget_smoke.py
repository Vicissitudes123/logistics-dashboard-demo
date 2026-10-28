"""Windows CI smoke for the actual PlayerWidget HWND and per-load mpv options.

Run scripts/native_smoke.py first to create the synthetic MP4 + AAC fixture.
This exercises the production widget rather than replacing its media engine.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-root', type=Path, default=ROOT)
    args = parser.parse_args()
    runtime_root = args.runtime_root.resolve()
    runtime = runtime_root / 'runtime'
    dll_path = runtime / 'mpv'
    handle = os.add_dll_directory(str(dll_path)) if os.name == 'nt' else None
    os.environ['PATH'] = str(dll_path) + os.pathsep + os.environ.get('PATH', '')
    import mpv
    from PySide6.QtWidgets import QApplication
    from anime_shelf.player import PlayerWidget
    from anime_shelf import preview

    app = QApplication.instance() or QApplication([])
    if os.name == 'nt' and app.platformName() != 'windows':
        raise AssertionError('Native HWND smoke requires the Windows Qt platform, not offscreen/minimal')
    fixture = ROOT / 'reports/native-fixture'
    video, audio = fixture / 'video.mp4', fixture / 'audio.m4a'
    assert video.is_file() and audio.is_file(), 'Generate the local fixture with scripts/native_smoke.py first'
    # Respect --runtime-root for real thumbnail extraction too.
    preview.runtime_root = lambda: runtime_root
    native_log = []
    def factory(**options):
        original_log = options['log_handler']
        def log(level, prefix, text):
            original_log(level, prefix, text)
            # Only synthetic local fixture paths are used by this smoke.
            native_log.append(f'{level}: {prefix}: {text}')
            del native_log[:-32]
        options.update(ao='null', log_handler=log)
        return mpv.MPV(**options)
    player = PlayerWidget(media_factory=factory)
    player.resize(960, 540)
    player.show()
    errors, ready = [], []
    player.error.connect(errors.append)
    player.ready.connect(lambda: ready.append(True))

    def wait(condition, label, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            app.processEvents()
            if errors:
                raise AssertionError(f'{label}: {errors!r}; native synthetic-fixture log: {native_log!r}')
            if condition():
                return
            time.sleep(.01)
        raise AssertionError(f'{label} timed out; native synthetic-fixture log: {native_log!r}')

    try:
        app.processEvents()
        quality = {'id': 'fixture', 'label': '本机测试', 'audioUrl': 'https://fixture.invalid/audio'}
        task = {'complete': True, 'videoPath': str(video), 'audioPath': str(audio), 'quality': quality}
        assert player.load({'id': 'fixture', 'title': '双轨原生播放器检查'}, quality,
                           offline_task=task, start_ms=1000, paused=True)
        wait(lambda: bool(ready), 'production widget ready')
        wait(lambda: abs(float(player._get('time-pos', 0) or 0) - 1) < .3, 'resume into paused media')
        assert not player.is_playing
        assert 7900 <= player.duration_ms <= 8200
        tracks = player._get('track-list', [])
        assert any(t.get('type') == 'video' and t.get('selected') for t in tracks), tracks
        assert any(t.get('type') == 'audio' and t.get('external') and t.get('selected') for t in tracks), tracks
        assert player._get('video-params', {}).get('w') == 320
        player.resume()
        wait(lambda: float(player._get('time-pos', 0) or 0) > 1.4, 'production widget clock advances')
        player.pause()
        player.seek(4000)
        wait(lambda: abs(float(player._get('time-pos', 0) or 0) - 4) < .3, 'production widget absolute seek')
        player.set_speed(1.5)
        assert abs(float(player._get('speed')) - 1.5) < .001
        jpeg = preview.extract_frame(player._plan, 4000, lambda: False)
        assert jpeg and jpeg.startswith(b'\xff\xd8'), 'Production bounded preview failed'
        (fixture / 'widget-preview.jpg').write_bytes(jpeg)
        player.grab().save(str(ROOT / 'reports/player-widget.png'))
        report = {
            'ok': True, 'qtPlatform': app.platformName(),
            'videoOutput': player._get('current-vo'),
            'durationMs': player.duration_ms,
            'actualSeekSeconds': player._get('time-pos'),
            'externalAudioSelected': True,
            'checks': ['production native HWND', 'production per-load properties',
                       'offline protocol restrictions', 'external AAC selection',
                       'paused resume position', 'playback advances', 'actual mpv seek',
                       'persistent playback speed', 'production FFmpeg keyframe preview'],
            'limits': ['synthetic local media only', 'audio output is null',
                       'does not certify physical audio, hardware HDR, or account media'],
        }
        (ROOT / 'reports/player-widget-smoke.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
    finally:
        player.dispose()
        player.close()
        app.processEvents()
        if handle:
            handle.close()


if __name__ == '__main__':
    main()
