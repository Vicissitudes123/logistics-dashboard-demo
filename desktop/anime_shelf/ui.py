"""Native desktop library, playback and download workflows.

Network work uses a bounded Qt worker pool; selection generations discard stale results.
"""
from __future__ import annotations
import copy
import hashlib
import json
import os
import shutil
import sys
import threading
import time
import uuid
from pathlib import Path
from PySide6.QtCore import Qt, QTimer, QUrl, QSize, QPoint
from PySide6.QtGui import QAction, QColor, QDesktopServices, QIcon, QPainter, QPixmap, QPolygon
from PySide6.QtWidgets import (
    QApplication,QMainWindow,QWidget,QFrame,QLabel,QPushButton,QToolButton,QLineEdit,
    QComboBox,QCheckBox,QSpinBox,QDoubleSpinBox,QSlider,QProgressBar,QScrollArea,
    QStackedWidget,QHBoxLayout,QVBoxLayout,QGridLayout,QFormLayout,QDialog,
    QDialogButtonBox,QMessageBox,QFileDialog,QInputDialog,QMenu,QSystemTrayIcon,
    QProgressDialog,QListWidget,QListWidgetItem,QAbstractItemView,QSizePolicy,
)
from . import __version__
from .ui_logic import (THEMES,matching_title,ordered_episodes,profile_of,matching_quality,
    watched,library_groups,format_time,size_text,estimate_bytes,aggregate_progress,safe_error)
from .widgets import Task,SelectableCard,Cover,CoverLoader,QrCanvas


def label(text, role="", wrap=False):
    view=QLabel(text);view.setWordWrap(wrap)
    if role:view.setObjectName(role)
    return view


def button(text, action, primary=False):
    result=QPushButton(text);result.setCursor(Qt.PointingHandCursor);result.setMinimumHeight(38)
    result.setProperty("primary",primary);result.clicked.connect(lambda checked=False:action());return result


def clear_layout(layout):
    while layout.count():
        item=layout.takeAt(0)
        if item.widget():item.widget().deleteLater()
        elif item.layout():clear_layout(item.layout())


def panel(layout=None):
    view=QFrame();view.setObjectName("Card")
    if layout is not None:view.setLayout(layout)
    return view


def app_icon():
    pixmap=QPixmap(64,64);pixmap.fill(Qt.transparent);painter=QPainter(pixmap);painter.setRenderHint(QPainter.Antialiasing)
    painter.setPen(Qt.NoPen);painter.setBrush(QColor("#8db5ff"));painter.drawRoundedRect(4,4,56,56,14,14)
    painter.setBrush(QColor("#101725"));painter.drawPolygon(QPolygon([QPoint(25,18),QPoint(25,46),QPoint(46,32)]));painter.end()
    return QIcon(pixmap)


class MainWindow(QMainWindow):
    def __init__(self,data_dir:Path,store=None,downloads=None,player_factory=None,client_factory=None,smoke=False):
        super().__init__();self.data_dir=Path(data_dir);self.data_dir.mkdir(parents=True,exist_ok=True);self.smoke=smoke
        from .store import Store
        from .bili import BiliClient
        from .auth import CredentialStore
        from .downloads import DownloadManager
        from .network import network_allowed
        from .player import PlayerWidget
        self.store=store or Store(self.data_dir/'library.json');self.client_factory=client_factory or BiliClient
        self.credentials=CredentialStore(self.data_dir/'session.dat');self.cookie="";self.login_name=""
        try:self.cookie=self.credentials.load()
        except Exception:pass
        self.downloads=downloads or DownloadManager(self.data_dir/'offline',resolver=lambda episode,quality:self.client().qualities(episode),network_allowed=network_allowed)
        self.downloads.configure(wifi_only=bool(self.store.settings.get('wifiOnlyDownloads',True)))
        self.preferences_path=self.data_dir/'desktop-settings.json'
        try:self.preferences=json.loads(self.preferences_path.read_text('utf-8'))
        except (OSError,ValueError):self.preferences={}
        if not isinstance(self.preferences,dict):self.preferences={}
        self._dead=False;self._quitting=False;self._shutdown_event=threading.Event();self._generation=0;self._jobs=set();self._batch_cancel=None
        from PySide6.QtCore import QThreadPool
        self.pool=QThreadPool(self);self.pool.setMaxThreadCount(4)
        self.cover_loader=CoverLoader(self.data_dir/'covers',enabled=not smoke,parent=self)
        self.current_episode=None;self.current_quality=None;self.current_offline=None;self._playback_ready=False;self._playback_serial=0;self._pending_play_error=None;self._active_page='library'
        self.detail_series_id="";self.download_series_id=None;self._selected_episodes=set();self._episode_selection=False
        self._selected_downloads=set();self._download_selection=False;self._downloads_signature=None;self._download_rows={};self._group_rows={}
        self._full_screen=False;self._saved_window_state=None;self._episode_finished=False;self._skip_disabled=False;self._intro_applied=False
        self._skip_deadline=None;self._skip_undo=None;self._program_seek=False;self._sleep_deadline=None;self._sleep_after_episode=False
        self._backup_busy=False;self._last_backup_digest="";self._last_progress_save=0;self._last_resize_columns=0
        self.setWindowTitle('番匣 · Windows');self.setWindowIcon(app_icon());self.resize(1240,820);self.setMinimumSize(780,540)
        self.root=QWidget();self.setCentralWidget(self.root);self.shell=QHBoxLayout(self.root);self.shell.setContentsMargins(16,16,16,16);self.shell.setSpacing(20)
        self.nav=QFrame();self.nav.setObjectName('Navigation');self.nav.setFixedWidth(176);nav_layout=QVBoxLayout(self.nav);nav_layout.setContentsMargins(16,24,16,18)
        nav_layout.addWidget(label('番匣','Brand'));nav_layout.addWidget(label('喜欢的故事，接着看','Muted'));nav_layout.addSpacing(32)
        self.nav_buttons={}
        for key,text in [('library','◈  我的番剧'),('downloads','↓  离线下载'),('settings','⚙  设置')]:
            item=button(text,lambda checked=False,k=key:self.navigate(k));item.setObjectName('NavButton');nav_layout.addWidget(item);self.nav_buttons[key]=item
        nav_layout.addStretch();nav_layout.addWidget(label('Windows 版\n'+__version__,'Muted'));self.shell.addWidget(self.nav)
        self.content=QWidget();content_layout=QVBoxLayout(self.content);content_layout.setContentsMargins(0,0,0,0);content_layout.setSpacing(14);self.shell.addWidget(self.content,1)
        self.header=QWidget();header_layout=QHBoxLayout(self.header);header_layout.setContentsMargins(0,0,0,0)
        self.page_title=label('我的番剧','PageTitle');header_layout.addWidget(self.page_title);header_layout.addStretch()
        self.login_button=button('B站登录',self.login_dialog);header_layout.addWidget(self.login_button);header_layout.addWidget(button('＋ 添加',self.add_menu,True));content_layout.addWidget(self.header)
        self.status=label('','Muted');self.status.setMinimumHeight(20);content_layout.addWidget(self.status)
        self.stack=QStackedWidget();content_layout.addWidget(self.stack,1)
        self.library_page=QWidget();self.library_layout=QVBoxLayout(self.library_page);self.library_layout.setContentsMargins(0,0,0,0)
        search_row=QHBoxLayout();self.search=QLineEdit();self.search.setPlaceholderText('搜索番剧，例如：高木 / JOJO');self.search.setClearButtonEnabled(True);self.search.setMinimumHeight(44);self.search.textChanged.connect(self.render_library)
        search_row.addWidget(self.search,1);self.library_filter=QComboBox();self.library_filter.addItem('全部','all');self.library_filter.addItem('未看完','unfinished');self.library_filter.addItem('已看完','watched');self.library_filter.addItem('有离线','offline')
        self.library_filter.setCurrentIndex(max(0,self.library_filter.findData(self.store.settings.get('libraryFilter','all'))));self.library_filter.currentIndexChanged.connect(self._filter_changed);search_row.addWidget(self.library_filter)
        search_row.addWidget(button('刷新番剧',self.refresh_library));self.library_layout.addLayout(search_row)
        self.library_scroll=QScrollArea();self.library_scroll.setWidgetResizable(True);self.library_scroll.setFrameShape(QFrame.NoFrame)
        self.library_body=QWidget();self.library_cards=QVBoxLayout(self.library_body);self.library_cards.setContentsMargins(0,0,8,0);self.library_cards.setSpacing(16);self.library_scroll.setWidget(self.library_body);self.library_layout.addWidget(self.library_scroll)
        self.stack.addWidget(self.library_page)
        self.watch_page=QWidget();self.watch_layout=QHBoxLayout(self.watch_page);self.watch_layout.setContentsMargins(0,0,0,0);self.watch_layout.setSpacing(14)
        self.player_column=QWidget();self.player_column_layout=QVBoxLayout(self.player_column);self.player_column_layout.setContentsMargins(0,0,0,0);self.player_column_layout.setSpacing(8)
        self.player=(player_factory or PlayerWidget)();self.player.setMinimumSize(320,240);self.player_column_layout.addWidget(self.player,1)
        self.play_notice=QFrame();notice_layout=QHBoxLayout(self.play_notice);self.play_notice_label=label('','Muted',True);notice_layout.addWidget(self.play_notice_label,1);self.play_notice_button=button('取消',self.cancel_skip);notice_layout.addWidget(self.play_notice_button);self.play_notice.hide();self.player_column_layout.addWidget(self.play_notice)
        self.watch_tools=QWidget();tools_layout=QHBoxLayout(self.watch_tools);tools_layout.setContentsMargins(0,0,0,0)
        tools_layout.addWidget(button('← 首页',lambda:self.navigate('library')));tools_layout.addWidget(button('下载本集',self.download_current));tools_layout.addWidget(button('片头片尾',self.skip_dialog));tools_layout.addWidget(button('定时停止',self.sleep_dialog));tools_layout.addStretch();self.player_column_layout.addWidget(self.watch_tools)
        self.watch_layout.addWidget(self.player_column,1)
        self.episode_panel=QFrame();self.episode_panel.setObjectName('Sidebar');self.episode_panel.setMinimumWidth(276);self.episode_panel.setMaximumWidth(370);self.episode_layout=QVBoxLayout(self.episode_panel);self.episode_layout.setContentsMargins(16,16,16,16)
        self.episode_title=label('选集','SectionTitle',True);self.episode_layout.addWidget(self.episode_title)
        self.episode_subtitle=label('','Muted',True);self.episode_layout.addWidget(self.episode_subtitle)
        self.episode_actions=QHBoxLayout();self.episode_multi_button=button('批量下载',self.begin_episode_selection);self.episode_actions.addWidget(self.episode_multi_button);self.episode_actions.addWidget(button('片头片尾',self.skip_dialog));self.episode_layout.addLayout(self.episode_actions)
        self.episode_scroll=QScrollArea();self.episode_scroll.setWidgetResizable(True);self.episode_scroll.setFrameShape(QFrame.NoFrame);self.episode_body=QWidget();self.episode_cards=QVBoxLayout(self.episode_body);self.episode_cards.setContentsMargins(0,0,4,0);self.episode_cards.setSpacing(8);self.episode_scroll.setWidget(self.episode_body);self.episode_layout.addWidget(self.episode_scroll,1)
        self.episode_selection_bar=QWidget();selection=QVBoxLayout(self.episode_selection_bar);selection.setContentsMargins(0,4,0,0);selection_row=QHBoxLayout();selection_row.addWidget(button('全选',self.select_all_episodes));selection_row.addWidget(button('取消',self.end_episode_selection));selection.addLayout(selection_row);self.selected_download_button=button('下载所选',self.download_selected,True);selection.addWidget(self.selected_download_button);self.episode_selection_bar.hide();self.episode_layout.addWidget(self.episode_selection_bar)
        self.watch_layout.addWidget(self.episode_panel);self.stack.addWidget(self.watch_page)
        self.download_page=QWidget();self.download_layout=QVBoxLayout(self.download_page);self.download_layout.setContentsMargins(0,0,0,0)
        top=QHBoxLayout();self.download_back=button('← 全部离线番剧',lambda:self.open_downloads(None));top.addWidget(self.download_back);top.addStretch();top.addWidget(button('暂停全部',self.pause_all));top.addWidget(button('继续全部',self.resume_all));top.addWidget(button('存储清理',self.storage_cleanup));self.download_layout.addLayout(top)
        self.download_summary=label('','Muted',True);self.download_layout.addWidget(self.download_summary)
        self.download_selection_bar=QWidget();bar=QHBoxLayout(self.download_selection_bar);bar.setContentsMargins(0,0,0,0);self.download_selection_label=label('');bar.addWidget(self.download_selection_label);bar.addStretch();bar.addWidget(button('全选',self.select_all_downloads));bar.addWidget(button('删除所选',self.delete_selected_downloads));bar.addWidget(button('取消',self.end_download_selection));self.download_selection_bar.hide();self.download_layout.addWidget(self.download_selection_bar)
        self.download_scroll=QScrollArea();self.download_scroll.setWidgetResizable(True);self.download_scroll.setFrameShape(QFrame.NoFrame);self.download_body=QWidget();self.download_cards=QVBoxLayout(self.download_body);self.download_cards.setContentsMargins(0,0,8,0);self.download_cards.setSpacing(12);self.download_scroll.setWidget(self.download_body);self.download_layout.addWidget(self.download_scroll,1);self.stack.addWidget(self.download_page)
        self.settings_page=QScrollArea();self.settings_page.setWidgetResizable(True);self.settings_page.setFrameShape(QFrame.NoFrame);self.settings_body=QWidget();self.settings_layout=QVBoxLayout(self.settings_body);self.settings_layout.setContentsMargins(0,0,8,0);self.settings_page.setWidget(self.settings_body);self.stack.addWidget(self.settings_page)
        self._connect_player();self.apply_theme();self.render_library();self.render_settings();self._setup_tray()
        self.poll=QTimer(self);self.poll.setInterval(1000);self.poll.timeout.connect(self._tick);self.poll.start()
        self.backup_timer=QTimer(self);self.backup_timer.setInterval(30000);self.backup_timer.timeout.connect(self.auto_backup);self.backup_timer.start()
        self.resize_timer=QTimer(self);self.resize_timer.setSingleShot(True);self.resize_timer.setInterval(120);self.resize_timer.timeout.connect(lambda:self.render_library() if self._active_page=='library' else None)
        self.player.set_settings(dict(self.store.settings))
        if not smoke:self.downloads.start()
        self.navigate('library')

    def client(self):return self.client_factory(self.cookie)
    def notice(self,text):self.status.setText(text)
    def show_error(self,title,error):
        if not self._dead:QMessageBox.warning(self,title,safe_error(error))
    def run_job(self,function,done,failed=None,generation=None):
        task=Task(function);self._jobs.add(task)
        def completed(result,error):
            self._jobs.discard(task)
            if self._dead or (generation is not None and generation!=self._generation):return
            if error is not None:
                if failed:failed(error)
                else:self.show_error('操作未完成',error)
            else:
                try:done(result)
                except Exception as problem:self.show_error('操作未完成',problem)
        task.signals.done.connect(completed);self.pool.start(task);return task
    def _filter_changed(self):
        self.store.settings['libraryFilter']=self.library_filter.currentData();self.store.save();self.render_library()
    def apply_theme(self):
        accent=THEMES.get(self.store.settings.get('theme','blue'),THEMES['blue'])[1]
        self.setStyleSheet(f'''
            QWidget {{ background:#0d111c;color:#f1f4fa;font-family:"Microsoft YaHei UI","Microsoft YaHei","Segoe UI";font-size:14px; }}
            QFrame#Navigation,QFrame#Sidebar {{background:#161c2b;border-radius:18px;}}
            QFrame#Card {{background:#1b2233;border:1px solid #293247;border-radius:16px;}}
            QFrame#Hero {{background:#1d2940;border:1px solid #354661;border-radius:20px;}}
            QLabel {{background:transparent;}} QLabel#Brand{{font-size:32px;font-weight:700;}} QLabel#PageTitle{{font-size:28px;font-weight:700;}}
            QLabel#SectionTitle{{font-size:21px;font-weight:600;}} QLabel#HeroTitle{{font-size:26px;font-weight:600;}} QLabel#Muted{{color:#a0aabd;font-size:13px;}}
            QPushButton,QToolButton{{background:#272f43;border:1px solid transparent;border-radius:10px;padding:8px 14px;color:#eef3ff;}}
            QPushButton:hover,QToolButton:hover{{background:#354159;border-color:#516580;}} QPushButton:disabled{{color:#66738b;}}
            QPushButton[primary="true"]{{background:{accent};color:#101b2b;font-weight:600;}} QPushButton[primary="true"]:hover{{background:#b4ceff;}}
            QPushButton#NavButton{{text-align:left;padding:15px 12px;}} QPushButton#NavButton[selected="true"]{{background:#283751;color:{accent};}}
            QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox{{background:#182031;border:1px solid #354159;border-radius:9px;padding:9px;selection-background-color:{accent};}}
            QLineEdit:focus{{border-color:{accent};}} QComboBox QAbstractItemView{{background:#1b2233;selection-background-color:#354159;}}
            QScrollArea{{border:0;}} QScrollBar:vertical{{width:9px;background:transparent;}} QScrollBar::handle:vertical{{background:#354159;border-radius:4px;min-height:26px;}} QScrollBar::add-line:vertical,QScrollBar::sub-line:vertical{{height:0;}}
            QProgressBar{{border:0;background:#30394d;border-radius:4px;height:8px;text-align:center;}} QProgressBar::chunk{{background:{accent};border-radius:4px;}}
            QCheckBox{{spacing:10px;padding:8px;}} QCheckBox::indicator{{width:18px;height:18px;}}
            QMenu{{background:#20283b;border:1px solid #354159;padding:7px;}} QMenu::item{{padding:10px 22px;}} QMenu::item:selected{{background:#354159;border-radius:5px;}}
            QDialog,QMessageBox{{background:#171e2d;}} QListWidget{{background:#171e2d;border:1px solid #354159;border-radius:10px;}} QListWidget::item{{padding:12px;}} QListWidget::item:selected{{background:#324767;}}
        ''')
    def _connect_player(self):
        self.player.progressChanged.connect(self._on_progress);
        if hasattr(self.player,'requestHome'):self.player.requestHome.connect(lambda:self.navigate('library'))
        self.player.ended.connect(self._on_ended);self.player.error.connect(self._play_error)
        self.player.requestEpisode.connect(self.relative_episode);self.player.requestQuality.connect(self.quality_menu);self.player.requestEpisodes.connect(self.toggle_episode_panel);self.player.fullscreenChanged.connect(self.set_fullscreen)
        if hasattr(self.player,'speedChanged'):self.player.speedChanged.connect(self._on_speed)
        if hasattr(self.player,'ready'):self.player.ready.connect(self._on_ready)
        if hasattr(self.player,'userSeeked'):self.player.userSeeked.connect(self._manual_seek)
    def _setup_tray(self):
        self.tray=None
        if self.smoke or not QSystemTrayIcon.isSystemTrayAvailable():return
        self.tray=QSystemTrayIcon(app_icon(),self);self.tray.setToolTip('番匣');menu=QMenu();menu.addAction('打开番匣',self.show_from_tray);menu.addAction('暂停全部下载',self.pause_all);menu.addAction('继续全部下载',self.resume_all);menu.addSeparator();menu.addAction('退出',self.quit_app);self.tray.setContextMenu(menu);self.tray.activated.connect(lambda reason:self.show_from_tray() if reason in (QSystemTrayIcon.Trigger,QSystemTrayIcon.DoubleClick) else None);self.tray.show()
    def show_from_tray(self):self.showNormal();self.raise_();self.activateWindow()
    def navigate(self,page):
        if self._dead:return
        if page!='watch':self._generation+=1;self._stop_playback();self.set_fullscreen(False)
        if self._batch_cancel:self._batch_cancel.set();self._batch_cancel=None
        self._active_page=page
        for key,item in self.nav_buttons.items():item.setProperty('selected',key==page);item.style().unpolish(item);item.style().polish(item)
        pages={'library':self.library_page,'watch':self.watch_page,'downloads':self.download_page,'settings':self.settings_page};self.stack.setCurrentWidget(pages[page]);self.page_title.setText({'library':'我的番剧','watch':'正在观看','downloads':'离线下载','settings':'设置'}[page])
        if page=='library':self.render_library()
        elif page=='downloads':self.render_downloads()
        elif page=='settings':self.render_settings()
    def render_library(self):
        if not hasattr(self,'library_cards'):return
        scroll=self.library_scroll.verticalScrollBar().value();clear_layout(self.library_cards)
        tasks=self.downloads.snapshot();offline_ids={t['episode'].get('id') for t in tasks if t.get('complete')}
        groups=library_groups(self.store.series(),self.store.progress,self.store.data.get('lastSeries',''),self.search.text(),self.library_filter.currentData(),offline_ids)
        if not groups:
            empty=panel(QVBoxLayout());empty.layout().setContentsMargins(40,70,40,70);empty.layout().addWidget(label('把喜欢的故事放进番匣','HeroTitle'));empty.layout().addWidget(label('添加 B站分享链接或导入本地视频。已有片库也可以从备份恢复。','Muted',True));empty.layout().addWidget(button('＋ 添加番剧',self.add_menu,True));self.library_cards.addWidget(empty)
        available=max(400,self.library_scroll.viewport().width()-24);columns=max(1,min(5,available//215));self._last_resize_columns=columns
        for heading,series_items in groups:
            self.library_cards.addWidget(label(heading,'SectionTitle'))
            if heading=='最近观看':self.library_cards.addWidget(self._series_card(series_items[0],True));continue
            host=QWidget();grid=QGridLayout(host);grid.setContentsMargins(0,0,0,0);grid.setSpacing(16)
            for index,series in enumerate(series_items):grid.addWidget(self._series_card(series,False,max(170,min(250,(available-16*(columns-1))//columns))),index//columns,index%columns)
            for i in range(columns):grid.setColumnStretch(i,1)
            self.library_cards.addWidget(host)
        self.library_cards.addStretch();QTimer.singleShot(0,lambda:self.library_scroll.verticalScrollBar().setValue(scroll))
    def _series_card(self,series,hero=False,width=210):
        card=SelectableCard();card.setObjectName('Hero' if hero else 'Card');card.clicked.connect(lambda:self.open_series(series['id']));card.longPressed.connect(lambda:self.manage_series(series['id']))
        layout=QHBoxLayout(card) if hero else QVBoxLayout(card);layout.setContentsMargins(18,18,18,18);layout.setSpacing(16)
        cover=Cover(series.get('title',''),176 if hero else width-36,264 if hero else int((width-36)*1.45));self.cover_loader.request(series.get('coverUrl',''),cover);layout.addWidget(cover,0,Qt.AlignCenter if not hero else Qt.AlignTop)
        details=QVBoxLayout();details.setSpacing(12);title=label(series.get('title',''), 'HeroTitle' if hero else 'SectionTitle',True)
        if not hero:title.setMaximumHeight(64);title.setStyleSheet('font-size:17px;')
        details.addWidget(title);episodes=ordered_episodes(series);last=self.store.last_episode(series['id']);episode=next((e for e in episodes if e['id']==last),None);progress=self.store.progress(last) if last else {}
        details.addWidget(label(f"{len(episodes)} 集 · {'已看完' if watched(series,self.store.progress) else '观看中' if progress.get('updatedAt') else '未开始'}",'Muted'))
        if hero and episode:
            details.addWidget(label(f"第 {episode.get('index',1)} 集 · {format_time(progress.get('positionMs',0))}",'Muted'))
            bar=QProgressBar();bar.setRange(0,1000);bar.setTextVisible(False);bar.setFixedHeight(7);bar.setValue(int(progress.get('positionMs',0)*1000/max(1,progress.get('durationMs',0))));details.addWidget(bar)
        details.addStretch();actions=QHBoxLayout();actions.addWidget(button('继续观看' if progress.get('updatedAt') else '开始观看',lambda:self.open_series(series['id'],True),hero),1);actions.addWidget(button('⋯',lambda:self.manage_series(series['id'])));details.addLayout(actions)
        if hero:layout.addLayout(details,1)
        else:layout.addLayout(details)
        return card
    def add_menu(self):
        menu=QMenu(self);menu.addAction('粘贴 B站番剧 / 视频链接',self.import_link);menu.addAction('导入本地视频',self.import_local);menu.addAction('从片库备份恢复',self.import_backup);menu.exec(self.cursor().pos())
    def import_link(self):
        value,ok=QInputDialog.getText(self,'添加番剧','粘贴 B站分享内容、番剧链接或 BV 号')
        if not ok or not value.strip():return
        self.notice('正在读取番剧信息…')
        def loaded(series):self.store.save_series(series);self.notice('已加入：'+series.get('title',''));self.navigate('library')
        self.run_job(lambda:self.client().resolve(value),loaded)
    def import_local(self):
        files,_=QFileDialog.getOpenFileNames(self,'选择本地视频','','视频 (*.mp4 *.mkv *.webm *.mov *.avi *.m4v *.ts);;所有文件 (*)')
        if not files:return
        title,ok=QInputDialog.getText(self,'本地番剧名称','为这些视频命名',text=Path(files[0]).parent.name)
        if not ok or not title.strip():return
        series_id='local-'+uuid.uuid4().hex;episodes=[]
        import re
        for index,path in enumerate(sorted(files,key=lambda x:[int(p) if p.isdigit() else p.casefold() for p in re.split(r'(\d+)',Path(x).name)]),1):
            episodes.append({'id':'local-'+uuid.uuid4().hex,'seriesId':series_id,'seriesTitle':title.strip(),'index':index,'title':Path(path).stem,'localUri':str(Path(path).resolve()),'bvid':'','aid':0,'cid':0,'bangumi':False})
        self.store.save_series({'id':series_id,'title':title.strip(),'coverUrl':'','episodes':episodes});self.navigate('library');self.notice(f'已导入 {len(episodes)} 集本地视频')
    def manage_series(self,series_id):
        series=self.store.find_series(series_id)
        if not series:return
        menu=QMenu(self);menu.addAction('打开选集',lambda:self.open_series(series_id));menu.addAction('取消置顶' if series.get('pinned') else '置顶',lambda:self._manage_flag(series_id,'pin',not series.get('pinned')))
        menu.addAction('标记已看完',lambda:self._manage_flag(series_id,'watched','yes'));menu.addAction('标记未看完',lambda:self._manage_flag(series_id,'watched','no'));menu.addAction('根据进度判断看完',lambda:self._manage_flag(series_id,'watched','auto'))
        menu.addAction('刷新剧集',lambda:self.refresh_series(series_id));menu.addSeparator();menu.addAction('从首页移除',lambda:self.hide_series(series_id));menu.exec(self.cursor().pos())
    def _manage_flag(self,series_id,kind,value):
        if kind=='pin':self.store.set_pinned(series_id,value)
        else:self.store.set_watched(series_id,value)
        self.render_library()
    def hide_series(self,series_id):
        if QMessageBox.question(self,'从首页移除？','离线视频和观看记录会保留，可在设置中的「已移除番剧」恢复。')==QMessageBox.Yes:self.store.hide_series(series_id);self.render_library()
    def restore_hidden(self):
        hidden=[s for s in self.store.series() if s.get('hidden')]
        if not hidden:self.notice('没有已移除的番剧');return
        names=[s.get('title','') for s in hidden];chosen,ok=QInputDialog.getItem(self,'恢复番剧','选择要重新显示的番剧',names,editable=False)
        if ok:self.store.hide_series(hidden[names.index(chosen)]['id'],False);self.render_library();self.notice('已恢复到首页')
    def refresh_series(self,series_id):
        series=self.store.find_series(series_id)
        if not series:return
        if not any(not e.get('localUri') for e in series.get('episodes',[])):self.notice('本地视频无需检查新集');return
        def fresh(value):
            old=len(series.get('episodes',[]));was_hidden=series.get('hidden',False);self.store.save_series(value)
            if was_hidden:self.store.hide_series(series_id)
            updated=self.store.find_series(series_id);self.notice(f"刷新完成，新增 {max(0,len(updated.get('episodes',[]))-old)} 集");self.render_library()
            if self.detail_series_id==series_id:self.render_episodes()
        self.notice('正在检查新集…');self.run_job(lambda:self.client().resolve(series_id),fresh)
    def refresh_library(self):
        targets=[s for s in self.store.series() if not s.get('hidden') and any(not e.get('localUri') for e in s.get('episodes',[]))]
        if not targets:self.notice('没有需要刷新的在线番剧');return
        self.notice(f'正在检查 {len(targets)} 部番剧…')
        def work():
            results=[]
            for series in targets:
                if self._shutdown_event.is_set():break
                try:results.append((series['id'],self.client().resolve(series['id']),None))
                except Exception as error:results.append((series['id'],None,safe_error(error)))
            return results
        def completed(results):
            count=0;failures=0
            for series_id,fresh,error in results:
                existing=self.store.find_series(series_id)
                if error:failures+=1;continue
                if not existing:continue
                old_ids={e['id'] for e in existing.get('episodes',[])};hidden=existing.get('hidden',False);self.store.save_series(fresh)
                if hidden:self.store.hide_series(series_id)
                count+=sum(e['id'] not in old_ids for e in fresh.get('episodes',[]))
            self.render_library();self.notice(f'刷新完成，新增 {count} 集'+(f'；{failures} 部暂未更新' if failures else ''))
        self.run_job(work,completed)
    def login_dialog(self):
        from .auth import QrLogin
        dialog=QDialog(self);dialog.setWindowTitle('B站登录');dialog.setMinimumWidth(390);layout=QVBoxLayout(dialog)
        layout.addWidget(label('使用 B站 App 扫码登录','SectionTitle'));layout.addWidget(label('二维码由 B站官方接口生成。登录状态只保存在这台电脑。','Muted',True))
        qr=QrCanvas();layout.addWidget(qr,0,Qt.AlignHCenter);status=label('正在获取二维码…','Muted',True);layout.addWidget(status)
        buttons=QHBoxLayout();refresh=button('刷新二维码',lambda:generate());buttons.addWidget(refresh);buttons.addWidget(button('退出账号',lambda:self.logout(dialog)));buttons.addWidget(button('关闭',dialog.reject));layout.addLayout(buttons)
        state={'key':'','generation':0,'busy':False,'closed':False,'service':None};timer=QTimer(dialog);timer.setInterval(2200)
        def close():
            state['closed']=True;state['generation']+=1;timer.stop()
            if state['service']:state['service'].cancel()
        dialog.finished.connect(close)
        def generate():
            state['generation']+=1;stamp=state['generation'];state['key']='';state['busy']=False;timer.stop();status.setText('正在获取二维码…')
            if state['service']:state['service'].cancel()
            service=QrLogin();state['service']=service
            def ready(result):
                if state['closed'] or stamp!=state['generation']:return
                state['key']=result['key'];qr.set_url(result['url']);status.setText('请打开 B站 App 扫一扫，并在手机上确认。');timer.start()
            self.run_job(service.generate,ready,lambda error:status.setText(safe_error(error)) if not state['closed'] and stamp==state['generation'] else None)
        def poll():
            if state['busy'] or not state['key'] or state['closed']:return
            state['busy']=True;stamp=state['generation'];key=state['key'];service=state['service']
            def response(result):
                if state['closed'] or stamp!=state['generation']:return
                state['busy']=False
                status.setText(result.get('message','等待手机确认'))
                if result['status']=='expired':timer.stop();status.setText('二维码已过期，请刷新。')
                if result['status']=='success':
                    timer.stop();cookie=result.get('cookie','');status.setText('正在核验登录状态…')
                    def verified(name):
                        if state['closed'] or stamp!=state['generation']:return
                        if not name:status.setText('尚未确认登录成功，请刷新后重试。');return
                        self.credentials.save(cookie);self.cookie=cookie;self.login_name=name;self.login_button.setText(name);self.notice('登录成功');dialog.accept()
                    self.run_job(lambda:self.client_factory(cookie).verify_login(),verified,lambda error:status.setText(safe_error(error)) if not state['closed'] and stamp==state['generation'] else None)
            def failed(error):
                if stamp!=state['generation']:return
                state['busy']=False
                if not state['closed']:status.setText('网络暂未连接，稍后继续检查。')
            self.run_job(lambda:service.poll(key),response,failed)
        timer.timeout.connect(poll);generate();dialog.exec()
    def logout(self,dialog=None):
        self.credentials.clear();self.cookie='';self.login_name='';self.login_button.setText('B站登录');self.notice('已退出这台电脑上的账号')
        if dialog:dialog.reject()
    def open_series(self,series_id,autoplay=False):
        series=self.store.find_series(series_id)
        if not series:return
        if self.current_episode and self.current_episode.get('seriesId')!=series_id:self._stop_playback()
        self.detail_series_id=series_id;self._selected_episodes.clear();self._episode_selection=False;self.navigate('watch');self.episode_panel.show();self.render_episodes()
        if autoplay:
            episodes=ordered_episodes(series);last=self.store.last_episode(series_id);episode=next((e for e in episodes if e['id']==last),episodes[0] if episodes else None)
            if episode:self.select_episode(episode)
    def render_episodes(self):
        series=self.store.find_series(self.detail_series_id)
        if not series:return
        value=self.episode_scroll.verticalScrollBar().value();clear_layout(self.episode_cards);self.episode_title.setText(series.get('title',''));self.episode_subtitle.setText(f"{len(series.get('episodes',[]))} 集 · 点击播放，长按多选下载")
        complete={t['episode'].get('id') for t in self.downloads.snapshot() if t.get('complete')}
        for episode in ordered_episodes(series):
            card=SelectableCard();row=QHBoxLayout(card);row.setContentsMargins(12,12,12,12);number=label(f"{episode.get('index',0):02d}");number.setStyleSheet('color:#8db5ff;font-size:22px;');row.addWidget(number)
            detail=QVBoxLayout();title=label(episode.get('title',''),wrap=True);detail.addWidget(title);p=self.store.progress(episode['id']);parts=[]
            if self.current_episode and self.current_episode['id']==episode['id']:parts.append('正在观看')
            if episode['id'] in complete:parts.append('已下载')
            if episode.get('localUri'):parts.append('本地视频')
            if p.get('completed'):parts.append('已看完')
            elif p.get('positionMs',0)>0:parts.append(format_time(p['positionMs'])+' / '+format_time(p.get('durationMs',0)))
            detail.addWidget(label(' · '.join(parts) or '尚未观看','Muted',True));row.addLayout(detail,1)
            if self._episode_selection:
                check=QCheckBox();check.setChecked(episode['id'] in self._selected_episodes);check.toggled.connect(lambda selected,e=episode:self.toggle_episode_selection(e,selected));row.addWidget(check)
            card.clicked.connect(lambda e=episode:self.toggle_episode_selection(e) if self._episode_selection else self.select_episode(e));card.longPressed.connect(lambda e=episode:self.begin_episode_selection(e));self.episode_cards.addWidget(card)
        self.episode_cards.addStretch();self.episode_selection_bar.setVisible(self._episode_selection);self.selected_download_button.setText(f'下载所选（{len(self._selected_episodes)} 集）');self.selected_download_button.setEnabled(bool(self._selected_episodes));QTimer.singleShot(0,lambda:self.episode_scroll.verticalScrollBar().setValue(value))
    def begin_episode_selection(self,episode=None):
        self._episode_selection=True
        if isinstance(episode,dict) and not episode.get('localUri'):self._selected_episodes.add(episode['id'])
        self.render_episodes()
    def end_episode_selection(self):self._episode_selection=False;self._selected_episodes.clear();self.render_episodes()
    def toggle_episode_selection(self,episode,selected=None):
        if episode.get('localUri'):self.notice('本地视频无需下载');return
        key=episode['id'];selected=(key not in self._selected_episodes) if selected is None else selected
        if selected:self._selected_episodes.add(key)
        else:self._selected_episodes.discard(key)
        self.render_episodes()
    def select_all_episodes(self):
        series=self.store.find_series(self.detail_series_id) or {};self._selected_episodes={e['id'] for e in series.get('episodes',[]) if not e.get('localUri')};self.render_episodes()
    def toggle_episode_panel(self):self.episode_panel.setVisible(not self.episode_panel.isVisible() if self._full_screen else True);self.render_episodes()
    def select_episode(self,episode,force_quality=False):
        self._generation+=1;generation=self._generation;self._save_playback();self.detail_series_id=episode['seriesId']
        preference=self.store.playback_preference(episode['seriesId']) if hasattr(self.store,'playback_preference') else None
        preferred=preference.get('profile') if preference else self.store.settings.get('preferredQuality')
        if not force_quality:
            complete=self.downloads.get_complete(episode['id'],preferred)
            if complete:self._start_playback(episode,complete['quality'],complete);return
        if episode.get('localUri'):
            if not Path(episode['localUri']).is_file():self.show_error('本地视频不可用','文件已移动或删除，请重新导入。');return
            self._start_playback(episode,{'id':'local','label':'本地视频','qn':0,'videoUrl':'','audioUrl':''},None);return
        if preference and preference.get('offline') and not force_quality:
            if QMessageBox.question(self,'尚未离线保存','这一集没有完整离线版本。是否查询在线画质？')!=QMessageBox.Yes:return
        self.notice('正在读取可用画质…')
        def ready(qualities):
            if not qualities:self.show_error('暂无可用画质','接口没有返回可播放的清晰度。');return
            chosen=matching_quality(qualities,preferred) if self.store.settings.get('rememberQuality',True) else None
            if chosen and not force_quality:self._start_playback(episode,chosen,None)
            else:self.quality_dialog(episode,qualities)
        self.run_job(lambda:self.client().qualities(episode),ready,generation=generation)
    def quality_dialog(self,episode,qualities,download_only=False,download_episodes=None):
        dialog=QDialog(self);dialog.setWindowTitle('选择下载画质' if download_only else '清晰度与播放方式');dialog.resize(510,440);layout=QVBoxLayout(dialog);layout.addWidget(label(episode.get('seriesTitle',''),'SectionTitle',True));layout.addWidget(label(episode.get('title',''),'Muted',True))
        choices=QListWidget();layout.addWidget(choices,1)
        for q in qualities:
            expected,exact=estimate_bytes(q);subtitle=f"{q.get('width',0)} × {q.get('height',0)}" if q.get('width') else ''
            if expected>0:subtitle+=(' · ' if subtitle else '')+('大小 ' if exact else '预计 ')+size_text(expected)
            item=QListWidgetItem(q.get('label','未知画质')+ ('\n'+subtitle if subtitle else ''));item.setData(Qt.UserRole,q);choices.addItem(item)
        choices.setCurrentRow(0);layout.addWidget(label('仅列出当前接口实际返回的画质；HDR 输出还取决于屏幕、系统和解码器。','Muted',True))
        actions=QHBoxLayout();actions.addWidget(button('取消',dialog.reject))
        def selected():return choices.currentItem().data(Qt.UserRole) if choices.currentItem() else None
        if not download_only:
            def play():
                q=selected()
                if q:dialog.accept();self._start_playback(episode,q,None)
            actions.addWidget(button('在线播放',play,True))
            def offline():
                q=selected();task=self.downloads.get_complete(episode['id'],profile_of(q)) if q else None
                if not task or profile_of(task['quality'])!=profile_of(q):self.notice('所选画质尚未完整下载');return
                dialog.accept();self._start_playback(episode,task['quality'],task)
            actions.addWidget(button('离线播放',offline))
        def download():
            q=selected()
            if q:dialog.accept();self.prepare_downloads(download_episodes or [episode],q)
        actions.addWidget(button('下载所选画质',download,download_only));layout.addLayout(actions);dialog.exec()
    def _start_playback(self,episode,quality,offline_task):
        same=bool(self.current_episode and self.current_episode['id']==episode['id'] and self._playback_ready)
        resume=int(self.player.position_ms) if same else int(self.store.progress(episode['id']).get('positionMs',0))
        paused=not self.player.is_playing if same else False
        if not same and self.store.progress(episode['id']).get('completed'):resume=0
        self._save_playback();self._playback_ready=False;self._playback_serial+=1
        self.current_episode=copy.deepcopy(episode);self.current_quality=copy.deepcopy(quality);self.current_offline=offline_task
        self._episode_finished=False;self._skip_disabled=False;self._intro_applied=resume>1000;self._skip_deadline=None;self._skip_undo=None
        self.play_notice.hide();self.detail_series_id=episode['seriesId'];self._active_page='watch';self.stack.setCurrentWidget(self.watch_page)
        self.page_title.setText(episode.get('seriesTitle','正在观看'));self.player.set_settings(dict(self.store.settings))
        accepted=self.player.load(episode,quality,offline_task=offline_task,start_ms=resume,paused=paused)
        self.render_episodes()
        if accepted is False:
            self._playback_ready=False;self.notice('这一集暂未开始播放，可以重新选择画质或检查文件。');return False
        self.notice(('离线播放 · ' if offline_task else '')+episode.get('title',''));return True
    def _on_ready(self):
        if not self.current_episode:return
        self._playback_ready=True
        if self.current_quality and self.current_quality.get('qn',0)>0:self.store.set_playback_preference(self.current_episode['seriesId'],bool(self.current_offline),self.current_quality)
        self._apply_intro()
    def _save_playback(self):
        if self.current_episode and self._playback_ready:
            duration=max(0,int(self.player.duration_ms)) or int(self.store.progress(self.current_episode['id']).get('durationMs',0))
            position=duration if self._episode_finished and duration>0 else max(0,int(self.player.position_ms))
            self.store.save_progress(self.current_episode,position,duration)
    def _stop_playback(self):
        self._save_playback();self._playback_ready=False;self._playback_serial+=1;self.player.stop();self.current_episode=None;self.current_quality=None;self.current_offline=None;self._skip_deadline=None;self.play_notice.hide()
    def _on_progress(self,position,duration):
        if not self.current_episode or not self._playback_ready or self._episode_finished:return
        if time.monotonic()-self._last_progress_save>=2:
            self.store.save_progress(self.current_episode,position,duration);self._last_progress_save=time.monotonic()
        self._apply_intro();self._check_outro(position,duration)
    def _on_speed(self,speed):self.store.settings['speed']=float(speed);self.store.save()
    def _manual_seek(self,position):self._skip_disabled=True;self._skip_deadline=None;self.play_notice.hide();self._save_playback()
    def _play_error(self,message):
        self._save_playback();self._playback_ready=False;stamp=self._playback_serial
        if self._pending_play_error==stamp:return
        self._pending_play_error=stamp
        def present():
            if self._pending_play_error==stamp:self._pending_play_error=None
            if self._dead or stamp!=self._playback_serial or not self.current_episode:return
            dialog=QMessageBox(self);dialog.setWindowTitle('播放未能继续');dialog.setText(safe_error(message))
            retry=dialog.addButton('重新获取地址',QMessageBox.AcceptRole);dialog.addButton('关闭',QMessageBox.RejectRole);dialog.exec()
            if dialog.clickedButton()==retry and stamp==self._playback_serial and self.current_episode:self.select_episode(self.current_episode,True)
        # A native load can fail synchronously. Finish that transition before opening
        # a nested modal event loop, where another media selection could otherwise race.
        QTimer.singleShot(0,present)
    def relative_episode(self,delta):
        if not self.current_episode:return
        series=self.store.find_series(self.current_episode['seriesId']) or {};episodes=ordered_episodes(series);index=next((i for i,e in enumerate(episodes) if e['id']==self.current_episode['id']),-1)+delta
        if 0<=index<len(episodes):self.select_episode(episodes[index])
        else:self.notice('已经是最后一集' if delta>0 else '已经是第一集')
    def quality_menu(self):
        if not self.current_episode:return
        episode=self.current_episode;available=[t for t in self.downloads.snapshot() if t.get('complete') and t['episode']['id']==episode['id']]
        menu=QMenu(self)
        for task in available:menu.addAction('离线 · '+task['quality'].get('label',''),lambda checked=False,t=task:self._start_playback(episode,t['quality'],t))
        menu.addAction('查询在线画质…',lambda:self.select_episode(episode,True));menu.addAction('视频信息 / HDR',self.video_information);menu.exec(self.cursor().pos())
    def video_information(self):
        info=self.player.video_information();lines=[f'{key}：{value}' for key,value in info.items()];QMessageBox.information(self,'视频信息 / HDR','\n'.join(lines)+'\n\n视频标记与显示器实际 HDR 输出是不同信息，实际效果取决于系统与硬件。')
    def set_fullscreen(self,enabled):
        enabled=bool(enabled)
        if enabled==self._full_screen:return
        self._full_screen=enabled
        if enabled:
            self._saved_window_state=self.saveGeometry();self.nav.hide();self.header.hide();self.status.hide();self.watch_tools.hide();self.episode_panel.hide();self.shell.setContentsMargins(0,0,0,0);self.shell.setSpacing(0);self.showFullScreen()
        else:
            self.showNormal()
            if self._saved_window_state:self.restoreGeometry(self._saved_window_state)
            self.nav.show();self.header.show();self.status.show();self.watch_tools.show();self.episode_panel.show();self.shell.setContentsMargins(16,16,16,16);self.shell.setSpacing(20)
        if hasattr(self.player,'set_fullscreen'):self.player.set_fullscreen(enabled)
    def skip_dialog(self):
        series=self.store.find_series(self.detail_series_id)
        if not series:self.notice('请先打开一部番剧');return
        skip=series.get('skip') or {};dialog=QDialog(self);dialog.setWindowTitle('本番片头片尾');form=QFormLayout(dialog);enabled=QCheckBox('自动跳过');enabled.setChecked(skip.get('enabled',False));intro=QSpinBox();intro.setRange(0,600);intro.setSuffix(' 秒');intro.setValue(skip.get('introSeconds',0));outro=QSpinBox();outro.setRange(0,600);outro.setSuffix(' 秒');outro.setValue(skip.get('outroSeconds',0));form.addRow(enabled);form.addRow('片头',intro);form.addRow('片尾',outro);form.addRow(label('按时间跳过，不识别剧情和彩蛋。片尾跳过前可取消；手动快进后本集不再自动跳过。','Muted',True));buttons=QDialogButtonBox(QDialogButtonBox.Save|QDialogButtonBox.Cancel);buttons.accepted.connect(dialog.accept);buttons.rejected.connect(dialog.reject);form.addRow(buttons)
        if dialog.exec()==QDialog.Accepted:
            series['skip']={'enabled':enabled.isChecked(),'introSeconds':intro.value(),'outroSeconds':outro.value()};self.store.save_series(series);self._skip_disabled=False;self.notice('已保存本番片头片尾设置')
    def _skip_settings(self):
        series=self.store.find_series(self.current_episode['seriesId']) if self.current_episode else None
        return (series or {}).get('skip') or {}
    def _apply_intro(self):
        if not self.current_episode or not self._playback_ready or self._intro_applied or self._skip_disabled:return
        duration=int(self.player.duration_ms);position=int(self.player.position_ms);skip=self._skip_settings();intro=int(skip.get('introSeconds',0))*1000;outro=int(skip.get('outroSeconds',0))*1000
        if duration<=0:return
        self._intro_applied=True
        if skip.get('enabled') and intro>0 and position<=1000 and duration-intro-outro>=30000:
            self._skip_undo=position;self.player.seek(intro);self.play_notice_label.setText('已跳过片头');self.play_notice_button.setText('撤销');self.play_notice.show();QTimer.singleShot(5000,lambda:self.play_notice.hide() if self._skip_deadline is None else None)
    def _check_outro(self,position,duration):
        if self._skip_disabled or self._episode_finished or self._skip_deadline or not self.player.is_playing:return
        skip=self._skip_settings();intro=int(skip.get('introSeconds',0))*1000;outro=int(skip.get('outroSeconds',0))*1000
        if skip.get('enabled') and outro>0 and duration-intro-outro>=30000 and duration-outro<=position<duration-1000:
            self._skip_undo=None;self._skip_deadline=time.monotonic()+5;self.play_notice_label.setText('5 秒后跳过片尾');self.play_notice_button.setText('本集不跳过');self.play_notice.show()
    def cancel_skip(self):
        if self._skip_undo is not None:self.player.seek(self._skip_undo)
        self._skip_disabled=True;self._skip_undo=None;self._skip_deadline=None;self.play_notice.hide()
    def sleep_dialog(self):
        menu=QMenu(self)
        for minutes in (15,30,60):menu.addAction(f'{minutes} 分钟后停止',lambda checked=False,m=minutes:self.set_sleep(m))
        menu.addAction('本集结束后停止',lambda:self.set_sleep(-1));menu.addAction('关闭定时停止',lambda:self.set_sleep(0));menu.exec(self.cursor().pos())
    def set_sleep(self,minutes):
        self._sleep_deadline=time.monotonic()+minutes*60 if minutes>0 else None;self._sleep_after_episode=minutes<0;self.notice('本集结束后停止' if minutes<0 else f'{minutes} 分钟后停止播放' if minutes else '定时停止已关闭')
    def _on_ended(self):
        if not self.current_episode or not self._playback_ready or self._episode_finished:return
        self._episode_finished=True;self._skip_deadline=None;self.play_notice.hide();duration=int(self.player.duration_ms);self.store.save_progress(self.current_episode,duration,duration)
        if self._sleep_after_episode or (self._sleep_deadline is not None and time.monotonic()>=self._sleep_deadline):self._sleep_after_episode=False;self._sleep_deadline=None;self._generation+=1;self.player.pause();self.notice('已按定时设置停止播放');return
        if self.store.settings.get('autoNext',False):self.relative_episode(1)
        else:self.player.pause();self.notice('本集播放完毕')
    def download_current(self):
        if not self.current_episode:self.notice('请先选择一集');return
        if self.current_episode.get('localUri'):self.notice('本地视频无需下载');return
        if self.current_quality:self.prepare_downloads([self.current_episode],self.current_quality)
        else:self.choose_download_profile([self.current_episode])
    def download_selected(self):
        series=self.store.find_series(self.detail_series_id) or {};episodes=[e for e in ordered_episodes(series) if e['id'] in self._selected_episodes]
        if episodes:self.choose_download_profile(episodes)
    def choose_download_profile(self,episodes):
        episodes=[e for e in episodes if not e.get('localUri')]
        if not episodes:self.notice('所选视频无需下载');return
        generation=self._generation;self.notice('正在读取下载画质…');self.run_job(lambda:self.client().qualities(episodes[0]),lambda qualities:self.quality_dialog(episodes[0],qualities,True,episodes),generation=generation)
    def prepare_downloads(self,episodes,quality):
        if self._batch_cancel:self._batch_cancel.set()
        cancelled=threading.Event();self._batch_cancel=cancelled;generation=self._generation;dialog=QProgressDialog(f'正在检查 {len(episodes)} 集的画质与容量…','取消',0,0,self);dialog.setWindowTitle('准备下载');dialog.setWindowModality(Qt.WindowModal);dialog.setMinimumDuration(0);dialog.show();dialog.canceled.connect(cancelled.set)
        tasks=self.downloads.snapshot();existing={(t['episode']['id'],json.dumps(profile_of(t['quality']),sort_keys=True)) for t in tasks if t.get('status') not in ('failed',)};profile=profile_of(quality);profile_key=json.dumps(profile,sort_keys=True)
        def work():
            prepared=[];skipped=[];unavailable=[]
            for episode in episodes:
                if cancelled.is_set() or self._shutdown_event.is_set():return None
                if (episode['id'],profile_key) in existing:skipped.append(episode);continue
                try:
                    qualities=self.client().qualities(episode);matched=matching_quality(qualities,profile)
                    if matched:prepared.append((episode,matched))
                    else:unavailable.append(episode.get('title',''))
                except Exception:unavailable.append(episode.get('title',''))
            return prepared,skipped,unavailable
        def complete(result):
            dialog.close()
            if cancelled.is_set() or generation!=self._generation or result is None:return
            prepared,skipped,unavailable=result
            if not prepared:self.notice('没有可加入的任务：同档位已存在，或部分剧集暂不提供所选画质。');return
            sizes=[estimate_bytes(q) for e,q in prepared];known=sum(size for size,exact in sizes if size>0);unknown=sum(size<0 for size,exact in sizes);free=shutil.disk_usage(self.data_dir).free
            summary=f'准备下载 {len(prepared)} 集 · {quality.get("label","")}\n'+(('预计至少 ' if unknown else '合计约 ')+size_text(known) if known else '所需容量暂时未知')+f'\n设备可用空间 {size_text(free)}'
            if unknown:summary+=f'\n其中 {unknown} 集大小未知，下载时显示实际进度。'
            if skipped:summary+=f'\n跳过 {len(skipped)} 集已有同档位任务。'
            if unavailable:summary+=f'\n{len(unavailable)} 集暂时不可用，将跳过。'
            if known+200_000_000>free:self.show_error('存储空间不足',summary+'\n请先清理空间或减少所选集数。');return
            if QMessageBox.question(self,'确认离线下载',summary+'\n\n下载遵循设置中的网络策略，是否开始？')!=QMessageBox.Yes:return
            def added(keys):self.end_episode_selection();self.open_downloads(episodes[0].get('seriesId'));self.notice(f'已保存 {len(keys)} 集下载任务')
            self.run_job(lambda:self.downloads.enqueue_many(prepared),added)
        self.run_job(work,complete,lambda error:(dialog.close(),self.show_error('准备下载失败',error)))
    def open_downloads(self,series_id=None):self.download_series_id=series_id;self._download_selection=False;self._selected_downloads.clear();self.navigate('downloads')
    def _visible_tasks(self):
        tasks=self.downloads.snapshot()
        if self.download_series_id is not None:tasks=[t for t in tasks if t['episode'].get('seriesId')==self.download_series_id]
        return sorted(tasks,key=lambda t:(int(t['episode'].get('index',0)),-int(t['quality'].get('qn',0)),t.get('key','')))
    def render_downloads(self):
        scroll=self.download_scroll.verticalScrollBar().value();clear_layout(self.download_cards);self._download_rows={};self._group_rows={};tasks=self.downloads.snapshot();self.download_back.setVisible(self.download_series_id is not None)
        if self.download_series_id is None:
            groups={}
            for task in tasks:groups.setdefault(task['episode'].get('seriesId',''),[]).append(task)
            if not groups:self.download_cards.addWidget(label('还没有离线视频。打开番剧选集，长按多选即可批量下载。','Muted',True))
            for series_id,entries in sorted(groups.items(),key=lambda item:item[1][0]['episode'].get('seriesTitle','')):
                series=self.store.find_series(series_id) or {'title':entries[0]['episode'].get('seriesTitle','未命名番剧')};card=SelectableCard();row=QHBoxLayout(card);row.setContentsMargins(20,20,20,20);cover=Cover(series['title'],76,110);self.cover_loader.request(series.get('coverUrl',''),cover);row.addWidget(cover)
                details=QVBoxLayout();details.addWidget(label(series['title'],'SectionTitle',True));complete=sum(t.get('complete',False) for t in entries);details.addWidget(label(f'{complete} 个版本已完成 · 共 {len(entries)} 个下载任务','Muted'));bar=QProgressBar();bar.setFixedHeight(8);bar.setTextVisible(False);details.addWidget(bar);progress=label('','Muted');details.addWidget(progress);row.addLayout(details,1);row.addWidget(button('打开',lambda checked=False,s=series_id:self.open_downloads(s)));card.clicked.connect(lambda s=series_id:self.open_downloads(s));self.download_cards.addWidget(card);self._group_rows[series_id]=(bar,progress)
        else:
            visible=self._visible_tasks();series=self.store.find_series(self.download_series_id);self.page_title.setText((series or {}).get('title','离线剧集'))
            last=self.store.last_episode(self.download_series_id);latest=next((t for t in visible if t.get('complete') and t['episode']['id']==last),None)
            if not latest:
                candidates=[t for t in visible if t.get('complete') and self.store.progress(t['episode']['id']).get('updatedAt')]
                if candidates:latest=max(candidates,key=lambda t:self.store.progress(t['episode']['id']).get('updatedAt',0))
            if latest:self.download_cards.addWidget(button(f"继续第 {latest['episode'].get('index',0)} 集 · {format_time(self.store.progress(latest['episode']['id']).get('positionMs',0))}",lambda t=latest:self.play_download(t),True))
            self.download_cards.addWidget(button('批量删除',self.begin_download_selection))
            for task in visible:self.download_cards.addWidget(self._download_card(task))
        self.download_cards.addStretch();self.download_selection_bar.setVisible(self._download_selection);self.download_selection_label.setText(f'已选择 {len(self._selected_downloads)} 项');self._downloads_signature=self._download_signature(tasks);self.update_download_progress(tasks);QTimer.singleShot(0,lambda:self.download_scroll.verticalScrollBar().setValue(scroll))
    def _download_signature(self,tasks):return tuple((t['key'],t.get('status'),bool(t.get('needsRepair'))) for t in tasks)
    def _download_card(self,task):
        card=SelectableCard();box=QVBoxLayout(card);box.setContentsMargins(18,16,18,16);header=QHBoxLayout();episode=task['episode'];header.addWidget(label(episode.get('title',f"第 {episode.get('index',0)} 集"),'SectionTitle',True),1)
        if self._download_selection:
            check=QCheckBox();check.setChecked(task['key'] in self._selected_downloads);check.toggled.connect(lambda selected,t=task:self.toggle_download_selection(t,selected));header.addWidget(check)
        box.addLayout(header);box.addWidget(label(task['quality'].get('label',''),'Muted'));progress=self.store.progress(episode['id']);box.addWidget(label(('已看完' if progress.get('completed') else '看到 '+format_time(progress.get('positionMs',0)) if progress.get('positionMs',0)>0 else '尚未观看'),'Muted'))
        status=label('','Muted',True);box.addWidget(status);bar=QProgressBar();bar.setTextVisible(False);bar.setFixedHeight(8);box.addWidget(bar);bytes_label=label('','Muted');box.addWidget(bytes_label);actions=QHBoxLayout();key=task['key']
        if task.get('complete'):
            actions.addWidget(button('离线播放',lambda:self.play_download(task),True));actions.addWidget(button('检查文件',lambda:self.inspect_download(task)))
        elif task.get('needsRepair'):actions.addWidget(button('修复本集',lambda:self.repair_download(task),True))
        elif task.get('status')=='failed':actions.addWidget(button('重新解析重试',lambda:self.retry_download(task),True))
        else:
            actions.addWidget(button('继续' if task.get('paused') or task.get('status')=='paused' else '暂停',lambda:self.toggle_pause(task),True));actions.addWidget(button('优先下载',lambda:self.download_action(lambda:self.downloads.prioritize(key))))
        actions.addWidget(button('删除',lambda:self.delete_downloads([task])));actions.addStretch();box.addLayout(actions);card.longPressed.connect(lambda:self.begin_download_selection(task));card.clicked.connect(lambda:self.toggle_download_selection(task) if self._download_selection else None);self._download_rows[key]=(bar,bytes_label,status);return card
    def update_download_progress(self,tasks=None):
        tasks=self.downloads.snapshot() if tasks is None else tasks
        from .network import network_status
        network=network_status();free=shutil.disk_usage(self.data_dir).free;active=sum(t.get('status')=='downloading' for t in tasks);waiting=sum(t.get('status') in ('queued','waiting_network','paused') for t in tasks)
        self.download_summary.setText(f"可用空间 {size_text(free)} · 下载中 {active} · 等待 / 暂停 {waiting}\n{network.get('label','正在识别网络')}"+(' · 低于 200 MB 时自动暂停，请清理至至少 300 MB 后继续。' if free<500_000_000 else ''))
        for task in tasks:
            if task['key'] not in self._download_rows:continue
            bar,detail,status=self._download_rows[task['key']];total=int(task.get('totalBytes',-1));done=int(task.get('bytesDownloaded',0));percent=task.get('percent')
            if task.get('complete'):percent=100
            elif total>0:percent=min(99,round(done*100/total))
            else:percent=None
            if percent is None and task.get('status')=='downloading':bar.setRange(0,0)
            else:bar.setRange(0,100);bar.setValue(max(0,int(percent or 0)))
            detail.setText(size_text(done)+' / '+size_text(total)+(f' · {percent}%' if percent is not None else '')+(f" · {size_text(task.get('speed',0))}/s" if task.get('status')=='downloading' else '')+(f" · 剩余约 {format_time(int(task.get('eta',-1))*1000)}" if task.get('eta',-1) is not None and task.get('eta',-1)>=0 and not task.get('complete') else ''))
            status.setText(task.get('label',task.get('status',''))+(' · '+task.get('error','') if task.get('error') else ''))
        for series_id,(bar,text) in self._group_rows.items():
            entries=[t for t in tasks if t['episode'].get('seriesId')==series_id];done,total,percent=aggregate_progress(entries)
            if percent is None and any(t.get('status')=='downloading' for t in entries):bar.setRange(0,0)
            else:bar.setRange(0,100);bar.setValue(percent or 0)
            text.setText(size_text(done)+' / '+size_text(total)+(f' · {percent}%' if percent is not None else ''))
    def play_download(self,task):
        self.detail_series_id=task['episode']['seriesId'];self._generation+=1;self._start_playback(task['episode'],task['quality'],task)
    def download_action(self,action):self.run_job(action,lambda result:self.render_downloads())
    def toggle_pause(self,task):self.download_action(lambda:self.downloads.resume(task['key']) if task.get('paused') or task.get('status')=='paused' else self.downloads.pause(task['key']))
    def pause_all(self):self.download_action(self.downloads.pause_all)
    def resume_all(self):self.download_action(self.downloads.resume_all)
    def begin_download_selection(self,task=None):
        self._download_selection=True
        if isinstance(task,dict):self._selected_downloads.add(task['key'])
        self.render_downloads()
    def end_download_selection(self):self._download_selection=False;self._selected_downloads.clear();self.render_downloads()
    def toggle_download_selection(self,task,selected=None):
        key=task['key'];selected=key not in self._selected_downloads if selected is None else selected
        if selected:self._selected_downloads.add(key)
        else:self._selected_downloads.discard(key)
        self.render_downloads()
    def select_all_downloads(self):self._selected_downloads={t['key'] for t in self._visible_tasks()};self.render_downloads()
    def delete_selected_downloads(self):self.delete_downloads([t for t in self._visible_tasks() if t['key'] in self._selected_downloads])
    def delete_downloads(self,tasks):
        if not tasks:return
        total=sum(max(0,int(t.get('bytesDownloaded',0))) for t in tasks)
        if QMessageBox.question(self,'删除离线视频？',f'将删除 {len(tasks)} 个版本，预计释放 {size_text(total)}。\n片库和观看记录会保留。')!=QMessageBox.Yes:return
        if self.current_offline and self.current_offline['key'] in {t['key'] for t in tasks}:self._stop_playback()
        self.run_job(lambda:[self.downloads.remove(t['key']) for t in tasks],lambda result:self.end_download_selection())
    def inspect_download(self,task):
        self.notice('正在检查本机文件…')
        def inspected(report):
            dialog=QMessageBox(self);dialog.setWindowTitle('离线文件检查');dialog.setText(report.get('message','检查完成')+'\n\n首次检查只能建立本机校验基线，无法判断此前已存在的同长度损坏，也不代表解码兼容性检查。');dialog.addButton('关闭',QMessageBox.RejectRole);repair=dialog.addButton('修复本集',QMessageBox.AcceptRole) if not report.get('ok') else None;dialog.exec();self.render_downloads()
            if repair is not None and dialog.clickedButton()==repair:self.repair_download(task)
        self.run_job(lambda:self.downloads.inspect(task['key']),inspected)
    def repair_download(self,task):
        if QMessageBox.question(self,'修复本集？','重新获取相同画质的地址，清理异常片段并补下缺失部分。保留正常文件和观看记录；源文件变更时将重下该版本。')!=QMessageBox.Yes:return
        if self.current_offline and self.current_offline['key']==task['key']:self._stop_playback()
        def repair():
            qualities=self.client().qualities(task['episode']);quality=next((q for q in qualities if q['id']==task['quality']['id']),None)
            if quality is None:raise ValueError('原画质或音轨暂时不可用，请重新选择下载画质。')
            return self.downloads.repair(task['key'],quality)
        self.run_job(repair,lambda key:(self.render_downloads(),self.notice('已加入修复下载')))
    def retry_download(self,task):self.choose_download_profile([task['episode']])
    def render_settings(self):
        clear_layout(self.settings_layout)
        def group(title):
            box=panel(QVBoxLayout());box.layout().setContentsMargins(20,20,20,20);box.layout().setSpacing(12);box.layout().addWidget(label(title,'SectionTitle'));form=QFormLayout();form.setSpacing(12);box.layout().addLayout(form);self.settings_layout.addWidget(box);return form
        appearance=group('外观与播放');theme=QComboBox()
        for key,(name,color) in THEMES.items():theme.addItem(name,key)
        theme.setCurrentIndex(max(0,theme.findData(self.store.settings.get('theme','blue'))));theme.currentIndexChanged.connect(lambda:self.set_setting('theme',theme.currentData()));appearance.addRow('按钮颜色',theme)
        speed=QComboBox()
        for value in (.25,.5,.75,1,1.25,1.5,1.75,2,2.5,3):speed.addItem(f'{value:g}×',value)
        speed.setCurrentIndex(max(0,speed.findData(float(self.store.settings.get('speed',1)))));speed.currentIndexChanged.connect(lambda:self.set_setting('speed',speed.currentData()));appearance.addRow('默认倍速',speed)
        hold=QComboBox()
        for value in (1.5,2,2.5,3):hold.addItem(f'{value:g}×',value)
        hold.setCurrentIndex(max(0,hold.findData(float(self.store.settings.get('holdSpeed',2)))));hold.currentIndexChanged.connect(lambda:self.set_setting('holdSpeed',hold.currentData()));appearance.addRow('长按临时倍速',hold)
        timeout=QComboBox()
        for name,value in [('3.5 秒',3500),('5 秒',5000),('8 秒',8000),('保持显示',0)]:timeout.addItem(name,value)
        timeout.setCurrentIndex(max(0,timeout.findData(self.store.settings.get('controlsTimeoutMs',3500))));timeout.currentIndexChanged.connect(lambda:self.set_setting('controlsTimeoutMs',timeout.currentData()));appearance.addRow('控制栏自动隐藏',timeout)
        for title,key,default in [('自动播放下一集','autoNext',False),('记住每部番的画质','rememberQuality',True)]:
            check=QCheckBox(title);check.setChecked(self.store.settings.get(key,default));check.toggled.connect(lambda value,k=key:self.set_setting(k,value));appearance.addRow(check)
        appearance.addRow(button('定时停止播放',self.sleep_dialog));appearance.addRow(button('管理已移除番剧',self.restore_hidden))
        gestures=group('手势设置');mask=self.store.settings.get('gestureMask',31)
        for index,name in enumerate(['左侧上下滑动调亮度','右侧上下滑动调音量','左右滑动调整进度','长按临时加速','双击播放 / 暂停']):
            check=QCheckBox(name);check.setChecked(bool(mask&(1<<index)));check.toggled.connect(lambda enabled,bit=1<<index:self.set_setting('gestureMask',(self.store.settings.get('gestureMask',31)|bit) if enabled else (self.store.settings.get('gestureMask',31)&~bit)));gestures.addRow(check)
        sensitivity=QComboBox()
        for text,seconds in [('精细 · 一屏最多 90 秒',90),('标准 · 一屏最多 180 秒',180),('快速 · 一屏最多 300 秒',300)]:sensitivity.addItem(text,seconds)
        sensitivity.setCurrentIndex(max(0,sensitivity.findData(self.store.settings.get('seekSeconds',180))));sensitivity.currentIndexChanged.connect(lambda:self.set_setting('seekSeconds',sensitivity.currentData()));gestures.addRow('快进灵敏度',sensitivity);gestures.addRow(label('关闭画面手势后，按钮、进度条和键盘快捷键仍可使用。','Muted',True))
        downloads=group('下载与存储');wifi=QCheckBox('仅 Wi-Fi / 有线网络下载');wifi.setChecked(self.store.settings.get('wifiOnlyDownloads',True));wifi.toggled.connect(lambda checked:self.set_network_policy(checked,wifi));downloads.addRow(wifi);downloads.addRow(label('无法识别网络时先等待；存储少于 200 MB 自动暂停，清理至至少 300 MB 后可手动继续。','Muted',True));downloads.addRow(button('清理已看完及多余版本',self.storage_cleanup));downloads.addRow(button('打开离线文件夹',lambda:QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.data_dir/'offline')))))
        backup=group('片库与观看记录');backup.addRow(button('导出片库备份',self.export_backup));backup.addRow(button('从备份恢复',self.import_backup));folder=self.preferences.get('autoBackupDir','');backup.addRow('自动备份',label(folder or '未开启','Muted',True));backup.addRow(button('选择自动备份文件夹',self.choose_backup_directory));backup.addRow(button('立即自动备份',lambda:self.auto_backup(True)));backup.addRow(button('关闭自动备份',self.disable_auto_backup));backup.addRow(label('应用运行时保存变化后的片库、进度和设置，保留最近 7 份。备份不包含视频、登录信息或本地文件授权。','Muted',True))
        about=group('关于番匣');about.addRow(label('Windows '+__version__+' · 功能基线 0.1.13','Muted'));about.addRow(button('检查桌面版更新',self.check_updates));about.addRow(button('恢复默认播放与外观设置',self.reset_settings));about.addRow(label('视频和观看记录保存在这台电脑。关闭到通知区可继续下载；退出程序后任务保留，下次打开继续。','Muted',True));self.settings_layout.addStretch()
    def set_setting(self,key,value):
        self.store.settings[key]=value;self.store.save();self.player.set_settings(dict(self.store.settings))
        if key=='theme':self.apply_theme()
        if key=='speed' and self.current_episode:self.player.set_speed(float(value))
    def set_network_policy(self,enabled,checkbox):
        if not enabled and QMessageBox.question(self,'允许其他网络下载？','关闭后可能通过移动热点或计费网络下载，产生流量费用。')!=QMessageBox.Yes:
            checkbox.blockSignals(True);checkbox.setChecked(True);checkbox.blockSignals(False);return
        self.set_setting('wifiOnlyDownloads',enabled);self.downloads.configure(wifi_only=enabled)
    def reset_settings(self):
        if QMessageBox.question(self,'恢复默认设置？','恢复按钮颜色、倍速、手势和播放设置，保留片库、观看记录和离线视频。')!=QMessageBox.Yes:return
        defaults={'theme':'blue','speed':1,'holdSpeed':2,'controlsTimeoutMs':3500,'autoNext':False,'rememberQuality':True,'wifiOnlyDownloads':True,'libraryFilter':'all','gestureMask':31,'seekSeconds':180,'preferredQuality':None};self.store.settings.update(defaults);self.store.save();self.downloads.configure(wifi_only=True);self.player.set_settings(dict(self.store.settings));self.apply_theme();self.render_settings()
    def export_backup(self):
        self._save_playback();path,_=QFileDialog.getSaveFileName(self,'导出片库备份','番匣片库-'+time.strftime('%Y%m%d-%H%M%S')+'.json','JSON 备份 (*.json)')
        if not path:return
        try:
            text=self.store.export_backup();target=Path(path);temporary=target.with_name(target.name+'.tmp');temporary.write_text(text,'utf-8');os.replace(temporary,target);self.notice('片库备份已保存')
        except Exception as error:self.show_error('备份未完成',error)
    def import_backup(self):
        path,_=QFileDialog.getOpenFileName(self,'恢复片库备份','','JSON 备份 (*.json)')
        if not path:return
        try:
            if Path(path).stat().st_size>4*1024*1024:raise ValueError('备份文件超过 4 MiB 限制。')
            text=Path(path).read_text('utf-8');summary=self.store.inspect_backup(text);message=summary['summary']+'\n\n合并片库，保留本机更新的观看记录。'
            if not summary.get('wifiOnlyDownloads',True):message+='\n此备份允许其他网络下载，恢复后可能产生流量费用。'
            if QMessageBox.question(self,'确认恢复备份',message)!=QMessageBox.Yes:return
            self._restore_backup_text(text)
        except Exception as error:self.show_error('恢复未完成',error)
    def _restore_backup_text(self,text):
        # Clear the active media before merging timestamps so later navigation cannot
        # overwrite an imported record with a stale native-player position.
        self._generation+=1;self._stop_playback();result=self.store.import_backup(text)
        self.downloads.configure(wifi_only=self.store.settings.get('wifiOnlyDownloads',True));self.player.set_settings(dict(self.store.settings));self.apply_theme()
        self.library_filter.setCurrentIndex(max(0,self.library_filter.findData(self.store.settings.get('libraryFilter','all'))))
        self.navigate('library');self.notice(result.get('summary','备份已恢复'));return result
    def _save_preferences(self):
        temporary=self.preferences_path.with_suffix('.tmp');temporary.write_text(json.dumps(self.preferences,ensure_ascii=False,indent=2),'utf-8');os.replace(temporary,self.preferences_path)
    def choose_backup_directory(self):
        folder=QFileDialog.getExistingDirectory(self,'选择自动备份文件夹',self.preferences.get('autoBackupDir',''))
        if folder:self.preferences['autoBackupDir']=folder;self._save_preferences();self._last_backup_digest='';self.render_settings();self.auto_backup(True)
    def disable_auto_backup(self):self.preferences.pop('autoBackupDir',None);self._save_preferences();self.render_settings();self.notice('自动备份已关闭，已有备份继续保留')
    def auto_backup(self,force=False):
        folder=self.preferences.get('autoBackupDir')
        if self._backup_busy or not folder or self._dead:return
        try:
            text=self.store.export_backup();document=json.loads(text);document.pop('exportedAt',None);digest=hashlib.sha256(json.dumps(document,ensure_ascii=False,sort_keys=True).encode()).hexdigest()
            if not force and digest==self._last_backup_digest:return
        except Exception as error:
            if force:self.show_error('自动备份未完成',error)
            return
        if not self.preferences.get('backupInstanceId'):
            self.preferences['backupInstanceId']=uuid.uuid4().hex;self._save_preferences()
        prefix='anime-shelf-desktop-'+str(self.preferences['backupInstanceId'])+'-'
        self._backup_busy=True
        def write():
            target=Path(folder)
            if not target.is_dir():raise ValueError('自动备份文件夹暂不可用，请重新选择。')
            output=target/(prefix+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]+'.json');temporary=output.with_suffix('.tmp');temporary.write_text(text,'utf-8');self.store.inspect_backup(temporary.read_text('utf-8'));os.replace(temporary,output);self.store.inspect_backup(output.read_text('utf-8'))
            files=sorted(target.glob(prefix+'*.json'),key=lambda p:p.stat().st_mtime,reverse=True)
            for old in files[7:]:
                try:old.unlink()
                except OSError:pass
            return str(output)
        def done(path):self._backup_busy=False;self._last_backup_digest=digest;self.notice('自动备份已保存') if force else None
        def failed(error):self._backup_busy=False;self.notice('自动备份暂未完成，请检查所选文件夹')
        self.run_job(write,done,failed)
    def storage_cleanup(self):
        tasks=self.downloads.snapshot()
        if not tasks:self.notice('没有需要清理的离线视频');return
        dialog=QDialog(self);dialog.setWindowTitle('存储清理');dialog.resize(680,510);layout=QVBoxLayout(dialog);layout.addWidget(label('选择要删除的离线版本','SectionTitle'));layout.addWidget(label('删除只影响选中的视频，片库和观看记录会保留。','Muted',True));filters=QComboBox();filters.addItems(['全部离线任务','已看完的剧集','同一集的多个版本']);layout.addWidget(filters);listing=QListWidget();layout.addWidget(listing,1);total_label=label('','Muted');layout.addWidget(total_label)
        def populate():
            listing.clear();mode=filters.currentIndex();counts={}
            for task in tasks:counts[task['episode']['id']]=counts.get(task['episode']['id'],0)+1
            shown=[t for t in tasks if (mode!=1 or self.store.progress(t['episode']['id']).get('completed')) and (mode!=2 or counts[t['episode']['id']]>1)]
            for task in sorted(shown,key=lambda t:-int(t.get('bytesDownloaded',0))):
                item=QListWidgetItem(task['episode'].get('seriesTitle','')+' · '+task['episode'].get('title','')+'\n'+task['quality'].get('label','')+' · '+size_text(task.get('bytesDownloaded',0)));item.setFlags(item.flags()|Qt.ItemIsUserCheckable);item.setCheckState(Qt.Unchecked);item.setData(Qt.UserRole,task);listing.addItem(item)
            count()
        def selected():return [listing.item(i).data(Qt.UserRole) for i in range(listing.count()) if listing.item(i).checkState()==Qt.Checked]
        def count():
            picked=selected();total_label.setText(f'选择 {len(picked)} 个版本 · 预计释放 '+size_text(sum(int(t.get('bytesDownloaded',0)) for t in picked)))
        filters.currentIndexChanged.connect(populate);listing.itemChanged.connect(count);row=QHBoxLayout();row.addWidget(button('取消',dialog.reject));row.addStretch()
        def remove():
            picked=selected()
            if picked:dialog.accept();self.delete_downloads(picked)
        row.addWidget(button('删除所选',remove,True));layout.addLayout(row);populate();dialog.exec()
    def check_updates(self):
        from .updates import check_update
        self.notice('正在检查桌面版更新…')
        def ready(update):
            if not update.get('available'):self.notice('当前已是最新桌面版');return
            dialog=QMessageBox(self);dialog.setWindowTitle('有新版本 '+str(update.get('version','')));notes=update.get('notes',[]);dialog.setText('\n'.join(notes) if isinstance(notes,list) else str(notes));download=dialog.addButton('下载安装 ZIP',QMessageBox.AcceptRole);dialog.addButton('稍后',QMessageBox.RejectRole);dialog.exec()
            if dialog.clickedButton()==download:QDesktopServices.openUrl(QUrl(update['zipUrl']))
        self.run_job(lambda:check_update(__version__),ready)
    def _tick(self):
        if self._dead:return
        if self._active_page=='downloads':
            tasks=self.downloads.snapshot()
            if self._download_signature(tasks)!=self._downloads_signature:self.render_downloads()
            else:self.update_download_progress(tasks)
        if self._sleep_deadline is not None and time.monotonic()>=self._sleep_deadline:
            self._sleep_deadline=None;self._sleep_after_episode=False;self._generation+=1;self.player.pause();self._save_playback();self.notice('定时时间已到，播放已暂停')
        if self._skip_deadline is not None:
            if not self.player.is_playing:self._skip_deadline=None;self.play_notice.hide()
            else:
                seconds=max(0,int(self._skip_deadline-time.monotonic()+.99));self.play_notice_label.setText(f'{seconds} 秒后跳过片尾')
                if seconds<=0:self._skip_deadline=None;self.player.seek(int(self.player.duration_ms));self._on_ended()
    def resizeEvent(self,event):
        super().resizeEvent(event)
        if hasattr(self,'resize_timer'):self.resize_timer.start()
    def keyPressEvent(self,event):
        if event.key()==Qt.Key_Escape and self._full_screen:
            if self.episode_panel.isVisible():self.episode_panel.hide()
            else:self.set_fullscreen(False)
            event.accept();return
        super().keyPressEvent(event)
    def closeEvent(self,event):
        if not self._quitting and self.tray and any(t.get('status') in ('downloading','queued','waiting_network') for t in self.downloads.snapshot()):
            self._save_playback();self.player.pause();self.hide();self.tray.showMessage('番匣','已收起到通知区，下载继续运行。右键图标可退出。',QSystemTrayIcon.Information,3000);event.ignore();return
        self._shutdown();event.accept()
    def quit_app(self):self._quitting=True;self.close();QApplication.instance().quit()
    def _shutdown(self):
        if self._dead:return
        self._save_playback();self._dead=True;self._shutdown_event.set();self._generation+=1;self.poll.stop();self.backup_timer.stop();self.pool.clear();self.cover_loader.close()
        if self._batch_cancel:self._batch_cancel.set()
        self.player.stop()
        if hasattr(self.player,'dispose'):self.player.dispose()
        self.downloads.close()
        if self.tray:self.tray.hide()
