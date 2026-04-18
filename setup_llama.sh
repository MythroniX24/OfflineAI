#!/usr/bin/env bash
set -e
echo "=== OfflineAI Setup ==="

# 1. Clone llama.cpp
LLAMA_DIR="app/src/main/cpp/llama.cpp"
if [ -f "$LLAMA_DIR/llama.h" ]; then
  echo "✅ llama.cpp already present"
else
  echo "📦 Cloning llama.cpp..."
  git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$LLAMA_DIR"
  echo "✅ llama.cpp cloned"
fi

# 2. Download gradle-wrapper.jar (binary, not committed to git)
JAR_PATH="gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR_PATH" ]; then
  echo "✅ gradle-wrapper.jar already present"
else
  echo "📦 Downloading gradle-wrapper.jar..."
  mkdir -p gradle/wrapper
  curl -fsSL \
    "https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
    -o "$JAR_PATH"
  echo "✅ gradle-wrapper.jar downloaded"
fi

# 3. Make gradlew executable
chmod +x gradlew
echo "✅ gradlew executable"

echo ""
echo "Setup complete! Now run:"
echo "  ./gradlew assembleDebug"
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
