# Session 14: ContextBar + Weather + Final Polish

---

## ARIA Vision (read every session)

ARIA (Adaptive Reasoning Interface for Android) is an AI-native Android launcher. Instead of predicting apps, it predicts **information** — surfacing it proactively as cards. The home screen is a context-aware feed of actionable insight cards, powered by LLM reasoning over device context. ARIA is an **Ambient Agent OS**: each app can be queried, summarized, and acted upon without opening it.

**Core insight:** Google's agentic features are buried in Gemini. ARIA starts at the launcher level — it *is* the home screen.

### Temporal UI, not Spatial UI

| Spatial (do not do this) | Temporal (do this) |
|---|---|
| Fixed app grid | Predicted app row that reorders by context |
| Static widgets | Context-driven cards that appear and disappear |
| User hunts for the right app | ARIA surfaces it before the user thinks to look |
| Notification shade repackaged as cards | ARIA synthesizes alerts into judgments |
| Same layout at 7am and 10pm | Completely different surface at 7am vs 10pm |

**Test for every UI decision:** "Does showing this require the user to do something, or is ARIA already handling it?" If ARIA is handling it, the card should say so with *judgment*, not just data.

### The Brief

The home screen's primary content area is **the Brief** — a scrollable list of BriefItem cards, generated fresh on each context change (not every unlock).

- **Max 5 items.** Ruthless curation is the feature.
- **Judgment, not raw data.** ARIA synthesizes before showing.
- **Items appear and disappear.** Nothing is permanent.
- **Not a notification shade.** Only what ARIA has decided is worth attention.
- **Empty is correct.** Show nothing if nothing is worth showing.

**BriefItem types (1-line summary):**
- `AlertAssessed` — ARIA's verdict on an alert, not the raw alert text
- `ReminderNudge` — time-sensitive nudge for something to act on
- `CalendarEvent` — upcoming event with join/navigate/open action
- `MediaResume` — resumable media the user was consuming
- `LiveDataCard` — live data from MCP source (Phase 8+ seam)
- `ProactiveSuggestion` — pattern-detected suggestion with rationale
- `VenueCard` — venue-aware card shown near a known location
- `ContextBar` — compact weather/location line in greeting area (NOT in brief list)

### Home Screen Layout

```
┌─────────────────────────────┐
│  Good evening, Donovon      │  ← Greeting
│  Sunday · Lisle · 34°F Snow │  ← ContextBar (always present)
├─────────────────────────────┤
│  ○  Ask ARIA anything...    │  ← Chat bar
├─────────────────────────────┤
│  [BriefItem]                │  ← The Brief (max 5, scrollable)
│  [BriefItem]                │
├─────────────────────────────┤
│  [App] [App] [App] [App]    │  ← Predicted apps row
├─────────────────────────────┤
│  [Phone] [Messages] [Maps]  │  ← Dock
└─────────────────────────────┘
```

No app grid. No Google search bar. No static widgets. No folder grid.

### Anti-Patterns to Avoid

- **Notification shade problem**: showing raw notification content as cards — every card must be synthesis
- **Two input bars**: ARIA chat bar + Google search bar visible at once
- **Cards that never leave**: every BriefItem with a natural end must have expiry
- **Animating on every unlock**: animate on context change only, instant load otherwise
- **Excessive text**: 6-word headlines, 12-word subtext — enforce on parsing side
- **Explanatory empty states**: if the Brief is empty, show nothing

---

## Session 14 Deliverables

**Goal**: Production quality.

- `WeatherBriefSource` + `WeatherProvider` (Open-Meteo free API, no key required)
- `ContextBarComposable` — Always-visible weather/location line below greeting
- Brief polish: smooth transitions, swipe-to-dismiss, proper empty state (show nothing)
- Verify no animation on every unlock, only on context change

---

## Spec: ContextBar BriefItem

```kotlin
// Compact weather + context line — always present, minimal footprint
// Rendered in the greeting area, NOT in the scrollable brief list
data class ContextBar(
    val weatherLine: String,        // "34°F · Snow · Feels like 25°"
    val locationHint: String?,      // "Home", "Work", null if unknown
    val alertCount: Int = 0         // if >0, tap expands to alert details
) : BriefItem()
```

The ContextBar is not scrolled away — it lives in the greeting area above the Brief. It is rendered by `AriaGreeting`, not by `AriaBrief`.

---

## Spec: Context Change vs. Unlock (animation rules)

**ARIA does not regenerate the Brief on every unlock.**

- Brief is regenerated when the **context bucket changes** (meaningful shift in time, location, or activity)
- On unlock: reads already-computed Brief from cache and renders instantly (< 16ms, no network call)
- Brief also regenerates when:
  - A new calendar event enters the 15-minute window
  - The WiFi network changes
  - Detected activity changes significantly (STILL → IN_VEHICLE)
  - User dismisses an item

**Key verification for this session**: On rapid lock/unlock with no context change, the Brief must appear instantly with no animation. Animation (`fadeIn + slideInVertically`) fires only when items actually change.

Use `distinctUntilChanged()` on `contextMonitor.contextChanges` — already wired in `AriaHomeViewModel`. Confirm this is working by checking that a rapid lock/unlock cycle does NOT trigger `refreshBrief()`.

---

## Open-Meteo Weather API

Free, no API key required. Example endpoint:

```
https://api.open-meteo.com/v1/forecast
  ?latitude={lat}
  &longitude={lon}
  &current=temperature_2m,weather_code,apparent_temperature
  &temperature_unit=fahrenheit
  &wind_speed_unit=mph
  &precipitation_unit=inch
  &timezone=auto
```

Weather codes map to human-readable descriptions (WMO standard). Format as: `"{temp}°F · {condition} · Feels like {apparent}°"`.
