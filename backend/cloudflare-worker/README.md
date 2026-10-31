# XAI Cloud (Cloudflare Worker prototype)

This is a **prototype, not a deployed service**. It proxies OpenAI-compatible chat requests to a hosted model without exposing the provider key in the Android APK.

## Setup
1. Create a Cloudflare Workers project and deploy `src/index.js`.
2. Add `GROQ_API_KEY` as a **secret** in Worker settings (never commit it). Optionally set `GROQ_MODEL`.
3. **Before publishing the endpoint**, configure Cloudflare WAF/rate-limiting rules, daily request budgets and provider spending caps, plus monitoring. The current code does not enforce per-user quotas or authentication and MUST NOT be publicly exposed without abuse controls.
4. Test `POST /v1/chat/completions` with JSON `{"messages":[{"role":"user","content":"Hello"}]}`.
5. Only after production protections and deployment, configure the Android app to point at `https://YOUR-WORKER-DOMAIN/v1/chat/completions`. The Android app currently requires a provider key to enable its online branch, so the zero-config backend integration is **not complete**.

No server, account, or API credential has been created by this commit. Provider quotas and availability are external dependencies. Do not claim this is a free unlimited service.
