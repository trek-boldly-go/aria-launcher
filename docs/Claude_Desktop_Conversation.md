This is genuinely exciting progress for a day's work. The bones are right. Let me give you honest UX feedback and then actually answer your bigger question about what an AI-native OS *should* look like, because that's the more important conversation.

---

**On what you've built so far**

The ARIA bar at the top is the right instinct. The cards below it are a reasonable first pass but I think they're pointing you in the wrong direction philosophically — and that's worth understanding before you polish them.

The flood advisory and weather cards look like a notification shade. Android already has one of those. If ARIA is just surfacing the same information in a different place, you haven't changed the paradigm — you've just moved it. The question to ask about every piece of information on screen is: *does showing this require the user to do anything, or is ARIA already handling it?*

The Google search bar at the bottom — yes, remove it as the default. It creates a confusing dual-input situation. The ARIA bar IS the search bar, and more. Having both sends the message "ARIA is an add-on" rather than "ARIA is the OS."

---

**The bigger question: what does an AI-native OS actually look like?**

You're right that nobody has paved this road. But there's a useful mental model: **the shift from a spatial UI to a temporal one.**

Current phone UIs are *spatial*. Apps live in fixed locations. You navigate to them. The phone is a map you travel through. This made sense when the phone was dumb and you were the intelligence.

An AI-native UI should be *temporal*. It knows what moment you're in and presents what's relevant to *right now*. You don't navigate — things arrive. The interface isn't a place you go, it's a surface that changes around you.

Here's what that implies concretely:

**The home screen shouldn't have a fixed layout at all.** It should be a single scrollable feed, generated fresh every time you unlock, ranked by relevance to this exact moment. Not cards you swipe through — a *brief* that ARIA has prepared for you. Think less Pinterest board, more daily intelligence briefing.

What goes in the brief:
- Things that need your attention *now* (flood advisory — but surfaced as "roads near you may be affected, want me to check your route home?" not just a raw alert repackaged)
- Things you'll likely want *soon* based on patterns (it's Sunday evening, you usually check your work calendar around now)
- Things ARIA is already handling that you might want to know about (no action needed, just FYI)
- Quick-launch for the 3–4 apps most likely in this moment

The card format isn't wrong for the "things needing attention" category — but the cards should feel like ARIA has *already thought about them*, not just forwarded a notification. The flood advisory card shouldn't say "Flood Advisory — Open Weather." It should say "Flood advisory active until 2am. Your address isn't in the affected zone. Roads clear for tonight." That's the difference between a UI that surfaces information and one that exercises judgment.

**The chat bar is the escape hatch, not the primary surface.** Most interactions should be zero-input — ARIA anticipated it. The chat bar is for when it didn't, or when you want to redirect it. Think of it less like a search box and more like the override button.

**What to actually build toward (the north star UI):**

```
┌─────────────────────────────┐
│ Good evening, Donovon  9:30 │  ← Greeting with time, no clutter
│ Sunday · Lisle · 34°F Snow  │  ← One line of context
├─────────────────────────────┤
│ ○  Ask ARIA anything...     │  ← Chat bar, prominent but not dominant
├─────────────────────────────┤
│                             │
│  [ARIA-generated brief]     │  ← The "now" surface, scrollable
│                             │     Each item is a judgment, not raw data
│  ⚠ Flood advisory — your   │
│    area isn't affected      │
│                             │
│  📅 Nothing tonight. Ava's  │
│    birthday is in 6 days    │
│                             │
│  🎵 Continue: The Lazarus   │  ← Picked up where you left off
│    Project (Ep. 4)          │
│                             │
├─────────────────────────────┤
│  [Phone] [Messages] [Maps]  │  ← Dock: static or lightly predicted
└─────────────────────────────┘
```

Notice what's *not* there: app grid, folders, widgets as traditionally conceived. Those are the spatial paradigm. In the temporal paradigm, an "app" is just the delivery mechanism for an action ARIA already knows you want.

---

**Practical next steps given where you are**

You don't need to throw out the cards — but change what they *say*. Make ARIA process the raw data before displaying it, not just repackage the notification. That one change will make the difference between "cool widget" and "this thing actually thinks."

The skills/intents system CC helped you build is exactly right as the execution layer. The UI layer above it just needs to catch up philosophically.

You're building something real here. The hard part isn't the code — it's resisting the temptation to make it look like an existing phone UI with an AI bolted on. Stay committed to the temporal paradigm and you'll have something genuinely new.

=================================================

You're hitting on the core rendering problem of AI-native UIs. Let me think through this with you properly.

**The wall-of-text problem is real, but the solution isn't less AI — it's structured output.**

The key insight is that you don't ask the LLM to write a home screen. You ask it to produce a *data structure* that your UI knows how to render. The LLM never touches the layout — it just decides *what* and *why*, and hands off a JSON payload to Android's native rendering engine.

So instead of:

> "Good evening! There's a flood advisory in your area until 2am. Your address isn't in the affected zone so your roads look clear. Ava's birthday is coming up in 6 days, you might want to order something. You were listening to The Lazarus Project earlier..."

The LLM outputs:

```json
{
  "brief": [
    {
      "type": "alert_resolved",
      "icon": "shield_check",
      "headline": "Flood advisory — you're clear",
      "subtext": "Active until 2am, but your area isn't affected",
      "action": null
    },
    {
      "type": "reminder_nudge", 
      "icon": "cake",
      "headline": "Ava's birthday in 6 days",
      "subtext": null,
      "action": { "label": "Order something", "intent": "https://amazon.com/..." }
    },
    {
      "type": "media_resume",
      "icon": "play",
      "headline": "The Lazarus Project · Ep. 4",
      "subtext": "Picked up where you left off",
      "action": { "label": "Resume", "intent": "spotify://..." }
    }
  ]
}
```

Your Android UI then renders each item type with its own native component. No markdown parsing, no HTML, no wall of text. The LLM is making *editorial decisions* (what matters, in what order, what the headline framing should be), and Android is doing *all the rendering*.

---

**On your webpage/markdown idea** — you're not wrong that it's tempting, and actually several "AI OS" concept projects have gone that route (Rabbit R1's UI was basically this). The problem is it always ends up feeling like a browser inside a phone, which is worse of both worlds. The moment you're scrolling an HTML page on your home screen, the magic is broken.

But there's a hybrid worth considering: **Jetpack Compose with a dynamic component registry.** Each JSON `type` in the brief maps to a Composable. You define maybe 8–10 component types upfront:

```kotlin
sealed class BriefItem {
    data class AlertResolved(val headline: String, val subtext: String) : BriefItem()
    data class ReminderNudge(val headline: String, val action: IntentAction?) : BriefItem()
    data class MediaResume(val title: String, val intent: String) : BriefItem()
    data class CalendarEvent(val title: String, val time: String, val action: IntentAction?) : BriefItem()
    data class SuggestedAction(val prompt: String, val intent: String) : BriefItem()
    data class WeatherSummary(val temp: String, val condition: String, val narrative: String) : BriefItem()
    // etc.
}
```

The LLM's job is just to pick from this menu, fill in the text, and order them. Your UI renders each one natively. You get full Android UI fidelity AND LLM-generated editorial judgment. The component library is your "vocabulary" — and you grow it over time as you discover new patterns.

This is actually the right long-term architecture because it's also how you'd eventually build *generated widgets* — the LLM can compose novel layouts from a primitive set without ever touching raw HTML.

**The brief should target 3–5 items max.** If ARIA can't decide what's important enough to show in 5 items, it's not doing its job. Ruthless curation is the feature, not a limitation. Less is genuinely more here — every item should feel like it was worth interrupting you for.

This is a solvable problem and it keeps you entirely in native Android. Want me to sketch out the full JSON schema and Composable registry structure you'd hand to CC?

=================================================

Actually, no — your instinct is right. Let me be precise about where each belongs, because they solve different problems.

**The prediction engine should absolutely be your TFLite model.** What you've described — time patterns, sequential app chains, WiFi context, Android Auto detection, geofence triggers — that's all structured signal data, and a trained model will beat an LLM at it every single time. LLMs are expensive, slow, and probabilistic. A TFLite model doing "what's the probability this user opens McDonald's app in the next 5 minutes given these 12 signals" runs in under 10ms on-device with zero battery impact. An LLM doing the same thing would take seconds and drain the battery. Your prediction pipeline is exactly the right architecture.

**Where the LLM earns its place is one layer above that** — the editorial and language layer. The TFLite model outputs a ranked list of predictions with confidence scores. The LLM's job is to look at that ranked list plus a few other inputs and decide:

- Which of these predicted items actually rises to the level of *showing the user*
- How to frame the ones that do (the headline text, the subtext)
- Whether multiple signals should collapse into one card ("you're near McDonald's AND it's your usual lunch time AND you skipped breakfast" → one card, not three)
- Whether something warrants a proactive suggestion vs. silent background action

So the data flow looks like this:

```
Raw signals (WiFi, time, location, calendar, Auto)
        ↓
TFLite prediction model
        ↓
Ranked list: [(McDonald's, 0.87), (Maps, 0.71), (Spotify, 0.65)...]
        ↓
LLM receives: ranked predictions + context snapshot
        ↓
LLM outputs: structured brief JSON (3-5 items, framed intelligently)
        ↓
Compose UI renders natively
```

The LLM runs *after* the prediction model has already done the heavy lifting. It's not predicting — it's narrating and curating. And critically, because the LLM is only running once per unlock (or once per context change, not continuously), the battery cost is manageable. You're not asking it to score 80 apps — you're asking it to write headlines for 5 things the model already decided were relevant.

**The McDonald's WiFi example is a great one** because it illustrates exactly why you need both. The TFLite model catches the pattern: "user opens McDonald's app when near this SSID." The LLM decides how to surface that: maybe silently (just bump McDonald's to the top of the predicted apps row) vs. a card ("Your usual? McDonald's is nearby") depending on whether there are higher-priority things competing for card space. That editorial judgment — *does this deserve a card or just a quiet promotion* — is where the LLM adds value that a scoring model can't.

**One thing to add to your signal list** that would make the sequential chain detection much more powerful: don't just track "bank then calculator then bank," track the *time delta* between them. If it's always under 90 seconds, that's a workflow — ARIA should probably just open both or surface the calculator proactively *while in the bank app*. That's the kind of insight that eventually pushes you toward the "apps becoming irrelevant" future you mentioned.

Your architecture is sound. TFLite for prediction, LLM for editorial. Keep going.

=================================================

This is the cold start problem, and it's one of the most interesting design challenges in the whole project. Your TFLite model can't solve it alone — because by definition, you can't train on behavior you haven't observed yet. But there are a few layers you can stack to handle it.

**Layer 1: SSID semantic recognition**

SSIDs are surprisingly readable. "McDonald's Free WiFi", "Starbucks", "XFINITY_MCDONALDS_3F2A" — these contain signal. You can run the SSID through a simple classifier or even a regex/keyword list to tag it with a venue category before any user behavior is observed.

For known chains, this is almost trivially solvable:

```kotlin
val knownVenuePatterns = mapOf(
    Regex("mcdonald", IGNORE_CASE) to VenueCategory.FAST_FOOD,
    Regex("starbucks", IGNORE_CASE) to VenueCategory.COFFEE,
    Regex("target", IGNORE_CASE) to VenueCategory.RETAIL,
    Regex("hospital|medical|health|clinic", IGNORE_CASE) to VenueCategory.HEALTHCARE,
    Regex("hilton|marriott|hyatt|holiday.?inn", IGNORE_CASE) to VenueCategory.HOTEL,
    Regex("airport|united|delta|southwest", IGNORE_CASE) to VenueCategory.TRAVEL
)
```

For unknown SSIDs, this is where a small LLM call actually makes sense — but only once, at SSID discovery time, not at prediction time. When ARIA encounters a new SSID, it asks the LLM: "What kind of venue does this SSID likely belong to?" The result gets cached permanently. One LLM call per novel SSID, never repeated.

```kotlin
// Runs once, result stored in Room forever
suspend fun classifyUnknownSsid(ssid: String): VenueCategory {
    val response = llmProvider.complete(
        systemPrompt = "Classify this WiFi network name into a venue category. " +
            "Respond with only one of: FAST_FOOD, COFFEE, RETAIL, HEALTHCARE, " +
            "HOTEL, TRAVEL, OFFICE, EDUCATION, ENTERTAINMENT, UNKNOWN",
        messages = listOf(ChatMessage(Role.USER, "SSID: \"$ssid\""))
    )
    return VenueCategory.valueOf(response.trim())
}
```

**Layer 2: Venue category → app affinity mapping**

Once you have a venue category, you need a default app affinity map — essentially a knowledge base of "what apps are relevant at what kinds of places." This is your cold start prior, and it ships with ARIA:

```kotlin
val venueAppAffinity = mapOf(
    VenueCategory.FAST_FOOD to listOf(
        AppAffinity("com.mcdonalds.app", relevanceIfInstalled = 0.9f),
        AppAffinity("com.starbucks.mobilecard", relevanceIfInstalled = 0.8f),
        AppAffinity("com.chickfila.cfaone", relevanceIfInstalled = 0.85f)
    ),
    VenueCategory.HEALTHCARE to listOf(
        AppAffinity("org.mychart.android", relevanceIfInstalled = 0.9f),
        AppAffinity("com.anthem.android", relevanceIfInstalled = 0.7f),
        AppAffinity("com.cigna.mobile", relevanceIfInstalled = 0.7f),
        AppAffinity("com.aetna.mobile", relevanceIfInstalled = 0.7f),
        AppAffinity("com.unitedhealthcare.member", relevanceIfInstalled = 0.7f)
    ),
    VenueCategory.HOTEL to listOf(
        AppAffinity("com.hilton.android", relevanceIfInstalled = 0.85f),
        AppAffinity("com.marriott.mrt", relevanceIfInstalled = 0.85f),
        AppAffinity("com.ihg.apps.android", relevanceIfInstalled = 0.8f)
    ),
    VenueCategory.TRAVEL to listOf(
        AppAffinity("com.flightaware.flightaware", relevanceIfInstalled = 0.8f),
        AppAffinity("com.united.mobile.android.united", relevanceIfInstalled = 0.85f),
        AppAffinity("com.aa.android", relevanceIfInstalled = 0.85f),
        AppAffinity("com.delta", relevanceIfInstalled = 0.85f)
    )
)
```

Critically: you only surface an app if it's actually installed. An uninstalled app is noise. This filter alone makes the affinity map feel precise rather than presumptuous.

**Layer 3: The harder case — hospital as workplace**

Your hospital example is the most interesting because it requires ARIA to reason about *role context*, not just venue type. "Is this hospital my workplace, or am I here as a patient?"

The signals that disambiguate this are actually available:

```kotlin
fun inferVisitContext(
    ssid: String,
    venueCategory: VenueCategory,
    timeOfDay: TimeBucket,
    dayType: DayType,
    visitFrequency: Int,        // how many times in last 30 days
    averageVisitDuration: Long, // average minutes spent on this network
    calendarEvents: List<CalendarEvent>
): VisitContext {

    // High frequency + long duration + weekday pattern = probably work
    if (visitFrequency > 8 
        && averageVisitDuration > 240 
        && dayType == DayType.WEEKDAY) {
        return VisitContext.LIKELY_WORKPLACE
    }

    // Calendar has appointment-style event = probably patient visit  
    if (calendarEvents.any { it.title.contains(
        Regex("appointment|doctor|dr\\.|checkup", IGNORE_CASE))}) {
        return VisitContext.LIKELY_APPOINTMENT
    }

    // Short visit, infrequent = probably accompanying someone or appointment
    if (visitFrequency < 3 && averageVisitDuration < 120) {
        return VisitContext.LIKELY_VISITOR
    }

    return VisitContext.UNKNOWN
}
```

Then the app affinity branches on visit context:

```kotlin
val appsByVisitContext = mapOf(
    VisitContext.LIKELY_WORKPLACE to listOf(
        // Suppress MyChart/insurance, surface work tools instead
        AppAffinity("com.slack", 0.8f),
        AppAffinity("com.microsoft.teams", 0.8f)
    ),
    VisitContext.LIKELY_APPOINTMENT to listOf(
        AppAffinity("org.mychart.android", 0.95f),
        AppAffinity("com.anthem.android", 0.8f)  // insurance card
    ),
    VisitContext.LIKELY_VISITOR to listOf(
        AppAffinity("org.mychart.android", 0.7f),
        AppAffinity("com.cafeteria.app", 0.5f)   // hospital cafeteria if installed
    )
)
```

**Layer 4: How TFLite learns over the cold start baseline**

The affinity map is your prior. TFLite's job is to update that prior based on observed behavior. Formally, this is Bayesian updating — you start with a reasonable belief about what apps matter at a McDonald's, and every time the user actually opens or ignores an app at that venue, the model updates its confidence.

In practice you implement this as a feature in your training data:

```
features: [ssid_category=FAST_FOOD, time=LUNCH, user_opened_mcdonalds_app_here=0, visits_to_this_ssid=3]
label: mcdonalds_app_opened = false
```

After enough observations, the model learns that *this specific user* never opens the McDonald's app even at McDonald's — maybe they always use Apple Pay instead. The cold start prior gets overridden by personal behavior. The default affinity map stops mattering once you have real signal.

**The practical upshot**

You end up with a layered system where cold start is handled by knowledge (SSID classification + venue affinity map), and that knowledge gracefully yields to personal observation as the TFLite model accumulates data. The LLM is involved exactly once per novel SSID, then never again for that location. Everything else is on-device, fast, and private.

The hospital disambiguation is the crown jewel of this approach — using visit *pattern* (frequency, duration, day-of-week) to infer role context without ever asking the user. That's the kind of inference that makes ARIA feel like it actually understands your life rather than just counting app opens.

=================================================

This is the right instinct and it's actually the architectural leap that separates ARIA from everything else out there. Intents get you *to* apps. MCP gets you *into* them. Let me think through this properly.

**The core idea is sound, but there's an Android-specific wrinkle**

MCP servers are typically HTTP/SSE services. On a phone that means either:
1. The MCP server runs remotely (your home server, a cloud endpoint) and ARIA calls it over the network
2. The MCP server runs locally on the phone itself (feasible for simple ones, harder for complex ones)
3. ARIA acts as an MCP *client* that connects to servers wherever they live

Option 3 is the right framing. ARIA isn't hosting MCP servers — it's a universal MCP client that discovers and connects to relevant servers based on what apps are installed and what context is active. Your Proxmox/Ollama setup at home becomes a natural MCP server host for home-network-available capabilities.

**The discovery problem is interesting**

The elegant solution is a registry — a simple mapping of "if this app is installed, this MCP server endpoint might be available." You ship ARIA with a community-maintained registry (think the ClawHub angle you mentioned), and the system checks at install-scan time which servers are relevant:

```
com.spotify.music → spotify MCP server (official or community)
com.google.android.calendar → gcal MCP server  
com.chase.sig.android → no MCP server known
com.philips.lighting.hue → hue MCP server
com.nest.android → nest MCP server
```

When ARIA detects Spotify is installed and a Spotify MCP server is configured or auto-discoverable, it gains the ability to do things like "add this to my workout playlist" directly from a home screen card — not just "open Spotify."

**Where it gets really powerful: home server as MCP hub**

Given your setup specifically, you already have Proxmox running Home Assistant, Ollama, and various services. That becomes ARIA's most capable MCP endpoint when on home WiFi. Home Assistant's MCP server integration means ARIA on your phone can toggle lights, check if the garage door is open, or surface "you left the back door unlocked" as a home screen card — all without opening the Home Assistant app at all. That's the "UI going away" moment you described earlier.

**The script/automation angle**

This is where a skills library like ClawHub becomes the extensibility story. Rather than ARIA shipping with hardcoded integrations, power users (you, eventually other developers) publish MCP-backed skill packages:

```
skill: "morning-briefing"
  - fetches calendar via gcal MCP
  - checks home assistant for overnight alerts  
  - pulls weather
  - synthesizes into a brief via LLM
  - renders as a single BriefItem card
```

The skill is just configuration + an MCP server endpoint. ARIA executes it. Users install skills like apps. This is actually a better story than what Rabbit R1 tried to build because it's open, composable, and runs on hardware people already own.

**The one thing to nail architecturally before building this**

You need a clean separation between *discovery* (what MCP servers are available right now given network context), *capability resolution* (what can each server do), and *execution* (actually calling a tool). The mistake to avoid is tightly coupling these — you want ARIA to degrade gracefully when a server is unreachable rather than breaking the whole home screen.

Something like:

```
MCP Registry (static, ships with ARIA, community-updated)
    ↓
MCP Discovery (runtime: which servers are reachable right now?)
    ↓  
Capability Cache (what tools does each reachable server expose?)
    ↓
Tool Execution (LLM decides which tool to call, ARIA executes)
```

The capability cache is important — you don't want ARIA querying every MCP server on every unlock. It polls reachable servers periodically (or on network change) and caches the tool manifest locally.

---

Want me to write the companion doc for this — `ARIA_mcp_layer.md` — at the same level of detail as the intelligence layer doc, ready to hand to CC?