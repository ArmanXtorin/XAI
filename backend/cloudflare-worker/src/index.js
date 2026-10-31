// XAI Cloud prototype: Cloudflare-native model, no third-party API key.
// Keep Cloudflare Access set to ALL traffic until public abuse controls exist.
const MODEL = "@cf/meta/llama-3.1-8b-instruct-fast";
const HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};
function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...HEADERS, ...extra },
  });
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/v1/chat/completions" || request.method !== "POST") {
      return json({ error: "Not found" }, 404);
    }
    // Fail closed when inference or rate limiting bindings are missing.
    if (!env.AI || !env.RATE_LIMITER) {
      return json({ error: "XAI Cloud is not configured" }, 503);
    }
    const ip = request.headers.get("CF-Connecting-IP");
    if (!ip) return json({ error: "Client identity unavailable" }, 403);
    try {
      const result = await env.RATE_LIMITER.limit({ key: ip });
      if (!result.success) {
        return json({ error: "Rate limit reached. Retry later." }, 429, { "Retry-After": "60" });
      }
    } catch (_) {
      return json({ error: "Rate limiting unavailable" }, 503);
    }

    // Enforce actual request size: Content-Length may be missing or incorrect.
    let raw;
    try {
      if (Number(request.headers.get("Content-Length") || 0) > 32000)
        return json({ error: "Request too large" }, 413);
      raw = await request.text();
      if (raw.length > 32000) return json({ error: "Request too large" }, 413);
    } catch (_) {
      return json({ error: "Unable to read request" }, 400);
    }

    let payload;
    try { payload = JSON.parse(raw); }
    catch (_) { return json({ error: "Invalid JSON" }, 400); }
    if (!payload || !Array.isArray(payload.messages) ||
        payload.messages.length < 1 || payload.messages.length > 24) {
      return json({ error: "Expected 1-24 chat messages" }, 400);
    }
    const messages = [];
    for (const item of payload.messages) {
      if (!item || !["system", "user", "assistant"].includes(item.role) ||
          typeof item.content !== "string" || !item.content.trim() ||
          item.content.length > 6000) {
        return json({ error: "Invalid chat message" }, 400);
      }
      messages.push({ role: item.role, content: item.content });
    }
    if (!messages.some((m) => m.role === "user")) {
      return json({ error: "At least one user message is required" }, 400);
    }

    try {
      const result = await env.AI.run(MODEL, {
        messages,
        max_tokens: 450,
        temperature: 0.7,
      });
      const content = result?.response;
      if (typeof content !== "string" || !content.trim()) {
        return json({ error: "AI produced no response" }, 502);
      }
      // OpenAI-compatible response for XAI Android Brain.kt
      return json({
        model: MODEL,
        choices: [{ message: { role: "assistant", content } }],
      });
    } catch (_) {
      // Do not reveal internal platform errors or credentials.
      return json({ error: "AI unavailable or free quota reached" }, 503);
    }
  },
};
