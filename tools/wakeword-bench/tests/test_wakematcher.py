"""Port of WakeMatcherTest.kt — proves wakematcher.py matches the Kotlin spec.

Every accepted/rejected case here mirrors a @Test in
portal-commons/commons/src/test/kotlin/com/portal/commons/audio/WakeMatcherTest.kt.
If a case fails, the Vosk baseline in this benchmark is NOT faithful — fix the port.

Run:  cd tools/wakeword-bench && python -m pytest tests/ -q
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wakematcher import (  # noqa: E402
    BASELINE_CONF,
    Match,
    NearMiss,
    NoneOutcome,
    RecWord,
    WakeWord,
    collapse_repeats,
    evaluate,
    match,
)

# vega is lenient (floor at the baseline); jarvis stands in for a strict word.
vega = WakeWord("vega", keyword="vega", lead="hey", min_conf=BASELINE_CONF)
jarvis = WakeWord("jarvis", keyword="jarvis", lead="hey", min_conf=0.60)
words = [vega, jarvis]


def rec(*pairs):
    return [RecWord(w, c) for (w, c) in pairs]


# ---- recall -------------------------------------------------------------------

def test_clean_phrase_high_confidence_matches():
    assert match(rec(("hey", 0.99), ("vega", 0.98)), words) == "vega"


def test_clean_phrase_low_confidence_still_matches_for_lenient():
    assert match(rec(("hey", 0.20), ("vega", 0.10)), words) == "vega"


def test_hey_variants_are_accepted():
    for hey in ("hey", "hay"):
        assert match(rec((hey, 0.5), ("vega", 0.1)), words) == "vega"


def test_loose_hey_words_are_rejected():
    for not_hey in ("a", "hi", "he"):
        assert match(rec((not_hey, 0.9), ("vega", 0.1)), words) is None


def test_keyword_mentioned_earlier_then_real_wake_matches():
    transcript = rec(
        ("did", 0.9), ("you", 0.9), ("ask", 0.9), ("vega", 0.9),
        ("oh", 0.9), ("wait", 0.9), ("hey", 0.9), ("vega", 0.95),
    )
    assert match(transcript, words) == "vega"


def test_case_insensitive():
    assert match(rec(("HEY", 0.9), ("Vega", 0.9)), words) == "vega"


def test_embedded_keyword_high_confidence_matches():
    r = rec(("hey", 0.9), ("could", 0.8), ("you", 0.8), ("ask", 0.8), ("vega", 0.95))
    assert match(r, words) == "vega"


# ---- precision ----------------------------------------------------------------

def test_keyword_without_hey_does_not_match():
    assert match(rec(("vega", 0.99)), words) is None
    assert match(rec(("tell", 0.9), ("vega", 0.99)), words) is None


def test_look_alike_word_does_not_match():
    assert match(rec(("hey", 0.9), ("jeremy", 0.9)), words) is None


def test_embedded_keyword_low_confidence_does_not_match():
    r = rec(("hey", 0.9), ("could", 0.8), ("you", 0.8), ("ask", 0.8), ("vega", 0.15))
    assert match(r, words) is None


def test_empty_input_does_not_match():
    assert match([], words) is None


# ---- strict route -------------------------------------------------------------

def test_strict_clean_phrase_but_low_confidence_does_not_match():
    assert match(rec(("hey", 0.9), ("jarvis", 0.40)), words) is None


def test_strict_above_floor_matches():
    assert match(rec(("hey", 0.9), ("jarvis", 0.75)), words) == "jarvis"


def test_strict_long_background_decode_does_not_match():
    r = rec(("foo", 1.0), ("bar", 0.98), ("baz", 0.99), ("qux", 0.9), ("hey", 0.84), ("jarvis", 0.83))
    assert match(r, words) is None


def test_strict_cross_contaminated_decode_does_not_match():
    both = [jarvis, WakeWord("alexa", keyword="alexa", lead="hey", min_conf=0.60)]
    assert match(rec(("alexa", 0.79), ("hey", 0.84), ("jarvis", 0.83)), both) is None


def test_strict_clean_three_word_phrase_still_matches():
    assert match(rec(("um", 0.5), ("hey", 0.9), ("jarvis", 0.83)), words) == "jarvis"


def test_strict_embedded_high_confidence_does_not_match():
    r = rec(("hey", 0.9), ("could", 0.8), ("you", 0.8), ("ask", 0.8), ("jarvis", 0.95))
    assert match(r, words) is None


def test_strict_exact_production_false_fire_does_not_match():
    prod = [jarvis, WakeWord("alexa", keyword="alexa", lead="hey", min_conf=0.60)]
    r = rec(("[unk]", 1.0), ("alexa", 0.79), ("[unk]", 0.98), ("[unk]", 0.99), ("hey", 0.84), ("jarvis", 0.83))
    assert match(r, prod) is None


def test_strict_weak_hey_does_not_match():
    assert match(rec(("hey", 0.66), ("jarvis", 0.95)), words) is None


def test_strict_confident_hey_matches():
    assert match(rec(("hey", 0.96), ("jarvis", 0.95)), words) == "jarvis"


def test_strict_contaminated_decode_does_not_match():
    assert match(rec(("[unk]", 0.51), ("hey", 0.99), ("jarvis", 0.99)), words) is None


def test_lenient_weak_hey_still_matches():
    assert match(rec(("hey", 0.66), ("vega", 0.95)), words) == "vega"


def test_no_per_word_confidence_lenient_fires_strict_does_not():
    assert match(rec(("hey", -1.0), ("vega", -1.0)), words) == "vega"
    assert match(rec(("hey", -1.0), ("jarvis", -1.0)), words) is None


# ---- multi-wake routing -------------------------------------------------------

def test_routes_to_the_spoken_word():
    assert match(rec(("hey", 0.9), ("jarvis", 0.9)), words) == "jarvis"
    assert match(rec(("hey", 0.9), ("vega", 0.9)), words) == "vega"


def test_extensible_custom_wake_word():
    computer = WakeWord("computer", keyword="computer", lead="hey", min_conf=0.55)
    assert match(rec(("hey", 0.9), ("computer", 0.9)), [computer]) == "computer"


def test_plugin_declared_lead_hi_bob():
    bob = WakeWord("bob", keyword="bob", lead="hi", min_conf=0.55)
    assert match(rec(("hi", 0.9), ("bob", 0.9)), [bob]) == "bob"
    assert match(rec(("hey", 0.9), ("bob", 0.9)), [bob]) is None


def test_no_lead_word_fires_on_bare_keyword():
    computer = WakeWord("computer", keyword="computer", lead=None, min_conf=0.55)
    assert match(rec(("computer", 0.9)), [computer]) == "computer"


# ---- evaluate(): near-miss diagnostics ----------------------------------------

def test_evaluate_match_mirrors_match():
    assert evaluate(rec(("hey", 0.9), ("jarvis", 0.9)), words) == Match("jarvis")


def test_evaluate_no_keyword_is_none():
    assert isinstance(evaluate(rec(("hello", 0.9), ("there", 0.9)), words), NoneOutcome)
    assert isinstance(evaluate([], words), NoneOutcome)


def test_evaluate_keyword_without_hey_is_near_miss():
    out = evaluate(rec(("tell", 0.9), ("jarvis", 0.99)), words)
    assert isinstance(out, NearMiss) and out.keyword == "jarvis" and "no 'hey'" in out.reason


def test_evaluate_weak_hey_is_near_miss():
    out = evaluate(rec(("hey", 0.66), ("jarvis", 0.95)), words)
    assert isinstance(out, NearMiss) and "'hey' under" in out.reason


def test_evaluate_low_keyword_conf_is_near_miss():
    out = evaluate(rec(("hey", 0.9), ("jarvis", 0.40)), words)
    assert isinstance(out, NearMiss) and "'jarvis'" in out.reason and "floor" in out.reason


def test_evaluate_long_phrase_is_near_miss():
    out = evaluate(rec(("um", 0.5), ("hey", 0.96), ("jarvis", 0.95), ("weather", 0.9)), words)
    assert isinstance(out, NearMiss) and "phrase too long" in out.reason


def test_evaluate_contaminated_is_near_miss():
    out = evaluate(rec(("[unk]", 0.51), ("hey", 0.99), ("jarvis", 0.99)), words)
    assert isinstance(out, NearMiss) and "contaminated" in out.reason


# ---- collapseRepeats ----------------------------------------------------------

def test_collapse_repeats_folds_consecutive_duplicates_keeping_max_conf():
    out = collapse_repeats(rec(("hey", 0.72), ("hey", 0.98), ("hey", 1.0), ("jarvis", 1.0)))
    assert out == [RecWord("hey", 1.0), RecWord("jarvis", 1.0)]


def test_collapse_repeats_keeps_distinct_and_non_adjacent():
    out = collapse_repeats(rec(("hey", 0.9), ("jarvis", 0.9), ("hey", 0.8), ("jarvis", 0.8)))
    assert len(out) == 4


def test_doubled_hey_matches():
    assert match(rec(("hey", 0.72), ("hey", 0.98), ("hey", 1.0), ("jarvis", 1.0)), words) == "jarvis"


def test_doubled_keyword_matches():
    assert match(rec(("hey", 0.99), ("jarvis", 0.9), ("jarvis", 1.0), ("jarvis", 0.95)), words) == "jarvis"


def test_repeated_lead_does_not_defeat_contamination_gate():
    out = evaluate(rec(("[unk]", 0.5), ("hey", 0.99), ("hey", 1.0), ("jarvis", 0.99)), words)
    assert isinstance(out, NearMiss) and "contaminated" in out.reason


def test_distinct_filler_words_still_too_long_after_collapse():
    out = evaluate(rec(("hey", 0.96), ("could", 0.8), ("you", 0.8), ("jarvis", 0.95)), words)
    assert isinstance(out, NearMiss) and "phrase too long" in out.reason


def test_doubled_unk_folds_to_one_contamination_still_fires():
    out = evaluate(rec(("[unk]", 0.9), ("[unk]", 0.5), ("hey", 0.99), ("jarvis", 0.99)), words)
    assert isinstance(out, NearMiss) and "contaminated" in out.reason


def test_doubled_hey_matches_lenient_route():
    assert match(rec(("hey", 0.9), ("hey", 0.95), ("hey", 0.9), ("vega", 0.1)), words) == "vega"
