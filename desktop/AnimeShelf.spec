# PyInstaller 6 onedir build. Keep native libraries replaceable and licenses visible.
from pathlib import Path
from PyInstaller.utils.hooks import copy_metadata

root = Path(SPECPATH)
datas = [
    (str(root / "runtime/java"), "runtime/java"),
    (str(root / "runtime/mpv"), "runtime/mpv"),
    (str(root / "runtime/ffmpeg"), "runtime/ffmpeg"),
    (str(root / "bridge/anime-bridge.jar"), "bridge"),
    (str(root / "licenses"), "licenses"),
    (str(root / "build-lock.json"), "."),
]
for package in ("PySide6-Essentials", "shiboken6", "python-mpv", "qrcode"):
    datas += copy_metadata(package)
a = Analysis([str(root / "launch.py")], pathex=[str(root)], binaries=[], datas=datas,
    hiddenimports=["PySide6.QtCore", "PySide6.QtGui", "PySide6.QtWidgets", "PySide6.QtNetwork", "qrcode", "mpv"],
    hookspath=[], hooksconfig={}, runtime_hooks=[],
    excludes=["PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtQml", "PySide6.QtQuick", "tkinter"],
    noarchive=False, optimize=1)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="AnimeShelf", debug=False,
    bootloader_ignore_signals=False, strip=False, upx=False, console=False,
    disable_windowed_traceback=False, contents_directory="_internal")
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="AnimeShelf")
