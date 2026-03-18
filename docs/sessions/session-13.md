# Session 13: Onboarding + Settings Polish

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

## Session 13 Deliverables

**Goal**: Ready for beta.

- Complete onboarding flow: notification listener permission, rule tutorial page
- Settings: active LLM display, WiFi labels, skill toggles, notification access, rules link
- Debug: force SSID classification, fake SSID input, Brief regeneration trigger, context dump
- QR token pairing flow for Claude OAuth

---

## Gotchas (critical for this session)

- `PACKAGE_USAGE_STATS` cannot be granted at install — direct user to Settings > Special App Access. Grant via: `adb shell appops set com.aria.launcher.play.debug android:get_usage_stats allow`
- WiFi SSID returns `null` without location permission granted — LocationHint defaults to UNKNOWN. Must request `ACCESS_FINE_LOCATION` explicitly.
- ActivityRecognition returns `null` without `ACTIVITY_RECOGNITION` permission — commute detection disabled until granted.
- Accessibility Services face Google Play policy scrutiny — document the use case clearly during onboarding.
- WorkManager with `setRequiresCharging(true)` defers if not charging — this is **correct behavior**, not a bug. Don't fight it.
- Daytime prediction path is purely a Room read — must be < 5ms, no ML inference.
- Stream LLM responses token-by-token — never make user wait for full response.
- Ollama: when away from home on mobile data, fall back to Claude or Gemini if the Ollama url is not a public IP or domain.
- Widget hosting requires `BIND_APPWIDGET` + system dialog — explain during onboarding.

---

## QR Token Pairing (Claude OAuth)

The user can scan a QR code displayed in the Claude desktop app to transfer their Claude OAuth token to the phone. This avoids manual API key entry.

Flow:
1. On-phone: show "Pair with desktop" option in onboarding / LLM setup screen
2. Open camera / QR scanner
3. Decode QR → extract Claude OAuth token
4. Store in `EncryptedSharedPreferences` via `LlmProviderManager`
5. Set Claude as active LLM provider

The QR code format is defined by the desktop Claude app. Treat the token as opaque — just store and forward it as a Bearer token.
