# ARIA Manual Testing Guide

> **Audience:** Developers and contributors. For general setup instructions, see [Getting Started](getting-started.md).

Everything below assumes ARIA is already installed on your phone. Your ADB path is `~/Library/Android/sdk/platform-tools/adb` — it's not on your system PATH, so every `adb` command below uses the full path. Your phone must be plugged in via USB (or connected via wireless debugging).

> **What is ADB?** It's a command-line tool that lets your Mac talk to your Android phone. It comes with Android Studio. You run commands in Terminal on your Mac, and they execute on your phone.

> **What is logcat?** It's the phone's live log stream — every app prints messages there. We use it to verify ARIA's background code is running. You read it from Terminal on your Mac via ADB.

---

## Pre-flight: Verify your phone is connected

Open **Terminal** on your Mac and run:

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

You should see something like:

```
List of devices attached
XXXXXXXX    device
```

If it says `unauthorized`, unlock your phone and tap "Allow USB debugging" on the popup. If nothing shows up, try a different USB cable or enable USB debugging in Settings → Developer Options.

---

## Test 1: Data Pipeline — Is ARIA collecting app usage?

**What this tests:** The background worker that records which apps you open and when.

**Step 1 — Grant the usage stats permission** (one-time setup):

```bash
~/Library/Android/sdk/platform-tools/adb shell appops set com.aria.launcher.play.debug android:get_usage_stats allow
```

No output means success.

**Step 2 — Trigger a collection manually:**

Open ARIA on your phone (just go to the home screen — ARIA is your launcher). Then use your phone normally for a minute — open a few apps, switch between them.

**Step 3 — Check the logs:**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.UsageWorker" "ARIA.UsageCollector" -d
```

> `-s` filters to only ARIA's log tags. `-d` dumps existing logs and exits (instead of streaming forever).

**What you should see:**

```
ARIA.UsageWorker: Usage collection started
ARIA.UsageCollector: Collected 47 events in window [...]
ARIA.UsageWorker: Usage collection completed successfully
```

**What's wrong if:**
- "No permission" → Step 1 didn't work. Re-run the `appops` command.
- No output at all → The worker hasn't run yet. It runs every 4 hours automatically. You can force it by killing and reopening ARIA (swipe it away from recents, then tap your home button).
- "0 events" → You haven't used any apps since the last collection. Use some apps and try again.

---

## Test 2: Context Signals — Charging, WiFi, Activity

**What this tests:** ARIA detects whether your phone is charging, what WiFi you're on, and whether you're walking/driving/still.

### 2a — Charging detection

Plug your phone into a charger (or your Mac's USB counts). Then check:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" -d | grep -i charg
```

**What you should see:** Something like `Charging state changed: true`

Unplug and check again — should say `false`.

### 2b — WiFi SSID

> This requires the location permission. Grant it on your phone: long-press ARIA icon → App Info → Permissions → Location → Allow all the time.

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" -d | grep -i wifi
```

**What you should see:** Your WiFi network name, like `WiFi SSID: MyHomeNetwork`

**What's wrong if:** It says `null` → Location permission wasn't granted, or WiFi is off.

### 2c — Activity recognition

> Requires the activity recognition permission: ARIA app info → Permissions → Physical activity → Allow.

Walk around with your phone for 30 seconds, then:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" -d | grep -i activity
```

**What you should see:** Something like `Activity detected: ON_FOOT` or `STILL`

**What's wrong if:** Says `null` → Permission not granted, or Google Play Services isn't delivering activity updates (can be slow — wait a few minutes).

---

## Test 3: Prediction Engine — Are predictions being generated?

**What this tests:** The nightly job that analyzes your usage patterns and predicts which apps you'll want next.

The prediction worker normally runs at ~3 AM while charging. To test it now:

**Step 1 — Check if predictions exist already:**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.Predictions" -d
```

**Step 2 — If no output, force a prediction run:**

You need at least a day of usage data first. If you just installed ARIA, use your phone normally for a day and come back.

To check how many events have been collected:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.UsageCollector" -d | tail -5
```

Look for "Collected X events" — you want at least 50+ for meaningful predictions.

**What you should see after predictions run:** Log entries showing app package names with scores like:

```
ARIA.Predictions: com.google.android.gm → 0.85
ARIA.Predictions: com.slack → 0.72
```

---

## Test 4: LLM Provider — Can ARIA talk to an AI?

**What this tests:** The connection between ARIA and whichever AI service you've configured (Claude, Ollama, OpenAI-compatible).

> This test requires the chat UI from Session 7+. If the chat interface isn't built yet, skip this test.

**For Claude (API key method):**
1. Open ARIA settings → AI Provider
2. Select "Claude"
3. Enter your Anthropic API key
4. Go back to the home screen and open the ARIA chat
5. Type "Hello" and send

**What you should see:** A response from Claude within a few seconds.

**What's wrong if:**
- "Connection failed" → Check your API key, check your phone has internet
- Timeout → Claude's servers might be slow, or your network is blocking the connection

**Check the logs for details:**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.LLM" -d | tail -20
```

---

## Test 5: Permissions — Graceful degradation

**What this tests:** ARIA should work even when you deny permissions — it just has less context.

**How to test:**

1. Go to your phone's Settings → Apps → ARIA → Permissions
2. Deny **all** permissions (Location, Physical Activity, Calendar)
3. Go back to your home screen
4. Use your phone normally for a few minutes
5. Check the logs:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" "ARIA.UsageCollector" -d | tail -10
```

**What you should see:** ARIA still runs, still collects usage events, but WiFi/activity signals show as `null`. No crashes.

**What's wrong if:** You see `FATAL EXCEPTION` or `SecurityException` in the logs — that means ARIA is crashing instead of handling missing permissions gracefully.

To check for any ARIA crashes:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "AndroidRuntime" -d | grep -A 5 "com.aria"
```

---

## Test 6: Room Database — Does data survive app restarts?

**What this tests:** Your usage data and predictions persist when the app restarts.

**Step 1 — Confirm data exists:**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.UsageCollector" -d | tail -3
```

Note the number of events.

**Step 2 — Force-stop ARIA:**

```bash
~/Library/Android/sdk/platform-tools/adb shell am force-stop com.aria.launcher.play.debug
```

Your home screen will briefly reload (since ARIA is your launcher).

**Step 3 — Trigger another collection and compare:**

Use a couple apps, then:

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.UsageCollector" -d | tail -3
```

The event count should be **higher than before** (not reset to zero). If it reset, the database didn't persist.

---

## Test 7: Fresh Install — Clean slate

**What this tests:** ARIA installs cleanly with no prior data.

**Step 1 — Uninstall:**

```bash
~/Library/Android/sdk/platform-tools/adb uninstall com.aria.launcher.play.debug
```

**Step 2 — Reinstall:**

Build and install:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew installLawnWithQuickstepPlayDebug
```

**Step 3 — Set as default launcher:**

Your phone should prompt you to pick a launcher. Choose ARIA. If it doesn't prompt, go to Settings → Apps → Default apps → Home app → ARIA.

**Step 4 — Grant usage stats permission:**

```bash
~/Library/Android/sdk/platform-tools/adb shell appops set com.aria.launcher.play.debug android:get_usage_stats allow
```

**Step 5 — Verify no crashes after 30 seconds:**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s "AndroidRuntime" -d | grep "com.aria"
```

Should return nothing (no crashes).

---

## Quick Reference: Useful Log Commands

| What | Command |
|------|---------|
| **All ARIA logs** | `~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" "ARIA.UsageWorker" "ARIA.UsageCollector" "ARIA.Predictions" "ARIA.LLM" -d` |
| **Live ARIA logs** (streams until Ctrl+C) | `~/Library/Android/sdk/platform-tools/adb logcat -s "ARIA.ContextSignals" "ARIA.UsageWorker" "ARIA.UsageCollector" "ARIA.Predictions" "ARIA.LLM"` |
| **Any crashes** | `~/Library/Android/sdk/platform-tools/adb logcat -s "AndroidRuntime" -d` |
| **Clear old logs** (fresh start) | `~/Library/Android/sdk/platform-tools/adb logcat -c` |

> **Tip:** If the log output is overwhelming, add `| tail -20` at the end of any command to see only the last 20 lines.
