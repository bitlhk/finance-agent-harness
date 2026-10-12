import array
from types import SimpleNamespace
import pytest
from finance_solution_builder import speech


def test_missing_configuration_is_not_mock_transcription(monkeypatch):
    for key in speech.KEYS:
        monkeypatch.delenv(key, raising=False)
    assert speech.configuration_status()["status"]=="UNAVAILABLE"
    with pytest.raises(ValueError,match="ASR_UNAVAILABLE"):
        speech.transcribe(b"x","audio/wav",lambda:None)


@pytest.mark.parametrize("audio,mime", [(b"","audio/wav"),(b"x","text/plain"),(b"x"*(4*1024*1024+1),"audio/wav")])
def test_audio_size_and_type_before_decoder(monkeypatch,audio,mime):
    monkeypatch.setattr(speech,"configuration_status",lambda:{"status":"CONFIGURED_UNTESTED"})
    monkeypatch.setattr(speech.subprocess,"run",lambda *a,**k:pytest.fail("decoder reached"))
    with pytest.raises(ValueError,match="AUDIO_INVALID"):
        speech.transcribe(audio,mime,lambda:None)


def test_silence_never_calls_recognizer(monkeypatch):
    monkeypatch.setattr(speech,"configuration_status",lambda:{"status":"CONFIGURED_UNTESTED"})
    monkeypatch.setattr(speech.subprocess,"run",lambda *a,**k:SimpleNamespace(returncode=0,stdout=bytes(32000)))
    monkeypatch.setattr(speech,"recognize_pcm",lambda *a:pytest.fail("ASR reached"))
    with pytest.raises(ValueError,match="AUDIO_SILENT"):
        speech.transcribe(b"input","audio/wav",lambda:None)


def test_revocation_before_asr_blocks_audio_upload(monkeypatch):
    monkeypatch.setattr(speech,"configuration_status",lambda:{"status":"CONFIGURED_UNTESTED"})
    monkeypatch.setattr(speech.subprocess,"run",lambda *a,**k:SimpleNamespace(returncode=0,stdout=array.array("h",[100]*16000).tobytes()))
    calls=[]
    def authorize():
        calls.append(1)
        if len(calls)==2:
            raise PermissionError("AUTHORIZATION_REJECTED")
    monkeypatch.setattr(speech,"recognize_pcm",lambda *a:pytest.fail("ASR reached"))
    with pytest.raises(PermissionError):
        speech.transcribe(b"input","audio/wav",authorize)
