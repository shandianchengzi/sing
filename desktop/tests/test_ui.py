import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import numpy as np
import pytest
from PySide6.QtWidgets import QApplication
from PySide6.QtTest import QTest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import Window
from audio import RATE
from test_desktop import wav


@pytest.fixture(scope='module')
def app():
    return QApplication.instance() or QApplication([])


def until(app, condition):
    deadline = time.monotonic() + 10
    while not condition():
        assert time.monotonic() < deadline, 'GUI task timed out'
        app.processEvents()
        QTest.qWait(5)


def test_gui_record_from_zero_replay_preview_and_reload(app, tmp_path, monkeypatch):
    class Stop(Exception):
        pass
    class Stream:
        def __init__(self, **kwargs):
            self.duplex = isinstance(kwargs['channels'], tuple)
            self.callback = kwargs['callback']
        def start(self):
            out = np.zeros((1024, 2), np.float32)
            try:
                if self.duplex:
                    self.callback(np.full((1024, 1), .4, np.float32), out, 1024, None, False)
                else:
                    self.callback(out, 1024, None, False)
            except Stop:
                pass
        def stop(self): pass
        def close(self): pass
    monkeypatch.setitem(sys.modules, 'sounddevice', SimpleNamespace(Stream=Stream, OutputStream=Stream, CallbackStop=Stop, CallbackAbort=Stop))
    w = Window(tmp_path / 'state')
    errors = []
    w.fail = lambda text: errors.append(str(text))
    folder = tmp_path / 'music'
    folder.mkdir()
    w.library.add_folder(folder)
    wav(folder / '练习曲-伴奏.wav')
    (folder / '练习曲.lrc').write_text('[00:00.00]准备好\n[00:01.00]开始唱', encoding='utf-8')
    w.show()
    assert not w.local_lrc.isEnabled()
    w.load_song(w.library.scan()[0])
    until(app, lambda: not w.busy)
    assert w.record_button.isEnabled()
    w.record()  # No preceding preview required; starts at frame zero.
    assert w.engine.start == 0 and w.engine.mode == 'record'
    w.record()
    assert w.take is not None and w.take['start_frame'] == 0
    assert Path(w.take['voice']).exists()
    w.listen()
    until(app, lambda: not w.busy)
    assert w.replay and not w.record_button.isEnabled()
    assert w.preview_button.isEnabled()
    w.preview()
    assert not w.replay and w.record_button.isEnabled()
    w.close()
    assert not errors
    restored = Window(tmp_path / 'state')
    restored.fail = lambda text: errors.append(str(text))
    until(app, lambda: restored.song is not None and not restored.busy)
    assert restored.take is not None and restored.song.title == '练习曲'
    restored.close()
    assert not errors
