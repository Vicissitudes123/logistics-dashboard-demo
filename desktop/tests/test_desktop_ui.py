from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from anime_shelf.ui_logic import (THEMES,matching_title,library_groups,estimate_bytes,aggregate_progress,profile_of,matching_quality)
from anime_shelf.store import Store


def series_fixture():
    return {'id':'ss1','title':'擅长捉弄的高木同学','coverUrl':'','episodes':[{'id':f'ep{i}','title':f'第 {i} 集','seriesId':'ss1','seriesTitle':'擅长捉弄的高木同学','index':i,'localUri':'','aid':0,'cid':0,'bvid':'','bangumi':True} for i in range(1,4)]}

def quality_fixture():
    return {'id':'80:7:30280','qn':80,'label':'1080P · AVC','videoUrl':'https://media.example/video.m4s','audioUrl':'https://media.example/audio.m4s','videoBytes':1000,'audioBytes':100,'videoUrls':[],'audioUrls':[],'headers':{}}

class DesktopPoliciesTest(unittest.TestCase):
    def test_partial_search_normalizes_width_case_and_all_terms(self):
        self.assertTrue(matching_title('擅长捉弄的高木同学','高木'));self.assertTrue(matching_title('ＪＯＪＯ 的奇妙冒险','jojo 奇妙'));self.assertFalse(matching_title('JOJO 的奇妙冒险','jojo 高木'))
    def test_recent_pin_and_filters_do_not_duplicate_or_promote_unwatched_imports(self):
        one=series_fixture();two=copy.deepcopy(one);two.update(id='ss2',title='JOJO',pinned=True);two['episodes']=[dict(two['episodes'][0],id='ep4')]
        third=copy.deepcopy(one);third.update(id='ss3',hidden=True)
        p=lambda key:{'updatedAt':100,'completed':False} if key=='ep2' else {}
        groups=library_groups([two,third,one],p,'ss1');self.assertEqual(['最近观看','置顶番剧'],[title for title,items in groups]);self.assertEqual(['ss1','ss2'],[s['id'] for title,items in groups for s in items])
        self.assertEqual(['ss2'],[s['id'] for title,items in library_groups([one,two],p,'ss1',mode='offline',offline_ids=['ep4']) for s in items])
    def test_estimate_includes_audio_and_never_invents_unknown_size(self):
        q=quality_fixture();self.assertEqual((1100,True),estimate_bytes(q));q['audioBytes']=0;self.assertEqual((-1,False),estimate_bytes(q));q.update(bandwidth=800000,audioBandwidth=80000,durationMs=10000);self.assertEqual((1100000,False),estimate_bytes(q))
    def test_group_progress_weights_bytes_and_requires_complete_tracks(self):
        self.assertEqual((910,1100,83),aggregate_progress([{'bytesDownloaded':900,'totalBytes':1000},{'bytesDownloaded':10,'totalBytes':100}]))
        self.assertEqual((900,-1,None),aggregate_progress([{'bytesDownloaded':900,'totalBytes':1000},{'bytesDownloaded':0,'totalBytes':-1}]))
        self.assertEqual(99,aggregate_progress([{'bytesDownloaded':100,'totalBytes':100,'complete':False}])[2])
    def test_every_ui_theme_roundtrips_through_mobile_v4_backup(self):
        with tempfile.TemporaryDirectory() as path:
            store=Store(Path(path)/'library.json');store.save_series(series_fixture())
            for theme in THEMES:
                store.settings['theme']=theme;backup=store.export_backup();self.assertEqual(theme,json.loads(backup)['settings']['theme']);store.import_backup(backup)

try:
    from PySide6.QtCore import Signal
    from PySide6.QtWidgets import QApplication,QWidget
    from PySide6.QtTest import QTest
    HAVE_QT=True
except ImportError:HAVE_QT=False

if HAVE_QT:
    class FakePlayer(QWidget):
        progressChanged=Signal(int,int);ended=Signal();error=Signal(str);requestEpisode=Signal(int);requestQuality=Signal();requestEpisodes=Signal();fullscreenChanged=Signal(bool);speedChanged=Signal(float);ready=Signal();userSeeked=Signal(int);requestHome=Signal()
        def __init__(self):
            super().__init__();self.position_ms=0;self.duration_ms=300000;self.is_playing=False;self.calls=[];self.settings={}
        def set_settings(self,settings):self.settings=settings
        def load(self,episode,quality,offline_task=None,start_ms=0,paused=False):self.calls.append((episode,quality,offline_task,start_ms,paused));self.position_ms=start_ms;self.is_playing=not paused;self.ready.emit();return True
        def stop(self):self.is_playing=False
        def pause(self):self.is_playing=False
        def resume(self):self.is_playing=True
        def set_speed(self,value):self.speedChanged.emit(value)
        def seek(self,value):self.position_ms=value
        def set_fullscreen(self,value):self.fullscreen=value
        def dispose(self):pass
        def video_information(self):return {}

    class FakeDownloads:
        def __init__(self,tasks=None):self.tasks=tasks or [];self.closed=False
        def configure(self,wifi_only=True):self.wifi_only=wifi_only
        def snapshot(self):return copy.deepcopy(self.tasks)
        def get_complete(self,episodeid,profile=None):return next((copy.deepcopy(t) for t in self.tasks if t['episode']['id']==episodeid and t.get('complete')),None)
        def start(self):pass
        def close(self):self.closed=True
        def pause_all(self):pass
        def resume_all(self):pass

@unittest.skipUnless(HAVE_QT,'Qt runtime is required for native UI integration')
class DesktopWindowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app=QApplication.instance() or QApplication([])
    def setUp(self):
        from anime_shelf.ui import MainWindow
        self.directory=tempfile.TemporaryDirectory();self.store=Store(Path(self.directory.name)/'library.json');self.series=series_fixture();self.store.save_series(self.series);self.resolve_calls=[]
        class Client:
            def __init__(inner,cookie):pass
            def qualities(inner,episode):self.resolve_calls.append(episode['id']);raise AssertionError('Unexpected network')
        self.downloads=FakeDownloads();self.window=MainWindow(Path(self.directory.name),store=self.store,downloads=self.downloads,player_factory=FakePlayer,client_factory=Client,smoke=True);self.window.show();self.app.processEvents()
    def tearDown(self):
        self.window._quitting=True;self.window.close();self.app.processEvents();self.directory.cleanup()
    def test_offline_first_restores_each_episode_without_resolving(self):
        episode=self.series['episodes'][1];self.store.save_progress(episode,42000,300000);self.downloads.tasks=[{'key':'saved','episode':episode,'quality':quality_fixture(),'complete':True,'status':'completed','bytesDownloaded':1100,'totalBytes':1100}]
        self.window.open_series('ss1');self.window.select_episode(episode);self.assertEqual([],self.resolve_calls);self.assertEqual(42000,self.window.player.calls[-1][3]);self.assertTrue(self.window.player.calls[-1][2]['complete'])
        self.window.player.position_ms=75000;self.window.navigate('library');self.assertEqual(75000,self.store.progress(episode['id'])['positionMs'])
    def test_late_online_resolution_cannot_reopen_playback_after_navigation(self):
        started=threading.Event();release=threading.Event()
        class Client:
            def __init__(inner,cookie):pass
            def qualities(inner,episode):started.set();release.wait(3);return [quality_fixture()]
        self.window.client_factory=Client;self.store.settings['preferredQuality']=profile_of(quality_fixture());self.window.open_series('ss1');self.window.select_episode(self.series['episodes'][0]);self.assertTrue(started.wait(2));self.window.navigate('library');release.set()
        for i in range(20):QTest.qWait(20);self.app.processEvents()
        self.assertEqual([],self.window.player.calls);self.assertIsNone(self.window.current_episode);self.assertEqual('library',self.window._active_page)
    def test_fullscreen_and_sidebar_keep_same_player_and_native_parent(self):
        self.window.open_series('ss1');player=self.window.player;parent=player.parent();self.window.set_fullscreen(True);self.app.processEvents();self.assertIs(player,self.window.player);self.assertIs(parent,player.parent());self.assertFalse(self.window.episode_panel.isVisible());self.window.toggle_episode_panel();self.assertTrue(self.window.episode_panel.isVisible());self.window.set_fullscreen(False);self.assertIs(parent,player.parent())
    def test_sleep_after_episode_overrides_auto_next_and_preserves_complete_progress(self):
        episode=self.series['episodes'][0];self.downloads.tasks=[{'key':'saved','episode':episode,'quality':quality_fixture(),'complete':True,'status':'completed','bytesDownloaded':1100,'totalBytes':1100}];self.store.settings['autoNext']=True;self.window.open_series('ss1');self.window.select_episode(episode);self.window.set_sleep(-1);self.window.player.position_ms=300000;self.window._on_ended();self.window.player.position_ms=0;self.window.navigate('library');self.assertTrue(self.store.progress(episode['id'])['completed']);self.assertEqual(1,len(self.window.player.calls))

    def test_failed_new_media_does_not_assign_old_progress_to_new_episode(self):
        old,new=self.series['episodes'][:2]
        self.window._start_playback(old,quality_fixture(),None);self.window.player.position_ms=95000
        self.store.save_progress(new,42000,300000)
        before=copy.deepcopy(self.store.progress(new['id']))
        def fail(*args,**kwargs):
            self.window.player.progressChanged.emit(95000,300000)
            return False
        self.window.player.load=fail
        self.assertFalse(self.window._start_playback(new,quality_fixture(),None))
        self.window.player.progressChanged.emit(96000,300000);self.window._on_ended();self.window.navigate('library')
        self.assertEqual(95000,self.store.progress(old['id'])['positionMs'])
        self.assertEqual(before,self.store.progress(new['id']))
    def test_restore_newer_progress_while_playing_is_not_overwritten_by_navigation(self):
        episode=self.series['episodes'][0]
        self.window._start_playback(episode,quality_fixture(),None);self.window.player.position_ms=45000
        document=json.loads(self.store.export_backup())
        document['progress']=[{'episodeId':episode['id'],'positionMs':180000,'durationMs':300000,'updatedAt':int(time.time()*1000)+60000,'completed':False}]
        self.window._restore_backup_text(json.dumps(document))
        self.assertEqual(180000,self.store.progress(episode['id'])['positionMs'])
        self.assertIsNone(self.window.current_episode)
    def test_auto_backup_retention_never_removes_other_instance_files(self):
        folder=Path(self.directory.name)/'backups';folder.mkdir()
        foreign=folder/'anime-shelf-desktop-other-20200101.json';foreign.write_text('{}')
        self.window.preferences.update(autoBackupDir=str(folder),backupInstanceId='this-device')
        for i in range(10):(folder/f'anime-shelf-desktop-this-device-{i:02}.json').write_text('{}')
        self.window.run_job=lambda function,done,failed=None,generation=None:done(function())
        self.window.auto_backup(True)
        self.assertTrue(foreign.is_file());self.assertEqual(7,len(list(folder.glob('anime-shelf-desktop-this-device-*.json'))))
