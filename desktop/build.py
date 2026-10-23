"""Build a replaceable-library Windows folder, ZIP and integrity manifest."""
import hashlib
from importlib import metadata
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
version = os.environ.get('DESKTOP_VERSION', '1.0.0')
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise ValueError('Invalid version')
core = ROOT / 'desktop/core.py'
core.write_text(re.sub(r"^VERSION = '[^']+'", f"VERSION = '{version}'", core.read_text(encoding='utf-8'), flags=re.M), encoding='utf-8')
subprocess.run([sys.executable, '-m', 'PyInstaller', '--noconfirm', '--clean', '--windowed',
    '--name', 'SingDesktop', '--distpath', 'build/desktop/dist', '--workpath', 'build/desktop/work',
    '--specpath', 'build/desktop', '--paths', 'desktop',
    '--collect-all', 'imageio_ffmpeg', '--collect-data', 'soundfile', '--collect-binaries', '_soundfile_data',
    '--collect-binaries', '_sounddevice_data', '--copy-metadata', 'imageio-ffmpeg',
    '--exclude-module', 'tkinter', '--exclude-module', 'PySide6.QtQml', '--exclude-module', 'PySide6.QtQuick',
    '--exclude-module', 'PySide6.QtWebEngineCore', '--exclude-module', 'PySide6.QtMultimedia',
    'desktop/app.py'], check=True)
package = ROOT / 'build/desktop/dist/SingDesktop'
for source in ['LICENSE', 'NOTICE', 'desktop/README.md', 'desktop/THIRD_PARTY.md']:
    shutil.copy2(ROOT / source, package / Path(source).name)
shutil.copytree(ROOT / 'desktop/licenses', package / 'dependency-licenses', dirs_exist_ok=True)
shutil.copy2(ROOT / 'licenses/Apache-2.0.txt', package / 'dependency-licenses/Apache-2.0.txt')
# Preserve wheel-provided notices, including bundled libsndfile, PortAudio and FFmpeg notices.
index = []
for dist in metadata.distributions():
    name = dist.metadata.get('Name', '')
    if not name:
        continue
    texts = []
    for f in dist.files or []:
        if any(part.lower().startswith(('license', 'copying', 'notice', 'authors')) for part in Path(str(f)).parts):
            path = Path(dist.locate_file(f))
            if path.is_file() and path.stat().st_size < 2 * 1024 * 1024:
                dest = package / 'dependency-licenses' / name / str(f).replace('..', '_')
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, dest)
                texts.append(str(dest.relative_to(package)))
    index.append({'package': name, 'version': dist.version, 'notices': texts,
                  'homepage': dist.metadata.get('Home-page', ''), 'license': dist.metadata.get('License-Expression') or dist.metadata.get('License', '')})
(package / 'dependency-licenses/packages.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
# Source snapshot and matching dependency versions accompany the binary distribution.
source = ROOT / 'build/desktop/source/SingDesktop-source'
source.mkdir(parents=True, exist_ok=True)
shutil.copytree(ROOT / 'desktop', source / 'desktop', ignore=shutil.ignore_patterns('__pycache__', '.pytest_cache'), dirs_exist_ok=True)
for name in ['LICENSE', 'NOTICE']:
    shutil.copy2(ROOT / name, source / name)
shutil.copytree(ROOT / 'licenses', source / 'licenses', dirs_exist_ok=True)
shutil.make_archive(str(ROOT / 'build/sing-desktop-source'), 'zip', source.parent, source.name)
exe = package / 'SingDesktop.exe'
if os.name == 'nt':
    smoke = ROOT / 'build/desktop/smoke.json'
    subprocess.run([str(exe), '--smoke-test', str(smoke)], check=True, timeout=90)
    report = json.loads(smoke.read_text(encoding='utf-8'))
    if not report.get('success'):
        raise RuntimeError('Packaged executable smoke test failed')
name = f'sing-desktop-{version}-windows-x64'
archive = Path(shutil.make_archive(str(ROOT / 'build' / name), 'zip', package.parent, package.name))
shutil.copy2(archive, ROOT / 'build/sing-desktop-windows-x64.zip')
sha = hashlib.sha256(archive.read_bytes()).hexdigest()
manifest = {'version': version, 'windows_x64': {'url': f'https://github.com/shandianchengzi/sing/releases/download/desktop-preview/{archive.name}',
            'sha256': sha, 'size': archive.stat().st_size}}
(ROOT / 'build/desktop-update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=False))
