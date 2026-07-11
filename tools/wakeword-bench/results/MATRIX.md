# Wake recall isolation matrix

Threshold **0.5**. Same clip list across cells that have data.

| Cell | Path | Recall | Near-miss FA | BG FA/hr | n |
|------|------|-------:|-------------:|---------:|--:|
| **A** | Mac file inject (model + Mac ORT) | 85.7% | 15.6% | 0.00 | 97 |
| **B** | Mac acoustic (control) | 71.4% | 8.9% | 0.00 | 97 |
| **C** | Portal file inject (model + Android ORT) | 88.1% | 15.6% | 0.00 | 97 |
| **D** | Portal acoustic (mic + FX + model) | 66.7% | 17.8% | 0.00 | 97 |

## Deltas (how to read)

| Delta | Meaning | Value (recall pp) |
|-------|---------|------------------:|
| **A − C** | Runtime / port fidelity (Mac ORT vs Android ORT) | -2.4 |
| **C − D** | Portal mic + audio FX + room/speaker | 21.4 |
| **A − D** | End-to-end (model ceiling vs product path) | 19.0 |

- **A ≈ C** → model ceiling is trustworthy on-device; don't blame Android ORT.
- **C ≫ D** → fix capture / ducking / placement before training a new head.
- **A ≫ C** → asset mismatch, frame format, or Android path bug — fix before training.
- **A ≈ D** → model is the bottleneck even with a good mic path — train / phrase / architecture.

Cell **B** (Mac acoustic) is a control only — not a Portal product proxy.

## Positive recall by condition

| Cell | clean | noisy | quiet | reverb |
|------|------:|------:|------:|------:|
| A | 100.0% | 82.1% | 100.0% | 75.0% |
| B | 75.0% | 67.9% | 100.0% | 50.0% |
| C | 100.0% | 85.7% | 100.0% | 75.0% |
| D | 100.0% | 60.7% | 100.0% | 25.0% |

