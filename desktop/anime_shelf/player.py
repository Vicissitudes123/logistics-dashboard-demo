"""Windows native libmpv player with a Qt control surface.

The video stays in one native HWND throughout navigation/fullscreen changes. A
translucent owned tool window supplies controls above Direct3D without relying on
Qt child-widget stacking above an embedded native window. The host must keep the
PlayerWidget in the same top-level window when toggling fullscreen.
"""
from __future__ import annotations
from dataclasses import replace
import os
from pathlib import Path
import re
import sys
import time
from typing import Callable

from PySide6.QtCore import QEvent, QPoint, Qt, QTimer, Signal, Slot
from PySide6.QtGui import QColor, QImage, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import (QApplication, QFrame, QHBoxLayout, QLabel, QMenu,
                              QMessageBox, QSizePolicy, QSlider, QToolButton,
                              QVBoxLayout, QWidget)
from .player_policy import (ALL, DOUBLE_TAP, HOLD, Gesture, PlaybackSourceError,
                            bounded_number, format_time, safe_error, settings_values,
                            source_plan, media_proxy)
from .preview import PreviewWorker, runtime_root
from .ui_logic import THEMES

_DLL_HANDLES: list = []


def prepare_mpv():
    """Locate the bundled library before importing python-mpv. No PATH install needed."""
    root = runtime_root()
    if os.name == 'nt':
        paths = [root / 'runtime/mpv', Path(sys.executable).parent / 'runtime/mpv', Path(sys.executable).parent]
        found = False
        for path in paths:
            if any((path / name).is_file() for name in ('mpv-2.dll', 'libmpv-2.dll', 'mpv-1.dll')):
                absolute = str(path.resolve())
                os.environ['PATH'] = absolute + os.pathsep + os.environ.get('PATH', '')
                if hasattr(os, 'add_dll_directory'):
                    _DLL_HANDLES.append(os.add_dll_directory(absolute))
                found = True
                break
        if not found:
            raise RuntimeError('播放器组件缺失，请解压完整的电脑版安装 ZIP 后启动。')
    import mpv
    return mpv


def _button(text, description, callback, width=48):
    button = QToolButton()
    button.setText(text)
    button.setToolTip(description)
    button.setAccessibleName(description)
    button.setMinimumSize(width, 48)
    button.setCursor(Qt.CursorShape.PointingHandCursor)
    button.setFocusPolicy(Qt.FocusPolicy.NoFocus)
    button.clicked.connect(lambda checked=False: callback())
    return button


class _SeekSlider(QSlider):
    def _position(self, event):
        usable = max(1, self.width() - 16)
        ratio = min(1, max(0, (event.position().x() - 8) / usable))
        self.setSliderPosition(round(self.minimum() + ratio * (self.maximum() - self.minimum())))

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.setSliderDown(True)
            self._position(event)
            event.accept()
        else:
            super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self.isSliderDown():
            self._position(event)
            event.accept()
        else:
            super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and self.isSliderDown():
            self._position(event)
            self.setSliderDown(False)
            event.accept()
        else:
            super().mouseReleaseEvent(event)


class _ControlLayer(QWidget):
    def __init__(self, owner):
        flags = (Qt.WindowType.Tool | Qt.WindowType.FramelessWindowHint |
                 Qt.WindowType.WindowDoesNotAcceptFocus)
        super().__init__(owner, flags)
        self.owner = owner
        self.setObjectName('playerControlLayer')
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setAttribute(Qt.WidgetAttribute.WA_AcceptTouchEvents)
        self.setMouseTracking(True)
        self._last_touch_up = 0.0
        self._last_touch_point = (0.0, 0.0)
        self._skip_touch_up = False
        self.setStyleSheet('''
            QWidget#playerControlLayer {background:rgba(0,0,0,1);color:white;}
            QToolButton {background:transparent;color:white;border:none;padding:4px;font-size:20px;}
            QToolButton:hover {color:#91b8ff;background:rgba(255,255,255,22);border-radius:8px;}
            QToolButton:disabled {color:#777d88;}
            QLabel {color:white;background:transparent;}
            QFrame#controlGradient {background:rgba(5,8,15,165);border-radius:10px;}
            QSlider::groove:horizontal {height:5px;background:rgba(255,255,255,90);border-radius:2px;}
            QSlider::sub-page:horizontal {background:#8eb7ff;border-radius:2px;}
            QSlider::handle:horizontal {background:white;border:0px;width:15px;margin:-5px 0;border-radius:7px;}
            QFrame#previewCard {background:rgba(15,20,32,220);border-radius:10px;}
        ''')
        self._base_style = self.styleSheet()
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 8, 16, 8)
        self.header = QFrame()
        self.header.setObjectName('controlGradient')
        header = QHBoxLayout(self.header)
        header.setContentsMargins(4, 0, 6, 0)
        self.back = _button('‹', '退出全屏', owner._back)
        header.addWidget(self.back)
        titles = QVBoxLayout()
        titles.setSpacing(3)
        self.title = QLabel('番匣')
        self.title.setTextFormat(Qt.TextFormat.PlainText)
        self.title.setStyleSheet('font-size:18px;font-weight:600;')
        self.title.setMinimumWidth(0)
        self.title.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        self.subtitle = QLabel('')
        self.subtitle.setTextFormat(Qt.TextFormat.PlainText)
        self.subtitle.setStyleSheet('font-size:12px;color:#c4ccdb;')
        self.subtitle.setMinimumWidth(0)
        self.subtitle.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
        titles.addWidget(self.title)
        titles.addWidget(self.subtitle)
        header.addLayout(titles, 1)
        self.home = _button('⌂', '回到片库', owner.requestHome.emit)
        header.addWidget(self.home)
        layout.addWidget(self.header)
        layout.addStretch(1)
        middle = QHBoxLayout()
        self.lock = _button('◇', '锁定播放器', owner.toggle_lock)
        middle.addWidget(self.lock)
        middle.addStretch(1)
        self.preview = QFrame()
        self.preview.setObjectName('previewCard')
        preview = QVBoxLayout(self.preview)
        preview.setContentsMargins(10, 8, 10, 8)
        self.preview_image = QLabel()
        self.preview_image.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview_image.setFixedSize(256, 144)
        self.feedback = QLabel()
        self.feedback.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.feedback.setStyleSheet('font-size:17px;')
        preview.addWidget(self.preview_image)
        preview.addWidget(self.feedback)
        self.preview.hide()
        middle.addWidget(self.preview)
        middle.addStretch(1)
        middle.addSpacing(48)
        layout.addLayout(middle)
        layout.addStretch(1)
        self.bottom = QFrame()
        self.bottom.setObjectName('controlGradient')
        bottom = QVBoxLayout(self.bottom)
        bottom.setContentsMargins(10, 4, 10, 0)
        bottom.setSpacing(1)
        timeline = QHBoxLayout()
        self.current = QLabel('00:00')
        self.total = QLabel('00:00')
        self.current.setMinimumWidth(48)
        self.total.setMinimumWidth(48)
        self.timeline = _SeekSlider(Qt.Orientation.Horizontal)
        self.timeline.setRange(0, 1_000_000)
        self.timeline.setMinimumHeight(30)
        self.timeline.setAccessibleName('播放进度')
        self.timeline.sliderPressed.connect(owner._scrub_started)
        self.timeline.sliderMoved.connect(owner._scrub_moved)
        self.timeline.sliderReleased.connect(owner._scrub_finished)
        timeline.addWidget(self.current)
        timeline.addWidget(self.timeline, 1)
        timeline.addWidget(self.total)
        bottom.addLayout(timeline)
        actions = QHBoxLayout()
        actions.setSpacing(4)
        self.previous = _button('⏮', '上一集', lambda: owner.requestEpisode.emit(-1))
        self.play = _button('▶', '播放 / 暂停', owner.toggle_playback)
        self.next = _button('⏭', '下一集', lambda: owner.requestEpisode.emit(1))
        self.speed = _button('1.0×', '播放速度', owner.show_speed_menu, 66)
        self.episodes = _button('选集', '选择剧集', owner.requestEpisodes.emit, 58)
        self.display = _button('画面', '显示方式与视频信息', owner.show_display_menu, 58)
        self.more = _button('⋯', '更多播放设置', owner.show_more_menu)
        self.quality = _button('清晰度', '选择清晰度', owner.requestQuality.emit, 76)
        self.fullscreen = _button('⛶', '全屏', owner.toggle_fullscreen)
        for button in (self.previous, self.play, self.next):
            actions.addWidget(button)
        actions.addStretch(1)
        for button in (self.speed, self.episodes, self.display, self.more, self.quality, self.fullscreen):
            actions.addWidget(button)
        bottom.addLayout(actions)
        layout.addWidget(self.bottom)

    def paintEvent(self, event):
        # One alpha unit keeps the otherwise transparent HWND hit-testable on
        # Windows. Controls themselves paint normally above the Direct3D HWND.
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(0, 0, 0, 1))
        painter.end()
        super().paintEvent(event)

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.owner._pointer_down(event.position().x(), event.position().y())
            event.accept()
        else:
            super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        self.owner._pointer_move(event.position().x(), event.position().y())
        event.accept()

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.owner._pointer_up(event.position().x(), event.position().y())
            event.accept()
        else:
            super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event):
        self.owner._double_click()
        event.accept()

    def wheelEvent(self, event):
        if not self.owner.locked:
            self.owner.set_volume(self.owner.volume + (5 if event.angleDelta().y() > 0 else -5))
        event.accept()

    def event(self, event):
        if event.type() in (QEvent.Type.TouchBegin, QEvent.Type.TouchUpdate, QEvent.Type.TouchEnd, QEvent.Type.TouchCancel):
            points = event.points()
            if event.type() == QEvent.Type.TouchCancel or len(points) != 1:
                self.owner.cancel_interaction()
            elif event.type() == QEvent.Type.TouchBegin:
                x, y = points[0].position().x(), points[0].position().y()
                previous_x, previous_y = self._last_touch_point
                if time.monotonic() - self._last_touch_up < QApplication.doubleClickInterval() / 1000 and abs(x - previous_x) + abs(y - previous_y) < 32:
                    self.owner._double_click()
                    self._skip_touch_up = True
                    self._last_touch_up = 0
                else:
                    self.owner._pointer_down(x, y)
            elif event.type() == QEvent.Type.TouchUpdate:
                self.owner._pointer_move(points[0].position().x(), points[0].position().y())
            else:
                if self._skip_touch_up:
                    self._skip_touch_up = False
                else:
                    x, y = points[0].position().x(), points[0].position().y()
                    self.owner._pointer_up(x, y)
                    self._last_touch_up = time.monotonic()
                    self._last_touch_point = (x, y)
            event.accept()
            return True
        return super().event(event)


class PlayerWidget(QWidget):
    progressChanged = Signal(int, int)
    ended = Signal()
    ready = Signal()
    error = Signal(str)
    requestEpisode = Signal(int)
    requestQuality = Signal()
    requestEpisodes = Signal()
    requestHome = Signal()
    fullscreenChanged = Signal(bool)
    speedChanged = Signal(float)
    userSeeked = Signal(int)
    _propertyChanged = Signal(str, object)
    _nativeEvent = Signal(str, object)
    _previewReady = Signal(int, object)

    def __init__(self, parent=None, media_factory: Callable | None = None):
        super().__init__(parent)
        self.setObjectName('nativePlayer')
        self.setStyleSheet('QWidget#nativePlayer {background:#000;}')
        self.setMinimumSize(320, 180)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self._surface = QFrame(self)
        self._surface.setAttribute(Qt.WidgetAttribute.WA_NativeWindow)
        self._surface.setStyleSheet('background:black;')
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self._surface)
        self._media_factory = media_factory
        self._engine = None
        self._plan = None
        self._episode, self._quality = {}, {}
        self._position, self._duration = 0, 0
        self._paused = True
        self._loading = False
        self._disposed = False
        self._ended = False
        self._video_index = self._audio_index = 0
        self._active_entry = None
        self._expected_entry = None
        self._event_entry = None
        self._retired_entry = -1
        self._want_position = 0
        self._want_paused = False
        self._last_media_error = ''
        self._settings = settings_values({})
        self._speed, self._volume, self._brightness, self._zoom = 1.0, 80.0, 0.0, 0.0
        self._boost_previous = None
        self._gesture = None
        self._suppress_click = False
        self._scrubbing = False
        self._controls_visible = True
        self._fullscreen = False
        self.locked = False
        self._layer = _ControlLayer(self)
        self._preview_worker = PreviewWorker()
        self._preview_target = 0
        self._preview_timer = QTimer(self)
        self._preview_timer.setSingleShot(True)
        self._preview_timer.setInterval(160)
        self._preview_timer.timeout.connect(self._request_preview)
        self._click_timer = QTimer(self)
        self._click_timer.setSingleShot(True)
        self._click_timer.setInterval(QApplication.doubleClickInterval())
        self._click_timer.timeout.connect(self._toggle_controls)
        self._hold_timer = QTimer(self)
        self._hold_timer.setSingleShot(True)
        self._hold_timer.setInterval(450)
        self._hold_timer.timeout.connect(self._start_boost)
        self._hide_timer = QTimer(self)
        self._hide_timer.setSingleShot(True)
        self._hide_timer.timeout.connect(self._hide_controls)
        self._feedback_timer = QTimer(self)
        self._feedback_timer.setSingleShot(True)
        self._feedback_timer.setInterval(900)
        self._feedback_timer.timeout.connect(self._hide_feedback)
        self._sync_timer = QTimer(self)
        self._sync_timer.setInterval(80)
        self._sync_timer.timeout.connect(self._sync_overlay)
        self._sync_timer.start()
        self._save_timer = QTimer(self)
        self._save_timer.setInterval(750)
        self._save_timer.timeout.connect(self._emit_progress)
        self._save_timer.start()
        self._propertyChanged.connect(self._property_changed)
        self._nativeEvent.connect(self._native_event)
        self._previewReady.connect(self._preview_ready)
        self._shortcuts = []
        for key, callback in [('Space', self.toggle_playback), ('F', self.toggle_fullscreen),
                              ('Escape', self._back), ('Left', lambda: self._user_seek(self._position - 5000)),
                              ('Right', lambda: self._user_seek(self._position + 5000)),
                              ('Up', lambda: self.set_volume(self.volume + 5)),
                              ('Down', lambda: self.set_volume(self.volume - 5))]:
            shortcut = QShortcut(QKeySequence(key), self)
            shortcut.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
            shortcut.activated.connect(callback)
            self._shortcuts.append(shortcut)
        application = QApplication.instance()
        if application is not None:
            application.installEventFilter(self)

    @property
    def position_ms(self):
        return self._position

    @property
    def duration_ms(self):
        return self._duration

    @property
    def is_playing(self):
        return self._plan is not None and not self._paused and not self._loading

    @property
    def speed(self):
        return self._speed

    @property
    def volume(self):
        return self._volume

    @property
    def is_fullscreen(self):
        return self._fullscreen

    def set_settings(self, settings):
        self.cancel_interaction()
        self._settings = settings_values(settings or {})
        accent = THEMES.get((settings or {}).get('theme'), THEMES['blue'])[1]
        self._layer.setStyleSheet(self._layer._base_style.replace('#8eb7ff', accent).replace('#91b8ff', accent))
        self.set_speed(self._settings['speed'], persist=False)
        self._schedule_hide()

    def _ensure_engine(self):
        if self._engine is not None:
            return
        factory = self._media_factory
        if factory is None:
            factory = prepare_mpv().MPV
        options = dict(wid=str(int(self._surface.winId())), config=False, load_scripts=False,
                       osc=False, ytdl=False, input_default_bindings=False, input_vo_keyboard=False,
                       input_cursor=False, keep_open=True, idle=True, hwdec='auto-safe',
                       network_timeout=8, cache=True, demuxer_max_bytes='32MiB',
                       audio_file_auto='no', sub_auto='no', access_references=False,
                       demuxer_max_back_bytes='8MiB', cookies=False,
                       log_handler=self._native_log, loglevel='warn')
        if os.name == 'nt':
            options.update(vo='gpu-next', gpu_api='d3d11', gpu_context='d3d11', gpu_sw=True, target_colorspace_hint=True)
        engine = factory(**options)
        self._engine = engine
        for name in ('time-pos', 'duration', 'pause', 'eof-reached', 'speed', 'volume', 'video-params', 'video-out-params'):
            engine.observe_property(name, lambda key, value: self._propertyChanged.emit(key, value))
        @engine.event_callback('file-loaded', 'end-file', 'start-file')
        def media_event(event):
            try:
                event_id = int(event.event_id.value)
                if event_id == 8:
                    self._nativeEvent.emit('loaded', self._event_entry)
                elif event_id == 6:
                    self._event_entry = int(event.data.playlist_entry_id)
                    self._nativeEvent.emit('start', self._event_entry)
                else:
                    self._nativeEvent.emit('end', {'entry': int(event.data.playlist_entry_id),
                                                  'reason': int(event.data.reason), 'error': int(event.data.error)})
            except Exception:
                pass
        self._native_callback = media_event

    def _native_log(self, level, prefix, text):
        # Never forward raw native logs: they commonly contain signed source URLs.
        match = re.search(r'(?i)HTTP (?:error |status )?([45]\d\d)', str(text))
        if match:
            self._last_media_error = f'HTTP {match.group(1)}'

    def _set(self, name, value):
        if self._engine is not None:
            try:
                self._engine[name] = value
                return True
            except Exception:
                return False
        return False

    def _get(self, name, fallback=None):
        try:
            # python-mpv's [] API addresses options/NAME, not properties.
            # Tracks, playback position and decoder metadata are properties.
            return self._engine._get_property(name) if self._engine is not None else fallback
        except Exception:
            return fallback

    def load(self, episode, quality=None, offline_task=None, start_ms=0, paused=False):
        self.cancel_interaction()
        self._click_timer.stop()
        self.locked = False
        try:
            plan = source_plan(episode, quality, offline_task)
            self._ensure_engine()
        except Exception as exc:
            # The UI may already be naming the new episode. Never let old media
            # continue or emit its progress under that identity after a failed load.
            self._plan = None
            self._loading = False
            self._paused = True
            self._position = max(0, int(start_ms))
            self._duration = 0
            if self._engine is not None:
                try:
                    self._engine.command('stop')
                except Exception:
                    pass
            self._layer.hide()
            self.error.emit(safe_error(exc) if isinstance(exc, PlaybackSourceError) else '播放器无法启动，请确认已完整解压安装包并更新显卡驱动。')
            return False
        self._episode = dict(episode)
        self._quality = dict(quality or {})
        self._plan = plan
        self._video_index = self._audio_index = 0
        self._want_position = max(0, int(start_ms))
        self._want_paused = bool(paused)
        self._position = self._want_position
        self._duration = 0
        self._ended = False
        self._last_media_error = ''
        self._layer.title.setText(str(episode.get('seriesTitle') or episode.get('title') or '番匣')
                                  + (f" · 第 {episode.get('index')} 集" if episode.get('index') else ''))
        self._layer.subtitle.setText(str(episode.get('title', '')) + ' · ' + str((quality or {}).get('label', '本地视频'))
                                     + (' · 离线' if plan.offline else ' · 在线'))
        self._layer.quality.setText(self._quality_label())
        self.show_controls()
        return self._load_candidate()

    def _quality_label(self):
        qn = self._quality.get('qn')
        names = {127: '8K', 126: '杜比视界', 125: 'HDR', 120: '4K', 116: '1080P60',
                 112: '1080P+', 80: '1080P', 74: '720P60', 64: '720P', 32: '480P', 16: '360P'}
        return names.get(qn, '本地' if self._plan and self._plan.offline else '清晰度')

    def _load_candidate(self):
        if self._plan is None or self._disposed:
            return
        self._loading = True
        self._paused = True
        for previous in (self._active_entry, self._event_entry):
            if isinstance(previous, int):
                self._retired_entry = max(self._retired_entry, previous)
        self._active_entry = None
        self._expected_entry = None
        self._show_feedback('正在载入…')
        self._feedback_timer.stop()
        plan = self._plan
        video = plan.videos[self._video_index]
        audio = plan.audios[self._audio_index] if plan.audios else None
        try:
            # Array-valued options avoid comma/URL escaping in loadfile option strings.
            protocols = 'file,crypto,data' if plan.offline else 'http,https,tcp,tls,crypto,data'
            if not self._set('demuxer-lavf-o', {'protocol_whitelist': protocols}):
                raise PlaybackSourceError('播放器无法应用媒体访问限制，请重新启动后重试。')
            if not self._set('audio-files', [audio] if audio else []):
                raise RuntimeError('Unable to configure paired audio')
            proxy = media_proxy(video, plan.offline)
            if not self._set('http-proxy', proxy):
                raise PlaybackSourceError('播放器无法应用系统代理设置，请重新启动后重试。')
            self._set('cookies', False)
            if not self._set('user-agent', plan.headers.get('User-Agent', '')):
                raise PlaybackSourceError('播放器无法设置媒体请求标识，请重新启动后重试。')
            if not self._set('http-header-fields', [f'{k}: {v}' for k, v in plan.headers.items() if k != 'User-Agent']):
                raise PlaybackSourceError('播放器无法设置媒体请求信息，请重新启动后重试。')
            self._set('pause', True)
            self._set('speed', self._speed)
            self._set('volume', self._volume)
            self._set('brightness', self._brightness)
            self._engine.command('loadfile', video, 'replace')
            playlist = self._get('playlist', []) or []
            entries = [row for row in playlist if isinstance(row, dict) and isinstance(row.get('id'), int)]
            if entries:
                requested = next((row for row in entries if row.get('current')), entries[0])
                self._expected_entry = requested['id']
            return True
        except Exception as exc:
            self._loading = False
            self._paused = True
            self._plan = None
            try:
                self._engine.command('stop')
            except Exception:
                pass
            self._layer.hide()
            self.error.emit(safe_error(exc))
            return False

    @Slot(str, object)
    def _native_event(self, name, value):
        if self._disposed or self._plan is None:
            return
        if name == 'start':
            if not isinstance(value, int) or value <= self._retired_entry:
                return
            if self._expected_entry is not None and value != self._expected_entry:
                return
            self._active_entry = value
        elif name == 'loaded':
            if not self._loading or self._active_entry is None or value != self._active_entry:
                return
            tracks = self._get('track-list', []) or []
            external_audio = [t for t in tracks if isinstance(t, dict) and t.get('type') == 'audio' and t.get('external')]
            if self._plan.audios and not external_audio:
                if self._audio_index + 1 < len(self._plan.audios) and not self._plan.offline:
                    self._audio_index += 1
                    self._load_candidate()
                    return
                self._loading = False
                self._set('pause', True)
                self.error.emit('音轨未能载入，已暂停播放。请刷新清晰度地址或检查离线音轨。')
                return
            if self._plan.audios and external_audio and external_audio[0].get('id') is not None:
                self._set('aid', external_audio[0]['id'])
            self._duration = max(0, int(float(self._get('duration', 0) or 0) * 1000))
            self._loading = False
            if self._want_position:
                self.seek(self._want_position)
            self._paused = self._want_paused
            self._set('pause', self._paused)
            self._set('speed', self._speed)
            self._hide_feedback()
            self._refresh_controls()
            self._schedule_hide()
            self.ready.emit()
            self._emit_progress()
        elif name == 'end':
            if self._active_entry is None or value.get('entry') != self._active_entry:
                return
            if value.get('reason') == 0:
                self._finish()
            elif value.get('reason') == 4:
                self._want_position = self._position
                self._want_paused = self._paused if not self._loading else self._want_paused
                if not self._plan.offline and self._video_index + 1 < len(self._plan.videos):
                    self._video_index += 1
                    self._load_candidate()
                    return
                self._loading = False
                self._paused = True
                self.show_controls()
                self._hide_feedback()
                self.error.emit(safe_error(self._last_media_error or 'Media request failed'))

    @Slot(str, object)
    def _property_changed(self, name, value):
        if self._disposed or self._plan is None:
            return
        if name == 'time-pos' and value is not None and not self._loading:
            self._position = max(0, int(float(value) * 1000))
            if self._duration and self._position < self._duration - 1000:
                self._ended = False
        elif name == 'duration' and value is not None:
            self._duration = max(0, int(float(value) * 1000))
        elif name == 'pause' and value is not None and not self._loading:
            self._paused = bool(value)
        elif name == 'volume' and value is not None:
            self._volume = bounded_number(value, self._volume, 0, 100)
        elif name == 'eof-reached' and value and not self._loading:
            self._finish()
        self._refresh_controls()

    def _finish(self):
        if self._ended:
            return
        self._ended = True
        self._paused = True
        if self._duration:
            self._position = self._duration
        self.cancel_interaction()
        self._emit_progress()
        self.show_controls()
        self.ended.emit()

    def _refresh_controls(self):
        self._layer.play.setText('▶' if self._paused else 'Ⅱ')
        self._layer.play.setAccessibleName('播放' if self._paused else '暂停')
        self._layer.current.setText(format_time(self._position))
        self._layer.total.setText(format_time(self._duration))
        if not self._scrubbing and not (self._gesture and self._gesture.mode == 'seek'):
            self._layer.timeline.setValue(round(self._position / self._duration * 1_000_000) if self._duration else 0)
        self._layer.timeline.setEnabled(self._duration > 0 and not self.locked)
        self._layer.speed.setText(f'{self._speed:g}×')
        self._layer.lock.setText('🔒' if self.locked else '🔓')
        self._layer.lock.setAccessibleName('解锁播放器' if self.locked else '锁定播放器')

    def _emit_progress(self):
        if self._plan is not None and not self._loading:
            self.progressChanged.emit(min(2_147_483_647, self._position), min(2_147_483_647, self._duration))

    def pause(self):
        self.cancel_interaction()
        self._paused = True
        self._want_paused = True
        self._set('pause', True)
        self._emit_progress()
        self.show_controls()

    def resume(self):
        if self._plan is None:
            return
        self._paused = False
        self._want_paused = False
        self._set('pause', False)
        self._refresh_controls()
        self._schedule_hide()

    def toggle_playback(self):
        if self.locked:
            return
        self.pause() if not self._paused else self.resume()

    def seek(self, position_ms):
        if self._engine is None or self._plan is None:
            return
        value = max(0, int(position_ms))
        if self._duration:
            value = min(self._duration, value)
        try:
            self._engine.command('seek', value / 1000, 'absolute+exact')
            self._position = value
            self._want_position = value
            self._ended = False
            self._refresh_controls()
            self._emit_progress()
        except Exception:
            self.error.emit('暂时无法跳转到该位置，请等待视频载入后重试。')

    seek_to = seek

    def _user_seek(self, position_ms):
        if self.locked or self._duration <= 0:
            return
        self.seek(position_ms)
        self.userSeeked.emit(self._position)

    def set_speed(self, value, persist=True):
        self._restore_boost()
        self._speed = bounded_number(value, 1, .25, 3)
        self._set('speed', self._speed)
        self._refresh_controls()
        if persist:
            self.speedChanged.emit(self._speed)

    def set_volume(self, value):
        if self.locked:
            return
        self._volume = bounded_number(value, self._volume, 0, 100)
        self._set('volume', self._volume)
        self._show_feedback(f'音量 {round(self._volume)}%')

    def stop(self):
        self.cancel_interaction()
        self._emit_progress()
        self._plan = None
        self._loading = False
        self._paused = True
        self.locked = False
        if self._engine is not None:
            try:
                self._engine.command('stop')
            except Exception:
                pass
        self._layer.hide()

    def dispose(self):
        if self._disposed:
            return
        self.stop()
        self._disposed = True
        for timer in (self._sync_timer, self._save_timer, self._hold_timer, self._click_timer,
                      self._preview_timer, self._feedback_timer, self._hide_timer):
            timer.stop()
        self._preview_worker.close()
        application = QApplication.instance()
        if application is not None:
            application.removeEventFilter(self)
        self._layer.close()
        if self._engine is not None:
            try:
                self._engine.terminate()
            except Exception:
                pass
            self._engine = None

    def closeEvent(self, event):
        self.dispose()
        super().closeEvent(event)

    def hideEvent(self, event):
        self.cancel_interaction()
        self._layer.hide()
        super().hideEvent(event)

    def resizeEvent(self, event):
        self.cancel_interaction()
        super().resizeEvent(event)
        self._sync_overlay()

    def eventFilter(self, watched, event):
        if event.type() in (QEvent.Type.ApplicationDeactivate, QEvent.Type.WindowDeactivate):
            if watched in (self.window(), QApplication.instance()):
                self.cancel_interaction()
                self._layer.hide()
        return super().eventFilter(watched, event)

    def _sync_overlay(self):
        if getattr(self, '_disposed', False) or not hasattr(self, '_layer'):
            return
        window = self.window()
        visible = (self.isVisible() and self._plan is not None and not window.isMinimized()
                   and QApplication.applicationState() == Qt.ApplicationState.ApplicationActive)
        active = QApplication.activeWindow()
        if active is not None and active not in (window, self._layer):
            visible = False
        if not visible:
            self._layer.hide()
            return
        point = self._surface.mapToGlobal(QPoint(0, 0))
        self._layer.setGeometry(point.x(), point.y(), self._surface.width(), self._surface.height())
        if not self._layer.isVisible():
            self._layer.show()
        self._layer.back.setVisible(self._fullscreen)
        self._layer.header.setVisible(self._controls_visible and not self.locked)
        self._layer.bottom.setVisible(self._controls_visible and not self.locked)
        self._layer.lock.setVisible(self._fullscreen or self.locked)
        compact = self._surface.width() < 650
        self._layer.display.setVisible(not compact)
        self._layer.speed.setVisible(not compact)
        self._layer.episodes.setVisible(not compact)
        self._layer.more.setVisible(compact)
        self._layer.previous.setVisible(self._surface.width() >= 420)
        self._layer.next.setVisible(self._surface.width() >= 420)
        self._layer.speed.setMinimumWidth(55 if compact else 66)
        self._layer.episodes.setMinimumWidth(48 if compact else 58)
        self._layer.quality.setMinimumWidth(64 if compact else 76)

    def set_fullscreen(self, value):
        self.cancel_interaction()
        self._fullscreen = bool(value)
        if not self._fullscreen:
            self.locked = False
        self._layer.fullscreen.setAccessibleName('退出全屏' if value else '全屏')
        self.show_controls()
        self._sync_overlay()

    def toggle_fullscreen(self):
        if not self.locked:
            self.cancel_interaction()
            self.fullscreenChanged.emit(not self._fullscreen)

    def _back(self):
        if self.locked:
            self.toggle_lock()
        elif self._fullscreen:
            self.fullscreenChanged.emit(False)

    def toggle_lock(self):
        self.cancel_interaction()
        self.locked = not self.locked
        if self.locked:
            self._controls_visible = False
        else:
            self.show_controls()
        self._refresh_controls()
        self._sync_overlay()

    def show_controls(self):
        self._controls_visible = True
        self._refresh_controls()
        self._sync_overlay()
        self._schedule_hide()

    def _hide_controls(self):
        if self._gesture or self._scrubbing or self._paused:
            return
        self._controls_visible = False
        self._sync_overlay()

    def _toggle_controls(self):
        if self.locked:
            return
        self._controls_visible = not self._controls_visible
        self._sync_overlay()
        self._schedule_hide()

    def _schedule_hide(self):
        self._hide_timer.stop()
        timeout = self._settings['controlsTimeoutMs']
        if timeout and self._controls_visible and not self._paused and not self.locked:
            self._hide_timer.start(timeout)

    def _show_feedback(self, text, image=False):
        self._layer.feedback.setText(text)
        self._layer.preview_image.setVisible(image)
        if not image:
            self._layer.preview_image.clear()
        self._layer.preview.show()
        self._feedback_timer.start()

    def _hide_feedback(self):
        self._layer.preview.hide()
        self._layer.preview_image.clear()

    def _pointer_down(self, x, y):
        if self.locked or self._plan is None:
            return
        self.setFocus(Qt.FocusReason.MouseFocusReason)
        self._feedback_timer.stop()
        self._hide_timer.stop()
        self._gesture = Gesture(x, y, max(1, self._surface.width()), max(1, self._surface.height()),
                                self._position, self._duration, self._volume, self._brightness,
                                self._settings['gestureMask'], self._settings['seekSeconds'])
        self._suppress_click = False
        if self._settings['gestureMask'] & HOLD and self.is_playing:
            self._hold_timer.start()

    def _pointer_move(self, x, y):
        if self._gesture is None or self.locked:
            return
        mode, value = self._gesture.move(x, y)
        if mode != 'pending':
            self._hold_timer.stop()
            self._restore_boost()
            self._suppress_click = True
        if mode == 'seek':
            self._show_seek_preview(int(value))
        elif mode == 'volume':
            self.set_volume(value)
        elif mode == 'brightness':
            self._brightness = value
            self._set('brightness', value)
            self._show_feedback(f'画面亮度 {round(value):+d}')

    def _pointer_up(self, x, y):
        self._hold_timer.stop()
        boosted = self._boost_previous is not None
        self._restore_boost()
        gesture, self._gesture = self._gesture, None
        if gesture is None:
            return
        target = gesture.finish()
        self._cancel_preview()
        if target is not None:
            self._user_seek(target)
        elif not boosted and not self._suppress_click and not self.locked:
            self._click_timer.start()
        self._hide_feedback()
        self._schedule_hide()

    def _double_click(self):
        self._click_timer.stop()
        self.cancel_interaction()
        self._suppress_click = True
        if self._settings['gestureMask'] & DOUBLE_TAP and not self.locked:
            self.toggle_playback()

    def _start_boost(self):
        if self._gesture is None or self._gesture.mode != 'pending' or not self.is_playing or self.locked:
            return
        self._boost_previous = self._speed
        boosted = max(self._speed, self._settings['holdSpeed'])
        self._set('speed', boosted)
        self._suppress_click = True
        self._show_feedback(f'{boosted:g}× 临时加速')
        self._feedback_timer.stop()

    def _restore_boost(self):
        if self._boost_previous is not None:
            original, self._boost_previous = self._boost_previous, None
            self._set('speed', original)

    def cancel_interaction(self):
        if not hasattr(self, '_hold_timer'):
            return
        self._hold_timer.stop()
        self._click_timer.stop()
        self._restore_boost()
        if self._gesture is not None:
            self._gesture.finish(cancelled=True)
            self._gesture = None
        self._scrubbing = False
        self._cancel_preview()
        self._hide_feedback()

    def _scrub_started(self):
        self.cancel_interaction()
        self._scrubbing = True
        self._hide_timer.stop()

    def _scrub_moved(self, value):
        if self.locked or self._duration <= 0:
            return
        target = round(value / 1_000_000 * self._duration)
        self._show_seek_preview(target)

    def _scrub_finished(self):
        if self._scrubbing:
            self._scrubbing = False
            self._user_seek(round(self._layer.timeline.value() / 1_000_000 * self._duration))
        self._cancel_preview()
        self._hide_feedback()
        self._schedule_hide()

    def _show_seek_preview(self, position):
        self._preview_target = position
        self._show_feedback(f'{format_time(position)} / {format_time(self._duration)}', image=False)
        self._feedback_timer.stop()
        self._preview_timer.start()

    def _request_preview(self):
        if self._plan is None:
            return
        # The active candidate may be a backup after a primary CDN failure.
        plan = replace(self._plan, videos=(self._plan.videos[self._video_index],))
        self._preview_worker.request(plan, self._preview_target,
                                     lambda generation, data: self._previewReady.emit(generation, data))

    @Slot(int, object)
    def _preview_ready(self, generation, data):
        if self._disposed or generation != self._preview_worker.generation or not data:
            return
        if not self._scrubbing and not (self._gesture and self._gesture.mode == 'seek'):
            return
        image = QImage.fromData(data)
        if image.isNull():
            return
        self._layer.preview_image.setPixmap(QPixmap.fromImage(image).scaled(256, 144,
            Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
        self._layer.preview_image.show()
        self._layer.preview.show()

    def _cancel_preview(self):
        self._preview_timer.stop()
        self._preview_worker.cancel()

    def show_more_menu(self):
        menu = QMenu(self)
        for title, callback in [('播放速度', self.show_speed_menu),
                                ('选集', self.requestEpisodes.emit),
                                ('画面设置 / HDR', self.show_display_menu),
                                ('上一集', lambda: self.requestEpisode.emit(-1)),
                                ('下一集', lambda: self.requestEpisode.emit(1))]:
            menu.addAction(title).triggered.connect(lambda checked=False, action=callback: action())
        self.cancel_interaction()
        menu.exec(self._layer.more.mapToGlobal(QPoint(0, 0)))
        self.show_controls()

    def show_speed_menu(self):
        menu = QMenu(self)
        for speed in (.25, .5, .75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3):
            action = menu.addAction(f'{speed:g}×')
            action.setCheckable(True)
            action.setChecked(abs(speed - self._speed) < .001)
            action.triggered.connect(lambda checked=False, rate=speed: self.set_speed(rate))
        self.cancel_interaction()
        menu.exec(self._layer.speed.mapToGlobal(QPoint(0, 0)))
        self.show_controls()

    def show_display_menu(self):
        menu = QMenu(self)
        for title, callback in [('适应画面', lambda: self._display_mode(False)),
                                ('铺满画面（裁切边缘）', lambda: self._display_mode(True)),
                                ('缩小 −', lambda: self._set_zoom(self._zoom - .25)),
                                ('放大 ＋', lambda: self._set_zoom(self._zoom + .25)),
                                ('恢复 100%', lambda: self._set_zoom(0)),
                                ('视频信息 / HDR', self.show_video_information)]:
            menu.addAction(title).triggered.connect(callback)
        self.cancel_interaction()
        menu.exec(self._layer.display.mapToGlobal(QPoint(0, 0)))
        self.show_controls()

    def _display_mode(self, fill):
        self._zoom = 0
        self._set('video-zoom', 0)
        self._set('panscan', 1 if fill else 0)

    def _set_zoom(self, zoom):
        self._zoom = min(2, max(-2, zoom))
        self._set('video-zoom', self._zoom)
        self._show_feedback(f'画面缩放 {round(2 ** self._zoom * 100)}%')

    def video_information(self):
        params = self._get('video-params', {}) or {}
        output = self._get('video-out-params', {}) or {}
        video = {k: v for k, v in params.items() if k in ('w', 'h', 'pixelformat', 'hw-pixelformat', 'colorlevels', 'primaries', 'gamma', 'sig-peak', 'light')}
        actual_output = {k: v for k, v in output.items() if k in ('w', 'h', 'pixelformat', 'colorlevels', 'primaries', 'gamma')}
        transfer = str(params.get('gamma', '')).lower()
        return {'input': video, 'rendererInput': actual_output,
                'codec': str(self._get('video-codec', '') or ''),
                'hardwareDecoder': str(self._get('hwdec-current', '') or ''),
                'inputHdr': transfer in ('pq', 'hlg', 'smpte2084', 'arib-std-b67'),
                'displayHdrConfirmed': False,
                'displayNote': '源视频色彩信息来自解码器；显示器实际 HDR 输出仍取决于 Windows HDR、显示器与显卡。',
                'offline': bool(self._plan and self._plan.offline)}

    def show_video_information(self):
        info = self.video_information()
        source = info['input']
        rows = [f"实际解码：{source.get('w', '未知')} × {source.get('h', '未知')}",
                f"编码：{info['codec'] or '读取中'}", f"硬件解码：{info['hardwareDecoder'] or '未知'}",
                f"色域 / 传递函数：{source.get('primaries', '未知')} / {source.get('gamma', '未知')}",
                '输入信号：' + ('HDR' if info['inputHdr'] else 'SDR 或尚未识别'), '', info['displayNote']]
        QMessageBox.information(self, '视频信息 / HDR', '\n'.join(rows))
