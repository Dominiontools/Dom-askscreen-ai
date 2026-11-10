
/**
 * ASKSCREEN AI — INTELLIGENT MODEL ROUTER
 * Multi-provider AI backend with automatic failover.
 *
 * Providers:
 * Gemini (3 keys), Groq, OpenRouter, Cohere,
 * NVIDIA, Z.AI, Cloudflare and Custom AI.
 *
 * API keys must remain in Netlify environment variables.
 */

const rateLimitMap = new Map();
const cooldowns = new Map();

const RATE_WINDOW = 60000;
const MAX_REQUESTS = 30;
const PROVIDER_TIMEOUT = 9000;
const COOLDOWN_TIME = 20000;

const HEADERS = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*"
};

function reply(status, data) {
  return {
    statusCode: status,
    headers: HEADERS,
    body: JSON.stringify(data)
  };
}

// --------------------------------------------------
// RATE LIMITING
// --------------------------------------------------

function isRateLimited(ip) {
  const now = Date.now();

  const valid = (rateLimitMap.get(ip) || [])
    .filter(t => now - t < RATE_WINDOW);

  if (valid.length >= MAX_REQUESTS) return true;

  valid.push(now);
  rateLimitMap.set(ip, valid);

  if (rateLimitMap.size > 2000) {
    for (const [key, values] of rateLimitMap) {
      if (!values.length || now - values[values.length - 1] > RATE_WINDOW) {
        rateLimitMap.delete(key);
      }
    }
  }

  return false;
}

// --------------------------------------------------
// PROVIDER HEALTH
// --------------------------------------------------

function cooling(name) {
  const until = cooldowns.get(name) || 0;

  if (Date.now() >= until) {
    cooldowns.delete(name);
    return false;
  }

  return true;
}

function cool(name) {
  cooldowns.set(name, Date.now() + COOLDOWN_TIME);
}

function retryable(error) {
  return !error.status ||
    [408, 409, 425, 429, 500, 502, 503, 504].includes(error.status);
}

function logFailure(name, error) {
  console.warn("[AskScreen Router] Provider failure:", {
    provider: name,
    status: error.status || "NETWORK/TIMEOUT",
    message: String(error.message || "").slice(0, 200)
  });
}

// --------------------------------------------------
// REQUEST VALIDATION
// --------------------------------------------------

function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];

  return history
    .filter(x => x && typeof x.text === "string" && x.text.trim())
    .slice(-12)
    .map(x => ({
      role: x.role === "assistant" || x.role === "model"
        ? "assistant"
        : "user",
      content: x.text
    }));
}

function hasImages(p) {
  return Array.isArray(p.images) &&
    p.images.some(x => x && x.data && x.mimeType);
}

function hasAudio(p) {
  return Boolean(p.audio && p.audio.data && p.audio.mimeType);
}

function hasFiles(p) {
  return Array.isArray(p.files) &&
    p.files.some(x => x && x.data && x.mimeType);
}

// --------------------------------------------------
// PROVIDER CONFIGURATION
// --------------------------------------------------

function getProviders(payload) {
  const providers = [];

  const multimodal = hasImages(payload) ||
    hasAudio(payload) ||
    hasFiles(payload);

  const geminiKeys = [
    process.env.GEMINI_API_KEY,
    process.env.GEMINI_API_KEY_2,
    process.env.GEMINI_API_KEY_3
  ].filter(Boolean);

  if (geminiKeys.length) {
    providers.push({
      name: "Gemini",
      type: "gemini",
      keys: geminiKeys,
      model: process.env.GEMINI_MODEL || "gemini-2.5-flash",
      multimodal: true
    });
  }

  // Other providers are text fallbacks by default.
  // Configure models in Netlify environment variables.

  if (process.env.GROQ_API_KEY) {
    providers.push({
      name: "Groq",
      type: "openai",
      key: process.env.GROQ_API_KEY,
      url: "https://api.groq.com/openai/v1/chat/completions",
      model: process.env.GROQ_MODEL || "llama-3.3-70b-versatile"
    });
  }

  if (process.env.OPENROUTER_API_KEY) {
    providers.push({
      name: "OpenRouter",
      type: "openai",
      key: process.env.OPENROUTER_API_KEY,
      url: "https://openrouter.ai/api/v1/chat/completions",
      model: process.env.OPENROUTER_MODEL || "openai/gpt-4o-mini"
    });
  }

  if (process.env.COHERE_API_KEY) {
    providers.push({
      name: "Cohere",
      type: "cohere",
      key: process.env.COHERE_API_KEY,
      url: "https://api.cohere.com/v2/chat",
      model: process.env.COHERE_MODEL || "command-a-03-2025"
    });
  }

  if (process.env.NVIDIA_API_KEY) {
    providers.push({
      name: "NVIDIA",
      type: "openai",
      key: process.env.NVIDIA_API_KEY,
      url: "https://integrate.api.nvidia.com/v1/chat/completions",
      model: process.env.NVIDIA_MODEL || "meta/llama-3.3-70b-instruct"
    });
  }

  if (process.env.ZAI_API_KEY) {
    providers.push({
      name: "Z.AI",
      type: "openai",
      key: process.env.ZAI_API_KEY,
      url: process.env.ZAI_BASE_URL ||
        "https://api.z.ai/api/paas/v4/chat/completions",
      model: process.env.ZAI_MODEL || "glm-4.5-flash"
    });
  }

  const cloudflareKey =
    process.env.CLOUDFLARE_API_TOKEN ||
    process.env.CLOUDFLARE_API_KEY;

  if (cloudflareKey && process.env.CLOUDFLARE_ACCOUNT_ID) {
    providers.push({
      name: "Cloudflare",
      type: "openai",
      key: cloudflareKey,
      url:
        "https://api.cloudflare.com/client/v4/accounts/" +
        process.env.CLOUDFLARE_ACCOUNT_ID +
        "/ai/v1/chat/completions",
      model: process.env.CLOUDFLARE_MODEL ||
        "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
    });
  }

  if (
    process.env.CUSTOM1_API_KEY &&
    process.env.CUSTOM1_BASE_URL &&
    process.env.CUSTOM1_MODEL
  ) {
    const base = process.env.CUSTOM1_BASE_URL.replace(/\/+$/, "");

    providers.push({
      name: "Custom AI",
      type: "openai",
      key: process.env.CUSTOM1_API_KEY,
      url: base.endsWith("/chat/completions")
        ? base
        : base + "/chat/completions",
      model: process.env.CUSTOM1_MODEL
    });
  }

  return providers.filter(p =>
    !cooling(p.name) && (!multimodal || p.type === "gemini")
  );
}

// --------------------------------------------------
// NETWORK TIMEOUT
// --------------------------------------------------

async function timedFetch(url, options) {
  const controller = new AbortController();

  const timer = setTimeout(
    () => controller.abort(),
    PROVIDER_TIMEOUT
  );

  try {
    return await fetch(url, {
      ...options,
      signal: controller.signal
    });
  } finally {
    clearTimeout(timer);
  }
}

async function readResponse(response) {
  const text = await response.text();

  try {
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

// --------------------------------------------------
// GEMINI REQUEST
// --------------------------------------------------

function buildGeminiBody(p) {
  const contents = [];

  for (const h of normalizeHistory(p.history)) {
    contents.push({
      role: h.role === "assistant" ? "model" : "user",
      parts: [{ text: h.content }]
    });
  }

  const parts = [];

  for (const img of p.images || []) {
    if (img && img.data && img.mimeType) {
      parts.push({
        inlineData: {
          mimeType: img.mimeType,
          data: img.data
        }
      });
    }
  }

  if (p.audio && p.audio.data && p.audio.mimeType) {
    parts.push({
      inlineData: {
        mimeType: p.audio.mimeType,
        data: p.audio.data
      }
    });
  }

  for (const file of p.files || []) {
    if (file && file.data && file.mimeType) {
      parts.push({
        inlineData: {
          mimeType: file.mimeType,
          data: file.data
        }
      });
    }
  }

  parts.push({ text: p.prompt });

  contents.push({
    role: "user",
    parts
  });

  return {
    contents,
    systemInstruction: {
      parts: [{ text: p.systemInstruction }]
    },
    generationConfig: {
      temperature: 0.2,
      topP: 0.95,
      maxOutputTokens: 2048
    }
  };
}

// --------------------------------------------------
// OPENAI-COMPATIBLE REQUEST
// --------------------------------------------------

function buildMessages(p) {
  const messages = [
    {
      role: "system",
      content: p.systemInstruction
    },
    ...normalizeHistory(p.history),
    {
      role: "user",
      content: p.prompt
    }
  ];

  return messages;
}

// --------------------------------------------------
// CALL PROVIDER
// --------------------------------------------------

async function callProvider(provider, payload, key) {
  let url;
  let headers = {
    "Content-Type": "application/json"
  };
  let body;

  const started = Date.now();

  if (provider.type === "gemini") {
    url =
      "https://generativelanguage.googleapis.com/v1beta/models/" +
      encodeURIComponent(provider.model) +
      ":generateContent?key=" + encodeURIComponent(key);

    body = buildGeminiBody(payload);

  } else if (provider.type === "cohere") {
    url = provider.url;
    headers.Authorization = "Bearer " + key;

    body = {
      model: provider.model,
      messages: [
        {
          role: "system",
          content: payload.systemInstruction
        },
        ...normalizeHistory(payload.history).map(x => ({
          role: x.role,
          content: x.content
        })),
        {
          role: "user",
          content: payload.prompt
        }
      ],
      temperature: 0.2,
      max_tokens: 2048
    };

  } else {
    url = provider.url;
    headers.Authorization = "Bearer " + key;

    body = {
      model: provider.model,
      messages: buildMessages(payload),
      temperature: 0.2,
      max_tokens: 2048
    };
  }

  const response = await timedFetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(body)
  });

  const data = await readResponse(response);

  if (!response.ok) {
    const error = new Error(
      data?.error?.message ||
      data?.message ||
      "Provider HTTP " + response.status
    );

    error.status = response.status;
    throw error;
  }

  let answer = "";
  let finishReason = "STOP";
  let usageMetadata = null;

  if (provider.type === "gemini") {
    const candidate = data.candidates?.[0];

    answer = candidate?.content?.parts
      ?.map(x => x.text || "")
      .join("") || "";

    finishReason = candidate?.finishReason || "STOP";
    usageMetadata = data.usageMetadata || null;

  } else if (provider.type === "cohere") {
    answer = (data.message?.content || [])
      .map(x => x.text || "")
      .join("");

    finishReason = data.finish_reason || "STOP";
    usageMetadata = data.usage || null;

  } else {
    answer = data.choices?.[0]?.message?.content || "";
    finishReason = data.choices?.[0]?.finish_reason || "STOP";
    usageMetadata = data.usage || null;
  }

  if (!answer || !answer.trim()) {
    const error = new Error("Provider returned an empty answer.");
    error.status = 502;
    throw error;
  }

  return {
    success: true,
    answer: answer.trim(),
    model: provider.model,
    provider: provider.name,
    finishReason,
    usageMetadata,
    diagnostics: {
      latencyMs: Date.now() - started
    }
  };
}

// --------------------------------------------------
// INTELLIGENT ROUTER
// --------------------------------------------------

async function routeRequest(payload) {
  const providers = getProviders(payload);

  if (!providers.length) {
    throw new Error("NO_COMPATIBLE_PROVIDER");
  }

  const failures = [];

  for (const provider of providers) {
    const keys = provider.type === "gemini"
      ? provider.keys
      : [provider.key];

    // Do not rotate Gemini accounts to bypass quota limits.
    // If a Gemini key is rate-limited, move to another provider.
    for (let i = 0; i < keys.length; i++) {
      try {
        const result = await callProvider(
          provider,
          payload,
          keys[i]
        );

        console.info("[AskScreen Router] Success:", {
          provider: provider.name,
          model: provider.model,
          latencyMs: result.diagnostics.latencyMs,
          previousFailures: failures
        });

        return result;

      } catch (error) {
        logFailure(provider.name, error);

        failures.push(provider.name);

        if (error.status === 429) {
          cool(provider.name);
          break;
        }

        if (
          provider.type === "gemini" &&
          [401, 403].includes(error.status) &&
          i < keys.length - 1
        ) {
          continue;
        }

        if (
          !retryable(error) &&
          ![400, 401, 403, 404, 422].includes(error.status)
        ) {
          throw error;
        }

        cool(provider.name);
        break;
      }
    }
  }

  console.error(
    "[AskScreen Router] All compatible providers failed:",
    failures
  );

  throw new Error("ALL_PROVIDERS_FAILED");
}

// --------------------------------------------------
// MAIN NETLIFY FUNCTION
// --------------------------------------------------

exports.handler = async function(event) {

  if (event.httpMethod === "OPTIONS") {
    return {
      statusCode: 204,
      headers: {
        ...HEADERS,
        "Access-Control-Allow-Headers":
          "Content-Type, Authorization, X-Client-Version",
        "Access-Control-Allow-Methods": "POST, OPTIONS"
      },
      body: ""
    };
  }

  if (event.httpMethod !== "POST") {
    return reply(405, {
      success: false,
      error: "Method Not Allowed. Use POST."
    });
  }

  const headers = event.headers || {};

  const ip = (
    headers["x-forwarded-for"] ||
    headers["client-ip"] ||
    "unknown"
  ).split(",")[0].trim();

  if (isRateLimited(ip)) {
    return reply(429, {
      success: false,
      error: "AskScreen AI is busy. Please try again shortly."
    });
  }

  let payload;

  try {
    payload = JSON.parse(event.body || "{}");
  } catch {
    return reply(400, {
      success: false,
      error: "Invalid request format."
    });
  }

  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return reply(400, {
      success: false,
      error: "Invalid request payload."
    });
  }

  if (
    typeof payload.prompt !== "string" ||
    !payload.prompt.trim()
  ) {
    return reply(400, {
      success: false,
      error: "Please provide a question."
    });
  }

  const normalized = {
    ...payload,
    prompt: payload.prompt.trim(),

    systemInstruction:
      typeof payload.systemInstruction === "string" &&
      payload.systemInstruction.trim()
        ? payload.systemInstruction
        : "You are AskScreen AI, an on-screen reading and learning assistant. For multiple-choice practice or self-study questions, return only the option letter and answer text. Do not explain unless requested. For other questions, give a direct concise answer. Answer multiple visible questions separately in screen order. If text is unclear, say so instead of guessing. Never claim to have selected or submitted an answer.",

    history: Array.isArray(payload.history) ? payload.history : [],
    images: Array.isArray(payload.images) ? payload.images : [],
    files: Array.isArray(payload.files) ? payload.files : [],
    audio: payload.audio || null
  };

  try {
    const result = await routeRequest(normalized);
    return reply(200, result);

  } catch (error) {
    console.error("[AskScreen Router] Request failed:", error.message);

    return reply(503, {
      success: false,
      error:
        "AskScreen AI is temporarily unable to answer. Please try again shortly."
    });
  }
};
