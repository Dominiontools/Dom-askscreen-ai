/**
 * AskScreen AI - Netlify Serverless Backend Function
 * Receives requests from AskScreen AI Android app, validates payload, enforces rate limits,
 * securely attaches GEMINI_API_KEY from environment variables, and calls Gemini API.
 */

// In-memory rate limiting map: ip -> [timestamps]
const rateLimitMap = new Map();
const RATE_LIMIT_WINDOW_MS = 60 * 1000; // 1 minute
const MAX_REQUESTS_PER_WINDOW = 30;

function isRateLimited(ip) {
  const now = Date.now();
  const timestamps = rateLimitMap.get(ip) || [];
  const validTimestamps = timestamps.filter(t => now - t < RATE_LIMIT_WINDOW_MS);
  if (validTimestamps.length >= MAX_REQUESTS_PER_WINDOW) {
    return true;
  }
  validTimestamps.push(now);
  rateLimitMap.set(ip, validTimestamps);
  // Periodic cleanup if map grows
  if (rateLimitMap.size > 2000) {
    for (const [k, v] of rateLimitMap.entries()) {
      if (v.length === 0 || now - v[v.length - 1] > RATE_LIMIT_WINDOW_MS) {
        rateLimitMap.delete(k);
      }
    }
  }
  return false;
}

exports.handler = async function(event, context) {
  // Handle CORS preflight
  if (event.httpMethod === "OPTIONS") {
    return {
      statusCode: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Client-Version",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
      },
      body: "",
    };
  }

  if (event.httpMethod !== "POST") {
    return {
      statusCode: 405,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify({ success: false, error: "Method Not Allowed. Use POST." }),
    };
  }

  const clientIp = event.headers["x-forwarded-for"] || event.headers["client-ip"] || "unknown";
  if (isRateLimited(clientIp)) {
    return {
      statusCode: 429,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify({
        success: false,
        error: "Rate limit exceeded. Please wait a moment before sending another request.",
      }),
    };
  }

  // Retrieve Gemini API Key from server environment
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) {
    return {
      statusCode: 500,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify({
        success: false,
        error: "Server configuration error: GEMINI_API_KEY is not configured on this Netlify instance.",
      }),
    };
  }

  try {
    const payload = JSON.parse(event.body || "{}");
    const {
      prompt = "Analyze the screen content and answer any questions precisely.",
      model = "gemini-2.5-flash",
      images = [], // array of { mimeType, data (base64) }
      audio = null, // { mimeType, data (base64) }
      files = [], // array of { mimeType, data (base64) }
      history = [], // array of { role: 'user'|'model', text: string }
      systemInstruction = "You are AskScreen AI, an on-screen reading and learning assistant. For multiple-choice practice or self-study questions, return only the option letter and answer text (for example: B. Oxygen). Do not explain unless explicitly requested. For non-multiple-choice questions, give a direct concise answer. Answer multiple visible questions separately in screen order. If text is unclear, say so instead of guessing. Never claim to have selected or submitted an answer. Provide answer text only; any interaction with a practice page is performed separately by the app after the user explicitly enables its accessibility automation.",
    } = payload;

    // Validate model name to currently supported production models
    const supportedModels = ["gemini-2.5-flash", "gemini-1.5-flash", "gemini-2.5-pro", "gemini-1.5-pro"];
    const targetModel = supportedModels.includes(model) ? model : "gemini-2.5-flash";

    // Build Gemini contents array
    const contents = [];

    // Add prior conversation turns if any
    for (const h of history) {
      if (h.text && h.role) {
        contents.push({
          role: h.role === "assistant" ? "model" : "user",
          parts: [{ text: h.text }],
        });
      }
    }

    // Build current turn parts
    const currentParts = [];

    // Attach screenshots / images
    for (const img of images) {
      if (img.data && img.mimeType) {
        currentParts.push({
          inlineData: {
            mimeType: img.mimeType,
            data: img.data,
          },
        });
      }
    }

    // Attach audio if present
    if (audio && audio.data && audio.mimeType) {
      currentParts.push({
        inlineData: {
          mimeType: audio.mimeType,
          data: audio.data,
        },
      });
    }

    // Attach files if present (PDF, video frames, etc.)
    for (const file of files) {
      if (file.data && file.mimeType) {
        currentParts.push({
          inlineData: {
            mimeType: file.mimeType,
            data: file.data,
          },
        });
      }
    }

    // Attach text prompt
    currentParts.push({ text: prompt });

    contents.push({
      role: "user",
      parts: currentParts,
    });

    const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${targetModel}:generateContent?key=${apiKey}`;

    const geminiRequestBody = {
      contents,
      systemInstruction: {
        parts: [{ text: systemInstruction }],
      },
      generationConfig: {
        temperature: 0.2,
        topP: 0.95,
        maxOutputTokens: 2048,
      },
    };

    const response = await fetch(geminiUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(geminiRequestBody),
    });

    const responseData = await response.json();

    if (!response.ok) {
      const errorMsg = responseData?.error?.message || `Gemini API returned HTTP ${response.status}`;
      return {
        statusCode: response.status,
        headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
        body: JSON.stringify({
          success: false,
          error: errorMsg,
          code: responseData?.error?.code || response.status,
        }),
      };
    }

    const candidate = responseData.candidates?.[0];
    const answerText = candidate?.content?.parts?.map(p => p.text).join("") || "No answer generated.";

    return {
      statusCode: 200,
      headers: {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
      },
      body: JSON.stringify({
        success: true,
        answer: answerText,
        model: targetModel,
        finishReason: candidate?.finishReason || "STOP",
        usageMetadata: responseData.usageMetadata || null,
      }),
    };
  } catch (err) {
    return {
      statusCode: 500,
      headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
      body: JSON.stringify({
        success: false,
        error: err.message || "An unexpected error occurred processing your request.",
      }),
    };
  }
};
