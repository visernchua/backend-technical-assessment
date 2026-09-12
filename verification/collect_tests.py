#!/usr/bin/env python3
"""Capture correctness evidence immediately after clean verify, before running the benchmark."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
folder = root / 'verification'
suites = []
for path in sorted((root / 'target/surefire-reports').glob('TEST-*.xml')):
    report = ET.parse(path).getroot()
    if report.attrib['name'].endswith('H2Benchmark'):
        continue
    suite = {'name': report.attrib['name'], 'seconds': float(report.attrib['time'])}
    suite.update({key: int(report.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')})
    suite['cases'] = [
        {'name': case.attrib['name'], 'seconds': float(case.attrib['time']),
         'status': 'failed' if case.find('failure') is not None or case.find('error') is not None
         else 'skipped' if case.find('skipped') is not None else 'passed'}
        for case in report.findall('testcase')
    ]
    suites.append(suite)
if not suites:
    raise SystemExit('No correctness reports found. Run ./mvnw clean verify first.')
summary = {key: sum(suite[key] for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
summary['suites'] = suites
(folder / 'test-results.json').write_text(json.dumps(summary, indent=2) + '\n')
report = ET.parse(root / 'target/site/jacoco/jacoco.xml').getroot()
coverage = {counter.attrib['type']: {key: int(counter.attrib[key]) for key in ('covered', 'missed')}
            for counter in report.findall('counter')}
(folder / 'coverage-results.json').write_text(json.dumps(coverage, indent=2) + '\n')
paths = sorted([*(root / 'src/main').rglob('*.java'), *(root / 'src/main').rglob('*.yml'),
                *(root / 'src/main').rglob('*.sql'), root / 'pom.xml'])
(folder / 'source-sha256.txt').write_text(''.join(
    hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + str(path.relative_to(root)) + '\n'
    for path in paths))
print({key: value for key, value in summary.items() if key != 'suites'})
