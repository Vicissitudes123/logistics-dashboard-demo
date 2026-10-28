import http.cookiejar
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from anime_shelf.auth import QrLogin, CredentialStore, AuthError, _BiliRedirects

class Response(io.BytesIO):
    def __init__(self, obj, url): super().__init__(json.dumps(obj).encode()); self.url = url
    def geturl(self): return self.url

class Opener:
    def __init__(self, *responses): self.responses = list(responses); self.requests = []
    def open(self, request, timeout):
        self.requests.append(request); return Response(self.responses.pop(0), request.full_url)

def cookie(jar, domain, name, value):
    jar.set_cookie(http.cookiejar.Cookie(0, name, value, None, False, domain, True, domain.startswith('.'), '/', True, True, None, True, None, None, {}))

class AuthTests(unittest.TestCase):
    def test_qr_uses_official_domains_and_status_codes(self):
        opener = Opener({'code': 0, 'data': {'url': 'https://passport.bilibili.com/scan?key=abc', 'qrcode_key': '12345678'}},
                        {'code': 0, 'data': {'code': 86101}}, {'code': 0, 'data': {'code': 86090}}, {'code': 0, 'data': {'code': 86038}})
        qr = QrLogin(opener=opener)
        self.assertEqual('12345678', qr.generate()['key'])
        self.assertEqual(['waiting', 'scanned', 'expired'], [qr.poll('12345678')['status'] for _ in range(3)])
        self.assertTrue(all(r.full_url.startswith('https://passport.bilibili.com/') for r in opener.requests))

    def test_success_extracts_only_cookies_applicable_to_official_api(self):
        jar = http.cookiejar.CookieJar(); cookie(jar, '.bilibili.com', 'SESSDATA', 'PRIVATE'); cookie(jar, '.example.com', 'SECRET', 'DO_NOT_FORWARD')
        cookie(jar, 'passport.bilibili.com', 'PASSPORT_ONLY', 'NOT_FOR_API')
        result = QrLogin(opener=Opener({'code': 0, 'data': {'code': 0}}), cookie_jar=jar).poll('12345678')
        self.assertEqual('success', result['status']); self.assertIn('SESSDATA=PRIVATE', result['cookie'])
        self.assertNotIn('DO_NOT_FORWARD', result['cookie']); self.assertNotIn('NOT_FOR_API', result['cookie'])

    def test_untrusted_callback_is_never_requested(self):
        opener = Opener({'code': 0, 'data': {'code': 0, 'url': 'https://evil.example/?SESSDATA=secret'}})
        with self.assertRaises(AuthError): QrLogin(opener=opener).poll('12345678')
        self.assertEqual(1, len(opener.requests))
        with self.assertRaises(AuthError): QrLogin(opener=opener).poll('header\r\ninjection')

    def test_cancelled_poll_and_expired_code_never_return_session(self):
        jar = http.cookiejar.CookieJar(); cookie(jar, '.bilibili.com', 'SESSDATA', 'PRIVATE')
        qr = QrLogin(opener=Opener({'code': 0, 'data': {'code': 86038}}), cookie_jar=jar)
        expired = qr.poll('12345678'); self.assertEqual('expired', expired['status']); self.assertEqual('', expired['cookie'])
        qr.cancel()
        with self.assertRaisesRegex(AuthError, '已取消'): qr.poll('12345678')

    def test_cancellation_while_poll_response_arrives_cannot_disclose_login_cookie(self):
        jar = http.cookiejar.CookieJar(); cookie(jar, '.bilibili.com', 'SESSDATA', 'PRIVATE')
        qr = QrLogin(cookie_jar=jar)
        class CancelOnRead(Response):
            def read(self, *args): qr.cancel(); return super().read(*args)
        class SlowOpener:
            def open(self, request, timeout): return CancelOnRead({'code': 0, 'data': {'code': 0}}, request.full_url)
        qr.opener = SlowOpener()
        with self.assertRaises(AuthError) as failure: qr.poll('12345678')
        self.assertNotIn('PRIVATE', str(failure.exception))

    def test_session_file_contains_only_protected_payload_and_clear_removes_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'session.dat'
            box = CredentialStore(path, protector=lambda b: b[::-1], unprotector=lambda b: b[::-1])
            box.save('SESSDATA=PRIVATE_CANARY'); self.assertNotIn('PRIVATE_CANARY', path.read_text())
            self.assertEqual('SESSDATA=PRIVATE_CANARY', box.load()); box.clear(); self.assertEqual('', box.load())

    def test_protection_failure_never_creates_plaintext_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'session.dat'
            def fail(_): raise AuthError('fixture protect failed')
            with self.assertRaises(AuthError): CredentialStore(path, protector=fail).save('SESSDATA=secret')
            self.assertFalse(path.exists())

if __name__ == '__main__': unittest.main()
