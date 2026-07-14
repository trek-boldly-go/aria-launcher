# ARIA Roadmap

## Recently Completed

- **Brief card feed** — Context-aware home screen with curated insight cards (max 5, empty is correct)
- **Chat with tool calling** — Natural language chat with 15 native tools (open apps, search, fetch URLs, compose messages, etc.)
- **Rules engine** — Natural language rules with 8 trigger types and 8 action types, zero-LLM at runtime
- **Agent skills** — Extensible SKILL.md-based skills with heartbeat, on-demand, and scheduled triggers
- **LLM provider abstraction** — Claude, Gemini, Ollama, OpenRouter, OpenAI-compatible, and on-device LiteRT
- **Onboarding + permissions** — Guided setup flow with QR-based OAuth token pairing for Claude
- **Prediction engine** — Frequency-based app prediction with context signals (time, WiFi, charging, activity)
- **Editorial engine** — LLM-powered curation of Brief cards with judgment, not raw data

## In Progress

- **Skill ecosystem expansion** — More bundled skills, community skill contributions
- **Editorial engine refinement** — Better context awareness, improved card quality

## Planned

- **Ollama Model Settings** - Support changing the temperature and thinking level (if supported) to the Ollama Provider
- **Settings reorganization** — Hide irrelevant Lawnchair settings, promote ARIA settings to top-level
- **Notification filtering for Brief** — Prevent basic and persistent notifications from appearing as Brief cards
- **Notification dismiss tool** — Allow ARIA to dismiss notifications the user doesn't care to see
- **Brief card urgency consistency** — Unify urgency levels and side-color indicators across all card types
- **Home screen long-press cleanup** — Hide widgets and apps options when long-pressing the top section of the home screen
- **MCP server support** — Connect to app-hosted MCP endpoints for deep app integration
- **Permissions health UX** — Warn on missing PACKAGE_USAGE_STATS, add "Re-run bootstrap" recovery button
- **Server-side skill execution** — Companion container for heavy/long-running skills
- **Accessibility-based skills** — Screen reading + UI automation for apps without APIs
- **On-device model improvements** — LiteRT optimizations, larger model support
- **Spaces** — Context-aware workspaces (commute, office, home, gym)
- **Compound rules** — AND/OR logic for combining multiple triggers

## Testing & Validation

- **LLM provider matrix** — Test all providers, auth modes (API key, OAuth, token), and behavior with models that don't support tool calling
- **Context sensor validation** — Test WiFi detection (detected but not connected SSIDs), Android Auto connections, activity recognition accuracy
- **Skill import & execution** — Test importing skills from external repos and running them with scheduled, heartbeat, and chat triggers
- **Chat streaming completion** — Investigate why chat appears to still be typing for 30+ seconds after the LLM has actually finished responding

## Known Limitations

- Requires an LLM endpoint for AI features (Brief curation, chat, skill execution)
- Notification listener needs manual grant in Android settings
- WiFi SSID detection requires location permission
- Activity recognition requires a separate runtime permission
- No cloud sync — all data stays on-device (this is intentional)
- On-device LiteRT models have limited capability compared to cloud providers

## Non-Goals

- **Replacing the app drawer** — ARIA enhances the home screen, the drawer stays
- **Becoming a widget platform** — Cards are curated by AI, not arranged by the user
- **Telemetry or analytics** — No usage data leaves the device, period
- **Supporting pre-Android 16 devices** — Built on Launcher3 APIs that target API 36
