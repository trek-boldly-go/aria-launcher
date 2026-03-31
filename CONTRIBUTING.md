# Contributing to ARIA

ARIA is an AI-native Android launcher built on [Lawnchair](https://github.com/LawnchairLauncher/lawnchair). Contributions are welcome — whether that's fixing bugs, writing skills, improving documentation, or building new features.

## Development Setup

### Requirements

- **JDK 21** — Oracle JDK or any distribution. Set `JAVA_HOME` to point to it.
- **Android Studio** — recommended for editing, but not required for building.
- **Git** — for cloning and branching.

### Clone and Build

```bash
git clone https://github.com/trek-boldly-go/aria-launcher.git
cd aria-launcher

# Build the debug APK
./gradlew assembleLawnWithQuickstepPlayDebug

# Check code formatting
./gradlew spotlessCheck

# Auto-fix formatting
./gradlew spotlessApply

# Run unit tests
./gradlew testLawnWithQuickstepPlayDebugUnitTest
```

The build variant is **`lawnWithQuickstepPlayDebug`** — this is the only variant used for development.

### Build Flavors

| Dimension | Value | Purpose |
|-----------|-------|---------|
| `app` | `lawn` | Lawnchair + ARIA |
| `recents` | `withQuickstep` | Quickswitch recents integration |
| `channel` | `play` | Distribution channel (use `play` for local dev) |

## Architecture Overview

### Source Sets

| Source set | Path | Contents |
|------------|------|----------|
| `main` | `src/`, `src_plugins/` | AOSP Launcher3 upstream code |
| `lawn` | `lawnchair/src/` | Lawnchair customizations + all ARIA code |
| `withQuickstep` | — | Quickswitch recents integration |

**All ARIA code lives in** `lawnchair/src/com/aria/launcher/aria/`:

| Package | Purpose |
|---------|---------|
| `data/` | Room database, context signals, skill models |
| `engine/` | Prediction engine, rules engine, skill orchestration |
| `llm/` | LLM provider abstraction (Claude, Gemini, Ollama, etc.) |
| `agent/` | Agentic task execution |
| `chat/` | Chat UI (Jetpack Compose) |
| `scheduler/` | WorkManager jobs (usage collection, nightly predictions) |
| `ui/` | Home screen surfaces, composables |

### Dependency Injection

Hilt/Dagger with 4 modules installed in `SingletonComponent`:

| Module | Provides |
|--------|----------|
| `AriaDataModule` | Room database, DAOs, repositories |
| `LlmModule` | OkHttpClient, Json, LlmProviderManager |
| `ChatModule` | Chat-related dependencies |
| `SkillModule` | Skill execution dependencies |

## Code Style

- **Formatter:** Spotless with ktlint 1.8.0 + Compose rules 0.5.6
- **Run `spotlessCheck` before every commit.** CI will reject PRs that fail.
- **Self-explanatory code over comments.** Only add comments when the code genuinely can't be verbose enough to convey intent.
- **No unnecessary imports, no wildcard imports.**

## Git Workflow

1. **Branch from `aria-dev`** — this is the working branch.
2. **Name your branch descriptively** — e.g., `fix/brief-card-overflow`, `feat/weather-skill`, `docs/llm-setup`.
3. **PR back to `aria-dev`** — all PRs target `aria-dev`, not `main`.
4. **Keep commits focused** — one logical change per commit.

### CI

CI runs on all PRs and pushes to `*-dev` branches:
- `build-debug-apk` — builds the debug APK
- `check-style` — runs `spotlessCheck`
- `final-status` — aggregated pass/fail

## What to Contribute

### Good First Issues

Look for issues labeled `good first issue` in the [issue tracker](https://github.com/trek-boldly-go/aria-launcher/issues).

### Skills

ARIA's skill system is designed for extensibility. A skill is a `SKILL.md` file with YAML frontmatter that tells the LLM how to fetch data and present it. See the [Skill Authoring Guide](docs/skill-authoring.md) for the full spec and walkthroughs.

### Documentation

Improvements to guides, examples, and explanations are always welcome.

### Bug Reports

File bugs using the [bug report template](https://github.com/trek-boldly-go/aria-launcher/issues/new?template=bug_report.yaml). Include:
- Device model and Android version
- Which LLM provider you're using
- Steps to reproduce
- Relevant logcat output (filter by `ARIA.`)

### Feature Requests

Use the [feature request template](https://github.com/trek-boldly-go/aria-launcher/issues/new?template=feature_request.yaml). Explain the use case, not just the feature.

## What NOT to Do

- **Don't modify AOSP code in `src/`** — those files track upstream Launcher3.
- **Don't scatter ARIA logic into Lawnchair files** — keep integration points thin (one-line import + function call).
- **Don't add inline dependency versions** — all dependencies go in `gradle/libs.versions.toml`.
- **Don't add large third-party dependencies** without discussion first.
- **Don't add telemetry, analytics, or tracking** of any kind.

## Dependency Management

All dependencies are declared in `gradle/libs.versions.toml` and referenced in `build.gradle` as `libs.some.dependency`. Never use inline version strings. New licenses must be added to the `licensee` block in `build.gradle`.

## Licensing

Contributions to ARIA-original code are licensed under **GPL-3.0**. By submitting a pull request, you agree that your contribution is licensed under the same terms as the project (see [LICENSE-ARIA.md](LICENSE-ARIA.md)).

Copyright remains with the contributor. No CLA is required.

Upstream code (AOSP + Lawnchair) remains under Apache 2.0.
