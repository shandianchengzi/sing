"""Single-worker, token-protected local Demucs API. No paid services."""
import asyncio
from contextlib import asynccontextmanager
import hmac
import json
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse

ROOT = Path(os.getenv('SING_DATA', './data')).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
TOKEN = os.getenv('SING_TOKEN', '')
if len(TOKEN) < 12 or not TOKEN.isascii() or any(c.isspace() for c in TOKEN):
    raise RuntimeError('Set SING_TOKEN to a private ASCII token of at least 12 characters without spaces')
MAX_BYTES = int(os.getenv('SING_MAX_MB', '100')) * 1024 * 1024
TTL = int(os.getenv('SING_TTL_HOURS', '24')) * 3600
DEVICE = os.getenv('SING_DEVICE', 'cpu')
MODEL = 'htdemucs'
slots = threading.BoundedSemaphore(4)
pool = ThreadPoolExecutor(max_workers=1)
lock = threading.RLock()
jobs = {}
processes = {}


def save(job):
    path = ROOT / job['id'] / 'job.json'
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(job), encoding='utf-8')
    temporary.replace(path)


# Restore completed results; interrupted tasks are explicitly reported after restart.
for path in ROOT.glob('*/job.json'):
    try:
        job = json.loads(path.read_text(encoding='utf-8'))
        if job['id'] != path.parent.name:
            continue
        if job['status'] in ('queued', 'running', 'uploading'):
            job.update(status='failed', error='服务重启，请重新上传')
            save(job)
        jobs[job['id']] = job
    except (ValueError, KeyError, OSError):
        pass


def auth(request: Request):
    if not hmac.compare_digest(request.headers.get('authorization', ''), 'Bearer ' + TOKEN):
        raise HTTPException(401, '服务令牌不正确')


@asynccontextmanager
async def lifespan(application):
    async def sweep():
        while True:
            cleanup()
            await asyncio.sleep(60)
    timer = asyncio.create_task(sweep())
    try:
        yield
    finally:
        timer.cancel()
        with lock:
            for process in list(processes.values()):
                process.terminate()
        pool.shutdown(wait=False, cancel_futures=True)


app = FastAPI(title='Sing local vocal separation', dependencies=[Depends(auth)], lifespan=lifespan)


def find_job(job_id):
    with lock:
        job = jobs.get(job_id)
        if not job:
            raise HTTPException(404, '任务不存在或已清理')
        return job


def execute(job_id, command, timeout):
    with lock:
        if jobs[job_id]['status'] == 'cancelled':
            raise RuntimeError('任务已取消')
        # No shell, user filenames and URLs never become commands.
        with (ROOT / job_id / 'worker.log').open('ab') as log:
            process = subprocess.Popen(command, stdout=log, stderr=log)
        processes[job_id] = process
    try:
        code = process.wait(timeout=timeout)
        if code:
            raise RuntimeError('音频处理失败，请确认文件有效、模型已下载且内存充足')
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
        raise RuntimeError('处理超时，请尝试较短的歌曲')
    finally:
        with lock:
            processes.pop(job_id, None)


def separate(job_id):
    folder = ROOT / job_id
    try:
        with lock:
            job = jobs[job_id]
            if job['status'] == 'cancelled':
                return
            job['status'] = 'running'
            save(job)
        # ffprobe validates uploads before starting expensive inference.
        probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                                '-of', 'default=noprint_wrappers=1:nokey=1', str(folder / 'input')],
                               capture_output=True, text=True, timeout=30, check=True)
        duration = float(probe.stdout.strip())
        if not 0 < duration <= 1800:
            raise RuntimeError('仅支持 30 分钟以内的有效歌曲')
        execute(job_id, [sys.executable, '-m', 'demucs', '--two-stems', 'vocals', '-n', MODEL,
                        '-d', DEVICE, '--segment', '7', '-o', str(folder / 'stems'), str(folder / 'input')], 3600)
        stem = next((folder / 'stems').rglob('no_vocals.wav'), None)
        if stem is None:
            raise RuntimeError('未生成伴奏文件')
        execute(job_id, ['ffmpeg', '-nostdin', '-v', 'error', '-y', '-i', str(stem),
                        '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart', str(folder / 'instrumental.m4a')], 180)
        with lock:
            if jobs[job_id]['status'] != 'cancelled':
                jobs[job_id].update(status='succeeded', duration=duration)
                save(jobs[job_id])
    except Exception as error:
        logging.exception('Separation job %s failed; see its worker.log for details', job_id)
        with lock:
            if jobs[job_id]['status'] != 'cancelled':
                jobs[job_id].update(status='failed', error=str(error) if isinstance(error, RuntimeError) else '无法处理音频，请检查格式或服务器环境')
                save(jobs[job_id])
    finally:
        (folder / 'input').unlink(missing_ok=True)
        shutil.rmtree(folder / 'stems', ignore_errors=True)
        with lock:
            if jobs[job_id]['status'] == 'cancelled':
                shutil.rmtree(folder, ignore_errors=True)
                jobs.pop(job_id, None)
        slots.release()


def cleanup():
    with lock:
        for job_id, job in list(jobs.items()):
            if job['status'] not in ('queued', 'running', 'uploading') and time.time() - job['created'] > TTL:
                shutil.rmtree(ROOT / job_id, ignore_errors=True)
                del jobs[job_id]


@app.get('/health')
def health():
    cleanup()
    return {'service': 'sing-separation', 'api_version': 1, 'device': DEVICE}


@app.post('/jobs', status_code=202)
async def upload(request: Request):
    cleanup()
    if not slots.acquire(blocking=False):
        raise HTTPException(429, '服务队列已满，请稍后重试')
    job_id = uuid.uuid4().hex
    folder = ROOT / job_id
    folder.mkdir()
    job = {'id': job_id, 'status': 'uploading', 'created': time.time()}
    with lock:
        jobs[job_id] = job
        save(job)
    try:
        size = 0
        async with asyncio.timeout(300):
            with (folder / 'input').open('wb') as output:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise HTTPException(413, '歌曲超过服务器文件大小限制')
                    output.write(chunk)
        if not size:
            raise HTTPException(400, '文件为空')
        with lock:
            job['status'] = 'queued'
            save(job)
        pool.submit(separate, job_id)
        return {'id': job_id, 'status': 'queued'}
    except BaseException:
        with lock:
            jobs.pop(job_id, None)
            shutil.rmtree(folder, ignore_errors=True)
        slots.release()
        raise


@app.get('/jobs/{job_id}')
def status(job_id: str):
    return dict(find_job(job_id))


@app.get('/jobs/{job_id}/result')
def result(job_id: str):
    job = find_job(job_id)
    if job['status'] != 'succeeded':
        raise HTTPException(409, '伴奏尚未生成')
    path = ROOT / job_id / 'instrumental.m4a'
    if not path.is_file():
        raise HTTPException(404, '结果文件不存在，请重新上传')
    return FileResponse(path, media_type='audio/mp4', filename='instrumental.m4a')


@app.delete('/jobs/{job_id}')
def delete(job_id: str):
    with lock:
        job = find_job(job_id)
        active = job['status'] in ('queued', 'running')
        if job['status'] == 'uploading':
            raise HTTPException(409, '上传尚未结束')
        job['status'] = 'cancelled'
        save(job)
        process = processes.get(job_id)
        if process:
            process.terminate()
        if not active:
            shutil.rmtree(ROOT / job_id, ignore_errors=True)
            jobs.pop(job_id, None)
    return {'status': 'deleted'}
