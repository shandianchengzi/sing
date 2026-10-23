#!/usr/bin/env python3
"""Reproducibly build and validate the original-weight mobile model."""
import hashlib
import subprocess
import sys
import json
import shutil
import tarfile
import urllib.request
from pathlib import Path

root=Path(__file__).resolve().parents[2]
work=root/'build/mobile-model'
work.mkdir(parents=True,exist_ok=True)
archive=work/'2stems.tar.gz'
if not archive.exists():
    urllib.request.urlretrieve('https://github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz',archive)
if hashlib.sha256(archive.read_bytes()).hexdigest()!='f3a90b39dd2874269e8b05a48a86745df897b848c61f3958efc80a39152bd692':
    raise SystemExit('Original checkpoint archive checksum mismatch')
with tarfile.open(archive) as tar:
    for name in ['checkpoint','model.meta','model.index','model.data-00000-of-00001']:
        member=tar.getmember(name)
        if not member.isfile(): raise ValueError('Invalid checkpoint member')
        with tar.extractfile(member) as stream: (work/name).write_bytes(stream.read())
assets=work/'assets'; assets.mkdir(exist_ok=True)
model=assets/'spleeter-2stems-fp32.tflite'
subprocess.run([sys.executable,str(Path(__file__).with_name('export_model.py')),str(work/'model'),str(model)],check=True)
# TensorFlow may serialize equivalent graphs differently between build hosts.
# Authenticate each artifact through the signed APK's own manifest. Existing
# verified installations retain their model/checksum across program updates.
metadata=json.loads(model.with_suffix('.json').read_text())
info=work/'info'; info.mkdir(exist_ok=True)
shutil.copyfile(model.with_suffix('.json'),info/'spleeter-2stems-fp32.json')
shutil.copyfile(model,work/f"spleeter-2stems-fp32-{metadata['sha256']}.tflite")
subprocess.run([sys.executable,str(Path(__file__).with_name('verify_model.py')),str(work/'model'),str(model),str(work/'verification.json')],check=True)
