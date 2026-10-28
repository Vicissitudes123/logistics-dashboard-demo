from __future__ import annotations
import hashlib
import io
import re
import time
import threading
import urllib.parse
import urllib.request
import weakref
from pathlib import Path
from PySide6.QtCore import Qt, Signal, QObject, QRunnable, QThreadPool, QBuffer, QByteArray, QIODevice, QRectF, QSize, QTimer
from PySide6.QtGui import QPainter, QPainterPath, QPixmap, QImageReader, QLinearGradient, QColor, QPen, QFont
from PySide6.QtWidgets import QFrame, QLabel

class TaskSignals(QObject):
    done = Signal(object, object)

class Task(QRunnable):
    def __init__(self, function):
        super().__init__(); self.function = function; self.signals = TaskSignals()
    def run(self):
        try: result, failure = self.function(), None
        except Exception as error: result, failure = None, error
        try: self.signals.done.emit(result, failure)
        except RuntimeError: pass

class SelectableCard(QFrame):
    clicked = Signal()
    longPressed = Signal()
    def __init__(self, parent=None):
        super().__init__(parent); self.setObjectName("Card"); self.setCursor(Qt.PointingHandCursor)
        self._held = False; self._pressed = False; self._timer = QTimer(self); self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._long_press)
    def _long_press(self):
        if self._pressed: self._held = True; self.longPressed.emit()
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._pressed = True; self._held = False; self._timer.start(500); event.accept()
        else: super().mousePressEvent(event)
    def mouseReleaseEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._timer.stop()
            if self._pressed and not self._held and self.rect().contains(event.position().toPoint()): self.clicked.emit()
            self._pressed = False; event.accept()
        else: super().mouseReleaseEvent(event)
    def leaveEvent(self, event):
        self._timer.stop(); self._pressed = False; super().leaveEvent(event)

class Cover(QLabel):
    def __init__(self, title: str, width=176, height=264, parent=None):
        super().__init__(parent); self.title = title; self.image = None; self.setFixedSize(width, height)
        self.setAccessibleName("番剧封面：" + title)
    def set_cover_bytes(self, data: bytes):
        device = QBuffer(); device.setData(QByteArray(data)); device.open(QIODevice.ReadOnly)
        reader = QImageReader(device); size = reader.size()
        if size.width() <= 0 or size.height() <= 0 or size.width() * size.height() > 32_000_000: return
        reader.setAutoTransform(True); reader.setScaledSize(size.scaled(QSize(1000, 1500), Qt.KeepAspectRatio))
        image = reader.read()
        if not image.isNull(): self.image = QPixmap.fromImage(image); self.update()
    def paintEvent(self, event):
        painter = QPainter(self); painter.setRenderHint(QPainter.Antialiasing)
        shape = QPainterPath(); shape.addRoundedRect(QRectF(self.rect()), 14, 14); painter.setClipPath(shape)
        if self.image:
            image = self.image.scaled(QSize(int(self.width()*self.devicePixelRatioF()),int(self.height()*self.devicePixelRatioF())), Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
            image.setDevicePixelRatio(self.devicePixelRatioF()); logical_w = image.width()/image.devicePixelRatio(); logical_h = image.height()/image.devicePixelRatio()
            painter.drawPixmap(int((self.width()-logical_w)/2), int((self.height()-logical_h)/2), image)
        else:
            gradient = QLinearGradient(0, 0, self.width(), self.height()); gradient.setColorAt(0, QColor("#354d75")); gradient.setColorAt(1, QColor("#242238")); painter.fillRect(self.rect(), gradient)
            painter.setPen(QColor("#d6e4ff")); painter.setFont(QFont("Microsoft YaHei", 30, QFont.Bold)); painter.drawText(self.rect(), Qt.AlignCenter, self.title[:1] or "番")

class CoverLoader(QObject):
    def __init__(self, cache_dir: Path, enabled=True, parent=None):
        super().__init__(parent); self.directory = cache_dir; self.directory.mkdir(parents=True, exist_ok=True)
        self.enabled = enabled; self._closed = threading.Event(); self.pool = QThreadPool(self); self.pool.setMaxThreadCount(3); self.pending = {}; self.jobs = set()
    def close(self):
        self._closed.set(); self.pool.clear(); self.pending.clear()
    @staticmethod
    def normalized_url(url):
        parsed = urllib.parse.urlsplit(str(url)); host = parsed.hostname or ""
        if parsed.scheme != "https" or not re.fullmatch(r"i\d+\.hdslb\.com", host) or parsed.query or parsed.fragment or parsed.username or parsed.port not in (None,443): return ""
        if not re.fullmatch(r"(?i)/bfs/[a-z0-9/_-]+\.(jpg|jpeg|png|webp|avif)(@[a-z0-9_.-]+)?", parsed.path): return ""
        return f"https://{host}{parsed.path.split('@',1)[0]}"
    def request(self, url, cover: Cover):
        if self._closed.is_set(): return
        url = self.normalized_url(url)
        if not url: return
        target = self.directory / (hashlib.sha256(url.encode()).hexdigest() + ".image")
        if target.is_file():
            try: cover.set_cover_bytes(target.read_bytes())
            except (OSError, RuntimeError): pass
            return
        if not self.enabled: return
        receiver = weakref.ref(cover)
        if url in self.pending: self.pending[url].append(receiver); return
        if len(self.pending) >= 64: return
        self.pending[url] = [receiver]
        def fetch():
            if self._closed.is_set(): raise RuntimeError('封面加载已取消')
            class Redirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(inner, req, fp, code, msg, headers, newurl):
                    if not CoverLoader.normalized_url(newurl): raise ValueError("封面重定向无效")
                    return super().redirect_request(req,fp,code,msg,headers,newurl)
            request = urllib.request.Request(url, headers={"User-Agent":"AnimeShelf/0.1.13.1","Referer":"https://www.bilibili.com/"})
            with urllib.request.build_opener(Redirect()).open(request, timeout=15) as response:
                data = response.read(8*1024*1024+1)
            if self._closed.is_set(): raise RuntimeError('封面加载已取消')
            if len(data)>8*1024*1024: raise ValueError("封面过大")
            target.write_bytes(data)
            files = sorted(self.directory.glob("*.image"),key=lambda p:p.stat().st_mtime)
            total = sum(p.stat().st_size for p in files)
            for old in files:
                if total <= 128*1024*1024: break
                if old != target: total -= old.stat().st_size; old.unlink(missing_ok=True)
            return data
        job = Task(fetch); self.jobs.add(job)
        def complete(data,error):
            receivers = self.pending.pop(url, []); self.jobs.discard(job)
            if error is None and not self._closed.is_set():
                for reference in receivers:
                    view = reference()
                    if view is not None:
                        try: view.set_cover_bytes(data)
                        except RuntimeError: pass
        job.signals.done.connect(complete); self.pool.start(job)

class QrCanvas(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent); self.setFixedSize(264,264); self.matrix = None
    def set_url(self, url):
        import qrcode
        code = qrcode.QRCode(border=4,error_correction=qrcode.constants.ERROR_CORRECT_M); code.add_data(url);code.make(fit=True)
        self.matrix = code.get_matrix(); self.update()
    def paintEvent(self,event):
        painter = QPainter(self); painter.fillRect(self.rect(),Qt.white)
        if not self.matrix: return
        count=len(self.matrix); size=self.width()/count; painter.setPen(Qt.NoPen);painter.setBrush(Qt.black)
        for y,row in enumerate(self.matrix):
            for x,dark in enumerate(row):
                if dark:painter.drawRect(QRectF(x*size,y*size,size+0.1,size+0.1))
