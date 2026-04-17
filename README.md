# OfflineAI — Local LLM Chat App for Android

> A fully offline, ChatGPT-style Android AI assistant powered by llama.cpp.  
> No internet required after first model import. Runs on-device. Private by design.

---

## ✨ Features

| Feature | Details |
|---|---|
| 💬 ChatGPT-like UI | User/assistant bubbles, markdown rendering, streaming |
| 🧠 Memory Manager | Add / edit / delete / pin / mark important memories |
| 📚 Knowledge Base | Structured business and personal notes injected into prompts |
| 📁 Chat History | Multi-conversation with rename, pin, delete, search |
| ⚙️ Settings | Thread count, context size, max tokens, system prompt editor |
| 🔒 Fully Offline | No API calls — all inference runs on-device via llama.cpp |
| 📱 Low-end Support | Auto-detects device RAM and tunes thread/context settings |
| 🌗 Dark/Light Theme | Modern ChatGPT-like dark UI by default |

---

## 🏗️ Architecture

```
UI (Jetpack Compose + Navigation)
    ↓
ViewModels (Hilt)
    ↓
Repositories (ChatRepository, MemoryRepository, KnowledgeRepository, SettingsRepository)
    ↓
PromptBuilder  ←─── Memory + Knowledge + Settings
    ↓
LlamaEngine (Kotlin JNI wrapper)
    ↓
llama_bridge.cpp (C++ JNI)
    ↓
llama.cpp (native GGUF inference)
    ↓
Room Database (SQLite) — Conversations, Messages, Memories, KnowledgeItems, AppSettings
```

---

## 🚀 Quick Start

### Step 1 — Clone the repo

```bash
git clone https://github.com/YOUR_USERNAME/OfflineAI.git
cd OfflineAI
```

### Step 2 — Clone llama.cpp (required before building)

```bash
chmod +x setup_llama.sh
./setup_llama.sh
```

This clones llama.cpp into `app/src/main/cpp/llama.cpp/`.

### Step 3 — Open in Android Studio

Open the project in **Android Studio Hedgehog or later**.  
Let Gradle sync complete. NDK and CMake will be downloaded automatically.

### Step 4 — Build APK

```bash
# Debug APK (recommended for testing)
./gradlew assembleDebug

# APK location after build:
# app/build/outputs/apk/debug/app-debug.apk
```

### Step 5 — Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 Getting a GGUF Model

The app does **not** include a model. You must import one.

### Recommended models (small enough for mid-range phones):

| Model | Size | Quality |
|---|---|---|
| TinyLlama-1.1B-Chat Q4_0 | ~700 MB | Fast, basic |
| Phi-2 Q4_K_M | ~1.6 GB | Better quality |
| Gemma-2B Q4_K_M | ~1.5 GB | Good for chat |
| Mistral-7B Q2_K | ~2.7 GB | High-end phones only |

### Where to download:

1. Go to [huggingface.co](https://huggingface.co)
2. Search: `TinyLlama GGUF` or `TheBloke`
3. Download a `.gguf` file with `Q4_0` or `Q4_K_M` quantization
4. Copy the file to your Android phone (Downloads folder)
5. Open OfflineAI → tap **Import GGUF Model** → select the file

---

## 📤 Push to GitHub

```bash
cd OfflineAI

git init
git add app/src app/build.gradle.kts app/proguard-rules.pro \
        build.gradle.kts settings.gradle.kts gradle/ \
        .github/ setup_llama.sh README.md gradle.properties

git commit -m "feat: initial OfflineAI Android app"

git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/OfflineAI.git
git push -u origin main
```

> **Note:** Do NOT commit `app/src/main/cpp/llama.cpp/` — it's auto-cloned by the CI workflow.  
> Add it to `.gitignore` if needed.

---

## 🤖 GitHub Actions

On every push to `main`, the workflow:

1. Clones llama.cpp
2. Sets up JDK 17 + Android SDK + NDK 26 + CMake 3.22
3. Caches Gradle dependencies
4. Builds `app-debug.apk`
5. Uploads APK + source ZIP as workflow artifacts

Download from: **GitHub → Actions → latest run → Artifacts**

### To add release signing later:

Add these secrets to your GitHub repo:
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`  
- `SIGNING_STORE_PASSWORD`

Then uncomment the release build step in `.github/workflows/android.yml`.

---

## ⚙️ Local Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug

# Run unit tests
./gradlew test

# Check for dependency updates
./gradlew dependencyUpdates
```

**APK output locations:**
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## 🧩 Project Structure

```
OfflineAI/
├── app/
│   ├── src/main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          ← NDK build config
│   │   │   ├── llama_bridge.cpp        ← JNI glue to llama.cpp
│   │   │   └── llama.cpp/              ← cloned by setup_llama.sh
│   │   ├── java/com/om/offlineai/
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── AppDatabase.kt
│   │   │   │   │   ├── entities/       ← Room entities
│   │   │   │   │   └── dao/            ← Room DAOs
│   │   │   │   └── repository/
│   │   │   │       └── Repositories.kt ← Chat/Memory/Knowledge/Settings repos
│   │   │   ├── di/
│   │   │   │   └── AppModule.kt        ← Hilt DI bindings
│   │   │   ├── engine/
│   │   │   │   ├── LlamaEngine.kt      ← Kotlin JNI wrapper + streaming
│   │   │   │   └── PromptBuilder.kt    ← Dynamic prompt assembly
│   │   │   ├── ui/
│   │   │   │   ├── navigation/
│   │   │   │   │   └── Navigation.kt
│   │   │   │   ├── screens/
│   │   │   │   │   ├── ChatScreen.kt
│   │   │   │   │   ├── ConversationListScreen.kt  (+ ModelSetupScreen)
│   │   │   │   │   └── OtherScreens.kt (Memory/Knowledge/Settings/ModelInfo)
│   │   │   │   └── theme/
│   │   │   │       └── Theme.kt
│   │   │   ├── util/
│   │   │   │   └── DeviceCapability.kt ← Auto-tune for device RAM/CPU
│   │   │   ├── viewmodel/
│   │   │   │   ├── ChatViewModel.kt
│   │   │   │   └── ViewModels.kt       ← Model/ConvList/Memory/Knowledge/Settings VMs
│   │   │   ├── MainActivity.kt
│   │   │   └── OfflineAIApp.kt
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   └── themes.xml
│   │   │   ├── drawable/
│   │   │   │   └── ic_splash.xml
│   │   │   └── xml/
│   │   │       └── file_paths.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml              ← Version catalog
├── .github/workflows/
│   └── android.yml                     ← CI/CD pipeline
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── setup_llama.sh                      ← One-time llama.cpp setup
└── README.md
```

---

## 🧠 How Memory & Knowledge Work

### Memory injection flow:
1. User sends a message
2. `MemoryRepository.getHighPriority()` fetches pinned + important memories
3. `KnowledgeRepository.getPinned()` fetches pinned knowledge items
4. `PromptBuilder.build()` assembles: System Prompt → Memories → Knowledge → Chat History → User Message
5. Sliding window trims older history to fit context budget
6. Prompt is sent to `LlamaEngine.infer()`

### Auto-memory suggestions:
When the user writes something like "I prefer short answers" or "mujhe pasand hai...", the app suggests saving it as a memory. One tap saves it permanently.

### Pre-seeded memories (for Om):
On first model load, these are auto-saved:
- Name: Om, student
- Etsy jewellery business
- Prefers Hinglish, practical answers
- Interests: physics, chess, tech

---

## 📱 Low-End Device Optimization

| Device RAM | Context Size | Threads | Max Tokens |
|---|---|---|---|
| < 3 GB | 1024 | 2 | 256 |
| 3–6 GB | 2048 | 3–4 | 512 |
| > 6 GB | 4096 | 4–6 | 1024 |

The app auto-detects your device profile via `DeviceCapability.kt` and sets safe defaults. If the model file is larger than 1.5x available RAM, a warning is shown before import.

---

## ⚠️ Known Limitations

- **First inference is slow** — model loading takes 10–30s on mid-range devices
- **llama.cpp must be cloned separately** — not bundled in the repo (too large)
- **No GPU acceleration** — CPU-only for Android compatibility
- **Context resets per session** — multi-turn context is injected via prompt, not true stateful context

---

## 📄 License

MIT License — free to use and modify.
