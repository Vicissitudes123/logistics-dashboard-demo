from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

from starlette.requests import Request
from starlette.responses import JSONResponse

from .settings import settings


def supplied_token(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return request.headers.get("x-app-token", "").strip()


def token_ok(token: str) -> bool:
    return not settings.app_token or token == settings.app_token


def require_http_token(request: Request):
    if token_ok(supplied_token(request)):
        return None
    return JSONResponse({"error": "unauthorized"}, status_code=401)


def validate_public_https_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("audio_url must be an https URL")
    host = parsed.hostname.lower()
    if host in {"localhost", "localhost.localdomain"}:
        raise ValueError("localhost URLs are not allowed")
    try:
        direct_ip = ipaddress.ip_address(host)
        if not direct_ip.is_global:
            raise ValueError("private or non-public IP URLs are not allowed")
        return
    except ValueError as exc:
        if "not allowed" in str(exc):
            raise
    try:
        infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ValueError("audio_url host could not be resolved") from exc
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global:
            raise ValueError("audio_url resolves to a private or non-public IP")
