"""Validate the portable output, produce fixed release assets and small verification reports."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
REPORTS = ROOT / 'reports'
VERSION = '0.1.13.1'
TAG = 'anime-shelf-desktop-v' + VERSION
REPOSITORY = 'Vicissitudes123/logistics-dashboard-demo'

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''): digest.update(data)
    return digest.hexdigest()

def main():
    source = os.environ.get('GITHUB_SHA', '').strip()
    if not re.fullmatch('[0-9a-f]{40}', source): raise RuntimeError('A full source commit is required')
    output = ROOT / 'dist/AnimeShelf'
    executable = output / 'AnimeShelf.exe'
    data = executable.read_bytes()
    assert data[:2] == b'MZ'
    offset = struct.unpack_from('<I', data, 0x3c)[0]
    assert data[offset:offset+4] == b'PE\0\0' and struct.unpack_from('<H', data, offset+4)[0] == 0x8664, 'Expected Windows x64 PE'
    for relative in ('_internal/runtime/java/bin/java.exe', '_internal/runtime/ffmpeg/ffmpeg.exe', '_internal/bridge/anime-bridge.jar'):
        assert (output / relative).is_file(), relative
    assert any((output / '_internal/runtime/mpv').glob('*mpv*2.dll'))
    tests = ET.parse(REPORTS / 'desktop-tests.xml').getroot()
    assert int(tests.attrib['tests']) > 0 and int(tests.attrib['failures']) == int(tests.attrib['errors']) == 0
    smoke = json.loads((REPORTS / 'native-smoke.json').read_text(encoding='utf-8'))
    assert smoke['ok']
    widget = json.loads((REPORTS / 'player-widget-smoke.json').read_text(encoding='utf-8'))
    assert widget.get('ok') is True, 'Production native player widget must pass'
    ui = json.loads((REPORTS / 'packaged-ui-smoke.json').read_text(encoding='utf-8'))
    signature = json.loads((REPORTS / 'authenticode.json').read_text(encoding='utf-8-sig'))
    assert ui.get('ok') is True, 'Packaged UI smoke must explicitly report ok:true'
    shutil.copytree(ROOT / 'licenses', output / 'licenses', dirs_exist_ok=True)
    (output / '使用说明.txt').write_text('番匣 Windows 11 测试版 ' + VERSION + '\n\n解压整个文件夹后运行 AnimeShelf.exe，不能单独移动 EXE。无需另外安装 Python、Java 或 mpv。\n片库、进度与下载文件保存在本机应用数据目录，不在此安装文件夹。更新时先退出旧程序，再解压新包运行；不要删除本机数据目录。\n登录使用 B站官方扫码流程，二维码在本机绘制。请勿分享登录信息。\nHDR 实际输出取决于 Windows HDR 设置、显示器、显卡和视频源；测试报告不代表真机 HDR 认证。\n这是没有商业代码签名证书的测试版；SHA-256 用于核对文件完整性，不等于可信发布者签名。\n', encoding='utf-8')
    assets = ROOT / 'dist/release'; assets.mkdir(exist_ok=True)
    name = f'anime-shelf-desktop-{VERSION}-windows-x64.zip'
    archive = assets / name
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for file in sorted(output.rglob('*')):
            if file.is_file(): z.write(file, 'AnimeShelf/' + file.relative_to(output).as_posix())
    with zipfile.ZipFile(archive) as z: assert z.testzip() is None
    digest = sha(archive)
    (assets / (name + '.sha256')).write_text(digest + '  ' + name + '\n', encoding='utf-8')
    manifest = {'schemaVersion': 1, 'packageName': 'com.animeshelf.desktop', 'versionName': VERSION, 'platform': 'windows-x64',
                'zipUrl': f'https://github.com/{REPOSITORY}/releases/download/{TAG}/{name}', 'zipSha256': digest,
                'zipBytes': archive.stat().st_size, 'notes': ['Windows 11 桌面版，包含运行环境，解压后运行。', '基于手机版 0.1.13 的片库、播放、离线下载与备份功能。', '首次使用请在本机验证网络、解码和显示效果。'], 'sourceCommit': source}
    (assets / 'desktop-release.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=REPO).decode().split('\0')
    paths = [p for p in tracked if p and (p.startswith('desktop/') or p in ('.github/workflows/build-desktop.yml', 'app/src/main/java/com/animeshelf/app/BiliClient.java', 'app/src/main/java/com/animeshelf/app/Models.java', 'tests/ParserSelfTest.java'))]
    source_zip = assets / f'anime-shelf-desktop-{VERSION}-source.zip'
    with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in paths: z.write(REPO / name, name)
    receipt = {'schemaVersion': 1, 'sourceCommit': source, 'version': VERSION, 'platform': 'windows-x64', 'tag': TAG,
               'zip': {'name': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest},
               'exe': {'name': executable.name, 'bytes': executable.stat().st_size, 'sha256': sha(executable), 'authenticode': signature.get('status', 'unknown')},
               'sourceArchive': {'name': source_zip.name, 'bytes': source_zip.stat().st_size, 'sha256': sha(source_zip)},
               'tests': {key: int(tests.attrib.get(key, 0)) for key in ('tests', 'errors', 'failures', 'skipped')},
               'parserAssertions': 132, 'nativeSmoke': smoke, 'nativeWidgetSmoke': widget, 'uiSmoke': ui,
               'sources': [{'path': name, 'sha256': sha(REPO / name), 'bytes': (REPO / name).stat().st_size} for name in paths]}
    (REPORTS / 'desktop-verification.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    shutil.copyfile(assets / 'desktop-release.json', REPORTS / 'desktop-release.json')
    shutil.copyfile(ROOT / 'build-lock.json', REPORTS / 'build-lock.json')
    print(json.dumps({'zip': receipt['zip'], 'tests': receipt['tests'], 'sourceCommit': source}, indent=2))

if __name__ == '__main__': main()
