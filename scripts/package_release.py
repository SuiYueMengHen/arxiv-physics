#!/usr/bin/env python3
"""Package a signed release APK and its update manifest; no credentials are emitted."""
import hashlib
import json
from pathlib import Path
import shutil
root = Path(__file__).resolve().parents[1]
outputs = root / 'app/build/outputs/apk/release'
meta = json.loads((outputs / 'output-metadata.json').read_text())
element = meta['elements'][0]
source = outputs / element['outputFile']
version = element['versionName']
code = element['versionCode']
assert meta['applicationId'] == 'org.arxiv.physics'
assert source.name == 'app-release.apk', 'Build with private signing.properties before packaging'
assert code > 0 and all(c.isdigit() or c == '.' for c in version)
folder = root / 'dist' / f'v{version}'
folder.mkdir(parents=True, exist_ok=True)
name = f'arxiv-physics-v{version}.apk'
apk = folder / name
shutil.copyfile(source, apk)
notes_path = root / 'docs/releases' / f'v{version}.md'
manifest = {'versionCode': code, 'versionName': version, 'apkName': name,
            'size': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
            'notes': notes_path.read_text() if notes_path.exists() else ''}
(folder / 'update.json').write_text(json.dumps(manifest, indent=2) + '\n')
(folder / 'SHA256SUMS').write_text(f"{manifest['sha256']}  {name}\n")
print(folder)
print('发布 Release 后，将 update.json 复制到 updates/latest.json，再提交推送；避免清单指向尚未发布的 APK。')
