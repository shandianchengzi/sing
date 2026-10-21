# 自部署去人声服务（轻唱 API v1）

基于开源 Demucs 4 / htdemucs，把完整歌曲分成人声与伴奏。手机上传歌曲后会自动下载伴奏，不需要第三方付费 API。模型和推理在你的电脑上运行；需要自己的算力、存储和电费。

## 最方便：Docker（Windows / Linux）

电脑安装并启动 Docker Desktop（Windows 建议 WSL2 后端），下载或克隆本仓库，进入 `server` 目录。

1. 在 `server` 目录创建名为 `.env` 的文件，写入自己的服务令牌（至少 12 个字符，建议随机生成）：

```dotenv
SING_TOKEN=replace-with-your-own-long-private-token
```

2. 在此目录执行：

```sh
docker compose up -d --build
```

默认 CPU 推理，不需要 NVIDIA 显卡。首次构建需要下载 Python/PyTorch 依赖，首次处理还会下载模型，之后通过 volume 缓存。请预留数 GB 磁盘和充足内存；较长歌曲 CPU 分离会耗时。

3. 查电脑的局域网 IPv4（Windows 执行 `ipconfig`），例如 `192.168.1.10`。
4. 手机和电脑连同一 Wi-Fi，在轻唱 → “去人声服务设置”填写：
   - 地址：`http://192.168.1.10:8765`
   - 令牌：`.env` 中 `SING_TOKEN=` 后面的内容
5. 点“测试连接”，成功后保存，再点“上传歌曲去人声”。支持 MP3、M4A、WAV、MP4 等 FFmpeg 能解码的文件，取回 AAC/M4A 伴奏后自动作为伴奏使用。

Windows 防火墙需允许 Docker/WSL 的 8765 端口，且 Wi-Fi 不能开启客户端隔离。手机地址不要填写 `localhost` 或 `127.0.0.1`，它们在手机上指向手机自己。

停止服务：`docker compose down`。更新代码后：`docker compose up -d --build`。不要附加 `-v`，否则会删除模型缓存和服务器结果。

## 不使用 Docker：Python 环境

需要 Python 3.11 和 FFmpeg（`ffmpeg`、`ffprobe` 都能从命令行调用）。安装 FFmpeg 以后，在 server 目录执行：

```sh
python -m venv .venv
# Windows PowerShell
.\.venv\Scripts\Activate.ps1
# Linux / macOS 则用 source .venv/bin/activate
python -m pip install torch==2.5.1 torchaudio==2.5.1 --index-url https://download.pytorch.org/whl/cpu
python -m pip install -r requirements.txt
```

Windows PowerShell 启动：

```powershell
$env:SING_TOKEN="换成你自己的至少12字符令牌"
$env:SING_DEVICE="cpu"
python -m uvicorn app:app --host 0.0.0.0 --port 8765 --workers 1
```

Linux / macOS：

```sh
export SING_TOKEN='your-private-token-at-least-12-characters'
export SING_DEVICE=cpu
python -m uvicorn app:app --host 0.0.0.0 --port 8765 --workers 1
```

不要使用 `--reload` 或多个 workers。对于 NVIDIA GPU：在独立 Python 环境安装与显卡驱动匹配的 PyTorch/Torchaudio 2.5.1 CUDA 版本，将 `SING_DEVICE` 设置为 `cuda`；上面的 Dockerfile 是 CPU 专用镜像。GPU 部署未在本次验证中测试。

## 资源和文件

- 最多 100 MB、30 分钟的输入；单个任务运行最长 1 小时。
- 同时处理一首歌，最多容纳 4 个上传/处理/排队任务；超出返回 429。
- 原始文件和中间音轨在任务结束后删除。结果和任务日志默认保留最多 24 小时，每分钟自动清理。手机下载成功后也会请求立即删除服务端任务。
- 结果保存在手机的应用私有伴奏目录中，相同文件、相同服务会命中本地缓存，应用覆盖升级保留缓存。卸载会删除本机伴奏缓存和未导出的录音。
- 取消会尽力结束服务器任务；上传时取消或手机进程被杀导致未拿到任务 ID 的，残留任务由服务端超时/过期清理兜底。
- 模型首次下载需要访问其上游模型托管地址；下载成功后本地推理不需要付费服务。可以提前运行一次短歌曲来预热。
- `.env` 不提交仓库，令牌与 GitHub APK 签名 Secret 没有关系。服务器令牌仅保护本服务，可自行更换并同步修改手机设置。
- HTTP 适用于可信局域网；公网访问请使用 HTTPS 反向代理或自己的 VPN，不要直接把明文 HTTP 服务暴露公网。

查看故障：`docker compose logs -f`；详细处理日志在数据目录的 `<任务ID>/worker.log`。确认 FFmpeg 可用、电脑内存足够、模型可下载。不要关闭 TLS 证书验证。

## API 协议

所有业务请求使用 `Authorization: Bearer <SING_TOKEN>`。该适配器是**轻唱自部署协议**，不代表任意 Demucs/UVR 网站填入地址就能使用。

| 请求 | 行为 |
|---|---|
| `GET /health` | 返回 `service=sing-separation`、`api_version=1` |
| `POST /jobs` | body 为原始文件字节，返回 `202 {id,status}` |
| `GET /jobs/{id}` | 返回 queued / running / succeeded / failed |
| `GET /jobs/{id}/result` | 成功后下载伴奏 M4A |
| `DELETE /jobs/{id}` | 取消任务或删除结果 |

## 验证

```sh
python -m pip install httpx==0.28.1
python test_api.py
python smoke_test.py
```

协议测试检查鉴权、空文件、大小限制、排队上限、未就绪结果和清理。`smoke_test.py` 会真实下载模型并用 CPU 跑 3 秒合成音频 MP4，检查返回 AAC 音轨、时长和删除。这证明真实处理链路可运行，不代表实际歌曲的去人声质量。Android APK 已编译和固定签名验证，尚未在手机上实测服务连接与上传。Docker / NVIDIA GPU 启动未实测。

Demucs 使用 MIT 许可证；本服务是独立 HTTP 封装，不把模型打包进 APK。上游：[facebookresearch/demucs](https://github.com/facebookresearch/demucs)。
