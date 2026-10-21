# 轻唱 Sing

原生 Android 轻量录歌工具，Java + 系统 API，无第三方运行库、无 FFmpeg、无 WebView。安装包约 45 KB（实际大小见构建日志）。Android 8.0 及以上。

[下载最新测试版 APK](https://github.com/shandianchengzi/sing/releases/download/preview/sing.apk) · [仓库内 APK](https://github.com/shandianchengzi/sing/raw/refs/heads/main/apk/sing.apk) · [自动构建](https://github.com/shandianchengzi/sing/actions)

## 使用

1. 选择手机上的伴奏（系统支持的 MP3/M4A/WAV/OGG（Vorbis、Opus）等音频格式，或 MP4 视频）。
   MP4 会在手机本地自动无损提取第一条音轨作为伴奏，不显示或解码视频，不改变原文件；临时音轨在切换伴奏或关闭页面时删除。无音轨会提示错误，音轨编码须为系统支持的格式（推荐 AAC）。
2. LRC 可以通过电脑软件 [163MusicLyrics](https://github.com/jitwxs/163MusicLyrics) 获取，导出后传到手机。导入 LRC，或输入网易云歌曲 ID / 含 `id=` 的完整歌曲链接获取歌词。获取失败可直接本地导入。短链接须先在浏览器展开。
3. 试听确认歌词时间，可调整歌词延后毫秒数，负数为提前。
4. 戴耳机，拖动到想录制的位置，点击“从当前位置开始录音”，停止后自动保存。伴奏结束也会自动保存。
5. 回听时人声与对应伴奏一起播放，共用伴奏时间轴进度条，可暂停、拖动并继续；回听范围限于本次录制时段。“已保存的录音”可选择历史录音。
6. 点击“导出最近录音”，选择仅人声 M4A、人声＋伴奏混音 WAV，或人声和伴奏分别打包 ZIP。混音从本次录音起点截取对应的伴奏时段，ZIP 包含整段伴奏、人声和起点说明（MP4 只导出音轨）。

录音源文件仍是独立麦克风人声轨，导出时可选择与伴奏混音，也没有耳返、修音、降噪或评分。外放时麦克风会收进伴奏，建议有线耳机；蓝牙和设备录音启动可能产生延迟，此版本不做采样级同步。每次录音都会保存对应伴奏 URI 和起点，回听/导出时按此对齐；请不要删除或移动原伴奏。切到后台/锁屏/失去音频焦点会结束录音并保存，不做后台录制。

录音保存在应用私有目录，卸载会删除，请及时导出。默认操作都在本地；主动联网获取歌词时访问网易云，主动上传去人声时只把所选文件发给你配置的服务器。

## 应用内更新（v1.6）

点击“检查更新（应用内下载）”可比较已安装版本与发布版本，确认后直接在应用中下载 APK，显示下载进度，校验完成后点“安装”。首次可能需要在系统设置允许轻唱安装此来源的应用，返回后继续安装。无需浏览器另存安装包；覆盖安装保留录音、设置和缓存，不要卸载旧版。

更新请求仅访问本仓库的 HTTPS Release；下载后核对 SHA-256、文件大小、包名、版本号及当前安装的签名。取消或下载失败会清理临时文件，已校验完成的包可复用。录音/导出等操作进行中不发起安装。退出应用会取消未完成的更新下载。

发布流程根据实际签名 APK 生成 `update.json`；下载文件名同时包含版本号和完整 SHA-256。先上传 APK，再发布更新信息，避免发布时客户端下载到不匹配的文件。不需要新增 GitHub Secrets。

这次先手动覆盖安装含更新入口的新版，此后的版本即可通过应用内按钮拉取。最终安装仍由系统提示并需要你确认。

## 自部署去人声（v1.3）

无需第三方付费 API。电脑上运行本仓库的 Demucs 服务，手机填地址和令牌，直接上传原曲（也支持 MP4），自动下载伴奏并用于录歌。APK 不包含模型，仍然只有几十 KB。相同文件生成的伴奏会缓存到手机，下次无需重新上传。

[查看部署说明](server/README.md) · [下载自部署服务包](https://github.com/shandianchengzi/sing/releases/download/preview/sing-server.zip)。最方便的方式是安装 Docker，在 `server/.env` 设置自己的 `SING_TOKEN`，然后在 server 目录运行 `docker compose up -d --build`。手机“去人声服务设置”填 `http://电脑局域网IP:8765` 和相同令牌，测试连接后使用“上传歌曲去人声”。自部署依然需要自己的硬件、电费和初次下载模型；服务按本文档部署即可兼容，无需修改模型工具的桌面界面。

## 歌词来源

已将 [jitwxs/163MusicLyrics](https://github.com/jitwxs/163MusicLyrics) 的网易云歌词获取及 AES/RSA 请求流程移植为 Java，见 `LyricsApi.java` 和 `NOTICE`，无需安装原桌面工具。当前支持按歌曲 ID 获取，不含歌名搜索、QQ 音乐或登录。远端接口可能要求登录、限流或变化，自动获取不作可用性保证。

## 构建

JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，系统命令 `zip`。

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
bash tests/run.sh
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

默认使用 Android SDK 自带 R8 优化、裁剪和混淆代码，再按 ZIP 最大压缩率打包；保留 Activity 生命周期入口。可用 `NO_SHRINK=1` 构建相同功能的未裁剪对照包。没有引入第三方运行库，Android 8+ 使用 v2/v3 固定签名。

产物 `build/sing.apk`。直接使用 SDK 工具，省去 Gradle 和依赖下载。推送 main 自动构建，并更新 `preview` 测试版 Release。

### 固定签名和保留数据的升级

1. 下载新版 APK 后直接覆盖安装，**不要卸载、不要清除应用数据**。包名保持 `com.shandian.sing`，录音仍在原私有目录。歌词、歌词偏移和已授权的伴奏 URI 会自动保存，下次打开恢复；伴奏被移动/删除时需重选。
2. 当前发布版本沿用最初的同一签名。`signing/certificate.sha256` 固定了公钥证书指纹。构建后会核对签名，错误签名不能通过构建。
3. 不再自动生成密钥。私钥缺失会终止构建。私钥只在你的私密备份和 Actions Secrets 中保存，**不得上传到公共仓库**。
4. 首次配置：从私密签名备份解压出 `sing-fixed.jks` 和 `configure-signing.ps1`，在 Windows PowerShell 中运行脚本。需要 GitHub CLI（`winget install --id GitHub.cli`），脚本会引导登录，保存仓库 Actions Secrets 并触发构建。脚本不打印密钥内容。
5. CI 使用 `SING_KEYSTORE_BASE64`、`SING_KEYSTORE_PASSWORD`（当前密钥密码 android，alias androiddebugkey），每次版本号自动增加。CI 私钥仅写入临时目录，完成后删除。
6. 本地构建设置 `SIGNING_KEYSTORE=/private/path/sing-fixed.jks`，`SIGNING_PASSWORD=android`；或把备份恢复到被 git 忽略的 `build/development.jks`。不要更换密钥。CI 的 versionCode 为 1000 加运行序号；本地构建更新已安装 CI 包时需设置更大的 `VERSION_CODE`。

这里实现的是小体积完整 APK 的**覆盖升级**，数据保留由 Android 的同包名、同签名安装机制提供。没有引入二进制增量补丁引擎；约几十 KB 的 APK，额外补丁组件会得不偿失。“下载更新”按钮打开新版 APK 下载页面，由系统安装器确认安装。应用仅在你选择安装更新时引导授予安装来源权限，并由系统安装器确认安装。

如果曾安装其他来源的随机签名 CI 包，不能直接覆盖，需要先导出录音再迁移，不能通过更换签名绕过系统校验。

## 验证范围

已执行 Java 编译、R8 优化、APK 打包和固定签名校验，以及 LRC 多时间戳、排序、小数精度、offset 和查询边界测试；新增录音绝对时间→人声时间映射、混音幅度、单/双声道、44.1→48 kHz 重采样、文件结尾补静音和 WAV 头测试。混音导出使用系统解码器和流式 PCM，WAV 为 48 kHz/16-bit/stereo，两轨等比例混合以避免叠加溢出。解码过程中会使用临时磁盘空间，结束或取消后清理。旧录音没有元数据时按 00:00 对齐当前伴奏，混音/双轨导出前需要确认；不会删除原录音。[真机验收清单](tests/README.md)。已核对更新前后 APK 的包名、签名与递增版本号；尚未进行真机覆盖升级及麦克风/耳机录音验证，联网接口也需要在实际网络中确认。

Apache-2.0，第三方归属见 NOTICE。

OGG 伴奏可直接在手机播放，文件选择器同时识别 audio/* 和 application/ogg 类型，无需转换或上传服务器。
