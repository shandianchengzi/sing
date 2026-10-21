"""Real CPU Demucs + ffmpeg end-to-end smoke test, downloads model on first run."""
import os
import tempfile
import subprocess
import time
from pathlib import Path

workspace = tempfile.TemporaryDirectory()
os.environ['SING_TOKEN'] = 'local-smoke-test-token'
os.environ['SING_DATA'] = str(Path(workspace.name) / 'jobs')
os.environ['OMP_NUM_THREADS'] = '2'
import app as service
from fastapi.testclient import TestClient

source = Path(workspace.name) / 'song.mp4'
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=black:s=16x16:r=1:d=3',
                '-f', 'lavfi', '-i', 'sine=frequency=440:duration=3', '-c:v', 'mpeg4', '-c:a', 'aac', '-shortest', str(source)], check=True)
with TestClient(service.app) as client:
    headers = {'Authorization': 'Bearer local-smoke-test-token'}
    response = client.post('/jobs', headers=headers, content=source.read_bytes())
    assert response.status_code == 202, response.text
    job_id = response.json()['id']
    for attempt in range(300):
        state = client.get('/jobs/' + job_id, headers=headers).json()
        if state['status'] in ('succeeded', 'failed'):
            break
        time.sleep(2)
    assert state['status'] == 'succeeded', state
    result = client.get('/jobs/' + job_id + '/result', headers=headers)
    assert result.status_code == 200
    output = Path(workspace.name) / 'instrumental.m4a'
    output.write_bytes(result.content)
    probe = subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_type',
                                      '-of', 'csv=p=0', str(output)]).decode().strip()
    assert probe == 'audio', probe
    duration = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                                               '-of', 'csv=p=0', str(output)]))
    assert 2.8 < duration < 3.3, duration
    assert client.delete('/jobs/' + job_id, headers=headers).status_code == 200
    print('PASS: real Demucs CPU inference, MP4 upload, audio-only AAC download, duration and deletion')
