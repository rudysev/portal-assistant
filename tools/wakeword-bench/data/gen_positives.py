"""Generate the positive corpus: many "hey jarvis" utterances × acoustic conditions.

Default engine is macOS `say` across many natural voices × speaking rates (robust, no
downloads, and NOT Piper — so it doesn't hand openWakeWord's Piper-trained model an
in-distribution advantage). Each base utterance is padded (known phrase-end offset for
latency) then expanded into every condition in conditions.expand().

Each row: {path, label=1, category, condition, snr, voice, rate, phrase, phrase_end}.
"""
from __future__ import annotations

import numpy as np

from .audio_io import SAMPLE_RATE, available_say_voices, save_wav, say_to_int16, trim_silence
from .conditions import expand

PAD_LEAD = int(0.20 * SAMPLE_RATE)   # 200 ms lead-in
PAD_TAIL = int(0.70 * SAMPLE_RATE)   # 700 ms trailing room for endpointing/reverb tail
RATES = [None, 140, 160, 180, 200, 220, 240]  # None = voice default; denser rate sweep
# True-positive phrase variants (all contain the wake; not near-misses).
POSITIVE_PHRASES = [
    "hey jarvis",
    "Hey Jarvis",
    "hey  jarvis",
    "um hey jarvis",
    "hey jarvis what time is it",
]


def _pad(speech: np.ndarray):
    lead = np.zeros(PAD_LEAD, dtype=np.int16)
    tail = np.zeros(PAD_TAIL, dtype=np.int16)
    clip = np.concatenate([lead, speech, tail])
    phrase_end = PAD_LEAD + len(speech)
    return clip, phrase_end


def generate(out_dir, phrase="hey jarvis", voices=None, rates=RATES, snrs=None,
             category="positive", label=1, max_base=None, verbose=True, phrases=None):
    from .conditions import DEFAULT_SNRS
    snrs = snrs if snrs is not None else DEFAULT_SNRS
    voices = voices or available_say_voices()
    phrases = phrases if phrases is not None else [phrase]
    rows = []
    base_id = 0
    for phrase in phrases:
        for voice in voices:
            for rate in rates:
                speech = say_to_int16(phrase, voice, rate)
                if speech is None or len(speech) < SAMPLE_RATE // 4:
                    continue
                speech = trim_silence(speech)
                clip, phrase_end = _pad(speech)
                for cond, snr, pcm in expand(clip, snrs=snrs):
                    cond_tag = f"{cond}{snr}" if snr is not None else cond
                    safe = phrase.replace(" ", "-").replace("'", "")[:40]
                    path = out_dir / category / f"{base_id:05d}_{safe}_{voice}_{rate or 'def'}_{cond_tag}.wav"
                    save_wav(path, pcm)
                    rows.append({
                        "path": str(path), "label": label, "category": category,
                        "condition": cond, "snr": snr, "voice": voice, "rate": rate,
                        "phrase": phrase, "phrase_end": phrase_end,
                    })
                base_id += 1
                if max_base and base_id >= max_base:
                    if verbose:
                        print(f"  [{category}] {base_id} base × conditions = {len(rows)} clips")
                    return rows
    if verbose:
        print(f"  [{category}] {base_id} base utterances × conditions = {len(rows)} clips")
    return rows
