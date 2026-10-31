// XAI Cloud: text chat plus opt-in image analysis.
// Keep Cloudflare Access on ALL traffic until app authentication and abuse controls exist.
const CHAT_MODEL = "@cf/meta/llama-3.1-8b-instruct-fast";
const VISION_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";
const HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};
const json = (data, status = 200, extra = {}) =>
  new Response(JSON.stringify(data), { status, headers: { ...HEADERS, ...extra } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const chat = url.pathname === "/v1/chat/completions";
    const vision = url.pathname === "/v1/vision/analyze";
    if ((!chat && !vision) || request.method !== "POST") {
      return json({ error: "Not found" }, 404);
    }
    if (!env.AI || !env.RATE_LIMITER) {
      return json({ error: "XAI Cloud is not configured" }, 503);
    }
    // Rate-limit both text and vision before reading a user-supplied image.
    const ip = request.headers.get("CF-Connecting-IP");
    if (!ip) return json({ error: "Client identity unavailable" }, 403);
    try {
      const limit = await env.RATE_LIMITER.limit({ key: ip });
      if (!limit.success) {
        return json({ error: "Rate limit reached. Retry later." }, 429, { "Retry-After": "60" });
      }
    } catch (_) {
      return json({ error: "Rate limiting unavailable" }, 503);
    }

    // Enforce a strict request size ceiling, even when Content-Length is absent.
    const maxBody = vision ? 1_100_000 : 32_000;
    let raw;
    try {
      if (Number(request.headers.get("Content-Length") || 0) > maxBody) {
        return json({ error: "Request too large" }, 413);
      }
      const reader = request.body?.getReader();
      if (!reader) return json({ error: "Empty request" }, 400);
      const chunks = [];
      let total = 0;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > maxBody) {
          reader.cancel().catch(() => {});
          return json({ error: "Request too large" }, 413);
        }
        chunks.push(value);
      }
      const result = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) {
        result.set(chunk, offset);
        offset += chunk.byteLength;
      }
      raw = new TextDecoder().decode(result);
    } catch (_) {
      return json({ error: "Unable to read request" }, 400);
    }

    let payload;
    try { payload = JSON.parse(raw); }
    catch (_) { return json({ error: "Invalid JSON" }, 400); }

    if (vision) {
      if (!payload || typeof payload.question !== "string" ||
          typeof payload.image !== "string" ||
          payload.question.length > 300 || !payload.question.trim() ||
          !/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(payload.image) ||
          payload.image.length > 950_000) {
        return json({ error: "Expected a JPEG data URI and a short question" }, 400);
      }
      try {
        // The model requires accepting Meta's license once per Cloudflare account.
        const result = await env.AI.run(VISION_MODEL, {
          messages: [{ role: "user", content: payload.question }],
          image: payload.image,
          max_tokens: 400,
          temperature: 0.3,
        });
        const answer = result?.response;
        if (typeof answer !== "string" || !answer.trim()) {
          return json({ error: "Vision model returned an empty response" }, 502);
        }
        return json({ model: VISION_MODEL, answer });
      } catch (_) {
        return json({ error: "Vision unavailable; check model license and account quota" }, 503);
      }
    }

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
      const result = await env.AI.run(CHAT_MODEL, {
        messages,
        max_tokens: 450,
        temperature: 0.7,
        stream: false,
      });
      const content = result?.response;
      if (typeof content !== "string" || !content.trim()) {
        return json({ error: "AI produced no response" }, 502);
      }
      return json({ model: CHAT_MODEL, choices: [{ message: { role: "assistant", content } }] });
    } catch (_) {
      return json({ error: "AI unavailable or free quota reached" }, 503);
    }
  },
};
