"""Adapters for the same NetEase lyrics and self-hosted separation API as Android."""
import base64
import hashlib
import json
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlparse
import requests
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7
from core import validated_lyrics


def aes(text, key):
    pad = PKCS7(128).padder()
    data = pad.update(text.encode()) + pad.finalize()
    cipher = Cipher(algorithms.AES(key.encode()), modes.CBC(b'0102030405060708')).encryptor()
    return base64.b64encode(cipher.update(data) + cipher.finalize()).decode()


def lyrics(value):
    match = re.search(r'(?:[?&]id=|/song/)(\d+)', value)
    song_id = match[1] if match else value.strip()
    if not re.fullmatch(r'\d{1,19}', song_id):
        raise ValueError('请输入网易云歌曲数字 ID 或含 id= 的歌曲链接')
    key = ''.join(secrets.choice('0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ') for _ in range(16))
    modulus = int('00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7', 16)
    rsa = format(pow(int.from_bytes(key[::-1].encode(), 'big'), 65537, modulus), '0256x')
    payload = json.dumps({'id': song_id, 'os': 'pc', 'lv': '-1', 'tv': '-1', 'csrf_token': ''}, separators=(',', ':'))
    response = requests.post('https://music.163.com/weapi/song/lyric?csrf_token=',
        data={'params': aes(aes(payload, '0CoJUm6Qyw8W8jud'), key), 'encSecKey': rsa},
        headers={'Referer': 'https://music.163.com/'}, timeout=(12, 20))
    response.raise_for_status()
    if len(response.content) > 2 * 1024 * 1024:
        raise ValueError('歌词响应过大')
    return validated_lyrics((response.json().get('lrc') or {}).get('lyric') or '')


def service_address(value):
    value = value.strip().rstrip('/')
    u = urlparse(value)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.query or u.fragment:
        raise ValueError('请输入有效的 HTTP(S) 去人声服务地址')
    return value


def health(base, token):
    r = requests.get(service_address(base) + '/health', headers={'Authorization': 'Bearer ' + token}, timeout=15, allow_redirects=False)
    r.raise_for_status()
    data = r.json()
    if data.get('service') != 'sing-separation' or data.get('api_version') != 1:
        raise ValueError('该地址不是兼容的去人声服务')
    return '连接正常，去人声服务可用'


def separate(source, base, token, cache, cancel, progress):
    base = service_address(base)
    if source.stat().st_size > 100 * 1024 * 1024:
        raise ValueError('上传歌曲不能超过 100 MB')
    h = hashlib.sha256((base + '/demucs-htdemucs-v1').encode())
    with source.open('rb') as f:
        for block in iter(lambda: f.read(65536), b''):
            if cancel.is_set():
                raise InterruptedError('已取消')
            h.update(block)
    target = Path(cache) / (h.hexdigest() + '.m4a')
    if target.exists() and target.stat().st_size:
        return target
    headers = {'Authorization': 'Bearer ' + token}
    job = None
    temp = target.with_suffix('.part')
    def check():
        if cancel.is_set():
            raise InterruptedError('已取消分离')
    def checked(r):
        if r.status_code == 401:
            raise ValueError('服务令牌不正确')
        r.raise_for_status()
        if len(r.content) > 65536:
            raise ValueError('服务响应过大')
        return r.json()
    try:
        progress('正在上传原曲到你的服务器…')
        def chunks():
            with source.open('rb') as f:
                for block in iter(lambda: f.read(65536), b''):
                    check()
                    yield block
        job = checked(requests.post(base + '/jobs', data=chunks(), headers={**headers, 'Content-Type': 'application/octet-stream'}, timeout=(15, 30), allow_redirects=False))['id']
        if not re.fullmatch(r'[a-f0-9]{32}', job):
            job = None
            raise ValueError('无效任务 ID')
        deadline = time.monotonic() + 3600
        while True:
            check()
            data = checked(requests.get(base + '/jobs/' + job, headers=headers, timeout=30, allow_redirects=False))
            status = data.get('status')
            if status == 'succeeded':
                break
            if status not in ('queued', 'running'):
                raise ValueError(data.get('error') or '分离失败或已取消')
            if time.monotonic() > deadline:
                raise TimeoutError('等待服务器分离超时')
            progress('服务器正在排队…' if status == 'queued' else '服务器正在去人声；首次使用可能下载模型…')
            if cancel.wait(2):
                check()
        progress('正在下载伴奏…')
        with requests.get(base + '/jobs/' + job + '/result', headers=headers, stream=True, timeout=30, allow_redirects=False) as r:
            r.raise_for_status()
            total = 0
            with temp.open('wb') as f:
                for block in r.iter_content(65536):
                    check()
                    total += len(block)
                    if total > 100 * 1024 * 1024:
                        raise ValueError('伴奏下载超过 100 MB')
                    f.write(block)
            if not total:
                raise ValueError('伴奏文件为空')
        temp.replace(target)
        return target
    finally:
        temp.unlink(missing_ok=True)
        if job:
            try:
                requests.delete(base + '/jobs/' + job, headers=headers, timeout=5, allow_redirects=False)
            except requests.RequestException:
                pass
