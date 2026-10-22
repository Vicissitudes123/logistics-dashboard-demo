"""Optional live compatibility probe. Never uses an account or prints signed URLs.

Only a successful media response can demonstrate access. API blocks or two failed
media requests are inconclusive, and none of these results substitute for the
user's account, selected episode, and phone network.
"""
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

API_ROOT = "https://api.bilibili.com/"
REFERER = "https://www.bilibili.com/"
MOBILE = ("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")


def report(**fields):
    print(json.dumps(fields, ensure_ascii=False), flush=True)


def get_api(path, params):
    request = Request(API_ROOT + path + "?" + urlencode(params), headers={
        "User-Agent": MOBILE, "Referer": REFERER, "Accept-Encoding": "identity"})
    try:
        with urlopen(request, timeout=8) as response:
            body = response.read(2 * 1024 * 1024 + 1)
            if len(body) > 2 * 1024 * 1024:
                report(stage="api", result="response_too_large")
                return None
            payload = json.loads(body)
            code = payload.get("code")
            if code != 0:
                report(stage="api", result="unavailable", api_code=code if isinstance(code, int) else None)
                return None
            return payload.get("data") or payload.get("result")
    except HTTPError as error:
        report(stage="api", result="unavailable", http_status=error.code)
        error.close()
    except (URLError, TimeoutError, OSError, ValueError):
        report(stage="api", result="network_or_response_error")
    return None


def media_candidate(info):
    if not isinstance(info, dict):
        return None
    info = info.get("video_info") or info
    if info.get("is_drm") or info.get("is_preview"):
        return None
    dash = info.get("dash") or {}
    for track in sorted(dash.get("video") or [], key=lambda value: value.get("id", 0)):
        if track.get("drm_type") or track.get("is_drm") or track.get("license_key"):
            continue
        for field in ("baseUrl", "base_url", "url"):
            value = track.get(field)
            if isinstance(value, str) and urlsplit(value).scheme == "https":
                return value
    return None


def compare(url, sample):
    host = urlsplit(url).hostname
    statuses = {}
    for label, user_agent in (("android_browser", MOBILE), ("desktop_browser", DESKTOP)):
        request = Request(url, headers={"User-Agent": user_agent, "Referer": REFERER,
            "Origin": "https://www.bilibili.com", "Range": "bytes=0-1023", "Accept-Encoding": "identity"})
        status, received, is_mp4 = None, 0, False
        content_type = "unknown"
        try:
            with urlopen(request, timeout=8) as response:
                status = response.status
                payload = response.read(1024)
                received = len(payload)
                kind = response.headers.get_content_type()
                content_type = kind if kind in {"video/mp4", "audio/mp4", "application/octet-stream",
                    "video/iso.segment", "text/html", "application/json"} else "other"
                is_mp4 = len(payload) >= 8 and payload[4:8] in {b"ftyp", b"styp", b"moof", b"sidx"}
        except HTTPError as error:
            status = error.code
            error.close()
        except (URLError, TimeoutError, OSError, ValueError):
            pass
        statuses[label] = (status, received, is_mp4)
        report(stage="media", sample=sample, host=host, user_agent=label,
               http_status=status, bytes_read=received, mp4_header=is_mp4, content_type=content_type)
    desktop_status, desktop_bytes, desktop_mp4 = statuses["desktop_browser"]
    changed = (statuses["android_browser"][0] == 403 and desktop_status in (200, 206)
               and desktop_bytes > 0 and desktop_mp4)
    report(stage="comparison", sample=sample, result="desktop_resolves_403" if changed else "inconclusive")


def main():
    view = get_api("x/web-interface/view", {"bvid": "BV13x41117TL"})
    if view and view.get("cid"):
        stream = get_api("x/player/playurl", {"bvid": "BV13x41117TL", "cid": view["cid"],
            "qn": 127, "fnval": 4048, "fnver": 0, "fourk": 1})
        url = media_candidate(stream)
        if url:
            compare(url, "public_bv")
            return
    stream = get_api("pgc/player/web/playurl", {"ep_id": 678060, "qn": 127,
        "fnval": 4048, "fnver": 0, "fourk": 1})
    url = media_candidate(stream)
    if url:
        compare(url, "public_episode")
    else:
        report(stage="comparison", result="inconclusive_no_public_stream")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # External API changes are not application test results. Never print the
        # exception message because networking exceptions can embed signed URLs.
        report(stage="comparison", result="inconclusive", error_type=type(error).__name__)
