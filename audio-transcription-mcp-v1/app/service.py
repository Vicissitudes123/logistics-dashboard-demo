from __future__ import annotations

from pathlib import Path

import httpx

from .jobs import load_job, save_job
from .security import validate_public_https_url
from .settings import settings
from .transcriber import transcribe_file


async def run_file_job(job_id: str, source: Path, language: str, diarize: bool, glossary: str) -> None:
    job = load_job(job_id)
    if not job:
        return
    try:
        job["status"] = "running"
        save_job(job)

        def on_progress(done: int, total: int) -> None:
            current = load_job(job_id) or job
            current["progress"] = round(done / total * 100)
            current["chunks_done"] = done
            current["chunks_total"] = total
            save_job(current)

        result = await transcribe_file(
            source, language=language, diarize=diarize, glossary=glossary, progress=on_progress
        )
        job = load_job(job_id) or job
        job.update({"status": "completed", "progress": 100, "result": result})
        save_job(job)
    except Exception as exc:
        job = load_job(job_id) or job
        job.update({"status": "failed", "error": str(exc)})
        save_job(job)
    finally:
        try:
            source.unlink(missing_ok=True)
            source.parent.rmdir()
        except OSError:
            pass


async def download_public_audio(url: str, dest: Path) -> None:
    validate_public_https_url(url)
    max_bytes = settings.max_upload_mb * 1024 * 1024
    timeout = httpx.Timeout(60, read=600)
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        async with client.stream("GET", url) as response:
            if 300 <= response.status_code < 400:
                raise ValueError("redirecting audio URLs are not accepted in v1; use the final HTTPS URL")
            response.raise_for_status()
            length = response.headers.get("content-length")
            if length and int(length) > max_bytes:
                raise ValueError("remote audio exceeds MAX_UPLOAD_MB")
            written = 0
            with dest.open("wb") as fh:
                async for chunk in response.aiter_bytes():
                    written += len(chunk)
                    if written > max_bytes:
                        raise ValueError("remote audio exceeds MAX_UPLOAD_MB")
                    fh.write(chunk)
