# Session 16: Accessibility, Default Launcher Prompt & LLM Onboarding Redesign

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

## Session 16 Deliverables

**Goal**: Make ARIA usable by normal people — accessibility, default launcher prompt, and a complete LLM onboarding redesign that doesn't require understanding what an API key is.

### Part 1: Accessibility Audit & Fixes

- [ ] **`OnboardingPageLayout`** — Replace hardcoded `fontSize = 30.sp` with `MaterialTheme.typography.headlineLarge` (no override). Hardcoded sp values defeat the system font size preference.
- [ ] **All onboarding pages** — Audit every `Text` composable for hardcoded `sp` values. Use `MaterialTheme.typography.*` styles without font size overrides so the system "Display size" and "Font size" settings are respected.
- [ ] **Touch targets** — Every interactive element (buttons, checkboxes, dropdowns) must meet the 48dp minimum touch target. Use `Modifier.defaultMinSize(minHeight = 48.dp)` or Material3's built-in sizing. Audit: `PermissionRow`, dropdown menu items, page indicator dots (currently 8dp — not tappable, but should they be?).
- [ ] **Content descriptions** — Add `contentDescription` to all `Icon` composables that convey meaning (the arrow icon in "Continue" button already has `null` which is correct since the text label covers it). Add `semantics { heading() }` to page titles for screen reader navigation.
- [ ] **Scrollable content** — The LLM setup page is already scrollable (`verticalScroll`), but verify it works when system font size is set to maximum. The `Spacer(56.dp)` at the top of `OnboardingPageLayout` may push content off-screen at large font sizes — make it responsive (e.g., `WindowInsets.statusBars` padding instead of fixed spacer).
- [ ] **Contrast** — Verify `onSurfaceVariant` meets WCAG AA (4.5:1) against `background` in both light and dark themes. Material3 dynamic color should handle this, but verify with the actual theme.
- [ ] **Brief cards & home screen** — Audit `AriaPredictedPanel`, `CardFeed`, and greeting composables for the same issues (hardcoded sp, touch targets, content descriptions).

### Part 2: Default Launcher Prompt

- [ ] **New onboarding page** — Insert a "Set Default Launcher" page after the Welcome page (new page 1, shifting all others forward). This is the single most important setup step — if the user doesn't set ARIA as default, nothing works.
- [ ] **Page content:**
  - Title: "Set as Home"
  - Subtitle: "ARIA replaces your home screen with a context-aware feed. Set it as your default launcher to get started."
  - Button: "Set Default Launcher" → fires `Intent(Settings.ACTION_HOME_SETTINGS)` (opens the system launcher picker)
  - Status indicator: detect whether ARIA is currently the default launcher using `RoleManager.isRoleHeld(RoleManager.ROLE_HOME)` (API 29+) or by checking `resolveActivity` on a `HOME` intent
  - If already default: show green checkmark + "ARIA is your default launcher"
  - "Skip" is the existing Continue button (always available — don't gate progress)
- [ ] **Update `TOTAL_ONBOARDING_PAGES`** from 7 to 8
- [ ] **Update page indices** in `OnboardingWizard` `when` block

### Part 3: LLM Onboarding Redesign

The current LLM setup page shows all three providers stacked vertically with no guidance. Users think they need to fill out all of them, don't know where to get keys, and can't tell what succeeded or failed. This is the full redesign.

#### 3a. Provider Selection — Pick One, Not All

- [ ] **Add an explainer section at the top of the page** — before the user sees any choices, explain what this page is and why it matters. This is critical: most users have never configured an "LLM provider" and have no mental model for what they're being asked to do.

**Explainer content (rendered as styled body text, not a card):**

> **How ARIA's AI works**
>
> ARIA uses AI to understand your patterns, curate your home screen, and chat with you. Here's what powers it:
>
> **On-device model (automatic)** — A small AI model (~1 GB) will download to your phone in the background when you're on Wi-Fi and charging. Once downloaded, it handles most everyday tasks — app predictions, card ranking, and quick questions — with no internet and no account needed.
>
> **Cloud AI (optional, choose below)** — For complex tasks like long conversations, deep analysis, and multi-step actions, ARIA can use a cloud AI service. This requires an API key from one of the providers below. If you don't set one up, ARIA still works — it just uses the less intelligent, on-device model for everything once it downloads.

The explainer must:
- Use `MaterialTheme.typography.bodyMedium` — no tiny text
- Bold the two sub-headings ("On-device model" and "Cloud AI")
- Be part of the scrollable content, above the provider cards
- Not be dismissable — it's structural, not a tooltip

- [ ] **Below the explainer, show the provider chooser.** Show 2–3 cards the user taps to select:

```
┌─────────────────────────────────────────┐
│  ★ Gemini (Free)                        │
│  Google's AI. Free API key required.    │
│  Best for most users.                   │
│  [Get Started →]                        │
├─────────────────────────────────────────┤
│  Claude (Anthropic)                     │
│  Requires a paid API key or Pro sub.    │
│  [Set Up →]                             │
├─────────────────────────────────────────┤
│  ▽ Advanced options                     │  ← collapsed by default
│    Ollama (Self-hosted server)          │
│    OpenAI-compatible endpoint           │
└─────────────────────────────────────────┘
```

- Tapping a card navigates to a provider-specific sub-page (still within onboarding, use internal navigation state — NOT a new Activity page index)
- Only one provider is configured. Clear visual hierarchy: Gemini is the recommended default.
- **Ollama and OpenAI-compatible go under "Advanced options"** — collapsed by default, expandable. These are for power users; showing them to everyone creates confusion.

- [ ] **On-device model status footer** — Below the provider cards (always visible, not inside Advanced), show the current on-device model state:

```
┌─────────────────────────────────────────┐
│  📱 On-device AI                        │
│  Will download automatically (~1 GB)    │
│  Wi-Fi + charging · No setup needed     │
└─────────────────────────────────────────┘
```

This is NOT a selectable provider — it's informational. The on-device model downloads regardless of which cloud provider (if any) the user picks. When the model is already downloaded, show "Ready (Gemma3 1B) · Works offline" instead.

#### 3b. Gemini Sub-Page

- [ ] **"Get a free API key" link** — `AnnotatedString` with a clickable link that opens `https://aistudio.google.com/app/apikey` in the browser. Include a 1-line instruction: "Sign in with your Google account. Your key will be created automatically."
- [ ] **Update `GeminiProvider.AVAILABLE_MODELS`** — the current list has stale preview models:

```kotlin
// Current (stale):
"gemini-flash-latest" to "Gemini Flash (Latest)",
"gemini-2.0-flash" to "Gemini 2.0 Flash",
"gemini-2.5-flash-preview-05-20" to "Gemini 2.5 Flash (Preview)",
"gemini-2.5-pro-preview-05-06" to "Gemini 2.5 Pro (Preview)",

// Updated:
"gemini-2.5-flash" to "Gemini 2.5 Flash (Recommended)",
"gemini-2.0-flash" to "Gemini 2.0 Flash",
"gemini-2.5-pro" to "Gemini 2.5 Pro (Lower free limits)",
```

Default should be `gemini-2.5-flash` — best balance of quality, speed, and free-tier rate limits (10 RPM / 250 RPD / 250K TPM). The Pro model has only 5 RPM / 100 RPD on free tier, which is too restrictive for active use. Drop `gemini-flash-latest` alias (ambiguous — could resolve to any generation).

- [ ] **Update `LlmProviderManager.configureProvider()`** default `modelId` from `"gemini-flash-latest"` to `"gemini-2.5-flash"`
- [ ] **Inline test** — After entering the key and tapping "Save", automatically run the test. Show result inline: green "Connected" or red human-readable error (see 3f).
- [ ] **Model selector** — Keep the dropdown but default to "Gemini 2.5 Flash (Recommended)". Add a brief note: "Free tier · 250 requests/day"

#### 3c. Claude Sub-Page

- [ ] **Explain the two paths clearly:**
  - **Path 1: API Key** — "Go to console.anthropic.com → API Keys → Create. Requires adding billing (pay-per-use, ~$3/MTok for Haiku)."
  - **Path 2: Pro/Team Subscription** — "If you have a Claude Pro subscription ($20/mo), you can share your session with ARIA via QR code. Run `npx aria-token-qr` on your computer, then scan the code."
  - Use a segmented button or tab to switch between the two paths, not show both at once.
- [ ] **QR scan button** should be prominent in the OAuth tab, not a secondary action.

#### 3d. On-Device Model Status

- [ ] **Show download state on the provider chooser** — Below the "Advanced options" section or as a persistent footer:

```
On-device AI: Not yet downloaded
Downloads automatically on Wi-Fi + charging (~1 GB)
```

When downloaded:
```
On-device AI: Ready (Gemma3 1B)
Works without internet · No API key needed
[Use on-device model]
```

- [ ] **Show download state in AriaSettingsPreferences** — The current settings screen already has an on-device section. Add status text that distinguishes: "Waiting for Wi-Fi + charging", "Downloading (43%)", "Ready", "Download failed — tap to retry".

#### 3e. Test Button — Per-Provider, Not Global

- [ ] **Remove the single "Test Connection" button at the bottom.** Replace with per-provider inline testing that runs automatically after saving credentials.
- [ ] **During test**: show a `CircularProgressIndicator` next to the save button.
- [ ] **On success**: green checkmark + "Connected — responses take about 2 seconds" (or similar expectation-setting text).
- [ ] **On failure**: human-readable message (see 3f).

#### 3f. Human-Readable Error Messages

- [ ] **Parse common error responses into plain English.** The current implementation shows raw JSON/exception text truncated to 120 chars. Map known errors:

```kotlin
fun humanizeError(raw: String): String = when {
    "401" in raw || "Unauthorized" in raw ->
        "Invalid API key. Double-check that you copied the full key."
    "403" in raw || "Forbidden" in raw ->
        "This API key doesn't have permission. Check your account at the provider's website."
    "429" in raw || "rate" in raw.lowercase() ->
        "Rate limited — too many requests. Wait a minute and try again."
    "insufficient_quota" in raw || "billing" in raw.lowercase() ->
        "Your account needs billing set up. Visit the provider's billing page."
    "ECONNREFUSED" in raw || "ConnectException" in raw || "connect" in raw.lowercase() ->
        "Can't reach the server. Check the URL and your network connection."
    "timeout" in raw.lowercase() || "SocketTimeoutException" in raw ->
        "Connection timed out. The server may be slow or unreachable."
    "model" in raw.lowercase() && ("not found" in raw.lowercase() || "does not exist" in raw.lowercase()) ->
        "Model not found. It may have been renamed or removed. Try a different model."
    "SSL" in raw || "certificate" in raw.lowercase() ->
        "SSL/certificate error. Check that the server URL uses the correct protocol."
    else -> "Connection failed: ${raw.take(200)}"
}
```

- [ ] **Apply in both onboarding and settings** — anywhere `LlmResult.Error` is displayed.

#### 3g. "Skip" Affordance

- [ ] **Make it obvious that LLM setup is optional.** The subtitle already says "This is optional — ARIA works without it." but reinforce with:
  - The Continue button always works (no gating)
  - A "Skip for now" text button below the provider cards (above the on-device footer), separate from Continue
  - The explainer (3a) already explains that the on-device model handles most tasks without cloud AI — the skip text should reference this: "Skip — the on-device model will handle most tasks once it downloads. You can add a cloud provider later in Settings."

---

## Spec: Updated Onboarding Flow (8 pages)

```
Page 0: Welcome
Page 1: Set Default Launcher  ← NEW
Page 2: Permissions
Page 3: Notification Access
Page 4: LLM Setup (redesigned — provider chooser + sub-pages)
Page 5: WiFi Labels
Page 6: Rules Tutorial
Page 7: Ready
```

---

## Spec: Default Launcher Detection

```kotlin
// In AriaOnboardingActivity or a utility:
fun isDefaultLauncher(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
    }
    // Fallback for API < 29
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName == context.packageName
}
```

To open the launcher picker:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val roleManager = context.getSystemService(RoleManager::class.java)
    val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_HOME)
    // Launch with ActivityResultLauncher
} else {
    context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
}
```

The `RoleManager` approach on API 29+ shows a system bottom sheet asking "Use ARIA as your Home app?" — much better UX than sending the user to the full settings screen.

---

## Spec: LLM Provider Chooser Navigation

Internal to the LLM setup page — NOT new wizard pages. Use a simple state machine:

```kotlin
enum class LlmSetupScreen {
    CHOOSER,       // Provider selection cards
    GEMINI_SETUP,  // API key + model + test
    CLAUDE_SETUP,  // API key tab + OAuth tab + test
    OLLAMA_SETUP,  // Server URL + test
    OPENAI_SETUP,  // URL + key + model + test
}

// In LlmSetupPage:
var currentScreen by remember { mutableStateOf(LlmSetupScreen.CHOOSER) }
```

Each sub-page has a "Back" affordance (top-left or swipe) that returns to `CHOOSER`. The wizard's Continue button always advances to the next onboarding page regardless of which sub-screen is showing.

---

## Spec: Updated GeminiProvider.AVAILABLE_MODELS

```kotlin
companion object {
    private const val DEFAULT_MODEL = "gemini-2.5-flash"

    val AVAILABLE_MODELS = listOf(
        "gemini-2.5-flash" to "Gemini 2.5 Flash (Recommended)",
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-2.5-pro" to "Gemini 2.5 Pro (Lower free limits)",
    )
}
```

**Rationale:**
- `gemini-2.5-flash` — Best free-tier balance: 10 RPM, 250 RPD, 250K TPM, full function calling, 1M context. This is ARIA's default.
- `gemini-2.0-flash` — Fallback if 2.5 has issues. Well-established.
- `gemini-2.5-pro` — Available on free tier but only 5 RPM / 100 RPD. Worth offering for users who want higher quality and accept slower rate limits.
- Dropped: `gemini-flash-latest` (ambiguous alias), preview-dated models (no longer preview).

---

## Accessibility Checklist (verify before declaring session complete)

1. Set device font size to maximum ("Largest" in Android settings) → all onboarding pages remain usable, no text clipping, all buttons reachable by scrolling
2. Set device display size to maximum → same verification
3. Enable TalkBack → navigate through each onboarding page, verify all elements are announced with meaningful descriptions, headings are discoverable via heading navigation gesture
4. Verify all interactive elements have ≥48dp touch targets
5. Verify color contrast in both light and dark themes (use Android's "Color correction" or a manual check)
6. Verify the Brief cards and home screen composables pass the same checks

---

## Dependencies

No new Gradle dependencies. All changes are to existing Compose UI files and `GeminiProvider.kt` constants.

---

## Files Modified

| File | Change |
|------|--------|
| `ui/onboarding/AriaOnboardingActivity.kt` | +1 page (default launcher), update page count to 8, add `RoleManager` launcher request, shift page indices |
| `ui/onboarding/OnboardingPages.kt` | Remove hardcoded `fontSize`/`sp` overrides, use `MaterialTheme.typography` directly, add semantics headings |
| `ui/onboarding/LlmSetupPage.kt` | Full rewrite — provider chooser + sub-pages, per-provider test, human-readable errors, "Get API key" link, skip affordance |
| `llm/GeminiProvider.kt` | Update `AVAILABLE_MODELS` and `DEFAULT_MODEL` |
| `llm/LlmProviderManager.kt` | Update default `modelId` to `gemini-2.5-flash`, add `humanizeError()` helper |
| `ui/AriaSettingsPreferences.kt` | Use `humanizeError()` for test results, update on-device model status text |
| `ui/AriaPredictedPanel.kt` | Accessibility audit — content descriptions, touch targets |
| `ui/composables/CardFeed.kt` | Accessibility audit — content descriptions, touch targets |
| `ui/AriaHomeState.kt` | No changes expected (context-only reference) |
