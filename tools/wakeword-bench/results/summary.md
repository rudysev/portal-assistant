# Wake-word detector benchmark — summary

NOTE: positives are TTS (macOS `say`). openWakeWord's `hey_jarvis` was trained on Piper TTS; `say` is a different distribution, so no detector is strictly in-distribution here, but neural-TTS test audio still differs from real room speech. Treat as relative, not absolute. Porcupine detects the built-in keyword `jarvis` (not the phrase 'hey jarvis').

detector               recall   nearFA    FA/hr   latP50   latP90     RTF
------------------------------------------------------------------------
openwakeword            87.3%    29.1%     0.00     23ms    180ms   0.018
vosk                    69.8%    39.6%     0.00    700ms    700ms   0.030

## Detection rate by condition

detector                   clean   noisy@0dB  noisy@10dB  noisy@20dB   noisy@5dB       quiet      reverb
--------------------------------------------------------------------------------------------------------
openwakeword               98.3%       62.3%       93.5%       96.3%       82.0%       98.4%       80.1%
vosk                       66.1%       51.7%       77.6%       73.9%       70.4%       68.7%       80.1%

## Recall at common FA/hour targets (continuous-score detectors)

detector                 ≤2.0/hr     ≤1.0/hr     ≤0.5/hr     ≤0.1/hr
openwakeword               90.5%       90.5%       90.5%       90.5%

## Notes
- openwakeword: 4403 positives, 6804 near-miss clips, 24.1 min background (0 FA).
- vosk: 4403 positives, 6804 near-miss clips, 24.1 min background (0 FA).
