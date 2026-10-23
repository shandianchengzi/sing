"""Streaming hardware audio; one callback timebase for recording and backing."""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import queue
import subprocess
import threading
import time
import zipfile
import tempfile
import numpy as np
import soundfile as sf

RATE = 48000


def ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def command(arguments, cancel=None):
    kwargs = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    process = subprocess.Popen([ffmpeg(), '-hide_banner', '-loglevel', 'error', '-nostdin', *map(str, arguments)],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    try:
        while True:
            if cancel and cancel.is_set():
                raise InterruptedError('已取消')
            try:
                _, error = process.communicate(timeout=.15)
                break
            except subprocess.TimeoutExpired:
                continue
        if process.returncode:
            raise OSError(error.decode('utf-8', errors='replace')[-1500:] or '音频处理失败')
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()


class Buffer:
    def __init__(self, path):
        self.path = Path(path)
        self.frames = self.path.stat().st_size // 8
        if not self.frames:
            raise ValueError('音轨为空')
        self.samples = np.memmap(self.path, dtype='<f4', mode='r', shape=(self.frames, 2))

    def block(self, start, frames):
        result = np.zeros((frames, 2), np.float32)
        first, last = max(0, start), min(self.frames, start + frames)
        if last > first:
            result[first - start:last - start] = self.samples[first:last]
        return result

    def close(self):
        self.samples._mmap.close()


def decode(source, cache, cancel=None):
    source = Path(source)
    stat = source.stat()
    key = hashlib.sha256(f'{source.resolve()}|{stat.st_size}|{stat.st_mtime_ns}|stereo48k-v1'.encode()).hexdigest()
    target = Path(cache) / (key + '.f32')
    if not target.exists():
        temp = target.with_suffix('.part')
        try:
            command(['-y', '-i', source, '-map', '0:a:0', '-vn', '-ac', '2', '-ar', str(RATE),
                     '-f', 'f32le', '-acodec', 'pcm_f32le', temp], cancel)
            if temp.stat().st_size == 0 or temp.stat().st_size % 8:
                raise ValueError('音轨为空或解码不完整')
            temp.replace(target)
        finally:
            temp.unlink(missing_ok=True)
    return Buffer(target)


def mix(voice, backing, voice_gain, backing_gain):
    # Same half-headroom formula as Android, used by replay and export.
    return np.clip((voice * voice_gain + backing * backing_gain) * .5, -1, 1).astype(np.float32)


class VoiceWriter:
    def __init__(self, path):
        self.path = Path(path)
        self.queue = queue.Queue(maxsize=250)
        self.error = None
        self.frames = 0
        self.thread = threading.Thread(target=self._write, daemon=True)
        self.thread.start()

    def _write(self):
        try:
            with sf.SoundFile(self.path, 'x', samplerate=RATE, channels=1, subtype='FLOAT', format='WAV') as output:
                while True:
                    block = self.queue.get()
                    if block is None:
                        break
                    output.write(block)
                    self.frames += len(block)
        except Exception as e:
            self.error = str(e)

    def append(self, data):
        if self.error:
            raise OSError(self.error)
        try:
            self.queue.put_nowait(data.copy())
        except queue.Full:
            raise OSError('磁盘写入太慢，录音已停止，请更换保存位置或关闭其他程序')

    def finish(self):
        while self.thread.is_alive():
            try:
                self.queue.put(None, timeout=.2)
                break
            except queue.Full:
                continue
        self.thread.join()
        if self.error:
            raise OSError(self.error)
        return self.frames


class Engine:
    def __init__(self):
        self.stream = None
        self.mode = 'idle'
        self.position = 0
        self.start = 0
        self.end = 0
        self.backing = self.voice = None
        self.writer = None
        self.voice_gain = self.backing_gain = 1.
        self.finished = False
        self.error = ''
        self.device_warning = ''
        self.input_device = self.output_device = None

    @property
    def active(self):
        return self.stream is not None

    def render(self, frames, indata=None):
        count = max(0, min(frames, self.end - self.position))
        backing = self.backing.block(self.position, frames)
        if self.mode == 'replay':
            output = mix(self.voice.block(self.position - self.start, frames), backing,
                         self.voice_gain, self.backing_gain)
        else:
            output = backing * self.backing_gain
        if self.mode == 'record' and count:
            self.writer.append(indata[:count, 0])
        self.position += count
        if count < frames:
            output[count:] = 0
        return output

    def _callback(self, outdata, frames, status, indata=None):
        import sounddevice as sd
        try:
            if status:
                self.device_warning = str(status)
                if self.mode == 'record' and (getattr(status, 'input_overflow', False) or getattr(status, 'output_underflow', False)):
                    raise OSError('音频设备发生丢帧，本次录音已停止；请关闭占用设备的程序后重试')
            outdata[:] = self.render(frames, indata)
            if self.position >= self.end:
                self.finished = True
                raise sd.CallbackStop
        except sd.CallbackStop:
            raise
        except Exception as e:
            self.error = str(e)
            self.finished = True
            outdata.fill(0)
            raise sd.CallbackAbort

    def play(self, backing, start=0, voice=None, cue=0):
        import sounddevice as sd
        self.stop()
        self.backing, self.voice = backing, voice
        self.mode = 'replay' if voice is not None else 'preview'
        self.start = cue if voice is not None else 0
        self.end = cue + voice.frames if voice is not None else backing.frames
        self.position = min(max(start, self.start), self.end)
        if self.position >= self.end:
            self.position = self.start
        self.finished, self.error = False, ''
        try:
            self.stream = sd.OutputStream(samplerate=RATE, channels=2, dtype='float32', blocksize=1024,
                device=self.output_device, callback=lambda out, frames, timing, status: self._callback(out, frames, status))
            self.stream.start()
        except Exception:
            self.stop()
            raise

    def record(self, backing, path, start):
        import sounddevice as sd
        self.stop()
        self.backing, self.voice = backing, None
        self.mode = 'record'
        self.start = self.position = max(0, min(start, backing.frames - 1))
        self.end = backing.frames
        self.finished, self.error = False, ''
        self.writer = VoiceWriter(path)
        try:
            self.stream = sd.Stream(samplerate=RATE, channels=(1, 2), dtype='float32', blocksize=1024,
                device=(self.input_device, self.output_device),
                callback=lambda inp, out, frames, timing, status: self._callback(out, frames, status, inp))
            self.stream.start()
        except Exception:
            self.stop()
            writer, self.writer = self.writer, None
            writer.finish()
            Path(path).unlink(missing_ok=True)
            raise

    def stop(self):
        if self.stream is not None:
            stream, self.stream = self.stream, None
            try:
                stream.stop()
            finally:
                stream.close()
        self.finished = False

    def finish_record(self):
        self.stop()
        writer, self.writer = self.writer, None
        if not writer:
            return 0
        return writer.finish()


def export_take(take, backing, voice, path, kind, voice_gain, backing_gain, cancel=None):
    path = Path(path)
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        tmp = Path(tmp)
        vocals, music, mixed = tmp / 'vocals.wav', tmp / 'backing.wav', tmp / 'mix.wav'
        cue = int(take['start_frame'])
        with sf.SoundFile(vocals, 'w', RATE, 2, subtype='PCM_16') as vf, \
             sf.SoundFile(music, 'w', RATE, 2, subtype='PCM_16') as bf, \
             sf.SoundFile(mixed, 'w', RATE, 2, subtype='PCM_16') as mf:
            for pos in range(0, voice.frames, 65536):
                if cancel and cancel.is_set():
                    raise InterruptedError('已取消导出')
                count = min(65536, voice.frames - pos)
                v = voice.block(pos, count)
                b = backing.block(cue + pos, count) if backing is not None else np.zeros_like(v)
                vf.write(np.clip(v * voice_gain, -1, 1))
                bf.write(np.clip(b * backing_gain, -1, 1))
                mf.write(mix(v, b, voice_gain, backing_gain))
        if kind == '双轨 ZIP':
            output = tmp / 'tracks.zip'
            with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
                z.write(vocals, 'vocals.wav')
                z.write(music, 'backing.wav')
                z.writestr('说明.txt', f'原伴奏起点：{cue / RATE:.3f} 秒\n两轨均从零开始；已应用音量设置。\n')
        else:
            output = vocals if kind == '仅人声 WAV' else mixed
        os.replace(output, path)
