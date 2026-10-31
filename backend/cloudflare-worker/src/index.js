export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
    };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    if (new URL(request.url).pathname !== "/v1/chat/completions" || request.method !== "POST")
      return json({ error: "Not found" }, 404, cors);
    if (!env.GROQ_API_KEY) return json({ error: "Backend not configured" }, 503, cors);
    const length = Number(request.headers.get("content-length") || 0);
    if (length > 32000) return json({ error: "Request too large" }, 413, cors);
    let body;
    try { body = await request.json(); } catch { return json({ error: "Invalid JSON" }, 400, cors); }
    if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 24)
      return json({ error: "Invalid message history" }, 400, cors);
    const messages = [];
    for (const msg of body.messages) {
      if (!msg || !["system", "user", "assistant"].includes(msg.role) || typeof msg.content !== "string")
        return json({ error: "Invalid message" }, 400, cors);
      if (msg.content.length > 6000) return json({ error: "Message too long" }, 413, cors);
      messages.push({ role: msg.role, content: msg.content });
    }
    // Important: deploy ONLY after setting Cloudflare WAF rate limits and usage quotas.
    // An unauthenticated public endpoint can exhaust provider credits.
    const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { "Authorization": "Bearer " + env.GROQ_API_KEY, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: env.GROQ_MODEL || "openai/gpt-oss-20b",
        messages,
        max_tokens: 600,
        temperature: 0.7,
      }),
      signal: AbortSignal.timeout(25000),
    });
    if (!response.ok) return json({ error: response.status === 429 ? "AI busy; try again later" : "AI provider unavailable" }, response.status === 429 ? 429 : 502, cors);
    const data = await response.json();
    return json({ choices: [{ message: { role: "assistant", content: data.choices?.[0]?.message?.content || "" } }] }, 200, cors);
  },
};
function json(data, status, headers) {
  return new Response(JSON.stringify(data), { status, headers: { ...headers, "Content-Type": "application/json", "Cache-Control": "no-store" } });
}
