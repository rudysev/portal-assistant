"""Generate the negative corpus: near-misses, background speech, and silence/noise.

  near_miss   — confusable phrases the WakeMatcher spec cares about (hey jeremy, a jarvis,
                hi jarvis, hey there, bare jarvis, rival "hey alexa", …) × conditions.
                Probes precision directly (any fire here is a false accept).
  background  — long continuous non-wake speech (varied `say` sentences) sliced into clips;
                total duration → false-accepts-per-hour, the headline precision metric.
  silence     — pure silence and noise-only clips (no speech at all).

Each row: {path, label=0, category, condition, snr, voice, phrase, phrase_end, dur_s}.
"""
from __future__ import annotations

import numpy as np

from . import augment
from .audio_io import SAMPLE_RATE, available_say_voices, save_wav, say_to_int16, trim_silence
from .conditions import expand
from .gen_positives import _pad

# Confusable phrases that do NOT contain the exact wake "hey jarvis" (those wouldn't be
# fair negatives — a detector firing on them isn't wrong). Wrong-lead ("okay jarvis",
# "a jarvis"), wrong-keyword soundalikes ("hey jeremy", "hey harris"), bare keyword,
# rival wake, and no-wake filler.
NEAR_MISS_PHRASES = [
    # Soundalike keyword (grammar bias / embedding near-misses)
    "hey jeremy", "hey travis", "hey harris", "hey james", "hey jervis",
    "hey jasmine", "hey charles", "hey service", "hey darkness", "hey charmless",
    "they marvis", "hey marvis", "a jarvis", "hey jarvus", "hey jarvahs",
    "hey Jerry", "hey Chris",
    # Wrong lead / bare keyword / rival wake / repeated keyword
    "hi jarvis", "okay jarvis", "yo jarvis", "jarvis", "jarvis jarvis",
    "hey alexa", "hey there", "hey google", "hey siri", "okay google",
]

# Non-wake sentences for the background-speech stream (varied, none contain the wake).
BACKGROUND_SENTENCES = [
    "the weather today is mild with a gentle breeze from the west",
    "please remember to pick up milk and bread on the way home",
    "the quarterly report shows a steady increase in overall revenue",
    "she walked along the river as the sun began to set behind the hills",
    "our meeting has been rescheduled to three o'clock this afternoon",
    "the recipe calls for two cups of flour and a pinch of salt",
    "traffic on the highway is heavier than usual this morning",
    "the museum exhibit features paintings from the early twentieth century",
    "he practiced the piano for an hour before dinner every evening",
    "the software update improves battery life and fixes several bugs",
    "a flock of birds settled on the wire outside the kitchen window",
    "we should book the tickets before the prices go up next week",
    "the children built an enormous sandcastle near the water's edge",
    "turn left at the second light and the office is on your right",
    "the documentary explored the deep ocean and its strange creatures",
    "my favorite season is autumn when the leaves turn red and gold",
]


def gen_near_misses(out_dir, voices=None, rates=(None, 200), snrs=None, max_base=None, verbose=True):
    from .conditions import DEFAULT_SNRS
    snrs = snrs if snrs is not None else DEFAULT_SNRS
    voices = voices or available_say_voices()
    rows, base_id = [], 0
    for phrase in NEAR_MISS_PHRASES:
        for voice in voices:
            for rate in rates:
                speech = say_to_int16(phrase, voice, rate)
                if speech is None or len(speech) < SAMPLE_RATE // 4:
                    continue
                clip, phrase_end = _pad(trim_silence(speech))
                for cond, snr, pcm in expand(clip, snrs=snrs):
                    cond_tag = f"{cond}{snr}" if snr is not None else cond
                    safe = phrase.replace(" ", "-").replace("'", "")
                    path = out_dir / "near_miss" / f"{base_id:05d}_{safe}_{voice}_{cond_tag}.wav"
                    save_wav(path, pcm)
                    rows.append({
                        "path": str(path), "label": 0, "category": "near_miss",
                        "condition": cond, "snr": snr, "voice": voice, "rate": rate,
                        "phrase": phrase, "phrase_end": phrase_end,
                        "dur_s": len(pcm) / SAMPLE_RATE,
                    })
                base_id += 1
                if max_base and base_id >= max_base:
                    return rows
    if verbose:
        print(f"  [near_miss] {base_id} base × conditions = {len(rows)} clips")
    return rows


def gen_background(out_dir, voices=None, clip_s=6.0, sentences=BACKGROUND_SENTENCES,
                   max_voices=None, verbose=True):
    """Long non-wake speech sliced into fixed clips → FA/hour denominator.

    Every voice reads every sentence, so background duration scales with the voice set
    (a real FA/hour needs many minutes of speech). Cap with max_voices for a quick run.
    """
    voices = voices or available_say_voices()
    if max_voices:
        voices = voices[:max_voices]
    stream = []
    for voice in voices:
        for sent in sentences:
            speech = say_to_int16(sent, voice, None)
            if speech is None:
                continue
            stream.append(speech)
            stream.append(np.zeros(int(0.3 * SAMPLE_RATE), dtype=np.int16))
    if not stream:
        return []
    full = np.concatenate(stream)
    clip_n = int(clip_s * SAMPLE_RATE)
    rows = []
    for k in range(len(full) // clip_n):
        pcm = full[k * clip_n:(k + 1) * clip_n]
        path = out_dir / "background" / f"bg_{k:05d}.wav"
        save_wav(path, pcm)
        rows.append({
            "path": str(path), "label": 0, "category": "background",
            "condition": "speech", "snr": None, "voice": None, "rate": None,
            "phrase": None, "phrase_end": None, "dur_s": len(pcm) / SAMPLE_RATE,
        })
    if verbose:
        total_min = sum(r["dur_s"] for r in rows) / 60
        print(f"  [background] {len(rows)} clips, {total_min:.1f} min of non-wake speech")
    return rows


def gen_silence(out_dir, n=20, clip_s=6.0, verbose=True):
    rows = []
    clip_n = int(clip_s * SAMPLE_RATE)
    for k in range(n):
        if k % 2 == 0:
            pcm = np.zeros(clip_n, dtype=np.int16)
            cond = "silence"
        else:
            # pure pink noise at a modest amplitude (no speech)
            noise = augment.pink_noise(clip_n)
            pcm = augment.to_int16(0.08 * noise / (np.max(np.abs(noise)) + 1e-9))
            cond = "noise"
        path = out_dir / "silence" / f"sil_{k:05d}_{cond}.wav"
        save_wav(path, pcm)
        rows.append({
            "path": str(path), "label": 0, "category": "silence",
            "condition": cond, "snr": None, "voice": None, "rate": None,
            "phrase": None, "phrase_end": None, "dur_s": clip_s,
        })
    if verbose:
        print(f"  [silence] {len(rows)} silence/noise clips")
    return rows
