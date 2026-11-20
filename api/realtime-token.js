// Vercel serverless function: creates short-lived OpenAI Realtime credentials.
// The permanent OpenAI API key is kept only in Vercel environment variables.
export default async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store, max-age=0");
  res.setHeader("Pragma", "no-cache");

  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "method_not_allowed" });
  }

  const apiKey = process.env.OPENAI_API_KEY;
  const accessCode = process.env.DELFINA_ACCESS_CODE;
  if (!apiKey || !accessCode) {
    return res.status(503).json({
      error: "server_not_configured",
      message: "The Delfina credential service is not configured yet."
    });
  }

  const auth = req.headers.authorization || "";
  const suppliedCode = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!suppliedCode || suppliedCode.length !== accessCode.length) {
    return res.status(401).json({ error: "unauthorized" });
  }

  // Constant-time comparison avoids leaking the configured access code by timing.
  let mismatch = 0;
  for (let i = 0; i < accessCode.length; i++) {
    mismatch |= accessCode.charCodeAt(i) ^ suppliedCode.charCodeAt(i);
  }
  if (mismatch !== 0) {
    return res.status(401).json({ error: "unauthorized" });
  }

  try {
    const upstream = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        expires_after: { anchor: "created_at", seconds: 600 },
        session: {
          type: "realtime",
          model: process.env.OPENAI_REALTIME_MODEL || "gpt-realtime",
          instructions:
            "You are Delfina, a capable, warm, natural voice assistant. " +
            "Speak conversationally with a warm, youthful feminine delivery. " +
            "Automatically respond in the language the user speaks, including " +
            "Hindi, Urdu, English, and natural code-switching. Keep spoken replies " +
            "clear and concise unless the user asks for detail. Never claim to have " +
            "performed an action you did not perform.",
          audio: {
            input: {
              transcription: { model: "gpt-4o-mini-transcribe" },
              turn_detection: { type: "server_vad" }
            },
            output: { voice: "marin" }
          }
        }
      })
    });

    const payload = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      const safeMessage =
        typeof payload?.error?.message === "string"
          ? payload.error.message.slice(0, 300)
          : "OpenAI could not create a Realtime session.";
      return res.status(upstream.status).json({
        error: "openai_realtime_error",
        message: safeMessage
      });
    }

    if (!payload.value) {
      return res.status(502).json({ error: "invalid_upstream_response" });
    }

    return res.status(200).json({
      client_secret: payload.value,
      expires_at: payload.expires_at,
      session: payload.session
    });
  } catch {
    return res.status(502).json({
      error: "upstream_unavailable",
      message: "Could not reach OpenAI. Check the server connection and try again."
    });
  }
}
