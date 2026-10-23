"""Sing desktop: landscape UI, native file management and audio controls."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import sys
import threading
from datetime import datetime
import requests
from PySide6.QtCore import Qt, QObject, Signal, QRunnable, QThreadPool, QTimer, QStandardPaths, QUrl, QSize
from PySide6.QtGui import QAction, QDesktopServices, QKeySequence, QShortcut, QFont
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QLabel, QPushButton, QCheckBox,
    QVBoxLayout, QHBoxLayout, QSplitter, QListWidget, QListWidgetItem, QLineEdit, QSlider,
    QSpinBox, QComboBox, QFileDialog, QInputDialog, QMessageBox, QDialog, QMenu, QProgressDialog,
    QStackedWidget, QFrame, QScrollArea, QAbstractItemView, QSizePolicy)
from core import (Store, Library, Song, Lyrics, AUDIO, REPO, VERSION, read_lrc, song_title,
                  audio_title, path_key, recording_name, atomic_json, digest)
from audio import Engine, RATE, decode, export_take
import services

STYLE = '''
QMainWindow, QDialog, QWidget { background:#f3f5f1; color:#243b32; font-family:"Microsoft YaHei UI","Noto Sans CJK SC",sans-serif; font-size:13px; }
QFrame#panel { background:#ffffff; border:1px solid #dce4dd; border-radius:14px; }
QLabel { background:transparent; border:0; }
QLabel#eyebrow { color:#788a7f; font-size:12px; }
QLabel#heading { font-size:19px; font-weight:600; }
QLabel#status { background:#e3ede5; border-radius:8px; padding:9px 14px; color:#285943; }
QPushButton { background:#eaf0e9; border:1px solid #dbe4da; border-radius:7px; padding:9px 12px; }
QPushButton:hover { background:#dce8dc; border-color:#9db8a5; }
QPushButton:pressed { background:#ccdccc; }
QPushButton:disabled { color:#aab5ac; background:#edf0eb; border-color:#e5e9e2; }
QPushButton#primary { background:#28684b; color:white; border:0; font-weight:600; padding:13px 18px; }
QPushButton#primary:hover { background:#1e553d; }
QPushButton#primary:disabled { background:#bccdc1; }
QPushButton#recording { background:#a64242; color:white; font-weight:600; padding:13px 18px; }
QLineEdit, QSpinBox, QComboBox { background:white; border:1px solid #d6e0d7; border-radius:6px; padding:7px; selection-background-color:#28684b; }
QListWidget { background:white; border:0; outline:0; padding:3px; }
QListWidget::item { padding:9px 6px; border-bottom:1px solid #edf1ec; }
QListWidget#libraryList::item { padding:0; }
QListWidget::item:selected { color:#1c553b; background:#e3eee4; border-radius:5px; }
QSlider::groove:horizontal { height:5px; background:#dfe7de; border-radius:2px; }
QSlider::sub-page:horizontal { background:#43805a; border-radius:2px; }
QSlider::handle:horizontal { background:#28684b; width:14px; margin:-5px 0; border-radius:7px; }
QCheckBox { spacing:7px; background:transparent; }
QScrollArea { border:0; background:transparent; }
QToolTip { background:#243b32; color:white; padding:6px; border:0; }
'''


def button(text, slot, primary=False):
    b = QPushButton(text)
    if primary:
        b.setObjectName('primary')
    b.clicked.connect(slot)
    return b


def label(text, name=None):
    w = QLabel(text)
    if name:
        w.setObjectName(name)
    return w


def panel():
    w = QFrame()
    w.setObjectName('panel')
    layout = QVBoxLayout(w)
    layout.setContentsMargins(20, 20, 20, 20)
    layout.setSpacing(12)
    return w, layout


def stamp(frames):
    seconds = max(0, int(frames / RATE))
    return f'{seconds // 60:02d}:{seconds % 60:02d}'


class LibraryList(QListWidget):
    def fit_rows(self):
        width = max(80, self.viewport().width() - 6)
        for i in range(self.count()):
            widget = self.itemWidget(self.item(i))
            if widget:
                widget.setFixedWidth(width)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        QTimer.singleShot(0, self.fit_rows)


class SongButton(QPushButton):
    def __init__(self, text, slot):
        super().__init__(text)
        self.full_text = text
        self.setToolTip(text)
        self.setMinimumWidth(20)
        self.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed)
        self.clicked.connect(slot)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.setText(self.fontMetrics().elidedText(self.full_text, Qt.TextElideMode.ElideRight, max(8, self.width() - 24)))


class Signals(QObject):
    done = Signal(object)
    error = Signal(str)
    progress = Signal(str)


class Worker(QRunnable):
    def __init__(self, fn):
        super().__init__()
        self.fn, self.signals = fn, Signals()
        self.cancel = threading.Event()

    def run(self):
        try:
            self.signals.done.emit(self.fn(self.cancel, self.signals.progress.emit))
        except Exception as e:
            self.signals.error.emit(str(e) or type(e).__name__)


class Window(QMainWindow):
    def __init__(self, root=None):
        super().__init__()
        root = Path(root) if root else Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppLocalDataLocation))
        self.store = Store(root)
        self.library = Library(self.store)
        self.engine = Engine()
        self.song = None
        self.backing = self.voice = None
        self.take = None
        self.lyrics = Lyrics()
        self.replay = False
        self.busy = False
        self.worker = None
        self.record_path = None
        self.setWindowTitle('Sing · 桌面录歌')
        self.resize(1380, 850)
        self.setMinimumSize(1050, 700)
        self.pages = QStackedWidget()
        self.setCentralWidget(self.pages)
        self.build_home()
        self.build_more()
        self.refresh_library()
        self.refresh_takes()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(50)
        QShortcut(QKeySequence('Space'), self, activated=self.preview)
        QShortcut(QKeySequence('Ctrl+R'), self, activated=self.record)
        QShortcut(QKeySequence('Ctrl+O'), self, activated=self.choose_audio)
        self.controls()
        old = self.store.data.get('audio')
        if old and Path(old).exists():
            song = next((s for s in self.library.scan() if path_key(s.audio) == path_key(old)), None)
            if song is None and path_key(old) not in self.store.data['hidden']:
                song = self.library.remember_external(Path(old))
            if song:
                QTimer.singleShot(0, lambda: self.load_song(song))

    def build_home(self):
        home = QWidget()
        outer = QVBoxLayout(home)
        outer.setContentsMargins(24, 18, 24, 22)
        outer.setSpacing(16)
        self.status = label('戴耳机录制 · 从伴奏当前位置开始，独立保存人声', 'status')
        outer.addWidget(self.status)
        split = QSplitter(Qt.Orientation.Horizontal)
        split.setChildrenCollapsible(False)
        outer.addWidget(split, 1)
        left, lib = panel()
        lib.addWidget(label('本地曲库', 'heading'))
        self.search = QLineEdit()
        self.search.setPlaceholderText('搜索歌名')
        self.search.textChanged.connect(self.refresh_library)
        lib.addWidget(self.search)
        self.library_list = LibraryList()
        self.library_list.setObjectName('libraryList')
        self.library_list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.library_list.setSpacing(3)
        lib.addWidget(self.library_list, 1)
        lib.addWidget(button('管理曲库文件夹', self.folder_dialog))
        lib.addWidget(button('添加伴奏 ＋ LRC', self.add_pair))
        lib.addWidget(button('扫描整理音频 / LRC', self.scan_dialog))
        self.library_note = label('仅匹配 歌名-伴奏.扩展名 / 歌名.lrc', 'eyebrow')
        self.library_note.setWordWrap(True)
        lib.addWidget(self.library_note)
        split.addWidget(left)
        center, work = panel()
        work.setSpacing(18)
        row = QHBoxLayout()
        row.addWidget(button('选择伴奏', self.choose_audio))
        self.import_library = QCheckBox('导入曲库（移动）')
        self.import_library.setChecked(self.store.data['import_library'])
        self.import_library.toggled.connect(lambda v: self.setting('import_library', v))
        row.addWidget(self.import_library)
        row.addStretch()
        work.addLayout(row)
        self.song_label = label('选择一首伴奏，开始唱歌', 'heading')
        self.song_label.setWordWrap(True)
        work.addWidget(self.song_label)
        row = QHBoxLayout()
        self.local_lrc = button('导入 LRC', self.import_current_lyrics)
        self.online_lrc = button('联网获取歌词', lambda: self.lyrics_menu(self.song, online=True))
        row.addWidget(self.local_lrc)
        row.addWidget(self.online_lrc)
        work.addLayout(row)
        work.addStretch(1)
        self.previous = label('')
        self.previous.setStyleSheet('color:#94a196;font-size:18px;')
        self.current = label('准备好，开始唱')
        self.current.setStyleSheet('color:#225c40;font-size:34px;font-weight:600;')
        self.next_line = label('导入 LRC 后，歌词将在这里同步显示')
        self.next_line.setStyleSheet('color:#94a196;font-size:18px;')
        for w in (self.previous, self.current, self.next_line):
            w.setAlignment(Qt.AlignmentFlag.AlignCenter)
            w.setWordWrap(True)
            w.setMinimumHeight(60 if w is not self.current else 120)
            work.addWidget(w)
        work.addStretch(1)
        self.seek = QSlider(Qt.Orientation.Horizontal)
        self.seek.setRange(0, 0)
        self.seek.sliderReleased.connect(self.seek_to)
        work.addWidget(self.seek)
        row = QHBoxLayout()
        self.clock = label('00:00 / 00:00')
        row.addWidget(self.clock)
        row.addStretch()
        row.addWidget(label('歌词延后'))
        self.shift = QSpinBox()
        self.shift.setRange(-600000, 600000)
        self.shift.setSuffix(' ms')
        self.shift.setValue(self.store.data['shift'])
        self.shift.valueChanged.connect(lambda v: self.setting('shift', v))
        row.addWidget(self.shift)
        work.addLayout(row)
        gains = QHBoxLayout()
        self.voice_slider = self.gain_slider('人声', 'voice_gain', gains)
        self.backing_slider = self.gain_slider('伴奏', 'backing_gain', gains)
        work.addLayout(gains)
        row = QHBoxLayout()
        self.preview_button = button('试听伴奏', self.preview)
        self.record_button = button('从当前位置开始录音', self.record, True)
        row.addWidget(self.preview_button)
        row.addWidget(self.record_button, 1)
        work.addLayout(row)
        hint = label('Space 试听 / 暂停　·　Ctrl+R 录音 / 停止　·　Ctrl+O 选择伴奏', 'eyebrow')
        hint.setWordWrap(True)
        work.addWidget(hint)
        split.addWidget(center)
        right, takes = panel()
        takes.addWidget(label('已保存的录音', 'heading'))
        self.take_list = QListWidget()
        self.take_list.currentRowChanged.connect(self.take_selected)
        takes.addWidget(self.take_list, 1)
        self.take_info = label('录音将以“歌名_时间”保存', 'eyebrow')
        self.take_info.setWordWrap(True)
        takes.addWidget(self.take_info)
        self.listen_button = button('回听录音', self.listen)
        self.export_button = button('导出录音…', self.export_dialog)
        self.delete_take_button = button('删除选中录音', self.delete_take)
        takes.addWidget(self.listen_button)
        takes.addWidget(self.export_button)
        takes.addWidget(self.delete_take_button)
        takes.addSpacing(10)
        takes.addWidget(button('更多', lambda: self.pages.setCurrentIndex(1)))
        split.addWidget(right)
        split.setSizes([300, 650, 300])
        self.pages.addWidget(home)

    def gain_slider(self, title, key, parent):
        column = QVBoxLayout()
        text = label(f'{title}音量 100%', 'eyebrow')
        slider = QSlider(Qt.Orientation.Horizontal)
        slider.setRange(0, 100)
        def changed(value):
            text.setText(f'{title}音量 {value}%')
            setattr(self.engine, key, value / 100)
            self.setting(key, value / 100)
        slider.valueChanged.connect(changed)
        slider.setValue(round(self.store.data[key] * 100))
        changed(slider.value())
        column.addWidget(text)
        column.addWidget(slider)
        parent.addLayout(column, 1)
        return slider

    def build_more(self):
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(36, 28, 36, 28)
        layout.addWidget(button('← 返回录歌', lambda: self.pages.setCurrentIndex(0)), alignment=Qt.AlignmentFlag.AlignLeft)
        layout.addWidget(label('更多', 'heading'))
        layout.addWidget(button('上传歌曲去人声', self.separate_dialog))
        layout.addWidget(button('去人声服务设置', self.service_settings))
        layout.addWidget(button('麦克风 / 扬声器设置', self.device_settings))
        layout.addWidget(button('检查桌面版更新', self.check_update))
        layout.addWidget(button('打开录音保存目录', lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.store.takes)))))
        layout.addWidget(button('关于 / 版权信息', self.about))
        layout.addStretch()
        self.pages.addWidget(page)

    def setting(self, key, value):
        self.store.data[key] = value
        self.store.save()

    def notify(self, text):
        self.status.setText(str(text))

    def fail(self, text):
        self.notify(text)
        QMessageBox.warning(self, '操作未完成', str(text))

    def available(self):
        return not self.busy and self.engine.mode != 'record'

    def task(self, text, fn, done=lambda value: None):
        if self.busy or self.engine.mode == 'record':
            return
        self.busy = True
        self.controls()
        self.notify(text)
        dialog = QProgressDialog(text, '取消', 0, 0, self)
        dialog.setWindowModality(Qt.WindowModality.WindowModal)
        dialog.setMinimumDuration(200)
        worker = Worker(fn)
        self.worker = worker
        dialog.canceled.connect(worker.cancel.set)
        def finish(value=None, error=None):
            dialog.close()
            self.busy = False
            self.worker = None
            self.controls()
            if error:
                if error != '已取消':
                    self.fail(error)
                else:
                    self.notify('已取消')
            else:
                try:
                    done(value)
                except Exception as e:
                    self.fail(e)
            self.controls()
        worker.signals.done.connect(lambda result: finish(value=result))
        worker.signals.error.connect(lambda error: finish(error=error))
        worker.signals.progress.connect(lambda t: (dialog.setLabelText(t), self.notify(t)))
        QThreadPool.globalInstance().start(worker)

    def ask_title(self, initial=''):
        while True:
            value, ok = QInputDialog.getText(self, '填写歌名', '伴奏：歌名-伴奏.扩展名　歌词：歌名.lrc', text=initial)
            if not ok:
                return None
            try:
                return song_title(value)
            except ValueError as e:
                self.fail(e)

    def pick_audio(self):
        value, _ = QFileDialog.getOpenFileName(self, '选择音频 / MP4', '', '音频与视频 (*.mp3 *.m4a *.wav *.ogg *.opus *.aac *.flac *.mp4 *.3gp);;所有文件 (*)')
        return Path(value) if value else None

    def choose_audio(self):
        if not self.available():
            return
        source = self.pick_audio()
        if not source:
            return
        if self.import_library.isChecked():
            if not self.library.folders:
                self.folder_dialog()
                if not self.library.folders:
                    return
            title = self.ask_title()
            if title:
                self.task('正在导入曲库…', lambda cancel, progress: self.library.import_audio(source, title, cancel=cancel), self.imported)
        else:
            self.load_song(self.library.remember_external(source))

    def imported(self, result):
        song, note = result
        self.refresh_library()
        self.load_song(song)
        self.notify(note)

    def add_pair(self):
        if not self.available():
            return
        if not self.library.folders:
            self.folder_dialog()
            if not self.library.folders:
                return
        source = self.pick_audio()
        if not source:
            return
        title = self.ask_title()
        if not title:
            return
        existing = self.library.existing(title)
        if existing:
            self.load_song(existing)
            self.notify('已使用曲库已有歌曲；新选文件未读取、未移动')
            return
        lrc, _ = QFileDialog.getOpenFileName(self, '选择配套 LRC', '', '歌词 (*.lrc);;所有文件 (*)')
        if lrc:
            self.task('正在添加伴奏和歌词…', lambda cancel, progress: self.library.import_audio(source, title, copy=self.store.data['copy'], lrc=Path(lrc), cancel=cancel), self.imported)

    def refresh_library(self, *_):
        self.library_list.clear()
        for copies in self.library.groups().values():
            song = copies[0]
            if self.search.text().casefold() not in song.title.casefold():
                continue
            row = QWidget()
            line = QHBoxLayout(row)
            line.setContentsMargins(0, 1, 0, 1)
            select = SongButton(song.title, lambda checked=False, s=song, alternatives=copies: self.select_group(s, alternatives))
            select.setToolTip(str(song.audio))
            line.addWidget(select, 1)
            line.addWidget(button('LRC ✓' if any(s.lrc for s in copies) else 'LRC', lambda checked=False, c=copies: self.group_lyrics(c)))
            line.addWidget(button('编辑', lambda checked=False, c=copies: self.edit_song(c)))
            item = QListWidgetItem()
            item.setSizeHint(QSize(0, 58))
            self.library_list.addItem(item)
            self.library_list.setItemWidget(item, row)
        self.library_list.fit_rows()
        if self.library.warnings:
            self.notify('部分曲库无法访问：' + '；'.join(self.library.warnings))

    def select_group(self, song, alternatives):
        if song.lrc is None:
            paired = next((s.lrc for s in alternatives if s.lrc), None)
            song = Song(song.audio, song.title, song.folder, paired)
        self.load_song(song)

    def choose_source(self, copies):
        if len(copies) == 1:
            return copies[0]
        names = [str(s.audio) for s in copies]
        selected, ok = QInputDialog.getItem(self, '选择文件来源', '选择要操作的实际文件', names, 0, False)
        return copies[names.index(selected)] if ok else None

    def group_lyrics(self, copies):
        if self.available():
            song = self.choose_source(copies)
            if song:
                self.lyrics_menu(song)

    def load_song(self, song):
        if not self.available():
            return
        self.stop_playback()
        def work(cancel, progress):
            buffer = decode(song.audio, self.store.cache, cancel)
            try:
                text = read_lrc(song.lrc) if song.lrc and song.lrc.exists() else ''
                return buffer, text
            except Exception:
                buffer.close()
                raise
        def ready(result):
            backing, text = result
            self.release_buffers()
            self.song, self.backing = song, backing
            self.engine.position = 0
            self.engine.mode = 'idle'
            self.replay = False
            self.lyrics = Lyrics(text)
            self.song_label.setText(song.title)
            self.seek.setRange(0, backing.frames // 48)
            self.setting('audio', str(song.audio))
            self.notify('伴奏已就绪 · 戴耳机后可直接开始录音')
        self.task('正在准备伴奏…', work, ready)

    def import_current_lyrics(self):
        if self.song:
            self.lyrics_menu(self.song, local=True)

    def lyrics_menu(self, song, local=False, online=False):
        if not song or not self.available():
            return
        if not local and not online:
            choice, ok = QInputDialog.getItem(self, '配套 LRC', '选择歌词来源', ['本地', '联网（网易云）'], 0, False)
            if not ok:
                return
            local, online = choice == '本地', choice != '本地'
        if local:
            p, _ = QFileDialog.getOpenFileName(self, '导入 LRC', '', '歌词 (*.lrc);;所有文件 (*)')
            if not p:
                return
            fn = lambda cancel, progress: self.library.save_lyrics(song, read_lrc(Path(p)))
        else:
            value, ok = QInputDialog.getText(self, '联网获取歌词', '网易云歌曲 ID 或歌曲链接')
            if not ok:
                return
            fn = lambda cancel, progress: self.library.save_lyrics(song, services.lyrics(value))
        def ready(updated):
            if self.song and path_key(updated.audio) == path_key(self.song.audio):
                self.song = updated
                self.lyrics = Lyrics(read_lrc(updated.lrc))
            self.refresh_library()
            self.notify('LRC 已保存到曲库文件夹')
        self.task('正在获取并保存 LRC…', fn, ready)

    def edit_song(self, copies):
        if not self.available():
            return
        song = self.choose_source(copies)
        if not song:
            return
        menu = QMenu(self)
        move = menu.addAction('整理到…')
        rename = menu.addAction('改名…')
        remove = menu.addAction('删除…')
        chosen = menu.exec(self.cursor().pos())
        if chosen == rename:
            title = self.ask_title(song.title)
            if title:
                self.stop_playback()
                def ready(updated):
                    self.refresh_library()
                    if self.song and path_key(self.song.audio) == path_key(song.audio):
                        self.load_song(updated)
                self.task('正在修改源文件名称…', lambda c, p: self.library.rename(song, title), ready)
        elif chosen == move:
            if not self.library.folders:
                self.folder_dialog()
            folders = self.library.folders
            if not folders:
                return
            dest, ok = QInputDialog.getItem(self, '整理到曲库', '勾选“复制而不是移动”则保留源文件', [str(p) for p in folders], 0, False)
            if ok and (not song.folder or path_key(dest) != path_key(song.folder)):
                self.stop_playback()
                self.task('正在整理并校验文件…', lambda c, p: self.library.import_audio(song.audio, song.title, Path(dest), self.store.data['copy'], song.lrc, reuse=False, cancel=c), self.imported)
        elif chosen == remove:
            dialog = QMessageBox(QMessageBox.Icon.Question, '删除曲库条目', song.title, parent=self)
            checkbox = QCheckBox('同时删除源伴奏和配套 LRC 文件')
            dialog.setCheckBox(checkbox)
            dialog.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
            if dialog.exec() == QMessageBox.StandardButton.Yes:
                self.stop_playback()
                def done(_):
                    if self.song and path_key(self.song.audio) == path_key(song.audio):
                        self.release_buffers()
                        self.song = None
                        self.setting('audio', '')
                        self.lyrics = Lyrics()
                        self.song_label.setText('选择一首伴奏，开始唱歌')
                    self.refresh_library()
                    self.notify('已删除源文件' if checkbox.isChecked() else '已移除曲库条目，源文件保留')
                self.task('正在删除…', lambda c, p: self.library.remove(song, checkbox.isChecked()), done)

    def folder_dialog(self):
        if not self.available():
            return
        dialog = QDialog(self)
        dialog.setWindowTitle('曲库文件夹')
        dialog.resize(820, 470)
        layout = QVBoxLayout(dialog)
        layout.addWidget(label('第一个文件夹为默认导入目标。双击可查看其中所有文件。'))
        listing = QListWidget()
        def refresh():
            listing.clear()
            for i, p in enumerate(self.library.folders):
                listing.addItem(('默认：' if i == 0 else '') + str(p))
        def selected():
            i = listing.currentRow()
            return (i, self.library.folders[i]) if i >= 0 else (None, None)
        def add():
            value = QFileDialog.getExistingDirectory(dialog, '添加曲库文件夹')
            if value:
                try:
                    self.library.add_folder(value)
                    refresh()
                except Exception as e:
                    self.fail(e)
        def first():
            i, p = selected()
            if p:
                folders = self.store.data['folders']
                folders.insert(0, folders.pop(i))
                self.store.save()
                refresh()
        def remove():
            i, p = selected()
            if p and QMessageBox.question(dialog, '移除曲库', f'移除 {p}？\n文件不会删除。') == QMessageBox.StandardButton.Yes:
                self.store.data['folders'].pop(i)
                self.store.save()
                refresh()
        def view():
            _, p = selected()
            if p:
                self.files_dialog(p)
        listing.itemDoubleClicked.connect(view)
        layout.addWidget(listing, 1)
        row = QHBoxLayout()
        for text, slot in [('添加', add), ('查看', view), ('设为默认', first), ('移除', remove)]:
            row.addWidget(button(text, slot))
        layout.addLayout(row)
        copy = QCheckBox('复制而不是移动（曲库内添加和整理时保留源文件）')
        copy.setChecked(self.store.data['copy'])
        copy.toggled.connect(lambda v: self.setting('copy', v))
        layout.addWidget(copy)
        layout.addWidget(button('返回', dialog.accept))
        refresh()
        dialog.exec()
        self.refresh_library()

    def files_dialog(self, folder):
        try:
            files = self.library.files(folder)
        except Exception as e:
            self.fail(e)
            return
        dialog = QDialog(self)
        dialog.setWindowTitle(str(folder))
        dialog.resize(760, 500)
        layout = QVBoxLayout(dialog)
        listing = QListWidget()
        listing.addItems([p.name for p in files] or ['文件夹中没有文件'])
        layout.addWidget(listing)
        layout.addWidget(button('返回', dialog.accept))
        dialog.exec()

    def scan_dialog(self):
        if not self.available():
            return
        try:
            files = self.library.unmatched()
        except Exception as e:
            self.fail(e)
            return
        if not files:
            self.notify('没有需要整理的音频或 LRC；请先添加曲库文件夹')
            return
        dialog = QDialog(self)
        dialog.setWindowTitle('选择音频 / LRC 并填写歌名；右键或 Delete 删除')
        dialog.resize(850, 500)
        layout = QVBoxLayout(dialog)
        listing = QListWidget()
        def refresh():
            listing.clear()
            listing.addItems([str(p) for p in files])
        def organize():
            i = listing.currentRow()
            if i < 0:
                return
            source = files[i]
            title = self.ask_title()
            if not title:
                return
            try:
                target = self.library.organize(source, title)
                if self.song and path_key(source) == path_key(self.song.audio) and source.suffix.lower() in AUDIO:
                    self.song = next((s for s in self.library.scan(True) if path_key(s.audio) == path_key(target)), Song(target, title, target.parent))
                    self.song_label.setText(self.song.title)
                    self.setting('audio', str(target))
                files[:] = self.library.unmatched()
                refresh()
                self.refresh_library()
                self.notify('源文件已规范命名')
                if self.song:
                    matched = next((s for s in self.library.scan() if path_key(s.audio) == path_key(self.song.audio)), None)
                    if matched and matched.lrc:
                        self.lyrics = Lyrics(read_lrc(matched.lrc))
                        self.song = matched
            except Exception as e:
                self.fail(e)
        def remove():
            i = listing.currentRow()
            if i >= 0 and QMessageBox.question(dialog, '是否删除源文件？', f'{files[i]}\n删除后无法恢复。') == QMessageBox.StandardButton.Yes:
                try:
                    files[i].unlink()
                    files.pop(i)
                    refresh()
                    self.refresh_library()
                except OSError as e:
                    self.fail(e)
        listing.itemDoubleClicked.connect(organize)
        listing.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        def context(pos):
            item = listing.itemAt(pos)
            if item:
                listing.setCurrentItem(item)
                menu = QMenu(listing)
                menu.addAction('填写歌名并整理', organize)
                menu.addAction('删除源文件', remove)
                menu.exec(listing.viewport().mapToGlobal(pos))
        listing.customContextMenuRequested.connect(context)
        QShortcut(QKeySequence.Delete, listing, activated=remove)
        layout.addWidget(listing)
        row = QHBoxLayout()
        row.addWidget(button('填写歌名并整理', organize))
        row.addWidget(button('删除源文件', remove))
        row.addWidget(button('返回', dialog.accept))
        layout.addLayout(row)
        refresh()
        dialog.exec()

    def stop_playback(self):
        self.engine.stop()

    def release_buffers(self):
        for name in ('backing', 'voice'):
            buffer = getattr(self, name, None)
            if buffer is not None:
                buffer.close()
                setattr(self, name, None)

    def preview(self):
        if not self.available() or not self.backing:
            return
        try:
            if self.engine.active and not self.replay:
                self.engine.stop()
                self.notify('试听已暂停')
            else:
                self.replay = False
                if self.song:
                    self.song_label.setText(self.song.title)
                self.engine.play(self.backing, self.engine.position)
                self.notify('正在试听伴奏 · 可直接开始录音')
            self.controls()
        except Exception as e:
            self.fail(e)

    def seek_to(self):
        if self.busy or self.engine.mode == 'record' or not self.backing:
            return
        pos = self.seek.value() * 48
        active = self.engine.active
        try:
            self.engine.stop()
            if self.replay and self.voice and self.take:
                cue = int(self.take['start_frame'])
                self.engine.position = min(max(pos, cue), cue + self.voice.frames)
                if active:
                    self.engine.play(self.backing, self.engine.position, self.voice, cue)
            else:
                self.engine.position = pos
                if active:
                    self.engine.play(self.backing, pos)
        except Exception as e:
            self.fail(e)

    def record(self):
        if self.busy:
            return
        if self.engine.mode == 'record':
            self.finish_record()
            return
        if not self.backing or self.replay or not self.song:
            return
        try:
            self.record_path = self.store.takes / recording_name(self.song.title)
            self.engine.record(self.backing, self.record_path, self.engine.position)
            self.record_button.setObjectName('recording')
            self.notify('正在录音 · 点击停止后自动保存人声')
            self.controls()
        except Exception as e:
            self.engine.mode = 'idle'
            self.controls()
            self.fail(f'无法启动录音，请检查麦克风权限及设备：{e}')

    def finish_record(self):
        if not self.record_path:
            return
        try:
            frames = self.engine.finish_record()
            if not frames:
                self.record_path.unlink(missing_ok=True)
                self.notify('没有录到音频')
            else:
                row = {'title': self.song.title, 'source': str(self.song.audio.resolve()),
                       'voice': str(self.record_path.resolve()), 'start_frame': self.engine.start,
                       'frames': frames, 'rate': RATE, 'created': datetime.now().isoformat()}
                atomic_json(self.record_path.with_suffix('.json'), row)
                self.engine.mode = 'idle'
                self.refresh_takes(str(self.record_path))
                self.notify(f'已保存 {frames / RATE:.1f} 秒人声：{self.record_path.name}')
        except Exception as e:
            self.fail(f'录音保存未完成，已写入的 WAV 保留在录音目录：{e}')
        finally:
            self.record_path = None
            self.engine.mode = 'idle'
            self.controls()

    def refresh_takes(self, select=None):
        self.take_list.blockSignals(True)
        self.take_list.clear()
        self.takes = self.store.recordings()
        for row in self.takes:
            title = row['title'] if len(row['title']) <= 12 else row['title'][:12] + '…'
            item = QListWidgetItem(title + '\n' + row['created'][:16].replace('T', ' ') + ' · ' + stamp(row['frames']))
            item.setSizeHint(QSize(0, 64))
            item.setToolTip(str(row['voice']))
            self.take_list.addItem(item)
        self.take_list.blockSignals(False)
        index = next((i for i, t in enumerate(self.takes) if t['voice'] == select), 0 if self.takes else -1)
        self.take_list.setCurrentRow(index)

    def take_selected(self, index):
        if self.engine.mode == 'record' or self.busy:
            return
        if self.replay:
            self.stop_playback()
            self.replay = False
        self.take = self.takes[index] if index >= 0 else None
        if self.take:
            self.take_info.setText(f"伴奏起点 {stamp(self.take['start_frame'])} · {self.take['frames'] / RATE:.1f} 秒\n{Path(self.take['voice']).name}")
        self.controls()

    def listen(self):
        if not self.available() or not self.take:
            return
        if self.replay and self.engine.active:
            self.engine.stop()
            self.controls()
            return
        if self.replay and self.voice:
            try:
                self.engine.play(self.backing, self.engine.position, self.voice, int(self.take['start_frame']))
            except Exception as e:
                self.fail(e)
            self.controls()
            return
        self.stop_playback()
        take = dict(self.take)
        def work(cancel, progress):
            backing = decode(take['source'], self.store.cache, cancel)
            try:
                voice = decode(take['voice'], self.store.cache, cancel)
                return backing, voice
            except Exception:
                backing.close()
                raise
        def ready(result):
            self.release_buffers()
            self.backing, self.voice = result
            self.song = next((s for s in self.library.scan(True) if path_key(s.audio) == path_key(take['source'])), Song(Path(take['source']), take['title'], None))
            self.lyrics = Lyrics(read_lrc(self.song.lrc) if self.song.lrc else '')
            self.song_label.setText(self.song.title + ' · 录音回听')
            self.replay = True
            cue = int(take['start_frame'])
            self.seek.setRange(cue // 48, (cue + self.voice.frames) // 48)
            self.engine.play(self.backing, cue, self.voice, cue)
            self.notify('正在回听 · 人声与伴奏音量可实时调整；试听伴奏可恢复录音')
        self.task('正在准备录音回听…', work, ready)

    def export_dialog(self):
        if not self.available() or not self.take:
            return
        kind, ok = QInputDialog.getItem(self, '导出录音', '按当前人声和伴奏音量导出', ['混音 WAV', '仅人声 WAV', '双轨 ZIP'], 0, False)
        if not ok:
            return
        suffix = '.zip' if kind == '双轨 ZIP' else '.wav'
        path, _ = QFileDialog.getSaveFileName(self, '保存导出文件', Path(self.take['voice']).stem + suffix,
                                             'ZIP (*.zip)' if suffix == '.zip' else 'WAV (*.wav)')
        if not path:
            return
        if path_key(path) in {path_key(self.take['voice']), path_key(self.take['source']), path_key(self.take['_meta'])}:
            self.fail('请选择其他导出文件名，避免覆盖源人声录音')
            return
        take = dict(self.take)
        self.stop_playback()
        def work(cancel, progress):
            backing = None if kind == '仅人声 WAV' else decode(take['source'], self.store.cache, cancel)
            voice = None
            try:
                voice = decode(take['voice'], self.store.cache, cancel)
                export_take(take, backing, voice, path, kind, self.engine.voice_gain, self.engine.backing_gain, cancel)
            finally:
                if backing:
                    backing.close()
                if voice:
                    voice.close()
            return path
        self.task('正在导出录音…', work, lambda p: self.notify('已导出：' + str(p)))

    def delete_take(self):
        if not self.available() or not self.take:
            return
        if QMessageBox.question(self, '删除录音？', Path(self.take['voice']).name + '\n将删除人声录音及时间轴记录，伴奏和歌词保留。') != QMessageBox.StandardButton.Yes:
            return
        try:
            self.stop_playback()
            self.replay = False
            Path(self.take['voice']).unlink()
            Path(self.take['_meta']).unlink(missing_ok=True)
            self.refresh_takes()
            self.notify('录音已删除')
        except OSError as e:
            self.fail(e)

    def tick(self):
        if self.engine.finished:
            error = self.engine.error
            if self.engine.mode == 'record':
                self.finish_record()
            else:
                self.engine.stop()
                self.notify('播放结束')
            if error:
                self.fail(error)
            self.controls()
        pos = self.engine.position
        if not self.seek.isSliderDown():
            self.seek.setValue(pos // 48)
        end = self.engine.end if self.replay else self.backing.frames if self.backing else 0
        self.clock.setText(stamp(pos) + ' / ' + stamp(end))
        i = self.lyrics.at(pos / 48 - self.shift.value())
        lines = self.lyrics.lines
        self.previous.setText(lines[i - 1][1] if i > 0 else '')
        self.current.setText(lines[i][1] if i >= 0 else '准备好，开始唱')
        self.next_line.setText(lines[i + 1][1] if i + 1 < len(lines) else '')

    def controls(self):
        recording = self.engine.mode == 'record'
        self.record_button.setText('停止并保存录音' if recording else '从当前位置开始录音')
        self.record_button.setObjectName('recording' if recording else 'primary')
        self.record_button.style().unpolish(self.record_button)
        self.record_button.style().polish(self.record_button)
        self.record_button.setEnabled(not self.busy and bool(self.backing) and not self.replay)
        self.preview_button.setEnabled(not self.busy and bool(self.backing) and not recording)
        self.preview_button.setText('暂停试听' if self.engine.active and not self.replay else '试听伴奏')
        self.seek.setEnabled(not self.busy and not recording and bool(self.backing))
        self.local_lrc.setEnabled(self.available() and bool(self.song))
        self.online_lrc.setEnabled(self.available() and bool(self.song))
        self.library_list.setEnabled(self.available())
        self.take_list.setEnabled(self.available())
        for w in (self.listen_button, self.export_button, self.delete_take_button):
            w.setEnabled(self.available() and bool(self.take))
        self.listen_button.setText('暂停回听' if self.replay and self.engine.active else '回听录音 / 继续' if self.replay else '回听录音')
        if not self.replay and self.backing:
            self.seek.setRange(0, self.backing.frames // 48)

    def service_settings(self):
        if not self.available():
            return
        dialog = QDialog(self)
        dialog.setWindowTitle('去人声服务设置')
        dialog.resize(600, 220)
        layout = QVBoxLayout(dialog)
        layout.addWidget(label('与手机版共用同一套自部署服务'))
        base = QLineEdit(self.store.data.get('server', ''))
        base.setPlaceholderText('http://电脑局域网IP:8765')
        token = QLineEdit(self.store.data.get('token', ''))
        token.setPlaceholderText('服务令牌')
        token.setEchoMode(QLineEdit.EchoMode.Password)
        layout.addWidget(base)
        layout.addWidget(token)
        def save():
            try:
                self.setting('server', services.service_address(base.text()))
                self.setting('token', token.text())
                dialog.accept()
            except Exception as e:
                self.fail(e)
        layout.addWidget(button('保存', save))
        layout.addWidget(button('测试连接', lambda: self.task('正在测试服务…', lambda c, p: services.health(base.text(), token.text()), self.notify)))
        dialog.exec()

    def separate_dialog(self):
        if not self.available():
            return
        if not self.store.data.get('server'):
            self.service_settings()
            if not self.store.data.get('server'):
                return
        source = self.pick_audio()
        if source:
            self.task('正在检查伴奏缓存…', lambda c, p: services.separate(source, self.store.data['server'], self.store.data.get('token', ''), self.store.cache, c, p),
                      lambda path: self.load_song(self.library.remember_external(path)))

    def device_settings(self):
        if not self.available():
            return
        try:
            import sounddevice as sd
            devices = sd.query_devices()
            dialog = QDialog(self)
            dialog.setWindowTitle('音频设备')
            layout = QVBoxLayout(dialog)
            inputs, outputs = QComboBox(), QComboBox()
            for combo, key in ((inputs, 'max_input_channels'), (outputs, 'max_output_channels')):
                combo.addItem('系统默认', None)
                for i, device in enumerate(devices):
                    if device[key] > 0:
                        combo.addItem(f"{device['name']} · {sd.query_hostapis(device['hostapi'])['name']}", i)
            layout.addWidget(label('麦克风'))
            layout.addWidget(inputs)
            layout.addWidget(label('扬声器 / 耳机'))
            layout.addWidget(outputs)
            layout.addWidget(label('推荐有线耳机。麦克风和输出设备需支持 48 kHz；不做耳返。'))
            def save():
                self.stop_playback()
                self.engine.input_device, self.engine.output_device = inputs.currentData(), outputs.currentData()
                dialog.accept()
            layout.addWidget(button('使用这些设备', save))
            dialog.exec()
        except Exception as e:
            self.fail(e)

    def about(self):
        QMessageBox.information(self, '关于', f'Sing 桌面版 {VERSION}\nCopyright © 2026 shandianchengzi\n\n个人非商业使用免费，商业使用须付费取得授权。\n{REPO}\n\n网易云歌词流程改编自 jitwxs/163MusicLyrics（Apache-2.0）。\n第三方许可证及依赖源代码地址见程序目录 LICENSE、NOTICE、THIRD_PARTY.md 和 dependency-licenses。')

    def check_update(self):
        if not self.available():
            return
        def check(cancel, progress):
            r = requests.get(REPO + '/releases/download/desktop-preview/desktop-update.json', timeout=20)
            r.raise_for_status()
            data = r.json()
            if tuple(map(int, data['version'].split('.'))) <= tuple(map(int, VERSION.split('.'))):
                return None
            asset = data['windows_x64']
            prefix = REPO + '/releases/download/desktop-preview/'
            if not asset['url'].startswith(prefix) or not asset['url'].endswith('.zip') or not (0 < asset['size'] < 1024**3) or len(asset['sha256']) != 64:
                raise ValueError('更新信息不符合本仓库发布格式')
            return data
        def found(data):
            if data is None:
                self.notify('当前已是最新桌面版')
                return
            if QMessageBox.question(self, '发现更新', f"下载桌面版 {data['version']}？\n下载后校验文件，解压覆盖程序目录；录音和设置保留。") != QMessageBox.StandardButton.Yes:
                return
            dest, _ = QFileDialog.getSaveFileName(self, '保存更新包', f"sing-desktop-{data['version']}-windows-x64.zip", 'ZIP (*.zip)')
            if not dest:
                return
            def download(cancel, progress):
                asset = data['windows_x64']
                temp = Path(dest + '.part')
                h, total = hashlib.sha256(), 0
                try:
                    with requests.get(asset['url'], stream=True, timeout=30) as r:
                        r.raise_for_status()
                        with temp.open('wb') as f:
                            for block in r.iter_content(65536):
                                if cancel.is_set():
                                    raise InterruptedError('已取消')
                                total += len(block)
                                if total > asset['size']:
                                    raise ValueError('更新包大小不符')
                                h.update(block)
                                f.write(block)
                                progress(f'正在下载更新… {total // 1024 // 1024} MB')
                    if total != asset['size'] or h.hexdigest() != asset['sha256']:
                        raise ValueError('更新包校验失败')
                    temp.replace(dest)
                finally:
                    temp.unlink(missing_ok=True)
                return dest
            self.task('正在下载桌面更新…', download, lambda p: self.notify('更新包校验通过：' + str(p)))
        self.task('正在检查桌面版更新…', check, found)

    def closeEvent(self, event):
        if self.busy:
            if self.worker:
                self.worker.cancel.set()
            self.notify('正在取消操作，完成后可关闭窗口')
            event.ignore()
            return
        if self.engine.mode == 'record':
            self.finish_record()
        self.stop_playback()
        self.release_buffers()
        self.store.save()
        event.accept()


def main():
    app = QApplication(sys.argv)
    app.setApplicationName('SingDesktop')
    app.setOrganizationName('shandianchengzi')
    app.setStyle('Fusion')
    app.setStyleSheet(STYLE)
    if '--smoke-test' in sys.argv:
        import tempfile
        import numpy as np
        import soundfile as sf
        from audio import ffmpeg
        import subprocess
        import sounddevice  # Verify packaged PortAudio DLLs are present.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / '中文路径测试.wav'
            sf.write(source, np.zeros((4800, 2), np.float32), RATE)
            decoded = decode(source, root)
            assert decoded.frames == 4800
            decoded.close()
            window = Window(root / 'state')
            window.show()
            app.processEvents()
            assert not window.local_lrc.isEnabled()
            window.close()
            subprocess.run([ffmpeg(), '-version'], check=True, capture_output=True)
        output = Path(sys.argv[sys.argv.index('--smoke-test') + 1])
        output.write_text(json.dumps({'success': True, 'version': VERSION, 'tests': ['Qt window', 'Chinese paths', 'FFmpeg decode', 'PortAudio import']}), encoding='utf-8')
        return 0
    try:
        window = Window()
    except Exception as error:
        QMessageBox.critical(None, '无法启动', str(error))
        return 1
    window.show()
    return app.exec()


if __name__ == '__main__':
    sys.exit(main())
