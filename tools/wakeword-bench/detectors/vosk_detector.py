"""Baseline: the shipping Vosk wake path, replicated on macOS.

This mirrors portal-commons WakeRecognizer.kt + WakeMicEngine.kt exactly:
  - grammar-restricted KaldiRecognizer(model, 16000, '["hey jarvis","jarvis","hey","[unk]"]')
  - SetWords(True) for per-word confidence
  - feed 100 ms (3200-byte) frames
  - ~1 s silence warm-up after reset (settles Kaldi's online decoder)
  - on AcceptWaveform()==True, parse the final-result JSON and run the (verified)
    WakeMatcher gates to decide a fire.

Baseline pin: uses the STOCK model.conf (rule2.min-trailing-silence=0.5), matching
origin/feat/foreground-wake-detection. The local 0.3 patch is untested and left out;
pass endpoint_silence=0.3 to A/B it as a separate variant.

Uses the `vosk` PyPI package as-is (KaldiRecognizer) — no library code is modified.
"""
from __future__ import annotations

import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from detectors.base import FRAME_BYTES, SAMPLE_RATE, ClipResult, Detector  # noqa: E402
from wakematcher import NO_CONF, Match, RecWord, WakeWord, evaluate  # noqa: E402

WARMUP_SILENCE_FRAMES = 10  # ~1 s, matches WakeRecognizer.WARMUP_SILENCE_FRAMES
_WS = re.compile(r"\s+")


def parse_result(js: str) -> list[RecWord]:
    """Port of WakeRecognizer.parseResult — prefer per-word conf, else plain transcript."""
    try:
        obj = json.loads(js)
    except Exception:
        return []
    arr = obj.get("result")
    if isinstance(arr, list) and arr:
        out = []
        for o in arr:
            if isinstance(o, dict):
                out.append(RecWord(o.get("word", ""), float(o.get("conf", NO_CONF))))
        return out
    text = (obj.get("text") or "").strip()
    if not text:
        return []
    return [RecWord(w, NO_CONF) for w in _WS.split(text)]


def build_grammar(wake_words: list[WakeWord]) -> str:
    """Port of WakeRecognizer.buildGrammar: phrases + keywords + leads + [unk]."""
    entries: list[str] = []
    seen = set()

    def add(e: str):
        if e not in seen:
            seen.add(e)
            entries.append(e)

    for w in wake_words:
        add(w.phrase.lower())
        add(w.keyword.lower())
        if w.lead:
            add(w.lead.lower())
    add("[unk]")
    return "[" + ", ".join(f'"{e}"' for e in entries) + "]"


class VoskDetector(Detector):
    def __init__(self, model_dir: str, wake_words: list[WakeWord] | None = None,
                 endpoint_silence: float = 0.5, name: str | None = None):
        import vosk

        self.wake_words = wake_words or [
            WakeWord.from_phrase("hey jarvis", min_conf=0.60, id="jarvis")
        ]
        self.endpoint_silence = endpoint_silence
        self.name = name or f"vosk@sil{endpoint_silence}"

        if endpoint_silence != 0.5:
            _patch_endpoint(model_dir, endpoint_silence)

        vosk.SetLogLevel(-1)
        self._model = vosk.Model(model_dir)
        self._grammar = build_grammar(self.wake_words)
        self._new_recognizer()

    def _new_recognizer(self):
        import vosk
        try:
            self._rec = vosk.KaldiRecognizer(self._model, float(SAMPLE_RATE), self._grammar)
        except Exception:
            self._rec = vosk.KaldiRecognizer(self._model, float(SAMPLE_RATE))
        self._rec.SetWords(True)
        self._warm_up()

    def _warm_up(self):
        silence = b"\x00" * FRAME_BYTES
        for _ in range(WARMUP_SILENCE_FRAMES):
            self._rec.AcceptWaveform(silence)

    def reset(self) -> None:
        self._rec.Reset()
        self._warm_up()

    def process(self, pcm: np.ndarray) -> ClipResult:
        raw = pcm.astype("<i2").tobytes()
        best_conf = 0.0
        frame_samples = FRAME_BYTES // 2
        n_frames = len(pcm) // frame_samples
        for k in range(n_frames):
            s = k * frame_samples
            frame = raw[s * 2:(s + frame_samples) * 2]
            if self._rec.AcceptWaveform(frame):
                words = parse_result(self._rec.Result())
                best_conf = max(best_conf, _kw_conf(words, self.wake_words))
                out = evaluate(words, self.wake_words)
                if isinstance(out, Match):
                    return ClipResult(fired=True, score=1.0, offset=(s + frame_samples))
        # flush the tail (FinalResult) so a wake that didn't endpoint mid-clip still decodes
        words = parse_result(self._rec.FinalResult())
        best_conf = max(best_conf, _kw_conf(words, self.wake_words))
        out = evaluate(words, self.wake_words)
        if isinstance(out, Match):
            return ClipResult(fired=True, score=1.0, offset=len(pcm))
        return ClipResult(fired=False, score=best_conf, offset=None)


def _kw_conf(words: list[RecWord], wake_words: list[WakeWord]) -> float:
    kws = {w.keyword.lower() for w in wake_words}
    return max((rw.conf for rw in words if rw.word.lower() in kws and rw.conf > 0), default=0.0)


def _patch_endpoint(model_dir: str, silence: float) -> None:
    """Mirror WakeModelInstaller.tuneEndpoint: patch conf/model.conf rule2 trailing silence."""
    conf = os.path.join(model_dir, "conf", "model.conf")
    if not os.path.exists(conf):
        return
    with open(conf) as f:
        lines = f.readlines()
    key = "--endpoint.rule2.min-trailing-silence"
    patched = False
    for i, ln in enumerate(lines):
        if ln.strip().startswith(key):
            lines[i] = f"{key}={silence}\n"
            patched = True
    if not patched:
        lines.append(f"{key}={silence}\n")
    with open(conf, "w") as f:
        f.writelines(lines)
