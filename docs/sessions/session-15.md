# Session 15: LiteRT-LM On-Device LLM Provider

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

## Session 15 Deliverables

**Goal**: True on-device LLM — no API key, no internet, full privacy. Gemma3-1B runs locally.

- `llm/LiteRtLmProvider.kt` — `LlmProvider` implementation using LiteRT-LM Kotlin Flow API
- `llm/LiteRtModelManager.kt` — model file lifecycle: download, verify, path lookup
- `scheduler/ModelDownloadWorker.kt` — WorkManager task (WiFi + charging) to fetch model
- `llm/LlmModule.kt` — add `LiteRtLmProvider` binding
- `llm/LlmProviderManager.kt` — add `LITERT` to provider enum + warm-up call
- `scheduler/NightlyPredictionWorker.kt` — call `liteRtLmProvider.warmUp()` during charging window
- Settings screen addition: "On-device model" section (download status + model selector)
- `lawnchair/AndroidManifest.xml` — GPU native lib dependency declaration

**Gradle dependencies** (verify exact coordinates on Google Maven before adding):
```toml
# gradle/libs.versions.toml
litert-lm = "0.1.0"       # com.google.ai.edge.litert:litert-lm
litert-gpu = "1.4.0"      # com.google.ai.edge.litert:litert-gpu-jni (GPU backend)
```
Check https://maven.google.com for the latest `com.google.ai.edge.litert` coordinates before coding.

---

## Spec: LiteRT-LM Overview

LiteRT-LM is Google's on-device LLM inference SDK (Kotlin API marked Stable).

- **Engine** — one-time init per model file, must run on a background thread (~10s startup)
- **Conversation** — message exchange; returns `Flow<String>` (recommended for coroutines)
- **Backends**: CPU (all devices), GPU (most Android), NPU (Qualcomm/MediaTek)
- **Tool use**: `@Tool`/`@ToolParam` annotations OR OpenAPI spec
- **Models**: Gemma3-1B (~1 GB), Gemma3-4B (~4 GB), Phi, Qwen — `.litertlm` format
- **Performance**: Gemma3-1B → 1,876 tok/s GPU on Samsung S24 Ultra
- Both `Engine` and `Conversation` implement `AutoCloseable`

Default model for ARIA: **Gemma3-1B** (fastest, fits comfortably on modern Android devices).

---

## Spec: LiteRtModelManager

```kotlin
@Singleton
class LiteRtModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_FILENAME = "gemma3-1b.litertlm"
        // Actual download URL — verify before shipping (Google AI Edge / Kaggle Models)
        const val MODEL_DOWNLOAD_URL = "https://kaggle.com/models/google/gemma-3/tfLite/gemma3-1b-it-int4/1/download"
    }

    val modelFile: File get() = File(context.filesDir, "litert_models/$MODEL_FILENAME")

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0

    val modelPath: String get() = modelFile.absolutePath
}
```

Model download is handled by `ModelDownloadWorker` (separate from inference). Never download inline with user interaction.

---

## Spec: LiteRtLmProvider

```kotlin
@Singleton
class LiteRtLmProvider @Inject constructor(
    private val modelManager: LiteRtModelManager
) : LlmProvider {

    private var engine: Engine? = null

    // Called by NightlyPredictionWorker during charging window — NOT at unlock time
    // Engine init can take up to 10 seconds; keep alive as singleton
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!modelManager.isModelDownloaded() || engine != null) return@withContext
        engine = Engine.createFromFile(modelManager.modelPath)
        engine!!.initialize()
    }

    fun isReady(): Boolean = engine != null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: error("LiteRT engine not initialized — call warmUp() first")
        val config = ConversationConfig(systemInstructions = systemPrompt)
        eng.createConversation(config).use { conv ->
            val sb = StringBuilder()
            val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
            conv.sendMessageAsync(messages.last().content, object : MessageCallback {
                override fun onPartialResponse(text: String) { sb.append(text) }
                override fun onCompleted(response: GenerationResult) { latch.complete(Unit) }
                override fun onError(error: Throwable) { latch.completeExceptionally(error) }
            })
            latch.await()
            sb.toString()
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>
    ): Flow<String> = flow {
        val eng = engine ?: error("LiteRT engine not initialized")
        val config = ConversationConfig(systemInstructions = systemPrompt)
        eng.createConversation(config).use { conv ->
            conv.sendMessageAsFlow(messages.last().content).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    // completeWithTools: use LiteRT-LM @Tool annotations or OpenAPI spec
    // For now, delegate to complete() with tool definitions embedded in systemPrompt
    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): LlmResult = LlmResult.Text(complete(systemPrompt, messages))
}
```

---

## Spec: ModelDownloadWorker

```kotlin
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val modelManager: LiteRtModelManager
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (modelManager.isModelDownloaded()) return Result.success()

        return try {
            modelManager.modelFile.parentFile?.mkdirs()
            // Download model file with OkHttp streaming write to modelFile
            // Show notification progress during download
            downloadModel(LiteRtModelManager.MODEL_DOWNLOAD_URL, modelManager.modelFile)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
                        .setRequiresCharging(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("litert_model_download",
                    ExistingWorkPolicy.KEEP, request)
        }
    }
}
```

---

## Spec: LlmProviderManager changes

Add `LITERT` to the provider enum and route through `LiteRtLmProvider` when selected:

```kotlin
enum class LlmProviderType {
    CLAUDE, OLLAMA, OPENAI_COMPATIBLE, GEMINI, LITERT
}
```

In `LlmProviderManager.getActiveProvider()`: if `LITERT` selected but `!liteRtProvider.isReady()`, fall back to the previously configured remote provider and log a warning. Never show an error to the user — silent fallback only.

---

## Warm-up Strategy (battery-first)

`NightlyPredictionWorker` already runs during charging + idle. Add one call:

```kotlin
// In NightlyPredictionWorker.doWork(), after prediction work:
liteRtLmProvider.warmUp()   // no-op if model not downloaded or already warm
```

Engine lifecycle:
- Warmed up during nightly window (background, charging)
- Kept alive as `@Singleton` for the process lifetime
- Closed by Android when process is killed — no explicit shutdown needed
- **Do NOT initialize at unlock time** — 10s init blocks Brief generation

---

## Settings UI Addition

In the LLM settings screen, add an "On-device model" section:

- Status: "Not downloaded" / "Downloading…" / "Ready (Gemma3-1B)"
- Button: "Download on-device model" (triggers `ModelDownloadWorker.enqueue()`)
- Note: "~1 GB · Wi-Fi + charging required · No internet needed after download"
- Model selector (future): Gemma3-1B / Gemma3-4B

---

## AndroidManifest addition (GPU backend)

```xml
<!-- Required for LiteRT GPU acceleration -->
<uses-native-library
    android:name="libOpenCL.so"
    android:required="false" />
```

Add inside `<application>` in `lawnchair/AndroidManifest.xml`.
