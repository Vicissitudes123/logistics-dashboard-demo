"""Application entry point; all user data lives outside the portable installation."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import traceback


def main(argv=None):
    parser=argparse.ArgumentParser(prog='AnimeShelf');parser.add_argument('--smoke-test',metavar='REPORT');args=parser.parse_args(argv)
    from PySide6.QtCore import QTimer,QStandardPaths,QBuffer,QByteArray,QIODevice,Qt
    from PySide6.QtGui import QPainter,QColor,QLinearGradient,QFont,QPixmap
    from PySide6.QtWidgets import QApplication,QMessageBox
    app=QApplication.instance() or QApplication(sys.argv[:1]);app.setOrganizationName('AnimeShelf');app.setApplicationName('AnimeShelf');app.setApplicationDisplayName('番匣')
    from .ui import MainWindow
    from .store import Store
    override=os.environ.get('ANIME_SHELF_DATA_DIR')
    data_dir=Path(override) if override else Path(tempfile.mkdtemp(prefix='anime-shelf-smoke-')) if args.smoke_test else Path(QStandardPaths.writableLocation(QStandardPaths.AppLocalDataLocation))
    data_dir.mkdir(parents=True,exist_ok=True)
    try:
        if args.smoke_test:
            store=Store(data_dir/'library.json');(data_dir/'covers').mkdir(exist_ok=True)
            for i,(title,color) in enumerate([('擅长捉弄的高木同学','#4e8da8'),('JOJO 的奇妙冒险 不灭钻石','#785b9d'),('跃动青春','#568d75'),('夏日里的故事','#c58b63')],1):
                url=f'https://i0.hdslb.com/bfs/bangumi/smoke-{i}.jpg';series={'id':f'ss{i}','title':title,'coverUrl':url,'episodes':[{'id':f'ep{i}{j}','seriesId':f'ss{i}','seriesTitle':title,'index':j,'title':f'第 {j} 集 · 放学后的故事','bvid':'','aid':0,'cid':0,'bangumi':True,'localUri':''} for j in range(1,5)]};store.save_series(series)
                pixmap=QPixmap(640,960);pixmap.fill(QColor(color));painter=QPainter(pixmap);gradient=QLinearGradient(0,0,640,960);gradient.setColorAt(0,QColor(color));gradient.setColorAt(1,QColor('#162137'));painter.fillRect(pixmap.rect(),gradient);painter.setPen(QColor('#e6f1ff'));painter.setFont(QFont('Microsoft YaHei',84,QFont.Bold));painter.drawText(pixmap.rect(),Qt.AlignCenter,title[:2]);painter.end();buffer=QBuffer();buffer.open(QIODevice.WriteOnly);pixmap.save(buffer,'PNG');(data_dir/'covers'/(hashlib.sha256(url.encode()).hexdigest()+'.image')).write_bytes(bytes(buffer.data()))
            store.set_pinned('ss3',True);store.save_progress(store.find_series('ss1')['episodes'][1],136000,1395000)
        window=MainWindow(data_dir,smoke=bool(args.smoke_test));window.show()
    except Exception as error:
        if args.smoke_test:
            report=Path(args.smoke_test);report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps({'ok':False,'phase':'startup','error':str(error),'traceback':traceback.format_exc()},ensure_ascii=False,indent=2),'utf-8')
        else:
            (data_dir/'startup-error.log').write_text(traceback.format_exc(),'utf-8');QMessageBox.critical(None,'番匣启动未完成','请确认已完整解压安装 ZIP。原有片库文件保留。\n\n'+str(error)[:400])
        return 1
    if args.smoke_test:
        report=Path(args.smoke_test);report.parent.mkdir(parents=True,exist_ok=True)
        def verify():
            result={'ok':False,'phase':'native-window','windowSize':[window.width(),window.height()],'screenshots':[]}
            def capture(name):
                path=report.with_name(name)
                pixmap=window.grab()
                if pixmap.isNull():raise RuntimeError('Window capture is empty: '+name)
                saved=pixmap.save(str(path),'PNG')
                if not saved or not path.is_file() or path.stat().st_size<100:
                    raise RuntimeError('Window screenshot was not saved: '+name)
                if path.read_bytes()[:8]!=b'\x89PNG\r\n\x1a\n':
                    raise RuntimeError('Window screenshot has no PNG signature: '+name)
                result['screenshots'].append(path.name)
            try:
                # Frozen builds use optimize=1. Assertions must never perform smoke
                # checks or produce artifacts, because Python removes them under -O.
                if not window.isVisible() or window.width()<1000:
                    raise RuntimeError('Main window is not visible at the required desktop size')
                window.player._ensure_engine()
                if window.player._engine is None:
                    raise RuntimeError('Native media engine was not initialized')
                result['nativePlayerInitialized']=True
                for page in ('library','downloads','settings'):
                    window.navigate(page);app.processEvents();capture('desktop-'+page+'.png')
                window.open_series('ss1');app.processEvents();capture('desktop-episodes.png')
                if window.episode_title.text()!='擅长捉弄的高木同学':
                    raise RuntimeError('Opening the series did not display the selected episode list')
                window.set_fullscreen(True);app.processEvents()
                if not window._full_screen or not window.isFullScreen():
                    raise RuntimeError('The player did not enter fullscreen')
                window.toggle_episode_panel();app.processEvents()
                if not window.episode_panel.isVisible():
                    raise RuntimeError('The fullscreen episode panel did not open')
                window.set_fullscreen(False);app.processEvents()
                if window._full_screen or window.isFullScreen():
                    raise RuntimeError('The player did not leave fullscreen')
                if len(result['screenshots'])!=4:
                    raise RuntimeError('Expected four desktop screenshots')
                result['ok']=True;result['phase']='completed'
            except Exception as error:result.update(error=str(error),traceback=traceback.format_exc())
            report.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');window._quitting=True;window.close();app.exit(0 if result['ok'] else 1)
        QTimer.singleShot(1500,verify)
    return app.exec()

if __name__=='__main__':
    raise SystemExit(main())
