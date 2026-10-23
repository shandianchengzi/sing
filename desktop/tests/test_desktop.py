import json
from pathlib import Path
import sys
import threading
import zipfile
import numpy as np
import pytest
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import Store, Library, Song, Lyrics, audio_title, song_title, atomic_json, read_lrc
from audio import Buffer, Engine, VoiceWriter, RATE, decode, mix, export_take, command


@pytest.fixture
def library(tmp_path):
    lib = Library(Store(tmp_path / 'state'))
    lib.add_folder(tmp_path / 'music') if (tmp_path / 'music').exists() else None
    folder = tmp_path / 'music'
    folder.mkdir(exist_ok=True)
    lib.add_folder(folder)
    return lib


def wav(path, frames=4800, value=.25):
    sf.write(path, np.full((frames, 2), value, np.float32), RATE, subtype='FLOAT')
    return path


def test_strict_names_and_offsets():
    assert audio_title(Path('大梦归-伴奏.ogg')) == '大梦归'
    assert audio_title(Path('大梦归_伴奏.ogg')) == ''
    assert audio_title(Path('Song (Instrumental).mp3')) == ''
    for title in ('../x', 'CON', 'a:b', '', 'x.', 'COM2'):
        with pytest.raises(ValueError):
            song_title(title)
    lines = Lyrics('[offset:100]\n[00:00.1][00:02.20]同一句\n[00:01.001]中间')
    assert lines.at(0) == 0
    assert lines.at(901) == 1
    assert lines.at(2100) == 2


def test_duplicate_uses_library_without_reading_upload(library, tmp_path):
    folder = library.folders[0]
    existing = wav(folder / '歌曲-伴奏.wav')
    (folder / '歌曲.lrc').write_text('[00:00.00]歌词', encoding='utf-8')
    song, note = library.import_audio(tmp_path / 'DOES-NOT-EXIST.mp3', '歌曲')
    assert song.audio == existing
    assert song.lrc.name == '歌曲.lrc'
    assert '未读取' in note


def test_move_copy_rename_lyrics_and_recording_references(library, tmp_path):
    source = wav(tmp_path / 'random.wav')
    lyric = tmp_path / 'song - singer.lrc'
    lyric.write_bytes('[00:00.00]测试'.encode('gb18030'))
    voice = wav(library.store.takes / 'take.wav')
    meta = voice.with_suffix('.json')
    atomic_json(meta, {'title': 'old', 'source': str(source), 'voice': str(voice), 'created': '2026', 'start_frame': 123, 'frames': 4800})
    song, _ = library.import_audio(source, '新歌', lrc=lyric)
    assert not source.exists() and not lyric.exists()
    assert song.audio.name == '新歌-伴奏.wav' and song.lrc.name == '新歌.lrc'
    assert '测试' in read_lrc(song.lrc)
    song = library.rename(song, '改名')
    row = json.loads(meta.read_text(encoding='utf-8'))
    assert row['source'] == str(song.audio) and row['start_frame'] == 123
    assert song.audio.exists() and song.lrc.exists()
    other = tmp_path / 'second'
    other.mkdir()
    copied, _ = library.import_audio(song.audio, song.title, other, copy=True, lrc=song.lrc, reuse=False)
    assert song.audio.exists() and copied.audio.exists()
    assert json.loads(meta.read_text(encoding='utf-8'))['source'] == str(song.audio)


def test_collision_preserves_sources_and_existing(library, tmp_path):
    source = wav(tmp_path / 'input.wav')
    lrc = tmp_path / 'input.lrc'
    lrc.write_text('[00:00]new', encoding='utf-8')
    target = library.folders[0] / '歌曲.lrc'
    target.write_text('[00:00]old', encoding='utf-8')
    with pytest.raises(FileExistsError):
        library.import_audio(source, '歌曲', lrc=lrc)
    assert source.exists() and lrc.exists()
    assert target.read_text(encoding='utf-8') == '[00:00]old'
    assert not (target.parent / '歌曲-伴奏.wav').exists()


def test_scan_organize_and_delete(library):
    folder = library.folders[0]
    raw = wav(folder / 'raw.wav')
    lyric = folder / '歌曲 - 歌手.LRC'
    lyric.write_text('[00:00]测试', encoding='utf-8')
    assert {raw, lyric} <= set(library.unmatched())
    library.organize(raw, '歌曲')
    library.organize(lyric, '歌曲')
    song = library.scan()[0]
    assert song.lrc.name == '歌曲.lrc'
    assert library.unmatched() == []
    library.remove(song)
    assert library.scan() == [] and song.audio.exists()
    library.unhide(song.audio)
    library.remove(song, True)
    assert not song.audio.exists() and not song.lrc.exists()


def test_import_cancellation_keeps_source(library, tmp_path):
    source = wav(tmp_path / 'source.wav')
    cancel = threading.Event()
    cancel.set()
    with pytest.raises(InterruptedError):
        library.import_audio(source, '取消', cancel=cancel)
    assert source.exists()
    assert not (library.folders[0] / '取消-伴奏.wav').exists()


def test_audio_decode_mp4_cache_and_time_aligned_export(tmp_path):
    source = wav(tmp_path / '背景.wav', frames=9600, value=.8)
    mp4 = tmp_path / '视频.mp4'
    command(['-y', '-i', source, '-c:a', 'aac', mp4])
    cache = tmp_path / 'cache'
    cache.mkdir()
    backing = decode(mp4, cache)
    voice_path = wav(tmp_path / '人声.wav', frames=4800, value=.4)
    voice = decode(voice_path, cache)
    try:
        assert backing.frames >= 9600
        take = {'start_frame': 4800}
        output = tmp_path / 'mix.wav'
        export_take(take, backing, voice, output, '混音 WAV', .5, .25)
        pcm, rate = sf.read(output, always_2d=True)
        assert rate == RATE and len(pcm) == voice.frames
        expected = mix(voice.block(0, voice.frames), backing.block(4800, voice.frames), .5, .25)
        np.testing.assert_allclose(pcm, expected, atol=1 / 32768 + 1e-6)
        tracks = tmp_path / 'tracks.zip'
        export_take(take, backing, voice, tracks, '双轨 ZIP', .5, .25)
        with zipfile.ZipFile(tracks) as z:
            assert set(z.namelist()) == {'vocals.wav', 'backing.wav', '说明.txt'}
            assert '0.100' in z.read('说明.txt').decode()
        export_take(take, None, voice, tmp_path / 'voice-only.wav', '仅人声 WAV', 1, 1)
    finally:
        voice.close()
        backing.close()


def test_recording_callback_start_pause_replay_and_levels(tmp_path):
    samples = np.tile(np.arange(4096, dtype=np.float32)[:, None] / 4096, (1, 2))
    raw = tmp_path / 'source.f32'
    samples.tofile(raw)
    backing = Buffer(raw)
    engine = Engine()
    engine.backing = backing
    engine.mode = 'record'
    engine.position = engine.start = 1024
    engine.end = backing.frames
    engine.writer = VoiceWriter(tmp_path / 'voice.wav')
    mic = np.full((1024, 1), .4, np.float32)
    np.testing.assert_allclose(engine.render(1024, mic), samples[1024:2048])
    engine.render(1024, mic)
    assert engine.finish_record() == 2048
    recorded, rate = sf.read(tmp_path / 'voice.wav')
    np.testing.assert_allclose(recorded, .4)
    assert rate == RATE
    engine.mode = 'preview'
    engine.position = 1024
    engine.backing_gain = .3
    np.testing.assert_allclose(engine.render(1024), samples[1024:2048] * .3)
    engine.voice = backing
    engine.mode = 'replay'
    engine.start = engine.position = 1024
    engine.voice_gain = .5
    np.testing.assert_allclose(engine.render(1024), mix(samples[:1024], samples[1024:2048], .5, .3))
    backing.close()
