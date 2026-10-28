"""Prepare pinned Windows runtimes and the unchanged Android parser bridge for CI."""
from __future__ import annotations

import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import sys
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
LOCK = json.loads((ROOT / 'build-lock.json').read_text(encoding='utf-8'))
REPORTS = ROOT / 'reports'
REPORTS.mkdir(exist_ok=True)

def sha(path: Path, algorithm='sha256') -> str:
    digest = hashlib.new(algorithm)
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def download(url: str, path: Path, expected: str, algorithm='sha256') -> Path:
    if not url.startswith('https://') or not re.fullmatch(r'[a-f0-9]{40}|[a-f0-9]{64}', expected):
        raise ValueError('A pinned HTTPS URL and digest are required')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and sha(path, algorithm) == expected:
        return path
    partial = path.with_suffix(path.suffix + '.partial')
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'AnimeShelf-Verified-Build/1'})
            with urllib.request.urlopen(request, timeout=45) as response, partial.open('wb') as out:
                for block in iter(lambda: response.read(1024 * 1024), b''):
                    out.write(block)
            if sha(partial, algorithm) != expected:
                raise ValueError(f'Checksum mismatch: {path.name}')
            partial.replace(path)
            return path
        except Exception:
            partial.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise AssertionError('Unreachable')

def run(command: list[str], *, report: str | None = None, cwd=ROOT) -> str:
    result = subprocess.run(list(map(str, command)), cwd=cwd, text=True, encoding='utf-8', errors='replace',
                            capture_output=True, timeout=180)
    text = result.stdout + result.stderr
    if report:
        (REPORTS / report).write_text(text, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(f'{Path(command[0]).name} failed ({result.returncode}):\n{text[-6000:]}')
    return text

def native_archive(kind: str) -> dict:
    info = LOCK[kind]
    archive = download(info['url'], ROOT / '.artifacts' / info['url'].rsplit('/', 1)[-1], info['sha256'])
    output = ROOT / '.artifacts' / (kind + '-expanded')
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    seven = shutil.which('7z') or r'C:\Program Files\7-Zip\7z.exe'
    run([seven, 'x', '-y', '-o' + str(output), str(archive)], report=kind + '-extract.txt')
    runtime = ROOT / 'runtime' / kind
    runtime.mkdir(parents=True, exist_ok=True)
    candidates = list(output.rglob('*.dll')) if kind == 'mpv' else list(output.rglob('ffmpeg.exe'))
    if kind == 'mpv' and not any(p.name.lower() in ('libmpv-2.dll', 'mpv-2.dll') for p in candidates):
        raise RuntimeError('Pinned libmpv archive has no API v2 DLL')
    if not candidates:
        raise RuntimeError(f'Pinned {kind} archive has no requested runtime')
    for source in candidates:
        target = runtime / source.name
        if target.exists() and sha(source) != sha(target):
            target.unlink()
        shutil.copyfile(source, target)
    notice_root = ROOT / 'licenses' / 'upstream' / kind
    for file in output.rglob('*'):
        if file.is_file() and (file.suffix.lower() in ('.txt', '.md') or any(s in file.name.lower() for s in ('license', 'copying', 'copyright'))):
            target = notice_root / file.relative_to(output)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, target)
    return {'archive': archive.name, 'sha256': sha(archive), 'bytes': archive.stat().st_size,
            'runtimeFiles': [{'name': p.name, 'sha256': sha(p), 'bytes': p.stat().st_size} for p in sorted(runtime.iterdir()) if p.is_file()]}

def vulkan_loader() -> dict:
    """Bundle the official loader beside both dependent runtimes; no installer."""
    info = LOCK['vulkanLoader']
    archive = download(info['url'], ROOT / '.artifacts' / info['url'].rsplit('/', 1)[-1], info['sha256'])
    candidates = []
    notices = []
    with zipfile.ZipFile(archive) as package:
        for item in package.infolist():
            relative = Path(item.filename.replace('\\', '/'))
            if item.is_dir() or relative.is_absolute() or '..' in relative.parts:
                continue
            if relative.name.lower() == 'vulkan-1.dll':
                data = package.read(item)
                if len(data) < 64 or data[:2] != b'MZ':
                    continue
                offset = struct.unpack_from('<I', data, 0x3c)[0]
                if offset + 6 <= len(data) and data[offset:offset+4] == b'PE\0\0' and struct.unpack_from('<H', data, offset+4)[0] == 0x8664:
                    candidates.append((item.filename, data))
            elif any(term in relative.name.lower() for term in ('license', 'copyright', 'notice', 'copying')):
                target = ROOT / 'licenses/upstream/vulkan-loader' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(package.read(item))
                notices.append(relative.as_posix())
    if len(candidates) != 1 or not notices:
        raise RuntimeError(f'Official Vulkan components must contain one x64 loader and license notices: {len(candidates)}, {notices}')
    name, data = candidates[0]
    for kind in ('mpv', 'ffmpeg'):
        (ROOT / 'runtime' / kind / 'vulkan-1.dll').write_bytes(data)
    receipt = {'archive': archive.name, 'version': info['version'], 'sha256': sha(archive),
               'bytes': archive.stat().st_size, 'dllMember': name, 'dllSha256': hashlib.sha256(data).hexdigest(),
               'dllBytes': len(data), 'machine': 'x64', 'licenses': notices, 'checksumSource': info['checksumSource']}
    # Keep this receipt even if the later dependency load check fails.
    (REPORTS / 'vulkan-loader.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return receipt

def verify_native_dependencies() -> dict:
    import ctypes
    handles = [os.add_dll_directory(str(ROOT / 'runtime' / kind)) for kind in ('mpv', 'ffmpeg')]
    try:
        loader = ctypes.WinDLL(str(ROOT / 'runtime/mpv/vulkan-1.dll'))
        assert loader.vkGetInstanceProcAddr, 'Vulkan loader export missing'
        mpv_path = next(path for path in (ROOT / 'runtime/mpv').glob('*.dll') if path.name.lower() in ('mpv-2.dll', 'libmpv-2.dll'))
        media = ctypes.WinDLL(str(mpv_path))
        assert media.mpv_client_api_version, 'libmpv API export missing'
        version = run([ROOT / 'runtime/ffmpeg/ffmpeg.exe', '-version'], report='ffmpeg-version.txt')
        return {'vulkanLoaded': True, 'mpvLoaded': True, 'ffmpegVersion': version.splitlines()[0]}
    finally:
        for handle in handles: handle.close()

def java_bridge() -> dict:
    home = Path(os.environ['JAVA_HOME'])
    executable = '.exe' if os.name == 'nt' else ''
    javac, java, jlink = [home / 'bin' / (x + executable) for x in ('javac', 'java', 'jlink')]
    info = LOCK['jsonJava']
    # Maven Central publishes SHA-1 alongside this fixed artifact. Record a stronger
    # SHA-256 of the verified downloaded bytes in the release validation report too.
    request = urllib.request.Request(info['url'] + '.sha1', headers={'User-Agent': 'AnimeShelf-Verified-Build/1'})
    with urllib.request.urlopen(request, timeout=30) as response:
        expected = response.read(256).decode('ascii').strip().split()[0].lower()
    if not re.fullmatch(r'[0-9a-f]{40}', expected):
        raise RuntimeError('Unexpected Maven checksum response')
    jar = download(info['url'], ROOT / 'bridge/deps/json.jar', expected, 'sha1')
    classes = ROOT / 'bridge/classes'
    parser_classes = ROOT / 'bridge/parser-classes'
    for path in (classes, parser_classes):
        if path.exists(): shutil.rmtree(path)
        path.mkdir(parents=True)
    source = REPO / 'app/src/main/java/com/animeshelf/app'
    run([javac, '-encoding', 'UTF-8', '--release', '17', '-cp', jar, '-d', classes,
         source / 'Models.java', source / 'BiliClient.java', ROOT / 'bridge/DesktopBridge.java'], report='bridge-compile.txt')
    run([javac, '-encoding', 'UTF-8', '--release', '17', '-cp', str(classes) + os.pathsep + str(jar),
         '-d', parser_classes, REPO / 'tests/ParserSelfTest.java'])
    tests = run([java, '-cp', os.pathsep.join(map(str, (classes, parser_classes, jar))), 'com.animeshelf.app.ParserSelfTest'], report='parser-tests.txt')
    if 'ParserSelfTest passed: 132 assertions' not in tests:
        raise RuntimeError('Unexpected parser baseline result')
    fat = ROOT / 'bridge/anime-bridge.jar'
    with zipfile.ZipFile(fat, 'w', zipfile.ZIP_DEFLATED) as out:
        out.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nMain-Class: com.animeshelf.app.DesktopBridge\n\n')
        for item in sorted(classes.rglob('*.class')): out.write(item, item.relative_to(classes).as_posix())
        with zipfile.ZipFile(jar) as original:
            for name in original.namelist():
                if name.startswith('org/json/') and name.endswith('.class'): out.writestr(name, original.read(name))
                if any(x in name.lower() for x in ('license', 'notice', 'copyright')) and not name.endswith('/'):
                    target = ROOT / 'licenses/package-licenses/org.json' / Path(name).name
                    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(original.read(name))
    runtime = ROOT / 'runtime/java'
    if runtime.exists(): shutil.rmtree(runtime)
    run([jlink, '--add-modules', 'java.base,jdk.crypto.ec', '--strip-debug', '--no-header-files', '--no-man-pages',
         '--compress=2', '--output', runtime], report='jlink.txt')
    version = run([runtime / 'bin' / ('java' + executable), '-version'], report='java-version.txt')
    return {'jsonVersion': info['version'], 'jsonSha1': expected, 'jsonSha256': sha(jar),
            'bridgeSha256': sha(fat), 'javaVersion': version.strip(), 'parserAssertions': 132}

def copy_python_licenses() -> None:
    for name in ('PySide6-Essentials', 'shiboken6', 'python-mpv', 'qrcode', 'colorama'):
        try: distribution = importlib.metadata.distribution(name)
        except importlib.metadata.PackageNotFoundError: continue
        for entry in distribution.files or []:
            if any(term in str(entry).lower() for term in ('license', 'copying', 'copyright', 'notice')):
                source = Path(distribution.locate_file(entry))
                if source.is_file() and source.stat().st_size < 4 * 1024 * 1024:
                    target = ROOT / 'licenses/package-licenses' / name / Path(str(entry))
                    # Wheel metadata paths may contain '..'; never let them escape the license destination.
                    if '..' in target.parts: target = ROOT / 'licenses/package-licenses' / name / source.name
                    target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
    stdlib_license = Path(sys.base_prefix) / 'LICENSE.txt'
    if stdlib_license.is_file(): shutil.copyfile(stdlib_license, ROOT / 'licenses/Python-LICENSE.txt')

def main() -> None:
    if os.name != 'nt': raise SystemExit('Use a Windows x64 runner to build the Windows application')
    receipt = {'schemaVersion': 1, 'lock': LOCK, 'mpv': native_archive('mpv'), 'ffmpeg': native_archive('ffmpeg'),
               'vulkanLoader': vulkan_loader(), 'nativeDependencies': verify_native_dependencies(),
               'java': java_bridge(), 'python': sys.version, 'dependencies': {p: importlib.metadata.version(p) for p in ('PySide6-Essentials', 'shiboken6', 'python-mpv', 'qrcode', 'pyinstaller')}}
    copy_python_licenses()
    (REPORTS / 'runtime-verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Pinned native runtime hashes and Java parser verified.')

if __name__ == '__main__': main()
