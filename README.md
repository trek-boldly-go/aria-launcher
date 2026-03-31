# ARIA — Adaptive Reasoning Interface for Android

ARIA is an AI-native Android launcher that predicts what **information** you need and surfaces it proactively on your home screen. Instead of launching Gmail, see your inbox summary. Instead of opening your calendar, see your next meeting with a join button. The AI is ambient, not opt-in — every unlock is a touchpoint.

Built as an **Ambient Agent OS**: a skill-based system where each installed app can be queried, summarized, and acted upon without ever opening it. The home screen becomes a context-aware feed of actionable insight cards, powered by LLM reasoning over device context signals.

**Core insight:** Google's agentic features are buried inside the Gemini app. ARIA starts at the launcher level — it *is* the home screen.

Built on [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (`16-dev`), which itself extends Android's Launcher3.

## Home Screen

```
┌─────────────────────────────────┐
│  Good morning, Donovon     9:15 │
│  ┌───────────────────────────┐  │
│  │  Ask ARIA anything...   🎤│  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  ┌───────────────────────────┐  │
│  │ ✉  Gmail · 3 new          │  │
│  │ Sarah: Project update...  │  │
│  │ Mike: Lunch tomorrow?     │  │
│  │            [Open] [Reply] │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │ 📅 Calendar · Next up     │  │
│  │ Team standup in 45 min    │  │
│  │ Room: Conf B / Google Meet│  │
│  │          [Join] [Snooze]  │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │ 🚗 Commute · 22 min       │  │
│  │ via I-95, light traffic   │  │
│  │              [Navigate]   │  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  Suggested                      │
│  [Chrome] [Slack] [Spotify] ... │
├─────────────────────────────────┤
│  [Phone] [Msgs] [Camera] [Mail]│
└─────────────────────────────────┘
```

## Philosophies

1. **Battery-first** — ML inference and trend analysis runs during a nightly charging window. Daytime execution only applies already-computed decisions.
2. **Privacy-first** — No usage data ever leaves the device unless the user explicitly configures a remote LLM endpoint.
3. **LLM-agnostic** — All LLM calls go through a provider abstraction layer. Swap Claude for Gemini for Ollama with zero code changes.
4. **Launcher-native** — Not a widget or overlay. The actual home screen.
5. **Skills over apps** — Predict what information to surface and what actions to take, not which app to open.

## Skills System

A "skill" is something ARIA can do *with* or *about* an installed app, without the user opening it. Skills follow the [agentskills](https://agentskills.io/specification) specification.

| Layer | Source | Example |
|-------|--------|---------|
| Android Shortcuts | `LauncherApps.getShortcuts()` | "New message", "Search" |
| Built-in Skill Packs | Curated by ARIA | Stock quotes, destination weather |
| Intent Skills | Standard Android intents | Share, View URL, Send email |
| MCP Servers | App-hosted MCP endpoints | Full app API access |
| Accessibility | Screen reading + UI automation | Any app, any action |

## LLM Providers

ARIA works with any LLM provider. See the [LLM setup guide](docs/llm-setup.md) for detailed configuration instructions.

| Setup | Provider | Auth | Cost |
|-------|----------|------|------|
| Google AI Studio (Gemini) | `OpenAICompatibleProvider` | API key | Free tier |
| Claude Pro/Max | `ClaudeProvider` | OAuth via QR pairing | Subscription |
| Claude API | `ClaudeProvider` | API key | Per-token |
| Self-hosted Ollama | `OllamaProvider` | Server URL | Free |
| OpenRouter | `OpenAICompatibleProvider` | API key | Per-token |
| OpenAI / GPT-4o | `OpenAICompatibleProvider` | API key | Per-token |
| On-device (LiteRT) | `LiteRtProvider` | None | Free (offline) |

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 2.3.20 |
| UI | Jetpack Compose |
| Base | Lawnchair 2 fork (16-dev) |
| Database | Room 2.8.4 |
| Background jobs | WorkManager 2.10.0 |
| DI | Hilt/Dagger 2.59.2 |
| Async | Coroutines + Flow |
| HTTP | OkHttp 5.3.2 (SSE streaming) |
| Context signals | UsageStatsManager, ActivityRecognition, WiFi, Calendar |
| Build system | Gradle 9.4.1, AGP 9.1.0 |
| Target SDK | API 36 (Android 16) |

## Project Structure

```
aria-launcher/
├── src/                              ← AOSP Launcher3
├── lawnchair/src/
│   └── com/aria/launcher/aria/       ← ARIA feature layer
│       ├── data/                     ← Room DB, context signals, skill models
│       ├── engine/                   ← Prediction engine, rules, skills
│       ├── llm/                      ← LLM provider abstraction
│       ├── agent/                    ← Agentic tasks
│       ├── chat/                     ← Chat UI (Compose)
│       ├── scheduler/                ← WorkManager jobs
│       └── ui/                       ← Home screen surfaces
├── docs/                             ← User & developer guides
└── build.gradle
```

## Documentation

| Guide | Audience |
|-------|----------|
| [Getting Started](docs/getting-started.md) | Users |
| [LLM Setup](docs/llm-setup.md) | Users |
| [Rules Guide](docs/rules-guide.md) | Users |
| [Skill Authoring](docs/skill-authoring.md) | Developers |
| [Contributing](CONTRIBUTING.md) | Contributors |
| [Roadmap](ROADMAP.md) | Everyone |
| [Manual Testing](docs/MANUAL_TESTING.md) | Contributors |

## Getting Started

See the [Getting Started guide](docs/getting-started.md) for installation and setup instructions.

**Quick start:**
1. Download the latest APK from [Releases](https://github.com/trek-boldly-go/aria-launcher/releases)
2. Install and set as your default launcher
3. Grant permissions when prompted
4. Configure an LLM provider (Gemini free tier is the easiest)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, architecture overview, code style, and how to submit changes.

## Sponsor

ARIA is built and maintained by [Donovon Simpson](https://github.com/trek-boldly-go). If you find it useful, consider sponsoring:

[![GitHub Sponsors](https://img.shields.io/github/sponsors/trek-boldly-go?style=for-the-badge&logo=github)](https://github.com/sponsors/trek-boldly-go)

## License

- **Upstream code** (AOSP Launcher3 + Lawnchair): [Apache License 2.0](LICENSE.txt)
- **ARIA-original code**: Dual-licensed — [GPL-3.0](LICENSE-GPL.txt) for community use, [commercial license](LICENSE-ARIA.md) available. Copyright (c) 2026 Donovon Simpson.

See [LICENSE-ARIA.md](LICENSE-ARIA.md) for full details.
