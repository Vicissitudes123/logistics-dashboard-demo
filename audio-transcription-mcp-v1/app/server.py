from __future__ import annotations

import asyncio
import contextlib
import tempfile
from pathlib import Path

from mcp.server.fastmcp import FastMCP
from starlette.applications import Starlette
from starlette.datastructures import UploadFile
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse
from starlette.routing import Mount, Route

from .jobs import create_job, load_job
from .security import require_http_token, token_ok
from .service import download_public_audio, run_file_job
from .settings import settings


mcp = FastMCP(
    "Audio Transcription",
    instructions=(
        "Create and inspect audio-transcription jobs. The service is optimized for Chinese meetings, "
        "long recordings, timestamps, and optional speaker diarization."
    ),
    stateless_http=True,
    json_response=True,
)
mcp.settings.streamable_http_path = "/"


@mcp.tool()
async def create_transcription_job(
    audio_url: str,
    access_token: str,
    language: str = "zh",
    diarize: bool = True,
    glossary: str = "",
) -> dict:
    """Create a transcription job from a public HTTPS audio URL."""
    if not token_ok(access_token):
        return {"error": "unauthorized"}
    suffix = Path(audio_url.split("?", 1)[0]).suffix.lower() or ".mp3"
    temp_dir = Path(tempfile.mkdtemp(prefix="mcp-audio-url-"))
    source = temp_dir / f"input{suffix}"
    try:
        await download_public_audio(audio_url, source)
    except Exception:
        source.unlink(missing_ok=True)
        try:
            temp_dir.rmdir()
        except OSError:
            pass
        raise
    job = create_job(source.name, language, diarize)
    asyncio.create_task(run_file_job(job["id"], source, language, diarize, glossary))
    return {"job_id": job["id"], "status": job["status"]}


@mcp.tool()
async def get_transcription_job(job_id: str, access_token: str) -> dict:
    """Get job status and, when complete, the timestamped transcript."""
    if not token_ok(access_token):
        return {"error": "unauthorized"}
    job = load_job(job_id)
    return job or {"error": "job not found"}


INDEX_HTML = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>会议录音转写</title><style>
body{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;background:#f5f6f8;margin:0;color:#1f2328}main{max-width:860px;margin:36px auto;padding:0 18px}.card{background:#fff;border:1px solid #ddd;border-radius:14px;padding:24px;margin-bottom:16px}h1{font-size:26px;margin:0 0 8px}.muted{color:#667085}.row{display:grid;grid-template-columns:1fr 1fr;gap:12px}label{display:block;font-weight:600;margin:12px 0 6px}input,textarea,select,button{font:inherit}input[type=password],input[type=file],textarea,select{width:100%;box-sizing:border-box;padding:10px;border:1px solid #bbb;border-radius:8px}button{margin-top:16px;padding:11px 18px;border:0;border-radius:9px;background:#111;color:#fff;cursor:pointer}button:disabled{opacity:.5}.bar{height:10px;background:#eee;border-radius:99px;overflow:hidden;margin:10px 0}.bar>i{display:block;height:100%;width:0;background:#111}pre{white-space:pre-wrap;word-break:break-word;background:#fafafa;border:1px solid #eee;border-radius:10px;padding:14px;max-height:520px;overflow:auto}@media(max-width:650px){.row{grid-template-columns:1fr}}
</style></head><body><main>
<div class="card"><h1>会议录音转写</h1><p class="muted">MP3 / WAV / M4A 等；长录音自动分段。默认中文 + 说话人区分。</p><form id="f">
<label>访问口令</label><input id="token" type="password" placeholder="部署时设置的 APP_TOKEN">
<label>录音文件</label><input id="file" type="file" accept="audio/*,.mp3,.m4a,.wav,.mp4,.ogg,.webm,.flac" required>
<div class="row"><div><label>语言</label><select id="language"><option value="zh">中文</option><option value="en">English</option><option value="ja">日本語</option></select></div><div><label>说话人区分</label><select id="diarize"><option value="true">开启</option><option value="false">关闭</option></select></div></div>
<label>专业词提示（仅关闭说话人区分时用于模型 prompt）</label><textarea id="glossary" rows="3" placeholder="例如：Carol、媛媛、危包、电子底账、EQLQ、A197、KJ3"></textarea>
<button id="go">开始转写</button></form></div>
<div class="card" id="statusCard" hidden><b id="status">排队中</b><div class="bar"><i id="bar"></i></div><span class="muted" id="progress"></span></div>
<div class="card" id="resultCard" hidden><b>转写结果</b><pre id="result"></pre></div>
<script>const $=s=>document.querySelector(s);const sleep=ms=>new Promise(r=>setTimeout(r,ms));$('#f').addEventListener('submit',async e=>{e.preventDefault();$('#go').disabled=true;$('#statusCard').hidden=false;$('#resultCard').hidden=true;const fd=new FormData();fd.append('file',$('#file').files[0]);fd.append('language',$('#language').value);fd.append('diarize',$('#diarize').value);fd.append('glossary',$('#glossary').value);const headers={'Authorization':'Bearer '+$('#token').value};let r=await fetch('/api/jobs',{method:'POST',headers,body:fd});let j=await r.json();if(!r.ok){alert(j.error||JSON.stringify(j));$('#go').disabled=false;return}while(true){await sleep(3000);r=await fetch('/api/jobs/'+j.job_id,{headers});j=await r.json();$('#status').textContent=j.status||'unknown';$('#bar').style.width=(j.progress||0)+'%';$('#progress').textContent=(j.progress||0)+'%';if(j.status==='completed'){$('#resultCard').hidden=false;$('#result').textContent=j.result.text||'';break}if(j.status==='failed'){alert(j.error||'转写失败');break}}$('#go').disabled=false;});</script>
</main></body></html>'''


async def index(request: Request):
    return HTMLResponse(INDEX_HTML)


async def health(request: Request):
    return JSONResponse({"ok": True, "openai_configured": bool(settings.openai_api_key)})


async def create_http_job(request: Request):
    unauthorized = require_http_token(request)
    if unauthorized:
        return unauthorized
    form = await request.form()
    upload = form.get("file")
    if not isinstance(upload, UploadFile):
        return JSONResponse({"error": "file is required"}, status_code=400)
    language = str(form.get("language") or "zh")
    diarize = str(form.get("diarize") or "true").lower() not in {"0", "false", "no"}
    glossary = str(form.get("glossary") or "")
    suffix = Path(upload.filename or "audio.mp3").suffix.lower() or ".mp3"
    temp_dir = Path(tempfile.mkdtemp(prefix="web-audio-"))
    source = temp_dir / f"input{suffix}"
    max_bytes = settings.max_upload_mb * 1024 * 1024
    written = 0
    try:
        with source.open("wb") as fh:
            while True:
                chunk = await upload.read(1024 * 1024)
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise ValueError(f"file exceeds {settings.max_upload_mb} MB")
                fh.write(chunk)
    except Exception as exc:
        source.unlink(missing_ok=True)
        try:
            temp_dir.rmdir()
        except OSError:
            pass
        return JSONResponse({"error": str(exc)}, status_code=400)

    job = create_job(upload.filename or source.name, language, diarize)
    asyncio.create_task(run_file_job(job["id"], source, language, diarize, glossary))
    return JSONResponse({"job_id": job["id"], "status": job["status"]}, status_code=202)


async def get_http_job(request: Request):
    unauthorized = require_http_token(request)
    if unauthorized:
        return unauthorized
    job = load_job(request.path_params["job_id"])
    if not job:
        return JSONResponse({"error": "job not found"}, status_code=404)
    return JSONResponse(job)


@contextlib.asynccontextmanager
async def lifespan(app: Starlette):
    async with mcp.session_manager.run():
        yield


app = Starlette(
    routes=[
        Route("/", index, methods=["GET"]),
        Route("/health", health, methods=["GET"]),
        Route("/api/jobs", create_http_job, methods=["POST"]),
        Route("/api/jobs/{job_id}", get_http_job, methods=["GET"]),
        Mount("/mcp", app=mcp.streamable_http_app()),
    ],
    lifespan=lifespan,
)
