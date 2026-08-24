from app.transcriber import normalize_openai_result, render_transcript


def test_merge_diarized_segments_with_offset():
    payload = {
        "segments": [
            {"start": 1.2, "end": 3.5, "speaker": "speaker_0", "text": "你好"},
            {"start": 4.0, "end": 6.0, "speaker": "speaker_1", "text": "开始吧"},
        ]
    }
    segs = normalize_openai_result(payload, 900.0, 15.0)
    assert segs[0]["start"] == 901.2
    assert segs[1]["speaker"] == "speaker_1"
    text = render_transcript(segs)
    assert "[00:15:01 - 00:15:04]" in text
    assert "speaker_1: 开始吧" in text


def test_plain_response_fallback():
    segs = normalize_openai_result({"text": "测试"}, 10.0, 5.0)
    assert segs == [{"start": 10.0, "end": 15.0, "speaker": "Speaker ?", "text": "测试"}]
