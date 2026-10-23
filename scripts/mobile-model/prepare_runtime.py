#!/usr/bin/env python3
"""Fetch pinned Apache-2.0 Android inference runtime (no model download here)."""
from pathlib import Path
import hashlib
import urllib.request
import zipfile

root=Path(__file__).resolve().parents[2]
output=root/'build/tflite'
output.mkdir(parents=True,exist_ok=True)
artifacts={
 'tensorflow-lite':'34b065817e294e7dd3569504e8b9454938ceae453b6a459da54d5f6e45d3c94e',
 'tensorflow-lite-api':'885b95bc9e47a4aa5cf8e71523aed9b05e397cfef1ea09773f13fea8b649fcee',
}
for name,expected in artifacts.items():
    archive=output/(name+'.aar')
    if not archive.exists():
        urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/org/tensorflow/{name}/2.16.1/{name}-2.16.1.aar',archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
        raise SystemExit('Android runtime SHA256 mismatch: '+name)
    with zipfile.ZipFile(archive) as z:
        (output/(name+'.jar')).write_bytes(z.read('classes.jar'))
        for item in z.namelist():
            if item.startswith('jni/arm64-v8a/') or item.startswith('jni/x86_64/'):
                target=output/item.replace('jni/','lib/',1)
                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(item))
        if 'LICENSE' in z.namelist(): (output/(name+'-LICENSE')).write_bytes(z.read('LICENSE'))
print('Verified TensorFlow Lite 2.16.1 runtime: arm64-v8a / x86_64')
