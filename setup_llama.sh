#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# setup_llama.sh
# Run this ONCE before opening the project in Android Studio.
# It clones llama.cpp into the NDK source directory.
# ─────────────────────────────────────────────────────────────────────────────

set -e

LLAMA_DIR="app/src/main/cpp/llama.cpp"

if [ -f "$LLAMA_DIR/llama.h" ]; then
  echo "✅ llama.cpp already present at $LLAMA_DIR"
  exit 0
fi

echo "📦 Cloning llama.cpp (shallow clone)…"
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
echo "✅ llama.cpp cloned to $LLAMA_DIR"
echo ""
echo "Now open the project in Android Studio and build!"
