# LLM Provider Setup

ARIA's AI features require an LLM provider. This guide covers every supported option.

## Provider Comparison

| Provider | Cost | Privacy | Streaming | Tool Use | Setup Difficulty |
|----------|------|---------|-----------|----------|-----------------|
| **Gemini** (Google AI Studio) | Free tier available | Data sent to Google | Yes | Yes | Easy |
| **Claude** (API key) | Per-token | Data sent to Anthropic | Yes | Yes | Easy |
| **Claude** (OAuth) | Included with Pro/Max subscription | Data sent to Anthropic | Yes | Yes | Medium |
| **Ollama** | Free (self-hosted) | Stays on your network | Yes | Partial | Medium |
| **OpenRouter** | Per-token (varies by model) | Data sent to provider | Yes | Yes | Easy |
| **OpenAI-compatible** | Varies | Varies | Yes | Yes | Easy |
| **On-device (LiteRT)** | Free | Fully local | Yes | No | Easy (limited capability) |

## Quick Start: Gemini (Free Tier)

The easiest way to get started. Google AI Studio offers a generous free tier.

1. Go to [Google AI Studio](https://aistudio.google.com/apikey) and sign in with your Google account.
2. Click **Create API key** and copy it.
3. In ARIA, open **Settings > ARIA > LLM Provider**.
4. Select **Gemini**.
5. Paste your API key.
6. The default model is `gemini-2.5-flash` — this works well for most use cases.
7. Tap **Test Connection** to verify.

## Claude (API Key)

Best for high-quality reasoning. Requires an Anthropic account with API access.

1. Go to [console.anthropic.com](https://console.anthropic.com/) and create an account.
2. Navigate to **API Keys** and create a new key.
3. In ARIA, open **Settings > ARIA > LLM Provider**.
4. Select **Claude (API Key)**.
5. Paste your API key.
6. The default model is `claude-sonnet-4-20250514`.
7. Tap **Test Connection** to verify.

**Pricing:** Per-token. See [Anthropic pricing](https://www.anthropic.com/pricing) for current rates.

## Claude (OAuth)

Use your existing Claude Pro or Max subscription — no separate API key needed.

### QR Pairing Flow

1. On your computer, go to [claude.ai](https://claude.ai) and sign in.
2. In ARIA, open **Settings > ARIA > LLM Provider**.
3. Select **Claude (OAuth)**.
4. ARIA displays a QR code.
5. Scan the QR code with your computer's camera (or navigate to the displayed URL).
6. Authorize ARIA on the Claude website.
7. ARIA receives an OAuth token and refresh token automatically.

The token refreshes automatically — you shouldn't need to re-pair unless you revoke access.

## Ollama

Self-hosted LLM inference. Free, private, and runs on your own hardware.

**Important:** Ollama runs on a server (your PC, a home server, etc.), not on the phone itself. Your phone connects to it over your network.

### Setup

1. Install Ollama on your server: [ollama.com/download](https://ollama.com/download)
2. Pull a model: `ollama pull qwen2.5:7b` (or any model you prefer)
3. Start the Ollama server: `ollama serve`
4. Find your server's IP address on your local network (e.g., `192.168.1.100`).
5. In ARIA, open **Settings > ARIA > LLM Provider**.
6. Select **Ollama**.
7. Enter the server URL: `http://192.168.1.100:11434`
8. Enter the model name (e.g., `qwen2.5:7b`).
9. Tap **Test Connection** to verify.

### Authentication

If your Ollama server requires authentication:
- **HTTP Basic:** Enter username and password in the auth fields.
- **Bearer token:** Enter the token in the bearer token field.

### Recommended Models

| Model | Size | Quality | Speed |
|-------|------|---------|-------|
| `qwen2.5:7b` | 4.7 GB | Good | Fast |
| `llama3.1:8b` | 4.7 GB | Good | Fast |
| `qwen2.5:14b` | 9 GB | Better | Medium |
| `llama3.1:70b` | 40 GB | Best | Slow (needs GPU) |

## OpenRouter

Access many models through a single API. Pay-per-token with model-specific pricing.

1. Go to [openrouter.ai](https://openrouter.ai/) and create an account.
2. Navigate to **Keys** and create a new API key.
3. In ARIA, open **Settings > ARIA > LLM Provider**.
4. Select **OpenRouter**.
5. Paste your API key.
6. The default model is `anthropic/claude-sonnet-4` — you can change it to any model on OpenRouter.
7. Tap **Test Connection** to verify.

## OpenAI-Compatible

Works with any endpoint that implements the OpenAI chat completions API. This includes OpenAI itself, Azure OpenAI, local servers (LM Studio, text-generation-webui), and many other services.

1. In ARIA, open **Settings > ARIA > LLM Provider**.
2. Select **OpenAI Compatible**.
3. Enter the **Base URL** (e.g., `https://api.openai.com/v1` for OpenAI, or your custom endpoint).
4. Enter your **API key**.
5. Enter the **model name** (e.g., `gpt-4o`).
6. Tap **Test Connection** to verify.

### Common Endpoints

| Service | Base URL | Model Example |
|---------|----------|---------------|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o` |
| Azure OpenAI | `https://YOUR-RESOURCE.openai.azure.com/openai/deployments/YOUR-DEPLOYMENT` | `gpt-4o` |
| LM Studio | `http://YOUR-IP:1234/v1` | (auto-detected) |

## On-Device (LiteRT)

Run a small model directly on your phone. No network needed, fully private. Limited capability compared to cloud providers.

1. In ARIA, open **Settings > ARIA > LLM Provider**.
2. Select **On-device (LiteRT)**.
3. Choose a model:
   - **Gemma 1B** — smallest, fastest
   - **Phi 3.5 Mini** — better quality, slower
4. The model downloads on first use (~1-2 GB depending on model).
5. First inference takes ~10 seconds for GPU warmup. Subsequent responses are faster.

### Limitations

- No tool use — skills and some chat features won't work.
- Lower quality responses compared to cloud models.
- Uses significant device memory and battery during inference.
- Best as a fallback when no network is available. ARIA can fall back to a previously configured remote provider when online.

## Privacy Notes

| Provider | What's sent | What stays local |
|----------|-------------|------------------|
| **Cloud providers** (Claude, Gemini, OpenRouter, OpenAI) | Chat messages, context for Brief curation, skill execution prompts | Usage data, app predictions, rules, all stored data |
| **Ollama** | Same as cloud, but to your own server | Same as cloud |
| **LiteRT** | Nothing | Everything |

ARIA never sends your raw usage data, app list, or personal information to any provider. The LLM receives only the context needed for the specific task (e.g., "the user has a meeting in 15 minutes" rather than raw calendar data).
