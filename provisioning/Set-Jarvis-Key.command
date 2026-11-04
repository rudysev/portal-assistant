#!/bin/bash
# macOS double-click entry point. Double-click to add or change your Gemini API key (and the optional Search key, only used on Gemini 3.8).
cd "$(dirname "$0")" || exit 1
chmod +x install.sh 2>/dev/null
./install.sh --key
echo
read -n 1 -s -r -p "Press any key to close this window…"
echo
