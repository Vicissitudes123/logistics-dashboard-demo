import unittest
from anime_shelf.network import classify
from anime_shelf.updates import validate_manifest, check_update, UpdateError, API_URL

class PlatformToolsTest(unittest.TestCase):
    def manifest(self, version='0.1.13.2'):
        return {'schemaVersion':1,'packageName':'com.animeshelf.desktop','platform':'windows-x64','versionName':version,
                'zipUrl':f'https://github.com/Vicissitudes123/logistics-dashboard-demo/releases/download/anime-shelf-desktop-v{version}/anime-shelf-desktop-{version}-windows-x64.zip',
                'zipSha256':'a'*64,'zipBytes':123,'notes':['更新'], 'sourceCommit':'b'*40}
    def test_network_fails_closed_for_unknown_virtual_and_cellular(self):
        for kind in ['unknown','checking','cellular','vpn','invalid']:
            self.assertFalse(classify({'kind':kind})['allowed'])
        for kind in ['wifi','ethernet']:self.assertTrue(classify({'kind':kind})['allowed'])
    def test_manifest_rejects_untrusted_urls_platforms_and_missing_data(self):
        m=self.manifest();self.assertTrue(validate_manifest(m,'0.1.13.2','0.1.13.1')['available'])
        for key,value in [('zipUrl','https://example.com/app.zip'),('zipUrl',m['zipUrl']+'?token=secret'),('zipBytes',True),('platform','android'),('notes',['private\nvalue'])]:
            bad=dict(m);bad[key]=value
            with self.assertRaises(UpdateError):validate_manifest(bad,'0.1.13.2','0.1.13.1')
    def test_only_matching_desktop_release_is_selected(self):
        version='0.1.13.2';url=f'https://github.com/Vicissitudes123/logistics-dashboard-demo/releases/download/anime-shelf-desktop-v{version}/desktop-release.json'
        releases=[{'tag_name':'anime-shelf-tablet-v99.0.0','assets':[]},{'tag_name':'anime-shelf-desktop-v'+version,'assets':[{'name':'desktop-release.json','browser_download_url':url}]}]
        calls=[]
        def fetch(u):calls.append(u);return releases if u==API_URL else self.manifest()
        self.assertTrue(check_update(fetch=fetch)['available']);self.assertEqual([API_URL,url],calls)
        self.assertFalse(check_update(fetch=lambda u:[])['available'])

if __name__=='__main__':unittest.main()
