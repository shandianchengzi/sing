# 手机本地提取伴奏

在轻唱 Android 应用「更多 → 手机本地提取伴奏」选择原曲、填写歌名即可。
支持 MP3、AAC、M4A、WAV 及系统可解码音视频的首个音轨。
提取完成后关闭进度框，自动载入录歌页，并保存为 `歌名-伴奏.wav`。
伴奏出现在应用曲库，可继续关联 LRC、整理到已有曲库文件夹并录歌。

首次使用普通 APK 时下载约 75 MB 原模型；安装完整离线 APK 则从包内安装模型。
之后手机上的歌曲不会上传、分离不依赖服务器或电脑。模型独立保存在应用数据中，
程序更新沿用模型、录音与曲库设置。卸载/清除应用数据会删除应用内部文件。
Android 系统仍需确认安装更新；不是绕过系统的静默更新。

## 原模型与实现

沿用 vocal-separate v0.0.4 所使用的原 Spleeter 2stems 权重。
已逐文件校验 vocal-separate v0.0.4 原包中的 `.meta`、`.index` 和权重数据，与 Deezer v1.4.0 分发完全相同。
源 checkpoint 归档 SHA-256：
`f3a90b39dd2874269e8b05a48a86745df897b848c61f3958efc80a39152bd692`。

手机模型为 FP32，不重新训练、不量化、不更换为 Demucs 或其他模型。
从 checkpoint 冻结两个 U-Net 及原 exponent=2 比率掩码；STFT/iSTFT 移至 Java。
44.1 kHz 双声道，周期 Hann 4096，步长 1024，T=512、F=1024，
高频掩码扩展为零，逆变换补偿 2/3；保留 Spleeter 2.4 的开头 4096 点补零与裁剪。
按原 512 帧网络分区推理，全局连续 overlap-add，不在网络分区接缝重新补零。
原采样率不同的文件使用应用现有 PCM 重采样流程；不承诺与桌面 ffmpeg 重采样逐点相同。

模型：78,607,648 字节，SHA-256：
`4eb7713aa9b13906c5e8918c61a2d4bf2d667d4bfd0cda6501cb7c4aed915b68`。
使用 TensorFlow Lite 2.16.1 CPU/XNNPACK 四线程，arm64-v8a（目标 vivo X100）
及 x86_64（模拟器）；首次版本未启用 GPU/NPU 或 FP16。

整个歌曲解码到临时 PCM 磁盘文件，模型处理只保留一个网络分区。
后台前台服务带进度通知与取消，完成/失败清理临时文件；被系统强杀不自动续跑。
切换应用后任务继续，关闭/强行停止整个应用则不能保证继续。
取消在解码循环及模型分区间检查，正在执行的单次推理需返回后结束。

## 构建和验证

Java 17、Android SDK 35/build-tools 35.0.0、Python 3.12：

```sh
python -m pip install tensorflow-cpu==2.16.2 zopfli==0.4.0
python scripts/mobile-model/prepare_model.py
bash tests/run.sh
# 使用仓库已配置的固定签名（build.sh 不会生成替代密钥）
bash build.sh
# 可选：包含模型的完整离线 APK
OFFLINE_ASSETS=build/mobile-model/assets bash build.sh
```

`verify_model.py` 使用真实 checkpoint 输出对照「实际 Java STFT → 实际 TFLite →
实际 Java iSTFT」，覆盖跨 512 帧边界的双声道合成音和静音；校验长度、有限采样和数值误差。
这是数值一致性回归，不是对真实歌曲分离音质的主观评估。
设备速度、温升及真实歌曲听感仍须 vivo X100 真机验证，不能用构建机耗时代替。

原权重与 Spleeter 为 MIT；TensorFlow Lite 为 Apache-2.0。
没有引入 vocal-separate 的 GPL Windows 包装代码或未确认许可的 Android 示例代码。
