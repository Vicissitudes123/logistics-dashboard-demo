from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    app_token: str = os.getenv("APP_TOKEN", "")
    openai_base_url: str = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
    result_dir: Path = Path(os.getenv("RESULT_DIR", "/tmp/audio-transcription-mcp/jobs"))
    max_upload_mb: int = int(os.getenv("MAX_UPLOAD_MB", "200"))
    chunk_seconds: int = int(os.getenv("CHUNK_SECONDS", "900"))
    max_duration_hours: float = float(os.getenv("MAX_DURATION_HOURS", "4"))
    request_timeout_seconds: float = float(os.getenv("OPENAI_TIMEOUT_SECONDS", "900"))


settings = Settings()
settings.result_dir.mkdir(parents=True, exist_ok=True)
