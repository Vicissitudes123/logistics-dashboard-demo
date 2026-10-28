"""Official Bilibili QR authentication and Windows DPAPI credential persistence.

The UI renders the returned URL locally. This module never sends a QR code or
session to a third-party QR renderer, CDN, diagnostic endpoint or backup.
"""
from __future__ import annotations
import base64
import ctypes
from ctypes import wintypes
import http.cookiejar
import json
import os
from pathlib import Path
import re
import threading
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
REFERER = 'https://www.bilibili.com/'

class AuthError(RuntimeError):
    pass

def _trusted_url(value):
    try:
        u = urllib.parse.urlsplit(value)
        return (u.scheme == 'https' and u.hostname is not None and
                (u.hostname == 'bilibili.com' or u.hostname.endswith('.bilibili.com')) and
                not u.username and not u.password and u.port in (None, 443) and not u.fragment)
    except (TypeError, ValueError):
        return False

class _BiliRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not _trusted_url(newurl):
            raise AuthError('登录跳转地址不受支持，已停止请求')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

class QrLogin:
    def __init__(self, opener=None, cookie_jar=None):
        self._cancelled = threading.Event()
        self.jar = cookie_jar if cookie_jar is not None else http.cookiejar.CookieJar()
        self.opener = opener or urllib.request.build_opener(_BiliRedirects(), urllib.request.HTTPCookieProcessor(self.jar))

    def cancel(self):
        self._cancelled.set()

    def _check_active(self):
        if self._cancelled.is_set(): raise AuthError('登录已取消')

    def _get(self, url, json_response=True):
        self._check_active()
        if not _trusted_url(url): raise AuthError('登录地址不受支持')
        request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Referer': REFERER, 'Accept': 'application/json' if json_response else 'text/html'})
        try:
            with self.opener.open(request, timeout=20) as response:
                if hasattr(response, 'geturl') and not _trusted_url(response.geturl()): raise AuthError('登录返回了不受支持的地址')
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024: raise AuthError('登录响应超过大小限制')
            self._check_active()
            if not json_response: return None
            value = json.loads(raw.decode('utf-8'))
            if not isinstance(value, dict) or type(value.get('code')) is not int or value['code'] != 0 or not isinstance(value.get('data'), dict):
                raise AuthError('B站登录服务暂时不可用，请稍后重试')
            return value['data']
        except AuthError:
            raise
        except (OSError, ValueError, UnicodeError):
            raise AuthError('登录请求未完成，请检查网络后重试') from None

    def generate(self):
        self.jar.clear()
        data = self._get('https://passport.bilibili.com/x/passport-login/web/qrcode/generate')
        url, key = data.get('url'), data.get('qrcode_key')
        if not isinstance(url, str) or len(url) > 4096 or not _trusted_url(url) or not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,256}', key):
            raise AuthError('二维码信息不完整，请重新获取')
        return {'url': url, 'key': key}

    def poll(self, key):
        if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,256}', key): raise AuthError('二维码编号无效')
        data = self._get('https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=' + urllib.parse.quote(key, safe=''))
        code = data.get('code')
        states = {86101: ('waiting', '请用 B站 App 扫码'), 86090: ('scanned', '已扫码，请在手机上确认'), 86038: ('expired', '二维码已过期，请刷新')}
        if type(code) is not int: raise AuthError('B站登录状态格式无效，请重新扫码')
        if code in states:
            status, message = states[code]
            return {'status': status, 'cookie': '', 'message': message}
        if code != 0: raise AuthError('B站未确认此次登录，请重新扫码')
        request = urllib.request.Request('https://api.bilibili.com/x/web-interface/nav')
        self.jar.add_cookie_header(request)
        cookie = request.get_header('Cookie', '')
        # Some deployments complete the cookie exchange through an official callback.
        if not cookie or 'SESSDATA=' not in cookie:
            callback = data.get('url', '')
            if callback:
                if not _trusted_url(callback): raise AuthError('登录回调地址不受支持')
                self._get(callback, json_response=False)
                request = urllib.request.Request('https://api.bilibili.com/x/web-interface/nav')
                self.jar.add_cookie_header(request); cookie = request.get_header('Cookie', '')
        if not cookie or 'SESSDATA=' not in cookie or any(c in cookie for c in '\r\n\0'):
            raise AuthError('已确认扫码，但没有取得可验证的登录状态，请重试')
        self._check_active()
        return {'status': 'success', 'cookie': cookie, 'message': '扫码成功，正在验证账号'}

class _DataBlob(ctypes.Structure):
    _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_ubyte))]

def _dpapi(data, protect):
    if os.name != 'nt': raise AuthError('持久保存登录状态仅支持 Windows DPAPI；当前平台不会写入明文凭据')
    crypt32 = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    memory = ctypes.create_string_buffer(data)
    source = _DataBlob(len(data), ctypes.cast(memory, ctypes.POINTER(ctypes.c_ubyte)))
    output = _DataBlob()
    function = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    function.argtypes = [ctypes.POINTER(_DataBlob), ctypes.c_void_p, ctypes.POINTER(_DataBlob), ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(_DataBlob)]
    function.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]; kernel32.LocalFree.restype = ctypes.c_void_p
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise AuthError('Windows 无法保存或读取此账号的加密登录状态，请重新登录')
    try: return ctypes.string_at(output.pbData, output.cbData)
    finally: kernel32.LocalFree(output.pbData)

class CredentialStore:
    def __init__(self, path, protector=None, unprotector=None):
        path = Path(path)
        self.path = path if path.suffix else path / 'session.dat'
        self.protect = protector or (lambda data: _dpapi(data, True))
        self.unprotect = unprotector or (lambda data: _dpapi(data, False))

    def load(self):
        if not self.path.exists(): return ''
        try:
            if self.path.stat().st_size > 1024 * 1024: raise ValueError()
            data = json.loads(self.path.read_text(encoding='utf-8'))
            if set(data) != {'version', 'ciphertext'} or type(data['version']) is not int or data['version'] != 1: raise ValueError()
            cookie = self.unprotect(base64.b64decode(data['ciphertext'], validate=True)).decode('utf-8')
            if any(c in cookie for c in '\r\n\0') or len(cookie) > 65536: raise ValueError()
            return cookie
        except AuthError: raise
        except (OSError, ValueError, UnicodeError, TypeError):
            raise AuthError('本机登录状态无法读取，请重新登录') from None

    def save(self, cookie):
        if not isinstance(cookie, str) or not cookie or len(cookie) > 65536 or any(c in cookie for c in '\r\n\0'):
            raise AuthError('登录状态格式无效')
        encrypted = self.protect(cookie.encode('utf-8'))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + '.tmp')
        try:
            with temporary.open('w', encoding='utf-8') as output:
                json.dump({'version': 1, 'ciphertext': base64.b64encode(encrypted).decode('ascii')}, output)
                output.flush(); os.fsync(output.fileno())
            os.replace(temporary, self.path)
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise

    def clear(self):
        self.path.unlink(missing_ok=True)
