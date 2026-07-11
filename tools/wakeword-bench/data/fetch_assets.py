"""Download the model assets the detectors need (idempotent, cached under corpus/assets).

  - Vosk model  vosk-model-en-us-0.22-lgraph  (~1.8 GB unpacked; the app's MODEL_URL) —
    the SAME model the device uses, so the baseline is faithful.
  - sherpa-onnx English KWS zipformer model (~small) + a generated "hey jarvis" keyword file.

openWakeWord downloads its own pretrained `hey_jarvis` model at first use (handled in the
adapter). Porcupine bundles the built-in `jarvis` keyword. Noise/RIR are synthesised at
runtime by augment.py, so nothing else is required to download.

Usage:  python -m data.fetch_assets [--vosk] [--sherpa] [--all]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "corpus" / "assets"

VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
VOSK_DIR = ASSETS / "vosk-model"  # unpacked model root (am/ conf/ graph/ ivector/)

SHERPA_KWS_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/"
    "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
)
SHERPA_DIR = ASSETS / "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
SHERPA_KEYWORDS = ASSETS / "hey_jarvis_keywords.txt"


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        print(f"  cached: {dest.name}")
        return
    print(f"  downloading {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")

    def hook(block, bs, total):
        if total > 0:
            pct = min(100, block * bs * 100 // total)
            sys.stdout.write(f"\r    {pct:3d}%")
            sys.stdout.flush()

    urllib.request.urlretrieve(url, tmp, hook)
    sys.stdout.write("\n")
    tmp.rename(dest)


def fetch_vosk() -> Path:
    print("[vosk] model vosk-model-en-us-0.22-lgraph")
    if (VOSK_DIR / "am").is_dir() and (VOSK_DIR / "conf").is_dir():
        print("  already unpacked")
        return VOSK_DIR
    zip_path = ASSETS / "vosk-lgraph.zip"
    _download(VOSK_URL, zip_path)
    print("  unpacking…")
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(ASSETS)
    # zip top dir is vosk-model-en-us-0.22-lgraph/ — flatten to VOSK_DIR
    top = ASSETS / "vosk-model-en-us-0.22-lgraph"
    if top.exists():
        top.rename(VOSK_DIR)
    zip_path.unlink(missing_ok=True)
    print(f"  -> {VOSK_DIR}")
    return VOSK_DIR


def fetch_sherpa_kws() -> Path:
    print("[sherpa] KWS zipformer gigaspeech")
    if (SHERPA_DIR / "tokens.txt").exists():
        print("  already unpacked")
    else:
        tar_path = ASSETS / "sherpa-kws.tar.bz2"
        _download(SHERPA_KWS_URL, tar_path)
        print("  unpacking…")
        with tarfile.open(tar_path, "r:bz2") as t:
            t.extractall(ASSETS)
        tar_path.unlink(missing_ok=True)
    _make_keywords()
    return SHERPA_DIR


def _make_keywords() -> Path:
    """Generate the 'hey jarvis' keyword line via sherpa-onnx-cli text2token."""
    if SHERPA_KEYWORDS.exists():
        print(f"  keywords: {SHERPA_KEYWORDS.name} (cached)")
        return SHERPA_KEYWORDS
    tokens = SHERPA_DIR / "tokens.txt"
    bpe = SHERPA_DIR / "bpe.model"
    infile = ASSETS / "_kw_in.txt"
    infile.write_text("HEY JARVIS\n")
    cmd = [
        sys.executable, "-m", "sherpa_onnx.cli", "text2token",
        "--tokens", str(tokens), "--tokens-type", "bpe", "--bpe-model", str(bpe),
        str(infile), str(SHERPA_KEYWORDS),
    ]
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        print(f"  keywords: {SHERPA_KEYWORDS.name} -> {SHERPA_KEYWORDS.read_text().strip()}")
    except Exception as e:
        # fall back to the sherpa-onnx-cli console script
        try:
            subprocess.run(["sherpa-onnx-cli", "text2token", "--tokens", str(tokens),
                            "--tokens-type", "bpe", "--bpe-model", str(bpe),
                            str(infile), str(SHERPA_KEYWORDS)], check=True,
                           capture_output=True, text=True)
            print(f"  keywords: {SHERPA_KEYWORDS.read_text().strip()}")
        except Exception:
            print(f"  WARN: text2token failed ({e}); sherpa detector will be skipped")
    finally:
        infile.unlink(missing_ok=True)
    return SHERPA_KEYWORDS


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vosk", action="store_true")
    ap.add_argument("--sherpa", action="store_true")
    ap.add_argument("--all", action="store_true")
    a = ap.parse_args()
    if a.all or a.vosk:
        fetch_vosk()
    if a.all or a.sherpa:
        fetch_sherpa_kws()
    if not (a.all or a.vosk or a.sherpa):
        ap.print_help()


if __name__ == "__main__":
    main()
