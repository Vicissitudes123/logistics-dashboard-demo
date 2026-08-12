from __future__ import annotations

import mimetypes
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Callable

import httpx

from .settings import settings


SUPPORTED_EXTENSIONS = {".mp3", ".wav", ".m4a", ".mp4", ".mpeg", ".mpga", ".ogg", ".webm", ".flac"}


def probe_duration(path: Path) -> float:
    proc = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return float(proc.stdout.strip())


def split_for_transcription(source: Path, out_dir: Path, segment_seconds: int | None = None) -> list[Path]:
    """Normalize audio and split it into small, deterministic chunks."""
    segment_seconds = segment_seconds or settings.chunk_seconds
    out_dir.mkdir(parents=True, exist_ok=True)
    pattern = out_dir / "chunk_%04d.mp3"
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(source),
            "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-b:a", "48k",
            "-f", "segment", "-segment_time", str(segment_seconds),
            "-reset_timestamps", "1", str(pattern),
        ],
        check=True,
    )
    chunks = sorted(out_dir.glob("chunk_*.mp3"))
    if not chunks:
        raise RuntimeError("ffmpeg produced no audio chunks")
    return chunks


def _fmt_time(seconds: float) -> str:
    seconds = max(0, int(round(seconds)))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h:02d}:{m:02d}:{s:02d}"


def normalize_openai_result(payload: dict[str, Any], offset: float, fallback_duration: float) -> list[dict[str, Any]]:
    raw_segments = payload.get("segments") or payload.get("speaker_segments") or payload.get("utterances") or []
    normalized: list[dict[str, Any]] = []

    if isinstance(raw_segments, list):
        for item in raw_segments:
            if not isinstance(item, dict):
                continue
            text = str(item.get("text") or item.get("transcript") or "").strip()
            if not text:
                continue
            start = float(item.get("start") or item.get("start_time") or 0.0) + offset
            end = float(item.get("end") or item.get("end_time") or max(0.0, fallback_duration)) + offset
            speaker = str(item.get("speaker") or item.get("speaker_id") or "Speaker ?")
            normalized.append({"start": start, "end": end, "speaker": speaker, "text": text})

    if not normalized:
        text = str(payload.get("text") or "").strip()
        if text:
            normalized.append({
                "start": offset,
                "end": offset + fallback_duration,
                "speaker": "Speaker ?",
                "text": text,
            })
    return normalized


def render_transcript(segments: list[dict[str, Any]]) -> str:
    lines: list[str] = []
    for seg in segments:
        lines.append(
            f"[{_fmt_time(float(seg['start']))} - {_fmt_time(float(seg['end']))}] "
            f"{seg.get('speaker', 'Speaker ?')}: {str(seg.get('text', '')).strip()}"
        )
    return "\n".join(lines).strip()


async def _transcribe_chunk(
    client: httpx.AsyncClient,
    path: Path,
    *,
    language: str,
    diarize: bool,
    glossary: str = "",
) -> dict[str, Any]:
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is not configured")

    data: dict[str, str] = {"language": language}
    if diarize:
        data.update({
            "model": "gpt-4o-transcribe-diarize",
            "response_format": "diarized_json",
            "chunking_strategy": "auto",
        })
    else:
        data.update({"model": "gpt-4o-transcribe", "response_format": "json"})
        if glossary.strip():
            data["prompt"] = glossary.strip()[:4000]

    mime = mimetypes.guess_type(path.name)[0] or "audio/mpeg"
    with path.open("rb") as fh:
        response = await client.post(
            f"{settings.openai_base_url.rstrip('/')}/audio/transcriptions",
            headers={"Authorization": f"Bearer {settings.openai_api_key}"},
            data=data,
            files={"file": (path.name, fh, mime)},
        )
    if response.is_error:
        body = response.text[:2000]
        raise RuntimeError(f"OpenAI transcription failed ({response.status_code}): {body}")
    return response.json()


async def transcribe_file(
    source: Path,
    *,
    language: str = "zh",
    diarize: bool = True,
    glossary: str = "",
    progress: Callable[[int, int], None] | None = None,
) -> dict[str, Any]:
    ext = source.suffix.lower()
    if ext not in SUPPORTED_EXTENSIONS:
        raise ValueError(f"unsupported audio type: {ext or '(no extension)'}")

    duration = probe_duration(source)
    if duration > settings.max_duration_hours * 3600:
        raise ValueError(f"audio is longer than {settings.max_duration_hours:g} hours")

    work_dir = Path(tempfile.mkdtemp(prefix="audio-transcribe-"))
    try:
        chunks = split_for_transcription(source, work_dir / "chunks")
        all_segments: list[dict[str, Any]] = []
        raw_chunks: list[dict[str, Any]] = []
        offset = 0.0

        timeout = httpx.Timeout(settings.request_timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
            for idx, chunk in enumerate(chunks, start=1):
                chunk_duration = probe_duration(chunk)
                payload = await _transcribe_chunk(
                    client, chunk, language=language, diarize=diarize, glossary=glossary
                )
                normalized = normalize_openai_result(payload, offset, chunk_duration)
                all_segments.extend(normalized)
                raw_chunks.append({"chunk": idx, "offset": offset, "duration": chunk_duration, "response": payload})
                offset += chunk_duration
                if progress:
                    progress(idx, len(chunks))

        return {
            "language": language,
            "diarize": diarize,
            "duration_seconds": duration,
            "chunk_count": len(chunks),
            "segments": all_segments,
            "text": render_transcript(all_segments),
            "raw_chunks": raw_chunks,
        }
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)
