"""Exercise the actual bundled mpv/FFmpeg against synthetic local dual-track media."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]

def wait(condition, label, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition(): return
        time.sleep(.05)
    raise AssertionError(label + ' timed out')

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--runtime-root', type=Path, default=ROOT); args = parser.parse_args()
    runtime = args.runtime_root.resolve() / 'runtime'
    dll_path = runtime / 'mpv'
    handle = os.add_dll_directory(str(dll_path)) if os.name == 'nt' else None
    os.environ['PATH'] = str(dll_path) + os.pathsep + os.environ.get('PATH', '')
    import mpv
    fixture = ROOT / 'reports/native-fixture'; fixture.mkdir(parents=True, exist_ok=True)
    ffmpeg = runtime / 'ffmpeg/ffmpeg.exe'
    video, audio, preview = [fixture / name for name in ('video.mp4', 'audio.m4a', 'preview.jpg')]
    def encode(*arguments):
        result = subprocess.run([str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-y', *map(str, arguments)], capture_output=True, timeout=45)
        if result.returncode: raise RuntimeError(result.stderr.decode('utf-8', 'replace')[-2000:])
    encode('-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=15', '-t', 8, '-an', '-c:v', 'mpeg4', '-q:v', 4, video)
    encode('-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', 8, '-vn', '-c:a', 'aac', '-b:a', '96k', audio)
    player = mpv.MPV(vo='null', ao='null', hwdec='no', keep_open='yes', pause=True, input_default_bindings=False, osc=False)
    try:
        with player.prepare_and_wait_for_event('file-loaded', timeout=15):
            player.loadfile(str(video), audio_file=str(audio))
        tracks = player.track_list
        assert any(t.get('type') == 'video' and t.get('selected') for t in tracks), tracks
        assert any(t.get('type') == 'audio' and t.get('selected') and t.get('external') for t in tracks), tracks
        assert 7.9 <= float(player.duration) <= 8.2
        player.pause = False
        wait(lambda: player.time_pos is not None and player.time_pos > .2, 'dual-track playback')
        player.pause = True; player.seek(4, 'absolute', 'exact')
        wait(lambda: player.time_pos is not None and abs(player.time_pos - 4) < .3, 'synchronous seek')
        player.speed = 1.5; assert abs(player.speed - 1.5) < .001
        encode('-ss', '4', '-i', video, '-frames:v', '1', '-vf', 'scale=320:-2', preview)
        assert preview.read_bytes().startswith(b'\xff\xd8') and preview.stat().st_size > 1000
        report = {'ok': True, 'runtimeRoot': str(args.runtime_root), 'mpvVersion': player.mpv_version,
                  'duration': player.duration, 'seekPosition': player.time_pos, 'speed': player.speed,
                  'selectedTracks': [{'type': t.get('type'), 'codec': t.get('codec'), 'external': t.get('external', False)} for t in tracks if t.get('selected')],
                  'checks': ['local video plus external AAC audio', 'playback clock advances', 'absolute seek', 'speed', 'real FFmpeg thumbnail'],
                  'limits': ['synthetic local fixture only', 'null video and audio outputs', 'not physical GPU/HDR/display/audio validation']}
        (ROOT / 'reports/native-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
    finally:
        player.terminate()
        if handle: handle.close()

if __name__ == '__main__': main()
