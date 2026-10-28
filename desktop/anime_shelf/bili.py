"""Call the unchanged, fixture-tested Java resolver without exposing cookies in argv."""
from __future__ import annotations
import json
import os
from pathlib import Path
import subprocess
import sys

class BiliError(RuntimeError):
    pass

def resource_root():
    return Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parent.parent))

class BiliClient:
    def __init__(self, cookie='', runner=None, java_path=None, jar_path=None):
        if not isinstance(cookie, str) or any(c in cookie for c in '\r\n\0'):
            raise BiliError('登录状态格式无效，请重新登录')
        self.cookie = cookie
        self.runner = runner
        root = resource_root()
        self.java_path = Path(java_path) if java_path else root / 'runtime/java/bin' / ('java.exe' if os.name == 'nt' else 'java')
        self.jar_path = Path(jar_path) if jar_path else root / 'bridge/anime-bridge.jar'

    def _run(self, payload):
        if not self.java_path.is_file() or not self.jar_path.is_file():
            raise BiliError('媒体解析运行组件缺失，请重新解压完整安装包')
        flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        try:
            result = subprocess.run([str(self.java_path), '-Dfile.encoding=UTF-8', '-Djava.net.useSystemProxies=true', '-jar', str(self.jar_path)],
                input=json.dumps(payload, ensure_ascii=False), text=True, encoding='utf-8',
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90, check=False, creationflags=flags)
            if result.returncode or len(result.stdout.encode('utf-8')) > 16 * 1024 * 1024:
                raise BiliError('媒体解析组件运行失败，请检查安装文件')
            return json.loads(result.stdout)
        except subprocess.TimeoutExpired:
            raise BiliError('请求超时，请检查网络后重试') from None
        except (OSError, UnicodeError, ValueError):
            raise BiliError('无法读取媒体解析结果，请重新解压完整安装包') from None

    def _call(self, operation, **values):
        response = (self.runner or self._run)({'operation': operation, 'cookie': self.cookie, **values})
        if not isinstance(response, dict) or type(response.get('ok')) is not bool:
            raise BiliError('媒体解析结果格式不正确')
        if not response['ok']:
            message = response.get('message')
            # Error text comes from our authored bridge, but still avoid accidental URL/credential output.
            if not isinstance(message, str) or len(message) > 1000 or any(s in message.lower() for s in ('https://', 'http://', 'sessdata', 'cookie', 'token=')):
                message = '请求失败，请检查网络、登录状态或所选画质'
            raise BiliError(message)
        return response.get('result')

    def resolve(self, value):
        result = self._call('resolve', input=value)
        if not isinstance(result, dict) or not isinstance(result.get('episodes'), list): raise BiliError('番剧信息不完整')
        return result

    def qualities(self, episode):
        result = self._call('qualities', episode=episode)
        if not isinstance(result, list): raise BiliError('清晰度信息不完整')
        return result

    def verify_login(self):
        result = self._call('verify_login')
        if not isinstance(result, str): raise BiliError('登录验证结果不明确')
        return result

    def refresh_series(self, series):
        return self._call('refresh_series', series=series)

    def refresh(self, episode, quality_id):
        return self._call('refresh', episode=episode, qualityId=quality_id)
