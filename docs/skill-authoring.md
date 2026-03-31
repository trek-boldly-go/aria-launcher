# Skill Authoring Guide

## What is a Skill?

A skill is a `SKILL.md` file that tells ARIA's LLM how to fetch data, process it, and present results to the user. Skills follow the [agentskills specification](https://agentskills.io/specification).

Skills are **instructions for the LLM**, not executable code. The LLM reads the skill's markdown body and uses ARIA's built-in tools (like `fetch_url`) to carry out the instructions. This means anyone who can write clear instructions can author a skill — no Kotlin or Android knowledge required.

### Two-Tier Disclosure

ARIA uses a two-tier system to manage LLM context efficiently:

1. **Tier 1 (catalog):** The LLM sees every installed skill's name and description. This lets it decide which skill to activate.
2. **Tier 2 (activation):** When a skill is activated (via `activate_skill` tool), the LLM receives the full markdown body with detailed instructions.

## SKILL.md Format

A skill file has YAML frontmatter followed by a markdown body:

```markdown
---
name: my-skill
description: One-line description of what this skill does and when to use it.
metadata:
  author: your-name
  version: "1.0"
  aria-trigger: heartbeat
aria-refresh: 15
---

# My Skill

Detailed instructions for the LLM on how to execute this skill...
```

## Frontmatter Reference

### Required Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Unique skill identifier. Must match `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` — lowercase alphanumeric with single hyphens, no leading/trailing hyphens. |
| `description` | string | Human-readable description. This is what the LLM sees in tier-1 disclosure, so make it clear about **when** to use the skill. |

### Optional Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `metadata.author` | string | — | Skill author name or handle. |
| `metadata.version` | string | — | Skill version (semver recommended). |
| `metadata.aria-trigger` | string | `on-demand` | When this skill activates (see Trigger Types below). |
| `metadata.aria-context` | string | — | Context requirements or hints for the skill. |
| `aria-refresh` | integer | `15` | Refresh interval in minutes for heartbeat skills. |

## Trigger Types

| Trigger | When it runs | Use case |
|---------|-------------|----------|
| `heartbeat` | Periodically, based on `aria-refresh` interval | Proactive cards (stock prices, weather, news) |
| `on-demand` | When the user or LLM requests it | Chat responses (destination weather, calculations) |
| `scheduled` | On a cron schedule | Daily digests, periodic data collection |

## Available Tools

When executing a skill, the LLM has access to these tools:

### `fetch_url`

Makes HTTP requests to external APIs.

```
fetch_url(url: "https://api.example.com/data", method: "GET")
fetch_url(url: "https://api.example.com/submit", method: "POST", body: "{\"key\": \"value\"}")
```

**Parameters:**
- `url` (required) — Target URL. Must be HTTPS.
- `method` — `GET` or `POST`. Defaults to `GET`.
- `body` — Request body for POST requests (JSON string).
- `headers` — Custom headers object.

**Security restrictions:** Blocks `localhost`, private IPs (`10.*`, `172.16-31.*`, `192.168.*`), and `file://` URIs.

**Response:** HTTP status code + response body (truncated to 4000 characters).

### `activate_skill`

Retrieves and activates another skill's instructions. Useful for multi-skill chains.

```
activate_skill(name: "weather-destination")
```

## Configuration

Skills can store per-user configuration using config keys. Config values are stored in SharedPreferences under the `aria_skill_config` namespace.

In your skill instructions, reference config keys and tell the LLM what to do if they're missing:

```markdown
## Configuration
The user's preferred city is stored in skill config key `city`.
If not configured, ask the user which city they want weather for.
```

Users set config through chat:
> "Set my stock watchlist to AAPL, GOOGL, TSLA"

The LLM updates the skill's config key accordingly.

## Walkthrough: stock-quote

The bundled `stock-quote` skill demonstrates a heartbeat skill with API fallback and proactive presentation.

### Frontmatter

```yaml
---
name: stock-quote
description: Check stock prices and market performance. Use when the user asks
  about stocks, their portfolio, market conditions, or proactively in the morning
  to show price updates.
metadata:
  author: aria
  version: "1.0"
  aria-trigger: heartbeat
---
```

Key decisions:
- **`heartbeat` trigger** — runs periodically so stock prices appear proactively on the home screen.
- **Description mentions both reactive and proactive use** — the LLM knows to activate this skill in chat *and* during Brief curation.

### Config

```markdown
## Configuration
The user's watchlist symbols are stored in skill config key `symbols`
(comma-separated, e.g., "AAPL,GOOGL,TSLA").
If no symbols are configured, ask the user what stocks they want to track.
```

The skill gracefully handles missing config by asking the user.

### API Instructions

```markdown
## How to check a single stock
Fetch price data for a symbol:
`https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1d&range=1d`

From the response, extract:
- `chart.result[0].meta.regularMarketPrice` - current price
- `chart.result[0].meta.chartPreviousClose` - previous close
```

The instructions tell the LLM exactly which API to call and which fields to extract. A fallback API is also provided.

### Presentation Rules

The skill includes separate instructions for proactive cards vs. chat responses, plus timing logic (skip weekends, only show meaningful moves).

## Walkthrough: weather-destination

The bundled `weather-destination` skill demonstrates an on-demand skill with multi-step API chains.

### Frontmatter

```yaml
---
name: weather-destination
description: Check weather for any city or travel destination. Use when planning
  trips, packing, or the user asks about weather somewhere other than their current
  location.
metadata:
  author: aria
  version: "1.0"
  aria-trigger: on-demand
---
```

Key decisions:
- **`on-demand` trigger** — only runs when the user asks about weather.
- **Description specifies "somewhere other than current location"** — helps the LLM distinguish this from a local weather skill.

### Multi-Step Chain

This skill requires two API calls:

1. **Geocoding** — convert city name to coordinates using Open-Meteo's geocoding API.
2. **Weather** — fetch the forecast using coordinates.

The instructions walk the LLM through each step sequentially, including error handling ("If no results, tell the user the city wasn't found").

### No API Key Required

Both APIs (Open-Meteo geocoding and forecast) are free and require no authentication — making this skill work out of the box.

## Installing Skills

### Via Chat

Tell ARIA to install a skill from a URL:
> "Install the skill at https://example.com/my-skill/SKILL.md"

ARIA fetches the file, validates it, and adds it to the skill catalog.

### Via File

Place a `SKILL.md` file in the app's skill directory. The skill name must match the parent directory name:

```
[app-data]/skills/my-skill/SKILL.md
```

Bundled skills ship in `lawnchair/assets/skills/` and are auto-copied on first launch.

## Testing Your Skill

1. Install the skill (via chat or file placement).
2. In chat, ask ARIA something that should trigger your skill. The LLM should activate it based on the description.
3. Check logcat for execution details:
   ```bash
   adb logcat -s ARIA.SkillExecutor ARIA.SkillOrchestrator
   ```
4. For heartbeat skills, you can force a refresh by asking ARIA: "Run the [skill-name] skill."

### Common Issues

- **Skill not activating:** Check that the `description` clearly states when to use it.
- **API calls failing:** Verify URLs work in a browser first. Check that the response format matches what your instructions expect.
- **Config not persisting:** Make sure your config key names are consistent throughout the skill instructions.
