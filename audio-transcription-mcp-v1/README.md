# Audio Transcription MCP v1

给长会议录音准备的中文转写服务。第一版同时提供：

- 浏览器上传 MP3 / WAV / M4A / MP4 / OGG / WEBM / FLAC
- 长录音自动用 `ffmpeg` 切成约 15 分钟的小段
- 默认调用 `gpt-4o-transcribe-diarize`，输出说话人标签和时间戳
- 可关闭说话人区分，改用 `gpt-4o-transcribe` 并传入专业词提示
- 后台 Job 模式：上传后立刻返回 Job ID，网页轮询结果，避免 90 分钟录音把 HTTP 请求卡死
- MCP Streamable HTTP 接口：先支持“公开 HTTPS 音频 URL → 创建转写任务 → 查询结果”
- 转写完成后删除原始音频和临时切片；默认只保存 JSON 转写结果

> v1 的目标是先把“稳定听录音”这件事解决。ChatGPT 当前聊天附件如何直接交给自定义 MCP App，仍受 ChatGPT App 文件传递能力影响，因此 v1 不假装支持拿不到的聊天附件本体。

## 为什么要先切片

即使 OpenAI 的 diarization 模型支持服务端自动 chunking，本项目仍会先把超长音频统一压成单声道 16 kHz、48 kbps 的小段。这样 60–120 分钟会议也能保持每段上传体积较小，同时每段结果再按 offset 拼回整场会议时间轴。

## OpenAI 模型

默认：

- `gpt-4o-transcribe-diarize`
- `response_format=diarized_json`
- `chunking_strategy=auto`
- `language=zh`

关闭说话人区分后：

- `gpt-4o-transcribe`
- `language=zh`
- 可通过 glossary 给模型专业词提示

## 本地运行

需要 Python 3.12+ 与 ffmpeg。

```bash
cp .env.example .env
# 编辑 .env，填 OPENAI_API_KEY 与 APP_TOKEN
pip install -r requirements.txt
set -a; . ./.env; set +a
uvicorn app.server:app --host 0.0.0.0 --port 8000
```

浏览器打开 `http://localhost:8000/`。

## Docker

```bash
docker build -t audio-transcription-mcp .
docker run --rm -p 8000:8000 \
  -e OPENAI_API_KEY='你的API Key' \
  -e APP_TOKEN='一个长随机口令' \
  audio-transcription-mcp
```

## GitHub + Render 部署

仓库里已经放了 `render.yaml` 和 `Dockerfile`。在 Render 新建 Blueprint / Web Service 并连接这个 GitHub 仓库，然后在 Render 控制台填写：

- `OPENAI_API_KEY`
- `APP_TOKEN`

API Key **不要** 写进 GitHub 文件。

如果后续改用 Railway / Fly.io / 自己的服务器，Dockerfile 可以原样复用。

## HTTP API

创建任务：

```bash
curl -X POST https://你的域名/api/jobs \
  -H 'Authorization: Bearer YOUR_APP_TOKEN' \
  -F 'file=@meeting.mp3' \
  -F 'language=zh' \
  -F 'diarize=true'
```

查询：

```bash
curl https://你的域名/api/jobs/JOB_ID \
  -H 'Authorization: Bearer YOUR_APP_TOKEN'
```

完成后返回 `result.text`，格式类似：

```text
[00:12:31 - 00:12:43] speaker_0: ……
[00:12:44 - 00:13:02] speaker_1: ……
```

## MCP

MCP 使用官方 Python SDK 的 Streamable HTTP transport。服务启动后，MCP endpoint 由 SDK 挂载在 `/mcp`。

v1 暴露两个工具：

1. `create_transcription_job(audio_url, access_token, language="zh", diarize=true, glossary="")`
2. `get_transcription_job(job_id, access_token)`

这里暂时把 `access_token` 作为工具参数，是为了让第一版在没有完整 OAuth 配置时也不会裸奔消耗 API 额度。正式接到 ChatGPT 自定义 App 时，应该把它换成 OAuth / 标准 MCP 授权，不要长期保留这种方式。

## 针对“很吵、5个人”的会议

- 默认开启 diarization；模型会给不同 speaker 标签，但**不会保证 speaker_0 一定是谁**。
- 如果以后有每个人 2–10 秒的干净说话样本，可以继续加入 known speaker reference，提升身份映射能力。
- 噪音很大、多人同时说话时仍可能出现串人或漏字。结果应该保留 `[听不清]`/不确定标记，不应靠后处理“编完整”。
- 物流专业词可以在后续“转写校对 Skill”里用 Excel / Word 作为术语参考，但不能用附件反推录音原话。

## v1 已知限制

- Job 状态默认写在本地磁盘。免费/无持久盘的托管环境重启后历史任务可能消失。
- MCP 目前接收公开 HTTPS 音频 URL，不直接读取 ChatGPT 当前对话附件。
- MCP 正式生产授权还没接 OAuth。
- 第一版没有自动把 `speaker_0/1/2` 映射成 Carol / 媛媛等真实姓名。

## 下一步

1. 用真实 2–5 分钟会议验证 OpenAI diarized response 的实际字段形状。
2. 用 60–100 分钟会议压力测试切片、拼接和失败重试。
3. 加入单段自动重试与断点续跑。
4. 做“专业词校正但不改事实”的二阶段处理。
5. 如果 ChatGPT 账号开放自定义 MCP App 入口，再配置远程 MCP 与正式 OAuth。
