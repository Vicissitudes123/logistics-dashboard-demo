import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from anime_shelf.bili import BiliClient, BiliError

class BiliTests(unittest.TestCase):
    def test_resolver_and_qualities_preserve_unicode_and_full_media_metadata(self):
        calls = []
        choices = [{'id': '125:12:30280', 'qn': 125, 'videoUrls': ['https://media.example/a', 'https://media.example/b'], 'audioUrl': 'https://media.example/audio'}]
        def runner(payload):
            calls.append(payload)
            return {'ok': True, 'result': {'title': '高木同学', 'episodes': []} if payload['operation'] == 'resolve' else choices}
        client = BiliClient('SESSDATA=private', runner=runner)
        self.assertEqual('高木同学', client.resolve('高木 ep1')['title'])
        self.assertEqual(choices, client.qualities({'id': 'ep1'}))
        self.assertEqual('SESSDATA=private', calls[0]['cookie']); self.assertEqual('qualities', calls[1]['operation'])

    def test_cookies_go_to_stdin_never_process_arguments_and_stderr_is_not_exposed(self):
        with tempfile.TemporaryDirectory() as tmp:
            java, jar = Path(tmp) / 'java', Path(tmp) / 'bridge.jar'; java.touch(); jar.touch()
            def run(argv, **kwargs):
                self.assertNotIn('COOKIE_CANARY', ' '.join(argv)); self.assertIn('COOKIE_CANARY', kwargs['input'])
                self.assertIn('-Djava.net.useSystemProxies=true', argv)
                self.assertEqual('utf-8', kwargs['encoding']); self.assertGreater(kwargs['timeout'], 0)
                return subprocess.CompletedProcess(argv, 0, '{"ok":true,"result":"测试账号"}', 'SECRET_STDERR')
            with patch('anime_shelf.bili.subprocess.run', side_effect=run):
                self.assertEqual('测试账号', BiliClient('COOKIE_CANARY', java_path=java, jar_path=jar).verify_login())

    def test_safe_auth_failure_and_bad_transport_messages(self):
        with self.assertRaisesRegex(BiliError, '请先登录'):
            BiliClient(runner=lambda _: {'ok': False, 'message': '请先登录'}).verify_login()
        with self.assertRaises(BiliError) as failure:
            BiliClient(runner=lambda _: {'ok': False, 'message': 'https://media.example/?token=SECRET'}).verify_login()
        self.assertNotIn('SECRET', str(failure.exception))
        with self.assertRaises(BiliError): BiliClient('injected\r\nCookie: value')
        with self.assertRaises(BiliError): BiliClient(runner=lambda _: {'ok': True, 'result': None}).verify_login()

    def test_missing_runtime_fails_with_actionable_message(self):
        with self.assertRaisesRegex(BiliError, '运行组件缺失'):
            BiliClient(java_path='/does-not-exist/java', jar_path='/does-not-exist/jar').resolve('ep1')

if __name__ == '__main__': unittest.main()
