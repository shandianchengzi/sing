"""Desktop library/session model. No GUI or audio hardware required."""
from __future__ import annotations
import bisect
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from dataclasses import dataclass
from datetime import datetime

AUDIO = {'.mp3', '.m4a', '.wav', '.ogg', '.opus', '.aac', '.flac', '.mp4', '.3gp'}
REPO = 'https://github.com/shandianchengzi/sing'
VERSION = '1.0.0'


def song_title(value: str) -> str:
    value = value.strip()
    if (not value or len(value) > 120 or re.search(r'[\\/:*?"<>|\x00-\x1f]', value)
            or value.endswith('.') or value.upper() in {'CON', 'PRN', 'AUX', 'NUL'}
            or re.fullmatch(r'(COM|LPT)[1-9]', value, re.I)):
        raise ValueError('请输入有效歌名，不能包含路径或 Windows 文件名保留字符')
    return value


def audio_title(path: Path) -> str:
    if path.suffix.lower() in AUDIO and path.stem.endswith('-伴奏'):
        try:
            title = song_title(path.stem[:-3])
            return title if title == path.stem[:-3] else ''
        except ValueError:
            pass
    return ''


def path_key(path: Path | str) -> str:
    return os.path.normcase(str(Path(path).resolve()))


def atomic_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=path.parent, suffix='.tmp')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as out:
            json.dump(data, out, ensure_ascii=False, indent=2)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temp, path)
    finally:
        Path(temp).unlink(missing_ok=True)


def read_lrc(path: Path) -> str:
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('LRC 不能超过 2 MB')
    data = path.read_bytes()
    if data.startswith((b'\xff\xfe', b'\xfe\xff')):
        return data.decode('utf-16')
    try:
        return data.decode('utf-8-sig')
    except UnicodeDecodeError:
        return data.decode('gb18030')


class Lyrics:
    def __init__(self, text=''):
        offset = re.search(r'\[offset:([+-]?\d+)\]', text, re.I)
        self.offset = int(offset[1]) if offset else 0
        self.lines = []
        for row in text.splitlines():
            stamps = list(re.finditer(r'\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]', row))
            for stamp in stamps:
                ms = int(stamp[1]) * 60000 + int(stamp[2]) * 1000 + int(((stamp[3] or '') + '000')[:3])
                self.lines.append((ms, row[stamps[-1].end():].strip()))
        self.lines.sort(key=lambda x: x[0])
        self.times = [x[0] for x in self.lines]

    def at(self, ms):
        return bisect.bisect_right(self.times, ms + self.offset) - 1


def validated_lyrics(text):
    if not Lyrics(text).lines:
        raise ValueError('LRC 没有有效时间戳，原文件保留')
    return text


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def copy_verified(source: Path, target: Path, cancel=None):
    if source.resolve() == target.resolve():
        return False
    if target.exists():
        if digest(source) == digest(target):
            return False
        raise FileExistsError(f'已有内容不同的同名文件：{target.name}')
    created = False
    try:
        with source.open('rb') as inp, target.open('xb') as out:
            created = True
            for block in iter(lambda: inp.read(1024 * 1024), b''):
                if cancel and cancel.is_set():
                    raise InterruptedError('已取消')
                out.write(block)
            out.flush()
            os.fsync(out.fileno())
        if digest(source) != digest(target):
            raise OSError('复制校验失败，源文件保留')
        return True
    except Exception:
        if created:
            target.unlink(missing_ok=True)
        raise


@dataclass(frozen=True)
class Song:
    audio: Path
    title: str
    folder: Path | None
    lrc: Path | None = None


class Store:
    def __init__(self, root: Path):
        self.root = root
        self.takes = root / 'recordings'
        self.cache = root / 'cache'
        self.takes.mkdir(parents=True, exist_ok=True)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.file = root / 'settings.json'
        self.data = {'folders': [], 'hidden': [], 'import_library': True, 'copy': False,
                     'voice_gain': 1., 'backing_gain': 1., 'shift': 0, 'external': []}
        if self.file.exists():
            # Never silently overwrite corrupt settings or discard recording references.
            self.data.update(json.loads(self.file.read_text(encoding='utf-8')))

    def save(self):
        atomic_json(self.file, self.data)

    def recordings(self):
        rows = []
        for p in self.takes.glob('*.json'):
            row = json.loads(p.read_text(encoding='utf-8'))
            row['_meta'] = str(p)
            if Path(row['voice']).is_file():
                rows.append(row)
        return sorted(rows, key=lambda r: r['created'], reverse=True)

    def update_references(self, old: Path, new: Path, title: str):
        previous_data = json.loads(json.dumps(self.data))
        backups = []
        try:
            for row in self.recordings():
                if path_key(row['source']) != path_key(old):
                    continue
                p = Path(row.pop('_meta'))
                backups.append((p, p.read_bytes()))
                row.update(source=str(new.resolve()), title=title)
                atomic_json(p, row)
            for row in self.data['external']:
                if path_key(row['audio']) == path_key(old):
                    row.update(audio=str(new.resolve()), title=title)
            if self.data.get('audio') and path_key(self.data['audio']) == path_key(old):
                self.data['audio'] = str(new.resolve())
            self.save()
        except Exception:
            self.data = previous_data
            for p, previous in backups:
                p.write_bytes(previous)
            raise


class Library:
    def __init__(self, store: Store):
        self.store = store
        self.warnings = []

    @property
    def folders(self):
        return [Path(p) for p in self.store.data['folders']]

    def add_folder(self, folder):
        folder = Path(folder).resolve()
        if not folder.is_dir():
            raise ValueError('请选择可访问的文件夹')
        if all(path_key(folder) != path_key(p) for p in self.folders):
            self.store.data['folders'].append(str(folder))
            self.store.save()

    def files(self, folder):
        return sorted((p for p in folder.iterdir() if p.is_file()), key=lambda p: p.name.casefold())

    def scan(self, include_hidden=False):
        songs, self.warnings = [], []
        for folder in self.folders:
            try:
                files = self.files(folder)
            except OSError as e:
                self.warnings.append(f'{folder}：{e}')
                continue
            lyrics = {p.stem.casefold(): p for p in files if p.suffix == '.lrc'}
            for p in files:
                title = audio_title(p)
                if title and (include_hidden or path_key(p) not in self.store.data['hidden']):
                    songs.append(Song(p, title, folder, lyrics.get(title.casefold())))
        seen = {path_key(s.audio) for s in songs}
        for row in self.store.data['external']:
            p = Path(row['audio'])
            if p.is_file() and audio_title(p) and path_key(p) not in seen and (include_hidden or path_key(p) not in self.store.data['hidden']):
                companion = p.with_name(audio_title(p) + '.lrc')
                songs.append(Song(p, row['title'], None, companion if companion.is_file() else None))
        return songs

    def groups(self):
        groups = {}
        for song in self.scan():
            groups.setdefault(song.title.casefold(), []).append(song)
        return groups

    def existing(self, title):
        return next((s for s in self.scan(True) if s.folder and s.title.casefold() == title.casefold()), None)

    def unmatched(self):
        result = []
        for folder in self.folders:
            files = self.files(folder)
            titles = {audio_title(p).casefold() for p in files if audio_title(p)}
            for p in files:
                if p.suffix.lower() in AUDIO and not audio_title(p):
                    result.append(p)
                elif p.suffix.lower() == '.lrc' and (p.suffix != '.lrc' or p.stem.casefold() not in titles):
                    result.append(p)
        return result

    def unhide(self, audio):
        self.store.data['hidden'] = [p for p in self.store.data['hidden'] if p != path_key(audio)]
        self.store.save()

    def import_audio(self, source, title, folder=None, copy=False, lrc=None, reuse=True, cancel=None):
        title = song_title(title)
        existing = self.existing(title) if reuse else None
        if existing:
            self.unhide(existing.audio)
            return existing, '已选用曲库已有伴奏及 LRC；新选文件未读取、未移动'
        if folder is None:
            if not self.folders:
                raise ValueError('请先添加曲库文件夹')
            folder = self.folders[0]
        folder = Path(folder)
        source = Path(source)
        if source.suffix.lower() not in AUDIO:
            raise ValueError('不支持的音频格式')
        target = folder / (title + '-伴奏' + source.suffix.lower())
        lyrics_target = folder / (title + '.lrc')
        # Check both names before creating either file.
        if lrc:
            validated_lyrics(read_lrc(Path(lrc)))
            if lyrics_target.exists() and digest(lrc) != digest(lyrics_target):
                raise FileExistsError('目标已有不同的同名 LRC，未覆盖')
        made = []
        try:
            if copy_verified(source, target, cancel):
                made.append(target)
            if lrc and copy_verified(Path(lrc), lyrics_target, cancel):
                made.append(lyrics_target)
            if cancel and cancel.is_set():
                raise InterruptedError('已取消')
            if not copy:
                self.store.update_references(source, target, title)
            self.unhide(target)
        except Exception:
            for p in reversed(made):
                p.unlink(missing_ok=True)
            raise
        warnings = []
        if not copy:
            for old, new in [(source, target)] + ([(Path(lrc), lyrics_target)] if lrc else []):
                if path_key(old) != path_key(new):
                    try:
                        old.unlink()
                    except OSError:
                        warnings.append(f'源文件无法删除，请手动清理：{old}')
        return Song(target, title, folder, lyrics_target if lyrics_target.exists() else None), '\n'.join(warnings) or '已导入本地曲库'

    def remember_external(self, audio):
        p = Path(audio).resolve()
        title = audio_title(p) or p.stem
        if not any(path_key(r['audio']) == path_key(p) for r in self.store.data['external']):
            self.store.data['external'].append({'audio': str(p), 'title': title})
        self.unhide(p)
        companion = p.with_name(title + '.lrc') if audio_title(p) else None
        return Song(p, title, None, companion if companion and companion.is_file() else None)

    def save_lyrics(self, song, text):
        validated_lyrics(text)
        folder = song.folder or (self.folders[0] if self.folders else None)
        if folder is None:
            raise ValueError('请先添加曲库文件夹以保存 LRC')
        target = folder / (song_title(song.title) + '.lrc')
        fd, temp = tempfile.mkstemp(dir=folder, suffix='.tmp')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temp, target)
        finally:
            Path(temp).unlink(missing_ok=True)
        return Song(song.audio, song.title, song.folder, target)

    def rename(self, song, title):
        title = song_title(title)
        target = song.audio.with_name(title + '-伴奏' + song.audio.suffix.lower())
        target_lrc = song.lrc.with_name(title + '.lrc') if song.lrc else None
        changes = [(song.audio, target)] + ([(song.lrc, target_lrc)] if song.lrc else [])
        for old, new in changes:
            if new.exists() and path_key(old) != path_key(new):
                raise FileExistsError(f'已有同名文件：{new.name}')
        done = []
        try:
            for old, new in changes:
                if str(old) != str(new):
                    old.rename(new)
                    done.append((old, new))
            self.store.update_references(song.audio, target, title)
        except Exception:
            for old, new in reversed(done):
                new.rename(old)
            raise
        return Song(target, title, song.folder, target_lrc)

    def organize(self, source, title):
        source, title = Path(source), song_title(title)
        if source.suffix.lower() == '.lrc':
            validated_lyrics(read_lrc(source))
            target = source.with_name(title + '.lrc')
            if target.exists() and path_key(source) != path_key(target):
                raise FileExistsError('已有同名 LRC，未覆盖')
            source.rename(target)
            return target
        existing = self.existing(title)
        if existing:
            return existing.audio
        return self.rename(Song(source, source.stem, source.parent), title).audio

    def remove(self, song, delete_files=False):
        if delete_files:
            song.audio.unlink()
            if song.lrc:
                song.lrc.unlink(missing_ok=True)
        if path_key(song.audio) not in self.store.data['hidden']:
            self.store.data['hidden'].append(path_key(song.audio))
        self.store.save()


def recording_name(title):
    title = re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', title).strip('. ')[:100] or '伴奏'
    return title + '_' + datetime.now().strftime('%Y%m%d_%H%M%S_%f')[:-3] + '.wav'
