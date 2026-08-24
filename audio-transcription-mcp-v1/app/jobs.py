from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .settings import settings


def _path(job_id: str) -> Path:
    return settings.result_dir / f"{job_id}.json"


def create_job(source_name: str, language: str, diarize: bool) -> dict[str, Any]:
    job = {
        "id": uuid.uuid4().hex,
        "status": "queued",
        "source_name": source_name,
        "language": language,
        "diarize": diarize,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "progress": 0,
    }
    save_job(job)
    return job


def save_job(job: dict[str, Any]) -> None:
    job["updated_at"] = datetime.now(timezone.utc).isoformat()
    tmp = _path(job["id"]).with_suffix(".tmp")
    tmp.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(_path(job["id"]))


def load_job(job_id: str) -> dict[str, Any] | None:
    path = _path(job_id)
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))
