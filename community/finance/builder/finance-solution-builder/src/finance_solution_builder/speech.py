"""Server-only XFYUN IAT adapter. Audio stays in memory; no signed URLs or upstream errors are logged."""
import base64
import hashlib
import hmac
import importlib.util
import json
import os
import shutil
import subprocess
import threading
import time
from email.utils import formatdate
from urllib.parse import urlencode

KEYS = ("FH_ASR_APP_ID", "FH_ASR_API_KEY", "FH_ASR_API_SECRET")
MIME = {"audio/webm", "audio/ogg", "audio/mp4", "audio/wav"}


def configuration_status():
    configured = all(os.environ.get(key) for key in KEYS)
    ready = configured and bool(shutil.which("ffmpeg")) and importlib.util.find_spec("websockets") is not None
    return {"provider": "XFYUN_IAT", "status": "CONFIGURED_UNTESTED" if ready else "UNAVAILABLE",
            "maxSeconds": 30, "retention": "NONE", "liveValidation": "NOT_ASSERTED"}


def transcribe(audio, content_type, authorize):
    if configuration_status()["status"] != "CONFIGURED_UNTESTED":
        raise ValueError("ASR_UNAVAILABLE")
    if content_type.split(";")[0] not in MIME or not 1 <= len(audio) <= 4 * 1024 * 1024:
        raise ValueError("AUDIO_INVALID")
    authorize()
    try:
        converted = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", "pipe:0", "-t", "31",
                                    "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"],
                                   input=audio, capture_output=True, timeout=8, check=True)
        pcm = converted.stdout
    except (OSError, subprocess.SubprocessError):
        raise ValueError("AUDIO_DECODE_FAILED") from None
    if len(pcm) > 30 * 32000 or len(pcm) < 3200:
        raise ValueError("AUDIO_DURATION_INVALID")
    # No ASR request for digital silence; silence is not a successful transcription.
    import array
    samples = array.array("h", pcm)
    if max(abs(x) for x in samples) < 32:
        raise ValueError("AUDIO_SILENT")
    authorize()
    return recognize_pcm(pcm, authorize)


def recognize_pcm(pcm, authorize):
    from websockets.sync.client import connect
    host, path = "iat-api.xfyun.cn", "/v2/iat"
    date = formatdate(usegmt=True)
    signature = base64.b64encode(hmac.new(os.environ[KEYS[2]].encode(),
            f"host: {host}\ndate: {date}\nGET {path} HTTP/1.1".encode(), hashlib.sha256).digest()).decode()
    auth = base64.b64encode(f'api_key="{os.environ[KEYS[1]]}", algorithm="hmac-sha256", headers="host date request-line", signature="{signature}"'.encode()).decode()
    url = f"wss://{host}{path}?" + urlencode({"authorization": auth, "date": date, "host": host})
    stop = threading.Event()
    errors = []
    segments = {}
    try:
        with connect(url, open_timeout=8, close_timeout=2, max_size=1024*1024) as ws:
            def send_audio():
                try:
                    for index, offset in enumerate(range(0, len(pcm), 1280)):
                        if stop.is_set():
                            return
                        if index % 25 == 0:
                            authorize()
                        frame = {"data": {"status": 0 if index == 0 else 1, "format": "audio/L16;rate=16000",
                                          "encoding": "raw", "audio": base64.b64encode(pcm[offset:offset+1280]).decode()}}
                        if index == 0:
                            frame.update(common={"app_id": os.environ[KEYS[0]]},
                                         business={"language": "zh_cn", "domain": "iat", "accent": "mandarin", "dwa": "wpgs"})
                        ws.send(json.dumps(frame))
                        stop.wait(.04)
                    ws.send(json.dumps({"data": {"status": 2, "format": "audio/L16;rate=16000", "encoding": "raw", "audio": ""}}))
                except Exception:
                    errors.append(True)
                    stop.set()
                    ws.close()
            sender = threading.Thread(target=send_audio, daemon=True)
            sender.start()
            try:
                deadline = time.monotonic() + 42
                while True:
                    message = json.loads(ws.recv(timeout=max(.01, deadline-time.monotonic())))
                    if message.get("code") != 0:
                        raise ValueError("ASR_SERVICE_REJECTED")
                    result = message.get("data", {}).get("result", {})
                    if result:
                        if result.get("pgs") == "rpl":
                            for index in range(result["rg"][0], result["rg"][1]+1):
                                segments.pop(index, None)
                        segments[result["sn"]] = "".join(item.get("cw", [{}])[0].get("w", "") for item in result.get("ws", []))
                    if message.get("data", {}).get("status") == 2:
                        break
                if errors:
                    raise ValueError("ASR_SERVICE_FAILED")
                authorize()
                text = "".join(segments[key] for key in sorted(segments)).strip()
                if not text:
                    raise ValueError("ASR_NO_SPEECH")
                return {"text": text[:4000], "provider": "XFYUN_IAT", "status": "TRANSCRIBED", "requiresUserSend": True}
            finally:
                stop.set()
                sender.join(timeout=3)
    except Exception as error:
        allowed = {"ASR_SERVICE_REJECTED", "ASR_NO_SPEECH", "ASR_SERVICE_FAILED", "AUTHORIZATION_REJECTED", "INSTANCE_CHANGED", "SESSION_UNAVAILABLE"}
        raise ValueError(str(error) if str(error) in allowed else "ASR_SERVICE_FAILED") from None
