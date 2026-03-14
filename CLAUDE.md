# CLAUDE.md — ARIA Launcher

## Project Overview

ARIA (Adaptive Reasoning Interface for Android) is an AI-native launcher built on Lawnchair/Launcher3. Full build plan and architecture: `docs/ARIA_PLAN.md`

## Repo Structure

- `src/` + `src_plugins/` — AOSP Launcher3 (`main` sourceSet)
- `lawnchair/src/` — Lawnchair + ARIA (`lawn` sourceSet)
- `lawnchair/src/com/aria/launcher/aria/` — **All ARIA code lives here**
  - `data/` — Room database, context signals, skill models
  - `engine/` — Prediction engine
  - `llm/` — LLM provider abstraction (Claude, Ollama, OpenAI-compatible)
  - `agent/` — Agentic tasks
  - `chat/` — Chat UI (Compose)
  - `scheduler/` — WorkManager jobs (usage collection, nightly predictions)
  - `ui/` — Home screen surfaces, composables
- `lawnchair/AndroidManifest.xml` — merged manifest (ARIA permissions + receivers)
- `build.gradle` — root app module (Groovy, not kts)
- `gradle/libs.versions.toml` — version catalog (all deps go here, not inline)
- `schemas/` — Room database migration schemas

## Git Workflow

- `origin` → `trek-boldly-go/aria-launcher` (private, push target)
- `upstream` → `LawnchairLauncher/lawnchair` (fetch-only for syncing)
- Working branch: `aria/main`
- Upstream sync: `git fetch upstream 16-dev && git merge upstream/16-dev`

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleLawnWithQuickstepPlayDebug
```

JDK must be Android Studio's bundled JBR 21. System JDK 24 and Temurin 17 both crash.

## Licensing Rules

This repo has a dual-license structure:

- **Upstream code** (AOSP + Lawnchair) is Apache 2.0 — see `LICENSE.txt`
- **ARIA-original code** is proprietary — see `LICENSE-ARIA.md`

When writing new code:

1. **Keep ARIA code in `lawnchair/src/com/aria/`** as much as possible — composables, view models, state, data classes all belong there.
2. **Minimize changes to Lawnchair files.** When integrating ARIA into upstream files (e.g., `PreferencesDashboard.kt`, `LawnchairLauncher.kt`), keep the touch points thin — a one-line import + function call, not large blocks of logic.
3. **If a Lawnchair file becomes substantially ARIA-authored** (70%+ rewritten), add this header at the top of that file:
   ```
   // Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
   ```
   Do not add this header to files with minor ARIA modifications.
4. **Never remove** the Apache 2.0 `LICENSE.txt` or upstream copyright notices.
