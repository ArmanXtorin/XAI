# XAI Cloud – private Workers AI prototype

This service has been deployed from GitHub to a Cloudflare Worker (first successful build reported by the project owner on 2026-10-31). **A successful deployment does not prove AI inference works.** The latest code uses Cloudflare Workers AI, no Groq account or provider API key needed.

## Architecture
- POST `/v1/chat/completions` with `{"messages":[{"role":"user","content":"Hello"}]}`
- Cloudflare Workers AI binding `AI` runs `@cf/meta/llama-3.1-8b-instruct-fast` (subject to model availability, terms, and free quota)
- Replies in OpenAI-compatible `choices[0].message.content` format.
- Rate limiter binding `RATE_LIMITER` (approx. 10 requests/minute per client IP **per Cloudflare location**), strict payload limits, and generic error responses.
- No provider keys in the repository or APK.

## Deploy and private testing
1. GitHub repo: `ArmanXtorin/XAI`. Cloudflare Worker root directory: `/backend/cloudflare-worker`. Deploy command: `npx wrangler deploy`.
2. Enable **Cloudflare Access > All traffic**, with **Cloudflare account** authentication policy. Do not disable this protection just to connect an Android app.
3. Verify the new production deployment succeeded after the AI binding change.
4. In Cloudflare Dashboard inspect Worker > Settings > Bindings to verify `AI` and `RATE_LIMITER`. Cloudflare may require separate Workers AI enablement/terms.
5. Test the JSON API using an authenticated request, e.g. from a secure development environment after an authorized Cloudflare Access session.
6. Keep monitoring Workers AI neuron consumption. Cloudflare Workers AI free plan has a daily allowance, not unlimited inference.

## Critical before public launch
- Cloudflare Access in All traffic mode protects the private beta **but the Android app cannot simply POST to it** without an appropriate login/auth flow.
- **Do not bundle a shared Cloudflare Access service token or API key in an APK.** Any static credential in a public APK can be copied.
- Build per-user access, abuse protection, global daily quotas and appropriate authentication before allowing anonymous public traffic.
- The Cloudflare rate limiting binding is approximate and per location; **not** a strict global spend guard.
- The Android app still defaults to optional direct Groq integration; it is NOT automatically connected to this backend. A zero-setup public release remains unfinished.
- Usage is subject to Cloudflare quota, model availability and privacy terms. Review applicable model license and attribution.

This repo does not itself manage the user's Cloudflare account, secrets, or Access configuration.
