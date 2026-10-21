# 轻唱 Sing

原生 Android 轻量录歌工具，Java + 系统 API，无第三方运行库、无 FFmpeg、无 WebView。安装包约 25 KB（实际大小见构建日志）。Android 8.0 及以上。

[下载最新测试版 APK](https://github.com/shandianchengzi/sing/releases/download/preview/sing.apk) · [仓库内 APK](https://github.com/shandianchengzi/sing/raw/refs/heads/main/apk/sing.apk) · [自动构建](https://github.com/shandianchengzi/sing/actions)

## 使用

1. 选择手机上的伴奏（系统支持的 MP3/M4A/WAV 等格式）。
2. 导入 LRC，或输入网易云歌曲 ID / 含 `id=` 的完整歌曲链接获取歌词。获取失败可直接本地导入。短链接须先在浏览器展开。
3. 试听确认歌词时间，可调整歌词延后毫秒数，负数为提前。
4. 戴耳机，点击“从头开始录音”，停止后自动保存。伴奏结束也会自动保存。
5. 回听人声，通过系统文件选择器导出 M4A；“已保存的录音”可选择历史录音。

录制的是麦克风人声轨，**不会把伴奏数字混入录音**，也没有耳返、修音、降噪或评分。外放时麦克风会收进伴奏，建议有线耳机；蓝牙和设备录音启动可能产生延迟，此版本不做采样级同步。开始录音会从伴奏开头重录。切到后台/锁屏/失去音频焦点会结束录音并保存，不做后台录制。

录音保存在应用私有目录，卸载会删除，请及时导出。无上传服务器，联网仅用于用户主动获取歌词。

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

开发签名私钥不提交仓库。构建时自动生成本地测试密钥；不同 CI 运行之间签名不同，需要卸载旧版再安装（先导出录音）。正式分发应在私有环境通过 `SIGNING_KEYSTORE`、`SIGNING_PASSWORD`、`SIGNING_ALIAS` 使用固定私钥。请勿把私钥提交仓库。

## 验证范围

已执行 Java 编译、DEX 转换、APK 打包和签名校验，以及 LRC 多时间戳、排序、小数精度、offset 和查询边界测试。尚未进行真机麦克风/耳机录音验证，联网接口也需要在实际网络中确认。

Apache-2.0，第三方归属见 NOTICE。
