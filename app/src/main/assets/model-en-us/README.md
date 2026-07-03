# Vosk wake-word model goes here (gitignored)

This folder must contain an English Vosk model at build time. It is not a secret, and **not
committed** (see the repo `.gitignore`). Download one once.

We use `vosk-model-en-us-0.22-lgraph` (~128 MB zipped — the accurate model needed for room-distance
wake detection):

```bash
curl -L -o /tmp/m.zip https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip
unzip -q /tmp/m.zip -d /tmp/m
cp -R /tmp/m/vosk-model-en-us-0.22-lgraph/* app/src/main/assets/model-en-us/
printf 'vosk-model-en-us-0.22-lgraph' > app/src/main/assets/model-en-us/uuid   # required by StorageService.unpack
```

After copying, this folder should contain `am/`, `conf/`, `graph/`, `ivector/`, and a `uuid` file.
If the model is absent the app still installs and runs — it just logs "wake unavailable" and does
nothing (no crash).
