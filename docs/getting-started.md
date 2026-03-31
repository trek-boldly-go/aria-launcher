# Getting Started with ARIA

## Install

1. Download the latest APK from [Releases](https://github.com/trek-boldly-go/aria-launcher/releases).
2. Open the APK on your Android device and install it. You may need to allow installation from unknown sources.
3. When prompted, set ARIA as your default launcher. You can also do this later in **Settings > Apps > Default apps > Home app**.

## Permissions

ARIA requests several permissions during onboarding. Each one enables a specific context signal:

| Permission | What it enables | Why |
|------------|----------------|-----|
| **Location** | WiFi SSID detection | Identifies venues (office, gym, coffee shop) by their WiFi network name. ARIA never sends your location anywhere. |
| **Activity Recognition** | Motion detection | Knows if you're walking, driving, or stationary — adjusts predictions accordingly. |
| **Calendar** | Upcoming events | Shows meeting cards, suggests relevant apps before events. |
| **Usage Stats** | App usage patterns | Learns which apps you use at which times to predict what you'll need next. This is a special permission — grant it in **Settings > Apps > Special app access > Usage access**. |
| **Notification Listener** | Notification summaries | Reads notifications to create Brief cards (e.g., "3 new emails"). Grant in **Settings > Apps > Special app access > Notification access**. |

You can skip any permission during onboarding and grant it later in Android Settings.

## Set Up an LLM Provider

ARIA's AI features (Brief cards, chat, skills) require an LLM provider. The easiest option to get started:

### Quick Start: Gemini (Free Tier)

1. Go to [Google AI Studio](https://aistudio.google.com/apikey) and create a free API key.
2. In ARIA, open **Settings > ARIA > LLM Provider**.
3. Select **Gemini**.
4. Paste your API key.
5. Tap **Test Connection** to verify.

That's it — ARIA will use Gemini for all AI features. For other providers (Claude, Ollama, OpenRouter, etc.), see the [LLM Setup Guide](llm-setup.md).

## Your First Brief

The Brief is the card feed on your home screen. After setup:

- ARIA collects context signals (time of day, WiFi, charging status, calendar events).
- During the nightly charging window (~3 AM), ARIA runs prediction and curation.
- On your next unlock, the Brief shows up to 5 curated cards.

**Empty is normal.** ARIA only shows cards when it has something genuinely useful. An empty Brief means nothing needs your attention right now — that's the feature working correctly.

Cards show **judgment, not data**. Instead of forwarding raw notifications, ARIA synthesizes information into actionable insights with clear next steps.

## Chat with ARIA

Tap the search bar on the home screen or swipe up to open the chat sheet.

ARIA can:
- Answer questions using its configured LLM
- Open apps and navigate to specific screens
- Search the web
- Set reminders and timers
- Compose messages and emails
- Get directions
- Check stock prices, weather, and more via skills
- Create and manage rules

**Example prompts:**
- "Open Slack"
- "What's the weather in Tokyo?"
- "Set a timer for 10 minutes"
- "How's AAPL doing today?"
- "Navigate to the nearest coffee shop"
- "When I connect to my office WiFi, show Slack and Teams"

## Create a Rule

Rules let you automate your home screen based on context. Tell ARIA in natural language:

> "When I connect to my office WiFi, show Slack"

ARIA will:
1. Parse your request into a structured rule (WiFi trigger + surface app action).
2. Ask you to confirm.
3. Apply the rule automatically whenever the trigger condition is met — no LLM call needed at runtime.

More examples:
- "Every weekday morning between 7 and 9, show my calendar and commute"
- "When I'm at the gym, show Spotify and my workout app"
- "When I have a meeting in 15 minutes, show Google Meet"

See the [Rules Guide](rules-guide.md) for the full trigger and action reference.
