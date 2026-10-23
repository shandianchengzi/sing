# Sing 桌面版

Windows 10/11 x64 原生横屏录歌工具。左侧曲库，中间歌词、时间轴与录音控制，右侧历史录音；界面不另占一行显示应用标题。状态在最上方。

[下载 Windows 便携版](https://github.com/shandianchengzi/sing/releases/download/desktop-preview/sing-desktop-windows-x64.zip) · [桌面版发布页](https://github.com/shandianchengzi/sing/releases/tag/desktop-preview)

解压整个 ZIP 后运行 `SingDesktop/SingDesktop.exe`，不要只取出 exe；无需安装 Python、FFmpeg 或配置凭据。程序没有 Windows 代码签名证书，首次运行可能出现系统发布者提示。录音和设置保存在 `%LOCALAPPDATA%/shandianchengzi/SingDesktop`，覆盖更新程序目录不会删除数据，不要删除用户数据目录。

## 与手机版一致的功能

- 多曲库文件夹、默认曲库、搜索、同名歌曲合并；不显示副本数量。文件夹管理中可查看其中所有文件。
- 只自动匹配 `歌名-伴奏.扩展名` 和 `歌名.lrc`。MP3、M4A、WAV、OGG、Opus、AAC、FLAC、MP4、3GP 可导入，MP4 只解码音轨。
- 选择伴奏旁“导入曲库（移动）”默认勾选；要求填写歌名。已有同名歌曲直接使用曲库伴奏和 LRC，不读取或移动新选文件。未勾选直接打开文件。
- 曲库管理中“复制而不是移动”默认不勾选，控制曲库添加和整理操作。复制并校验成功、录音引用更新后再删除源文件；不同内容的同名目标不会覆盖。
- 条目右侧保留 LRC；编辑菜单包含整理到、实际文件改名、删除。删除可选同时删除源伴奏和 LRC。
- 扫描整理未规范音频及未匹配 LRC，填写歌名后实际改名；电脑使用右键或 Delete 确认删除，代替手机长按。
- LRC 本地导入或网易云 ID / 链接获取；歌词保存到曲库文件夹。未选伴奏前禁用导入 LRC；可调正负毫秒偏移。
- 从当前播放头开始录音，独立保存人声。回听保留伴奏起点，支持暂停、继续、拖动、人声与伴奏实时音量。回听时点“试听伴奏”恢复录音功能。
- 历史录音自动以“歌名_时间.wav”命名，支持删除。导出混音 WAV、仅人声 WAV、双轨 ZIP；双轨从零对齐，附原伴奏起点。回听及混音导出使用同一算法。
- 更多中包含上传歌曲去人声、服务设置、音频设备、检查桌面更新、关于。去人声仍使用仓库的自部署服务，不内嵌 Demucs 模型。

## 桌面音频与数据

推荐有线耳机，外放会被麦克风收进录音。首次录音需允许 Windows 麦克风访问；可在更多中选择麦克风与扬声器。设备需支持 48 kHz。不做耳返、修音或蓝牙延迟补偿；输入输出设备的驱动延迟可能影响对齐。原始人声为 48 kHz 单声道 float WAV，便于再次处理；导出为常见 PCM16 WAV。与手机的 M4A 录音文件格式不同。

准备伴奏会生成本地解码缓存，不重复解码相同文件。麦克风、伴奏和录音不上传到任何服务器；只有主动获取歌词、检查更新，或上传去人声时联网。更新包会验证大小和 SHA-256，下载后手动解压覆盖程序目录。歌词接口与手机版相同，服务端限制可能导致获取失败，可改用本地 LRC。

手机与电脑可通过共享目录、Git、同步工具交换符合命名规则的曲库文件；应用本身不负责同步，也不直接迁移 Android 私有录音和文件夹授权。

## 源码运行与构建

```powershell
python -m pip install -r desktop/requirements.txt
python desktop/app.py
# 开发测试
python -m pip install pytest==9.1.1
python -m pytest desktop/tests -q
# 在 Windows 构建独立程序
python -m pip install pyinstaller==6.22.3
python desktop/build.py
```

Linux 源码运行需安装 PortAudio 系统库，例如 `libportaudio2`；Windows 包已携带所需库。GitHub Actions 在 Windows 上运行文件管理、音频时间轴、真实解码/导出、GUI 模拟设备测试，并启动打包后的 exe 验证窗口、中文路径、FFmpeg 和 PortAudio 加载。自动测试不等同于真实麦克风/声卡的人工听测。

项目版权、个人免费使用和付费商业授权见仓库 LICENSE。第三方依赖见 THIRD_PARTY.md 和 dependency-licenses。
