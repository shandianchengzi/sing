"""Build update metadata from the actual signed APK, not a guessed version."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

apk = Path(sys.argv[1])
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
aapt = sdk / 'build-tools' / '35.0.0' / 'aapt'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not package or package[1] != 'com.shandian.sing':
    raise SystemExit('Wrong or unreadable APK package')
code = int(package[2])
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
versioned = apk.with_name(f'sing-{code}-{sha256}.apk')
shutil.copyfile(apk, versioned)
info = {
    'versionCode': code,
    'versionName': package[3],
    'sha256': sha256,
    'size': apk.stat().st_size,
    'url': f'https://github.com/shandianchengzi/sing/releases/download/preview/{versioned.name}',
}
apk.with_name('update.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(info, ensure_ascii=False))
