# Rules Guide

## What are Rules?

Rules let you automate your home screen based on context. You describe what you want in natural language, and ARIA converts it into a structured trigger + action pair. Once created, rules evaluate instantly — no LLM call at runtime.

## Creating Rules via Chat

Open the chat sheet and describe what you want:

> "When I connect to my office WiFi, show Slack and Teams"

ARIA will:
1. Parse your request into a structured rule.
2. Show you the trigger and action for confirmation.
3. Save the rule and start evaluating it immediately.

**More examples:**
- "Every weekday morning between 7 and 9, show my calendar"
- "When I'm at a restaurant, suppress work apps"
- "When I have a meeting in 15 minutes, show Google Meet"
- "When I connect to Android Auto, show Spotify and Maps"
- "When I open Chrome, run the stock-quote skill"

## Trigger Reference

### WiFi SSID

Fires when connected to a matching WiFi network.

| Match type | Example phrase |
|------------|--------------|
| Exact | "When I'm on MyHomeWiFi" |
| Contains | "When my WiFi contains 'Office'" |
| Starts with | "When my WiFi starts with 'Guest'" |
| Regex | "When my WiFi matches 'Corp-\d+'" |

**Requires:** Location permission (Android requires it for WiFi SSID access).

### Venue Category

Fires based on the type of place you're at, detected via WiFi SSID classification.

**Categories:** restaurant, office, gym, cafe, hotel, airport, hospital, school, library, retail, transit

> "When I'm at a gym, show my workout app"

### Time-Based

Fires during specific hours and/or days of the week.

> "Every weekday between 8 AM and 6 PM"
> "On Saturday mornings between 7 and 10"
> "Every day after 10 PM"

**Days:** Sunday (1) through Saturday (7). Omit days to match every day.

### App Opened

Fires when a specific app is in the foreground.

> "When I open YouTube, suppress notifications"
> "When I open my banking app, show my budget skill"

### Calendar Event

Fires relative to upcoming calendar events, with optional keyword matching.

> "When I have a meeting in 15 minutes, show Google Meet"
> "15 minutes before any event with 'standup' in the title"

**Parameters:**
- `titleKeywords` — match events containing specific words (case-insensitive)
- `minutesBefore` — how many minutes before the event start (default: 15)

**Requires:** Calendar permission.

### Location Geofence

Fires when you're within a radius of specific coordinates.

> "When I'm near the downtown office, show Slack"

**Parameters:** latitude, longitude, radius in meters.

**Note:** This trigger type is defined but not yet fully implemented. It will always evaluate to false until GPS-based geofencing is enabled in a future release.

### Android Auto

Fires when connected to a car via Android Auto.

> "When I connect to Android Auto, show Maps and Spotify"
> "When I connect to my Tesla, show the charging app"

**Parameters:**
- `connectedCarName` — match a specific car name, or omit for any car connection.

### Compound (AND/OR)

Combines multiple triggers with logical operators.

> "When I'm on my office WiFi AND it's between 9 AM and 5 PM, show Slack"
> "When I'm at a gym OR it's Saturday morning, show my workout app"

## Action Reference

### Surface App

Makes an app more prominent on the home screen.

| Priority | Effect |
|----------|--------|
| `ALWAYS_SHOW` | Always visible in suggested apps |
| `BOOST` | Higher ranking in predictions |
| `PIN_TO_DOCK` | Pinned to dock area |

> "Show Slack" → surfaces the Slack app

### Suppress App

Hides or deprioritizes an app.

> "Hide work apps" → suppresses specified apps from predictions

### Show Card

Displays an informational card on the Brief.

> "Show a card reminding me to take my vitamins"

**Parameters:** card type, headline, optional subtext, optional deep link.

### Open App

Launches an application, optionally to a specific screen.

> "Open Google Maps to navigation"

**Parameters:** package name, optional deep link URI.

### Send Message

Sends a text message with optional dynamic tokens.

> "Send 'On my way' to Sarah"

**Tokens:** `{time}` (current time), `{location}` (current location hint).

### Set Space

Switches to a named workspace/space.

> "Switch to my work space"

### Run Skill

Activates an installed skill.

> "Run the stock-quote skill"

**Parameters:** skill ID, optional config parameters.

### Fetch Data (MCP)

Calls a remote tool via MCP server integration.

> "Fetch my latest order status from the shipping tracker"

**Parameters:** server ID, tool name, parameters, success/failure actions.

**Note:** This action type is reserved for future MCP server integration.

## Managing Rules

### View Rules

> "Show my rules"
> "What rules do I have?"

### Enable/Disable

> "Disable my office WiFi rule"
> "Enable the morning calendar rule"

### Delete

> "Delete the gym rule"
> "Remove all my WiFi rules"
