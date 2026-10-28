"""Run desktop unit tests and preserve machine-readable, reviewable results."""
import os
from pathlib import Path
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
reports = ROOT / 'reports'
reports.mkdir(exist_ok=True)

class Result(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.rows = []
    def startTest(self, test):
        self.started = time.monotonic(); self.current = {'name': test.id(), 'status': 'success', 'detail': ''}; super().startTest(test)
    def addFailure(self, test, error):
        self.current.update(status='failure', detail=self._exc_info_to_string(error, test)); super().addFailure(test, error)
    def addError(self, test, error):
        self.current.update(status='error', detail=self._exc_info_to_string(error, test)); super().addError(test, error)
    def addSkip(self, test, reason):
        self.current.update(status='skipped', detail=reason); super().addSkip(test, reason)
    def stopTest(self, test):
        self.current['time'] = time.monotonic() - self.started; self.rows.append(self.current); super().stopTest(test)

if __name__ == '__main__':
    suite = unittest.defaultTestLoader.discover(str(ROOT / 'tests'))
    with (reports / 'desktop-tests.txt').open('w', encoding='utf-8') as output:
        result = unittest.TextTestRunner(stream=output, verbosity=2, resultclass=Result).run(suite)
    document = ET.Element('testsuite', name='AnimeShelfDesktop', tests=str(result.testsRun), failures=str(len(result.failures)), errors=str(len(result.errors)), skipped=str(len(result.skipped)))
    for row in result.rows:
        case = ET.SubElement(document, 'testcase', name=row['name'], time=f"{row['time']:.4f}")
        if row['status'] != 'success': ET.SubElement(case, row['status']).text = row['detail']
    ET.ElementTree(document).write(reports / 'desktop-tests.xml', encoding='utf-8', xml_declaration=True)
    print((reports / 'desktop-tests.txt').read_text(encoding='utf-8'))
    if result.testsRun < 1 or not result.wasSuccessful(): raise SystemExit(1)
