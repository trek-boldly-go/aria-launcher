# CLAUDE.md — ARIA Launcher

Read this file before working on this codebase. It contains critical build, architecture, and workflow information.

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
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleLawnWithQuickstepPlayDebug 2>&1 | tail -20
```

JDK must be Android Studio's bundled JBR 21. System JDK 24 and Temurin 17 both crash.

## Build Variants

The build uses a 3-dimension flavor matrix:

| Dimension | Values | Purpose |
|-----------|--------|---------|
| `app` | `lawn` | Lawnchair + ARIA (only option) |
| `recents` | `withQuickstep` | Quickswitch recents integration |
| `channel` | `github`, `nightly`, `play` | Distribution channel |

The development variant is **`lawnWithQuickstepPlayDebug`** — use this for all local builds and tests. The `play` channel matches the installed package (`com.aria.launcher.play.debug`).

## Test Commands

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testLawnWithQuickstepPlayDebugUnitTest
```

- Tests live in `tests/unit/` (not `src/test/`)
- Framework: JUnit 4 + Mockito + Truth + coroutines-test
- `testOptions.unitTests.returnDefaultValues = true` is set in build.gradle

## Lint / Format Commands

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew spotlessCheck    # check formatting
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew spotlessApply    # auto-fix formatting
```

- Spotless targets `lawnchair/src/**/*.kt` only (not AOSP/Launcher3 code in `src/`)
- Also targets `compatLib/**/src/**/*.java` with google-java-format
- Uses ktlint 1.8.0 + compose rules 0.5.6
- CI runs `spotlessCheck` on every PR

## Architecture

### Source Sets

- **`main`** (`src/`, `src_plugins/`) — AOSP Launcher3 upstream code. Do not modify unless absolutely necessary.
- **`lawn`** (`lawnchair/src/`) — Lawnchair customizations + all ARIA code. This is where development happens.
- **`withQuickstep`** — Quickswitch recents integration.

### Dependency Injection

Hilt/Dagger with 4 modules installed in `SingletonComponent`:

| Module | Package | Provides |
|--------|---------|----------|
| `AriaDataModule` | `data/` | Room database, DAOs, repositories |
| `LlmModule` | `llm/` | OkHttpClient (`@AriaLlmClient`), Json, LlmProviderManager |
| `ChatModule` | `chat/` | Chat-related dependencies |
| `SkillModule` | `engine/` | Skill execution dependencies |

### App Startup Flow

`LawnchairApp` (`@HiltAndroidApp`) → injects `ContextSignalManager` → schedules WorkManager jobs (usage collection every 4h, nightly predictions at ~3AM).

### ARIA ↔ Lawnchair Boundary

ARIA is isolated in `lawnchair/src/com/aria/launcher/aria/`. Integration points with Lawnchair are intentionally thin:
- Manifest entries (receivers, services, activities)
- Minimal init in `LawnchairApp.onCreate`
- One-line imports + function calls in Lawnchair UI files (e.g., `PreferencesDashboard.kt`)

Do **not** scatter ARIA logic across Lawnchair files. If you need to touch a Lawnchair file, keep it to a thin call into ARIA code.

## Dependency Management

- **All dependencies go in `gradle/libs.versions.toml`** — never use inline version strings in `build.gradle`.
- Reference deps in build.gradle as `libs.some.dependency`, not raw coordinates.
- New licenses must be added to the `licensee` block in `build.gradle`. Currently allowed: Apache-2.0, BSD-3-Clause, GPL-2.0+, MIT, plus specific URL exceptions for libsu, opto, HiddenApiRefinePlugin, and Google/Android terms.

## Documentation Rules

- **`docs/ARIA_PLAN.md`** is the single source of truth for ARIA architecture, including reference implementations, data models, code examples, and design specs.
- **Never remove** code examples, data model definitions, reference implementations, or design specs from docs unless moving them to another tracked location. Summarizing reference implementations is data loss.
- **`docs/archive/`** contains the original source documents (intelligence layer, MCP layer, UI design). `ARIA_PLAN.md` must be a superset of their content.
- When consolidating or editing docs, preserve all detail. If in doubt, keep it.

## Working on ARIA

Development follows the session plan in `docs/ARIA_PLAN.md`. Each session (7-14) has specific deliverables, data models, and reference implementations.

### One session at a time
- **Only work on the current session/phase.** Do not start the next session unless the user explicitly says to.
- If a session's scope is large, break it into sub-tasks within that session — do not pull work forward from future sessions.
- This prevents context dilution. Each session has enough detail that rushing through it guarantees missed requirements.

### Read the plan before writing code
- Before starting any session, read the **entire session section** in `ARIA_PLAN.md` — not just the heading and bullet list, but every data model definition, reference implementation, code example, and design note.
- Cross-reference with the philosophy sections ("The Core Philosophy: Temporal UI, Not Spatial UI", "The Brief", "Philosophies") to ensure new code aligns with the vision.
- If the plan specifies a data model or interface, implement it as specified. Do not simplify, rename fields, or omit properties unless there is a compile-time reason to deviate.

### Maintain the vision
- ARIA is a **temporal UI** — surfaces change based on context, not user arrangement. Every UI decision must pass the test: "Does showing this require the user to do something, or is ARIA already handling it?"
- Cards show **judgment, not data**. Never forward raw notifications or API responses as card content.
- The Brief is capped at 5 items. Empty is correct. Ruthless curation is the feature.
- Battery-first: heavy inference runs in the nightly charging window. Daytime code only applies pre-computed decisions.
- Privacy-first: no usage data leaves the device unless the user configures a remote LLM endpoint.

### Attention to detail
- Follow the plan's reference implementations precisely. They exist because the architecture was designed holistically — changing one interface can break assumptions in later sessions.
- When the plan says "define now as a seam" for a future phase, create the interface/placeholder as specified — never simplify, but expand if the plan's example is clearly illustrative rather than exhaustive (e.g., a pattern matcher with 8 entries should ship with broader coverage). Future sessions depend on these seams existing.
- Verify each deliverable listed in the session before declaring it complete.

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
