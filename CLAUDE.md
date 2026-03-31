# CLAUDE.md — ARIA Launcher

Read this file before working on this codebase. It contains critical build, architecture, and workflow information.

## Project Overview

ARIA (Adaptive Reasoning Interface for Android) is an AI-native launcher built on Lawnchair/Launcher3.

## Guidelines

- All "ARIA" code should be maintained. Do NOT skip over bugs or findings simply because they were not touched by a current task. Raise them as issues and proactively attempt to fix/improve them as you work.
- Assume your user knows little to nothing about android development, so your job is to be the technical capability expert / developer, while your user is a PM and visionary for the project.

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

## Code Style

- Run `spotlessCheck` during every code change just as often as a build
- Code should be self-explanatory whenever possible, making comments mostly unnecessary. When code can't be verbose and it isn't readily understandable, then add comments.

## Git Workflow

- `origin` → `trek-boldly-go/aria-launcher` (private, push target)
- `upstream` → `LawnchairLauncher/lawnchair` (fetch-only for syncing)
- Working branch: `aria-dev` (matches `*-dev` CI trigger pattern)
- Upstream sync: `git fetch upstream refs/heads/16-dev:refs/remotes/upstream/16-dev && git merge upstream/16-dev`

### CI Branching

- CI (`.github/workflows/ci.yml`) triggers on pushes to `*-dev` branches (including `aria-dev`) and on all PRs.
- Required checks: `build-debug-apk`, `check-style` (spotlessCheck), `final-status`.
- The labeler (`.github/labeler.yml`) marks PRs as `outdated` unless they target `16-dev` or `aria-dev`.
- Feature branches should branch from `aria-dev` and PR back to `aria-dev`.

## Build

```bash
./gradlew assembleLawnWithQuickstepPlayDebug 2>&1 | tail -20
```

Requires JDK 21. The `JAVA_HOME` path is set in `gradle.properties` (`org.gradle.java.home`) and should point to your JDK 21 installation.

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
./gradlew testLawnWithQuickstepPlayDebugUnitTest
```

- Tests live in `tests/unit/` (not `src/test/`)
- Framework: JUnit 4 + Mockito + Truth + coroutines-test
- `testOptions.unitTests.returnDefaultValues = true` is set in build.gradle

## Lint / Format Commands

```bash
./gradlew spotlessCheck    # check formatting
./gradlew spotlessApply    # auto-fix formatting
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

- **`docs/`** contains user-facing guides (getting-started, llm-setup, rules, skill authoring) and developer docs (manual testing).
- When consolidating or editing docs, preserve all detail. If in doubt, keep it.

## Working on ARIA

Development is agile and user-driven. The user builds, tests on-device, gathers feedback, then requests changes directly — no pre-planned phases or session roadmap to follow.

### Workflow
- **The user sets priorities.** Implement what is asked for. Do not anticipate or queue up future work.
- **Iterate quickly.** Small, focused changes that can be built, deployed, and tested on-device.
- **Read before writing.** Understand the existing code in the area you're changing.

### Maintain the vision
- ARIA is a **temporal UI** — surfaces change based on context, not user arrangement. Every UI decision must pass the test: "Does showing this require the user to do something, or is ARIA already handling it?"
- Cards show **judgment, not data**. Never forward raw notifications or API responses as card content.
- The Brief is capped at 5 items. Empty is correct. Ruthless curation is the feature.
- Battery-first: heavy inference runs in the nightly charging window. Daytime code only applies pre-computed decisions.
- Privacy-first: no usage data leaves the device unless the user configures a remote LLM endpoint.

## Licensing Rules

This repo has a dual-license structure:

- **Upstream code** (AOSP + Lawnchair) is Apache 2.0 — see `LICENSE.txt`
- **ARIA-original code** is dual-licensed: GPL-3.0 for community use, commercial license available — see `LICENSE-ARIA.md`

When writing new code:

1. **Keep ARIA code in `lawnchair/src/com/aria/`** as much as possible — composables, view models, state, data classes all belong there.
2. **Minimize changes to Lawnchair files.** When integrating ARIA into upstream files (e.g., `PreferencesDashboard.kt`, `LawnchairLauncher.kt`), keep the touch points thin — a one-line import + function call, not large blocks of logic.
3. **If a Lawnchair file becomes substantially ARIA-authored** (70%+ rewritten), add this header at the top of that file:
   ```
   // Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
   ```
   Do not add this header to files with minor ARIA modifications.
4. **Never remove** the Apache 2.0 `LICENSE.txt` or upstream copyright notices.
