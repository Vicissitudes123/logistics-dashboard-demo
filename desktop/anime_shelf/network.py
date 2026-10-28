"""Conservative, non-blocking desktop download network policy."""
from __future__ import annotations
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

# Route lookup is local; it does not send a packet or disclose account/session data.
_SCRIPT = r'''$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
 $route = @(Find-NetRoute -RemoteIPAddress '1.1.1.1' | Where-Object { $_.DestinationPrefix } | Sort-Object RouteMetric)[0]
 if ($null -eq $route) { throw 'No route' }
 $adapter = @(Get-NetAdapter -IncludeHidden | Where-Object { $_.ifIndex -eq $route.InterfaceIndex })[0]
 $kind = 'unknown'
 if ($adapter.Status -eq 'Up' -and $adapter.HardwareInterface) {
  if ([int]$adapter.InterfaceType -eq 71) { $kind = 'wifi' }
  elseif ([int]$adapter.InterfaceType -eq 6) { $kind = 'ethernet' }
  elseif ([int]$adapter.InterfaceType -in @(243,244)) { $kind = 'cellular' }
 }
 @{kind=$kind} | ConvertTo-Json -Compress
} catch { '{"kind":"unknown"}' }
'''
_LABELS = {'wifi': 'Wi-Fi', 'ethernet': '有线网络', 'cellular': '移动网络', 'unknown': '网络类型未确认（VPN 或虚拟网卡也可能如此）', 'checking': '正在检测网络'}


def classify(value: object) -> dict:
    kind = value.get('kind', 'unknown') if isinstance(value, dict) else 'unknown'
    if kind not in _LABELS: kind = 'unknown'
    return {'allowed': kind in ('wifi', 'ethernet'), 'kind': kind, 'label': _LABELS[kind]}


def _probe() -> dict:
    if sys.platform != 'win32': return classify({})
    executable = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
    try:
        result = subprocess.run([str(executable), '-NoProfile', '-NonInteractive', '-Command', _SCRIPT], capture_output=True,
                                timeout=8, check=True, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if len(result.stdout) > 8192: return classify({})
        return classify(json.loads(result.stdout.decode('utf-8-sig').strip()))
    except (OSError, subprocess.SubprocessError, ValueError, UnicodeError): return classify({})


_lock = threading.Lock()
_state = classify({'kind': 'checking'})
_checked = 0.0
_running = False


def _refresh_worker() -> None:
    global _state, _checked, _running
    try:
        state = _probe()
    except Exception:
        state = classify({})
    with _lock:
        _state, _checked, _running = state, time.monotonic(), False


def refresh_network() -> None:
    global _running
    with _lock:
        if _running: return
        _running = True
    threading.Thread(target=_refresh_worker, name='network-policy', daemon=True).start()


def network_status() -> dict:
    with _lock:
        stale, state = time.monotonic() - _checked > 5, dict(_state)
    if stale: refresh_network()
    return state


def network_allowed() -> bool:
    return bool(network_status()['allowed'])
