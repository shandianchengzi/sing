# 轻唱 Sing

原生 Android 轻量录歌工具，Java + 系统 API，无第三方运行库、无 FFmpeg、无 WebView。安装包约 37 KB（实际大小见构建日志）。Android 8.0 及以上。

[下载最新测试版 APK](https://github.com/shandianchengzi/sing/releases/download/preview/sing.apk) · [仓库内 APK](https://github.com/shandianchengzi/sing/raw/refs/heads/main/apk/sing.apk) · [自动构建](https://github.com/shandianchengzi/sing/actions)

## 使用

1. 选择手机上的伴奏（系统支持的 MP3/M4A/WAV/OGG（Vorbis、Opus）等音频格式，或 MP4 视频）。
   MP4 会在手机本地自动无损提取第一条音轨作为伴奏，不显示或解码视频，不改变原文件；临时音轨在切换伴奏或关闭页面时删除。无音轨会提示错误，音轨编码须为系统支持的格式（推荐 AAC）。
2. 导入 LRC，或输入网易云歌曲 ID / 含 `id=` 的完整歌曲链接获取歌词。获取失败可直接本地导入。短链接须先在浏览器展开。
3. 试听确认歌词时间，可调整歌词延后毫秒数，负数为提前。
4. 戴耳机，点击“从头开始录音”，停止后自动保存。伴奏结束也会自动保存。
5. 回听人声，通过系统文件选择器导出 M4A；“已保存的录音”可选择历史录音。

录制的是麦克风人声轨，**不会把伴奏数字混入录音**，也没有耳返、修音、降噪或评分。外放时麦克风会收进伴奏，建议有线耳机；蓝牙和设备录音启动可能产生延迟，此版本不做采样级同步。开始录音会从伴奏开头重录。切到后台/锁屏/失去音频焦点会结束录音并保存，不做后台录制。

录音保存在应用私有目录，卸载会删除，请及时导出。默认操作都在本地；主动联网获取歌词时访问网易云，主动上传去人声时只把所选文件发给你配置的服务器。

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

产物 `build/sing.apk`。直接使用 SDK 工具，省去 Gradle 和依赖下载。推送 main 自动构建，并更新 `preview` 测试版 Release。

### 固定签名和保留数据的升级

1. 下载新版 APK 后直接覆盖安装，**不要卸载、不要清除应用数据**。包名保持 `com.shandian.sing`，录音仍在原私有目录。歌词、歌词偏移和已授权的伴奏 URI 会自动保存，下次打开恢复；伴奏被移动/删除时需重选。
2. v1.0、v1.1 的仓库内 APK 和本次 v1.2 使用同一签名。`signing/certificate.sha256` 固定了公钥证书指纹。构建后会核对签名，错误签名不能通过构建。
3. 不再自动生成密钥。私钥缺失会终止构建。私钥只在你的私密备份和 Actions Secrets 中保存，**不得上传到公共仓库**。
4. 首次配置：从私密签名备份解压出 `sing-fixed.jks` 和 `configure-signing.ps1`，在 Windows PowerShell 中运行脚本。需要 GitHub CLI（`winget install --id GitHub.cli`），脚本会引导登录，保存仓库 Actions Secrets 并触发构建。脚本不打印密钥内容。
5. CI 使用 `SING_KEYSTORE_BASE64`、`SING_KEYSTORE_PASSWORD`（当前密钥密码 android，alias androiddebugkey），每次版本号自动增加。CI 私钥仅写入临时目录，完成后删除。
6. 本地构建设置 `SIGNING_KEYSTORE=/private/path/sing-fixed.jks`，`SIGNING_PASSWORD=android`；或把备份恢复到被 git 忽略的 `build/development.jks`。不要更换密钥。CI 的 versionCode 为 1000 加运行序号；本地构建更新已安装 CI 包时需设置更大的 `VERSION_CODE`。

这里实现的是小体积完整 APK 的**覆盖升级**，数据保留由 Android 的同包名、同签名安装机制提供。没有引入二进制增量补丁引擎；约几十 KB 的 APK，额外补丁组件会得不偿失。“下载更新”按钮打开新版 APK 下载页面，由系统安装器确认安装。应用自身不请求安装其他应用的权限。

如果曾安装其他来源的随机签名 CI 包，不能直接覆盖，需要先导出录音再迁移，不能通过更换签名绕过系统校验。

## 验证范围

已执行 Java 编译、DEX 转换、APK 打包和签名校验，以及 LRC 多时间戳、排序、小数精度、offset 和查询边界测试。已核对更新前后 APK 的包名、签名与递增版本号；尚未进行真机覆盖升级及麦克风/耳机录音验证，联网接口也需要在实际网络中确认。

Apache-2.0，第三方归属见 NOTICE。

OGG 伴奏可直接在手机播放，文件选择器同时识别 audio/* 和 application/ogg 类型，无需转换或上传服务器。
