"""Faithful Python port of portal-commons WakeMatcher.kt + WakeWord.kt.

This is a line-for-line port of *our own* pure wake-decision logic (NOT of any
library under test). It exists so the Vosk baseline in this benchmark applies the
exact same accuracy gates the shipping app does. `tests/test_wakematcher.py` ports
WakeMatcherTest.kt and is the proof the port matches the Kotlin.

Source of truth:
  portal-commons/commons/src/main/kotlin/com/portal/commons/audio/WakeMatcher.kt
  portal-commons/commons/src/main/kotlin/com/portal/commons/audio/WakeWord.kt

Do not "improve" the logic here — mirror the Kotlin exactly. If the Kotlin changes,
re-port and re-run the tests.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional, Union

# ---- constants (WakeMatcher.kt) -----------------------------------------------

BASELINE_CONF = 0.50
STRICT_MIN_CONF = 0.60
CLEAN_PHRASE_MAX_WORDS = 3
LEAD_MIN_CONF = 0.80
LEAD_ALIASES = {"hey": {"hey", "hay"}}
UNK_TOKEN = "[unk]"
NO_CONF = -1.0  # sentinel: recognizer gave no per-word confidence (WakeRecognizer.NO_CONF)

_WHITESPACE = re.compile(r"\s+")


# ---- WakeWord.kt --------------------------------------------------------------

@dataclass(frozen=True)
class WakeWord:
    id: str
    keyword: str
    lead: Optional[str]
    min_conf: float

    @property
    def phrase(self) -> str:
        return f"{self.lead} {self.keyword}" if self.lead else self.keyword

    @staticmethod
    def tokenize(phrase: str) -> list[str]:
        return [w for w in _WHITESPACE.split(phrase.strip().lower()) if w]

    @classmethod
    def from_phrase(cls, phrase: str, min_conf: float, id: Optional[str] = None) -> Optional["WakeWord"]:
        words = cls.tokenize(phrase)
        if not words:
            return None
        keyword = words[-1]
        lead = words[-2] if len(words) >= 2 else None
        wid = (id.strip().lower() if id and id.strip() else None) or keyword
        return cls(id=wid, keyword=keyword, lead=lead, min_conf=min_conf)


@dataclass(frozen=True)
class RecWord:
    word: str
    conf: float


# ---- Outcome (sealed interface) -----------------------------------------------

@dataclass(frozen=True)
class Match:
    id: str


@dataclass(frozen=True)
class NearMiss:
    keyword: str
    reason: str


@dataclass(frozen=True)
class NoneOutcome:
    pass


NONE = NoneOutcome()
Outcome = Union[Match, NearMiss, NoneOutcome]


# ---- WakeMatcher --------------------------------------------------------------

def accepted_leads(w: WakeWord) -> Optional[set[str]]:
    if w.lead is None:
        return None
    return LEAD_ALIASES.get(w.lead, {w.lead})


def collapse_repeats(words: list[RecWord]) -> list[RecWord]:
    """Fold runs of the same consecutive word into one (keeping the run's max conf)."""
    if len(words) < 2:
        return words
    out: list[RecWord] = []
    for rw in words:
        last = out[-1] if out else None
        if last is not None and last.word == rw.word:
            if rw.conf > last.conf:
                out[-1] = rw
        else:
            out.append(rw)
    return out


def _conf(c: float) -> str:
    return "?" if c < 0 else f"{int(c * 100)}%"


def _contains_rival_keyword(words: list[RecWord], self_w: WakeWord, all_w: list[WakeWord]) -> bool:
    present = {rw.word for rw in words}
    return any(w.id != self_w.id and w.keyword.lower() in present for w in all_w)


def _rejection_reason(lower: list[RecWord], i: int, w: WakeWord, wake_words: list[WakeWord]) -> Optional[str]:
    before = lower[:i]
    leads = accepted_leads(w)
    if leads is not None and not any(rw.word in leads for rw in before):
        return f"no '{w.lead}' before '{w.keyword}'"

    clean_phrase = len(lower) <= CLEAN_PHRASE_MAX_WORDS
    if w.min_conf > BASELINE_CONF:
        # Strict route.
        if any(rw.word == UNK_TOKEN for rw in lower):
            return f"contaminated ('{UNK_TOKEN}' in decode)"
        if not clean_phrase:
            return f"phrase too long ({len(lower)} words > {CLEAN_PHRASE_MAX_WORDS})"
        if leads is not None and not any(rw.word in leads and rw.conf >= LEAD_MIN_CONF for rw in before):
            return f"'{w.lead}' under {LEAD_MIN_CONF} floor"
        if lower[i].conf < w.min_conf:
            return f"'{w.keyword}' {_conf(lower[i].conf)} under {w.min_conf} floor"
        if _contains_rival_keyword(lower, w, wake_words):
            return "rival wake keyword in decode"
        return None
    # Lenient route.
    if clean_phrase or lower[i].conf >= w.min_conf:
        return None
    return f"'{w.keyword}' {_conf(lower[i].conf)} under {w.min_conf} floor"


def evaluate(words: list[RecWord], wake_words: list[WakeWord]) -> Outcome:
    if not words:
        return NONE
    lower = collapse_repeats([RecWord(rw.word.lower(), rw.conf) for rw in words])
    near_miss: Optional[NearMiss] = None
    for w in wake_words:
        keyword = w.keyword.lower()
        # indexOfLast
        i = -1
        for idx in range(len(lower) - 1, -1, -1):
            if lower[idx].word == keyword:
                i = idx
                break
        if i < 0:
            continue
        reason = _rejection_reason(lower, i, w, wake_words)
        if reason is None:
            return Match(w.id)
        if near_miss is None:
            near_miss = NearMiss(keyword, reason)
    return near_miss if near_miss is not None else NONE


def match(words: list[RecWord], wake_words: list[WakeWord]) -> Optional[str]:
    out = evaluate(words, wake_words)
    return out.id if isinstance(out, Match) else None
