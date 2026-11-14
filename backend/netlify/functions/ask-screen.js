
'use strict';

const { randomUUID } = require('crypto');

/**
 * AskScreen AI — Netlify serverless model router
 * API keys are read only from Netlify environment variables.
 * Text requests can fail over across configured providers. Requests containing
 * attachments are sent only to Gemini so media is never silently discarded.
 */

const rateLimitMap = new Map();
const cooldowns = new Map();

const RATE_WINDOW_MS = 60_000;
const DEFAULT_MAX_REQUESTS_PER_WINDOW = 60;
const configuredRateLimit = Number.parseInt(process.env.MAX_REQUESTS_PER_MINUTE || '', 10);
const MAX_REQUESTS_PER_WINDOW = Number.isFinite(configuredRateLimit)
  ? Math.min(300, Math.max(10, configuredRateLimit))
  : DEFAULT_MAX_REQUESTS_PER_WINDOW;
const MAX_BODY_BYTES = 4 * 1024 * 1024;
const PROVIDER_TIMEOUT_MS = 8_000;
const ROUTER_DEADLINE_MS = 24_000;
const MAX_PROVIDER_RETRIES = 1;
const RETRY_BASE_DELAY_MS = 350;
const COOLDOWN_MS = 30_000;
const MAX_HISTORY_ITEMS = 12;
const MAX_ATTACHMENTS = 8;
const MAX_PROMPT_CHARS = 100_000;

const SYSTEM_INSTRUCTION =
  'You are DOM Ask AI, an on-screen reading and learning assistant. ' +
  'Carefully inspect every supplied screenshot or image before answering. Read visible text and answer choices accurately; ' +
  'for diagrams, charts, tables, maps, and labelled figures, use the visual relationships and labels rather than guessing from isolated words. ' +
  'If an image is blurry, cropped, too small, or ambiguous, identify exactly what cannot be read and ask for a clearer image instead of inventing details. ' +
  'For multiple-choice practice or self-study questions, return only the option letter and answer text unless an explanation is requested. ' +
  'For other questions, answer directly and concisely. Answer multiple visible questions separately in screen order. ' +
  'Never claim to have clicked, selected, or submitted an answer.';

const CORS_HEADERS = {
  'Content-Type': 'application/json; charset=utf-8',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Client-Version',
  'Access-Control-Expose-Headers': 'X-Request-Id'
};

function response(statusCode, data, requestId, extraHeaders = {}) {
  const headers = { ...CORS_HEADERS, ...extraHeaders };
  if (requestId) headers['X-Request-Id'] = requestId;
  return { statusCode, headers, body: JSON.stringify(data) };
}


// Firebase Admin is configured only with a Netlify environment variable.
// FIREBASE_SERVICE_ACCOUNT_JSON must contain the service-account JSON string.
let firebaseAdminPromise;
async function getFirebaseAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!firebaseAdminPromise) {
    firebaseAdminPromise = (async () => {
      const admin = require('firebase-admin');
      if (!admin.apps.length) {
        const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
        admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
      }
      return admin;
    })();
  }
  return firebaseAdminPromise;
}

async function verifyUser(event) {
  const authHeader = getHeader(event.headers, 'authorization');
  const match = authHeader.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    const error = new Error('AUTH_REQUIRED');
    error.statusCode = 401;
    throw error;
  }
  try {
    const admin = await getFirebaseAdmin();
    return await admin.auth().verifyIdToken(match[1]);
  } catch (error) {
    if (error.message === 'AUTH_NOT_CONFIGURED') throw error;
    const denied = new Error('INVALID_AUTH_TOKEN');
    denied.statusCode = 401;
    throw denied;
  }
}

function featureForRequest(raw) {
  const value = String(raw.feature || '').toLowerCase();
  if (['screen', 'spoken', 'transcription'].includes(value)) return value;
  const type = String(raw.queryType || '').toUpperCase();
  if (['AUDIO', 'VOICE', 'SCREEN_AND_AUDIO'].includes(type)) return 'spoken';
  if (['SCREEN', 'REGION'].includes(type)) return 'screen';
  if (type === 'TRANSCRIPTION') return 'transcription';
  return 'screen';
}

const FEATURE_FIELDS = {
  screen: 'screenAnswersUsed',
  spoken: 'spokenAnswersUsed',
  transcription: 'transcriptionsUsed'
};
const FREE_FEATURE_LIMIT = 2;
function currentUsagePeriod() {
  return new Date().toISOString().slice(0, 7);
}
async function reserveFeatureUse(uid, feature, requestId) {
  const admin = await getFirebaseAdmin();
  const db = admin.firestore();
  const ref = db.collection('askScreenAccounts').doc(uid);
  const field = FEATURE_FIELDS[feature];
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(ref);
    const data = snapshot.exists ? snapshot.data() : {};
    const period = currentUsagePeriod();
    const usage = data.usagePeriod === period ? (data.usage || {}) : {};
    const subscription = data.subscription || {};
    const periodEnd = subscription.currentPeriodEnd;
    const periodEndMs = periodEnd && typeof periodEnd.toDate === 'function'
      ? periodEnd.toDate().getTime()
      : 0;
    // Never grant paid access without a valid, unexpired subscription end date.
    const paid = subscription.status === 'active' &&
      Number.isFinite(periodEndMs) && periodEndMs > Date.now();
    const used = Number(usage[field] || 0);
    if (!paid && used >= FREE_FEATURE_LIMIT) {
      const error = new Error('SUBSCRIPTION_REQUIRED');
      error.statusCode = 402;
      error.feature = feature;
      throw error;
    }
    if (!paid) {
      transaction.set(ref, {
        usagePeriod: period,
        usage: { ...usage, [field]: used + 1 },
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    }
    return { paid, field, period };
  });
}
async function releaseFeatureUse(uid, reservation) {
  if (!reservation || reservation.paid) return;
  const admin = await getFirebaseAdmin();
  const db = admin.firestore();
  const ref = db.collection('askScreenAccounts').doc(uid);
  await db.runTransaction(async transaction => {
    const snapshot = await transaction.get(ref);
    if (!snapshot.exists || snapshot.data().usagePeriod !== reservation.period) return;
    const usage = snapshot.data().usage || {};
    transaction.set(ref, {
      usage: { ...usage, [reservation.field]: Math.max(0, Number(usage[reservation.field] || 0) - 1) },
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
  });
}
function safeJsonParse(value) {
  try { return value ? JSON.parse(value) : {}; } catch { return null; }
}

function getHeader(headers, name) {
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(headers || {})) {
    if (key.toLowerCase() === target) return String(value || '');
  }
  return '';
}

function requestIp(event) {
  return getHeader(event.headers, 'client-ip') ||
    getHeader(event.headers, 'x-nf-client-connection-ip') ||
    getHeader(event.headers, 'x-forwarded-for').split(',')[0].trim() || 'unknown';
}

function getRateLimitRetryAfterSeconds(ip) {
  const now = Date.now();
  const recent = (rateLimitMap.get(ip) || []).filter(t => now - t < RATE_WINDOW_MS);
  if (recent.length < MAX_REQUESTS_PER_WINDOW) {
    recent.push(now);
    rateLimitMap.set(ip, recent);
    if (rateLimitMap.size > 2_000) {
      for (const [key, times] of rateLimitMap) {
        if (!times.length || now - times[times.length - 1] >= RATE_WINDOW_MS) rateLimitMap.delete(key);
      }
    }
    return 0;
  }

  // The oldest request in the active window is the first one that will free a slot.
  return Math.max(1, Math.ceil((recent[0] + RATE_WINDOW_MS - now) / 1000));
}

function isCoolingDown(name) {
  const until = cooldowns.get(name) || 0;
  if (Date.now() >= until) {
    cooldowns.delete(name);
    return false;
  }
  return true;
}

function coolDown(name) {
  cooldowns.set(name, Date.now() + COOLDOWN_MS);
}

function byteLengthOfBody(event) {
  const body = event.body || '';
  if (event.isBase64Encoded) return Buffer.from(body, 'base64').length;
  return Buffer.byteLength(body, 'utf8');
}

function decodeEventBody(event) {
  if (!event.body) return '{}';
  return event.isBase64Encoded
    ? Buffer.from(event.body, 'base64').toString('utf8')
    : event.body;
}

function validBase64(data) {
  if (typeof data !== 'string' || !data.length) return false;
  if (data.length % 4 !== 0) return false;
  return /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data);
}

const SUPPORTED_MEDIA_TYPES = new Set([
  'image/png', 'image/jpeg', 'image/webp', 'image/heic', 'image/heif', 'image/gif',
  'audio/wav', 'audio/x-wav', 'audio/mp3', 'audio/mpeg', 'audio/aiff',
  'audio/aac', 'audio/ogg', 'audio/flac', 'audio/webm', 'application/pdf', 'text/plain'
]);

function normalizeAttachment(item, label) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) {
    throw new Error('INVALID_' + label.toUpperCase());
  }
  const mimeType = String(item.mimeType || '').toLowerCase().split(';')[0].trim();
  const data = item.data;
  if (!SUPPORTED_MEDIA_TYPES.has(mimeType)) throw new Error('UNSUPPORTED_ATTACHMENT_TYPE');
  if (!validBase64(data)) throw new Error('INVALID_ATTACHMENT_DATA');
  return { mimeType, data };
}

function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];
  const normalized = history.slice(-MAX_HISTORY_ITEMS).map(item => {
    if (!item || typeof item.text !== 'string' || !item.text.trim()) return null;
    const role = item.role === 'model' || item.role === 'assistant' ? 'assistant' : 'user';
    return { role, content: item.text.slice(0, 20_000) };
  }).filter(Boolean);

  const merged = [];
  for (const item of normalized) {
    if (!merged.length && item.role !== 'user') continue;
    const previous = merged[merged.length - 1];
    if (previous && previous.role === item.role) previous.content += '\n' + item.content;
    else merged.push({ ...item });
  }
  return merged;
}

function normalizePayload(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('INVALID_REQUEST');
  if (typeof raw.prompt !== 'string' || !raw.prompt.trim()) throw new Error('PROMPT_REQUIRED');
  const prompt = raw.prompt.trim();
  if (prompt.length > MAX_PROMPT_CHARS) throw new Error('PROMPT_TOO_LONG');

  const imagesRaw = raw.images == null ? [] : raw.images;
  const filesRaw = raw.files == null ? [] : raw.files;
  if (!Array.isArray(imagesRaw) || !Array.isArray(filesRaw)) throw new Error('INVALID_ATTACHMENTS');
  if (imagesRaw.length + filesRaw.length + (raw.audio ? 1 : 0) > MAX_ATTACHMENTS) throw new Error('TOO_MANY_ATTACHMENTS');

  const images = imagesRaw.map(x => normalizeAttachment(x, 'image'));
  const files = filesRaw.map(x => normalizeAttachment(x, 'file'));
  const audio = raw.audio == null ? null : normalizeAttachment(raw.audio, 'audio');
  if (audio && !audio.mimeType.startsWith('audio/')) throw new Error('INVALID_AUDIO_TYPE');
  if (images.some(x => !x.mimeType.startsWith('image/'))) throw new Error('INVALID_IMAGE_TYPE');

  return {
    prompt,
    history: normalizeHistory(raw.history),
    images,
    files,
    audio,
    requestedModel: typeof raw.model === 'string' ? raw.model.trim() : ''
  };
}

function geminiModelFor(payload) {
  const fallback = process.env.GEMINI_MODEL || 'gemini-3.8-flash';
  const allowed = (process.env.GEMINI_ALLOWED_MODELS || fallback)
    .split(',').map(x => x.trim()).filter(Boolean);
  return payload.requestedModel && allowed.includes(payload.requestedModel)
    ? payload.requestedModel
    : fallback;
}

function getProviders(payload) {
  const multimodal = payload.images.length > 0 || payload.files.length > 0 || Boolean(payload.audio);
  const providers = [];
  const geminiKeys = [process.env.GEMINI_API_KEY, process.env.GEMINI_API_KEY_2, process.env.GEMINI_API_KEY_3]
    .map(x => typeof x === 'string' ? x.trim() : '').filter(Boolean);

  if (geminiKeys.length) providers.push({
    name: 'Gemini', type: 'gemini', keys: geminiKeys, model: geminiModelFor(payload)
  });

  if (process.env.GROQ_API_KEY) providers.push({
    name: 'Groq', type: 'openai', key: process.env.GROQ_API_KEY,
    url: 'https://api.groq.com/openai/v1/chat/completions',
    model: process.env.GROQ_MODEL || 'llama-3.3-70b-versatile'
  });

  if (process.env.OPENROUTER_API_KEY) providers.push({
    name: 'OpenRouter', type: 'openai', key: process.env.OPENROUTER_API_KEY,
    url: 'https://openrouter.ai/api/v1/chat/completions',
    model: process.env.OPENROUTER_MODEL || 'openai/gpt-4o-mini'
  });

  if (process.env.COHERE_API_KEY) providers.push({
    name: 'Cohere', type: 'cohere', key: process.env.COHERE_API_KEY,
    url: 'https://api.cohere.com/v2/chat',
    model: process.env.COHERE_MODEL || 'command-a-plus-05-2026'
  });

  if (process.env.NVIDIA_API_KEY) providers.push({
    name: 'NVIDIA', type: 'openai', key: process.env.NVIDIA_API_KEY,
    url: 'https://integrate.api.nvidia.com/v1/chat/completions',
    model: process.env.NVIDIA_MODEL || 'meta/llama-3.3-70b-instruct'
  });

  if (process.env.ZAI_API_KEY) providers.push({
    name: 'Z.AI', type: 'openai', key: process.env.ZAI_API_KEY,
    url: process.env.ZAI_BASE_URL || 'https://api.z.ai/api/paas/v4/chat/completions',
    model: process.env.ZAI_MODEL || 'glm-5.3'
  });

  const cfToken = process.env.CLOUDFLARE_API_TOKEN || process.env.CLOUDFLARE_API_KEY;
  if (cfToken && process.env.CLOUDFLARE_ACCOUNT_ID) providers.push({
    name: 'Cloudflare', type: 'openai', key: cfToken,
    url: 'https://api.cloudflare.com/client/v4/accounts/' + encodeURIComponent(process.env.CLOUDFLARE_ACCOUNT_ID) + '/ai/v1/chat/completions',
    model: process.env.CLOUDFLARE_MODEL || '@cf/meta/llama-3.1-8b-instruct'
  });

  if (process.env.CUSTOM1_API_KEY && process.env.CUSTOM1_BASE_URL && process.env.CUSTOM1_MODEL) {
    let customUrl;
    try {
      customUrl = new URL(process.env.CUSTOM1_BASE_URL);
      if (customUrl.protocol !== 'https:') throw new Error('HTTPS_REQUIRED');
    } catch {
      console.error('[AskScreen Router] CUSTOM1_BASE_URL must be a valid HTTPS URL.');
      customUrl = null;
    }
    if (customUrl) {
      const base = customUrl.toString().replace(/\/+$/, '');
      providers.push({
        name: 'Custom AI', type: 'openai', key: process.env.CUSTOM1_API_KEY,
        url: base.endsWith('/chat/completions') ? base : base + '/chat/completions',
        model: process.env.CUSTOM1_MODEL
      });
    }
  }

  return providers.filter(p => !isCoolingDown(p.name) && (!multimodal || p.type === 'gemini'));
}

function geminiContents(payload) {
  const contents = payload.history.map(turn => ({
    role: turn.role === 'assistant' ? 'model' : 'user',
    parts: [{ text: turn.content }]
  }));

  const parts = [];
  for (const media of [...payload.images, ...payload.files, ...(payload.audio ? [payload.audio] : [])]) {
    parts.push({ inlineData: { mimeType: media.mimeType, data: media.data } });
  }
  parts.push({ text: payload.prompt });

  const last = contents[contents.length - 1];
  if (last && last.role === 'user') last.parts.push(...parts);
  else contents.push({ role: 'user', parts });

  return contents;
}

function openAiMessages(payload) {
  return [
    { role: 'system', content: SYSTEM_INSTRUCTION },
    ...payload.history.map(turn => ({ role: turn.role, content: turn.content })),
    { role: 'user', content: payload.prompt }
  ];
}

async function fetchJson(url, options, remainingMs) {
  const timeoutMs = Math.max(1, Math.min(PROVIDER_TIMEOUT_MS, remainingMs));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const { deadline, ...requestOptions } = options;

  try {
    const result = await fetch(url, { ...requestOptions, signal: controller.signal });
    const text = await result.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; }
    catch { data = { rawError: text.slice(0, 500) }; }

    if (Date.now() > (deadline || Infinity)) {
      const e = new Error('ROUTER_DEADLINE_EXCEEDED');
      e.code = 'TIMEOUT';
      throw e;
    }
    return { response: result, data, retryAfter: result.headers.get('retry-after') };
  } catch (error) {
    if (error.name === 'AbortError') {
      const e = new Error('Provider request timed out');
      e.code = 'TIMEOUT';
      throw e;
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function providerError(status, data, retryAfter) {
  const message = data?.error?.message || data?.message || data?.detail || data?.rawError || ('Provider HTTP ' + status);
  const error = new Error(String(message).slice(0, 500));
  error.status = status;
  const code = data?.error?.status || data?.error?.code || data?.code || '';
  error.providerCode = String(code);
  if (retryAfter) {
    const seconds = Number(retryAfter);
    const dateDelay = Number.isNaN(seconds) ? Date.parse(retryAfter) - Date.now() : seconds * 1000;
    if (Number.isFinite(dateDelay) && dateDelay > 0) error.retryAfterMs = Math.min(dateDelay, 5_000);
  }
  return error;
}

function extractText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map(part => {
    if (typeof part === 'string') return part;
    return typeof part?.text === 'string' ? part.text : '';
  }).join('');
  return '';
}

async function callProvider(provider, payload, key, deadline) {
  const started = Date.now();
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    const e = new Error('Router deadline exceeded');
    e.code = 'TIMEOUT';
    throw e;
  }

  let url = provider.url;
  const headers = { 'Content-Type': 'application/json' };
  let body;

  if (provider.type === 'gemini') {
    url = 'https://generativelanguage.googleapis.com/v1beta/models/' +
      encodeURIComponent(provider.model) + ':generateContent';
    headers['x-goog-api-key'] = key;
    body = {
      systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
      contents: geminiContents(payload),
      generationConfig: { temperature: 0.2, topP: 0.95, maxOutputTokens: 2048 }
    };
  } else {
    headers.Authorization = 'Bearer ' + key;
    const messages = openAiMessages(payload);
    body = {
      model: provider.model,
      messages,
      temperature: 0.2,
      max_tokens: 2048,
      stream: false
    };
  }

  const { response: httpResponse, data } = await fetchJson(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    deadline
  }, remaining);

  if (!httpResponse.ok) throw providerError(httpResponse.status, data, httpResponse.headers.get('retry-after'));

  let answer = '';
  let finishReason = 'STOP';
  let usageMetadata = null;

  if (provider.type === 'gemini') {
    const candidate = data?.candidates?.[0];
    answer = extractText(candidate?.content?.parts || []);
    finishReason = candidate?.finishReason || 'STOP';
    usageMetadata = data?.usageMetadata || null;

    if (!answer && data?.promptFeedback?.blockReason) {
      const e = new Error('Gemini blocked the request: ' + data.promptFeedback.blockReason);
      e.status = 422;
      throw e;
    }
  } else if (provider.type === 'cohere') {
    answer = extractText(data?.message?.content || []);
    finishReason = data?.finish_reason || 'STOP';
    usageMetadata = data?.usage || data?.meta?.tokens || null;
  } else {
    answer = extractText(data?.choices?.[0]?.message?.content || '');
    finishReason = data?.choices?.[0]?.finish_reason || 'STOP';
    usageMetadata = data?.usage || null;
  }

  if (!answer.trim()) {
    const e = new Error('Provider returned no text answer');
    e.status = 502;
    throw e;
  }

  return {
    success: true,
    answer: answer.trim(),
    model: provider.model,
    provider: provider.name,
    finishReason,
    usageMetadata,
    diagnostics: { latencyMs: Date.now() - started }
  };
}

function isInvalidGeminiKey(error) {
  const message = String(error.message || '').toLowerCase();
  const code = String(error.providerCode || '').toLowerCase();
  return error.status === 401 ||
    (error.status === 400 && (
      code.includes('api_key_invalid') ||
      message.includes('api key not valid') ||
      message.includes('invalid api key')
    ));
}

function shouldTryNextProvider(error) {
  if (error.code === 'TIMEOUT') return true;
  if (!error.status) return true;
  return [400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 502, 503, 504].includes(error.status);
}


function isRetryableProviderError(error) {
  if (error.code === 'TIMEOUT' || !error.status) return true;
  return [408, 425, 429, 500, 502, 503, 504].includes(error.status);
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function routeRequest(payload) {
  const providers = getProviders(payload);

  if (!providers.length) {
    const e = new Error(
      payload.images.length || payload.files.length || payload.audio
        ? 'NO_MULTIMODAL_PROVIDER'
        : 'NO_PROVIDER_CONFIGURED'
    );
    e.code = e.message;
    throw e;
  }

  const deadline = Date.now() + ROUTER_DEADLINE_MS;
  const failures = [];

  for (const provider of providers) {
    if (Date.now() >= deadline) break;

    const keys = provider.type === 'gemini' ? provider.keys : [provider.key];

    for (let index = 0; index < keys.length; index++) {
      let attempt = 0;
      while (attempt <= MAX_PROVIDER_RETRIES && Date.now() < deadline) {
        try {
          const result = await callProvider(provider, payload, keys[index], deadline);
          console.info('[AskScreen Router] Provider succeeded', {
            provider: provider.name,
            model: provider.model,
            latencyMs: result.diagnostics.latencyMs,
            attempt: attempt + 1
          });
          return result;
        } catch (error) {
          failures.push({
            provider: provider.name,
            status: error.status || error.code || 'NETWORK_ERROR',
            attempt: attempt + 1
          });
          console.warn('[AskScreen Router] Provider attempt failed', {
            provider: provider.name,
            status: error.status || error.code || 'NETWORK_ERROR',
            attempt: attempt + 1
          });

          if (
            provider.type === 'gemini' &&
            isInvalidGeminiKey(error) &&
            index < keys.length - 1
          ) break;

          const canRetry = isRetryableProviderError(error) &&
            attempt < MAX_PROVIDER_RETRIES;
          const backoff = error.retryAfterMs || RETRY_BASE_DELAY_MS * (attempt + 1);
          if (canRetry && Date.now() + backoff + 250 < deadline) {
            attempt++;
            await wait(backoff);
            continue;
          }

          if (error.status === 429 || isRetryableProviderError(error)) {
            coolDown(provider.name);
            break;
          }
          if (!shouldTryNextProvider(error)) throw error;
          coolDown(provider.name);
          break;
        }
      }
    }
  }

  console.error('[AskScreen Router] No provider completed the request', failures);
  const e = new Error('ALL_PROVIDERS_FAILED');
  e.code = 'ALL_PROVIDERS_FAILED';
  throw e;
}

exports.handler = async function handler(event) {
  const requestId = randomUUID();
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 204, headers: CORS_HEADERS, body: '' };
  }

  if (event.httpMethod !== 'POST') {
    return response(405, {
      success: false,
      error: 'Method not allowed. Use POST.'
    }, requestId);
  }

  const contentType = getHeader(event.headers, 'content-type').toLowerCase();
  if (!contentType.includes('application/json')) {
    return response(415, {
      success: false,
      error: 'Content-Type must be application/json.'
    }, requestId);
  }

  let bodyBytes;
  try {
    bodyBytes = byteLengthOfBody(event);
  } catch {
    return response(400, {
      success: false,
      error: 'Invalid request body.'
    }, requestId);
  }

  if (bodyBytes > MAX_BODY_BYTES) {
    return response(413, {
      success: false,
      error: 'Request is too large. Reduce attachment size and try again.'
    }, requestId);
  }

  const raw = safeJsonParse(decodeEventBody(event));
  if (!raw) {
    return response(400, {
      success: false,
      error: 'Invalid JSON request.'
    }, requestId);
  }

  let payload;
  try {
    payload = normalizePayload(raw);
  } catch (error) {
    const messages = {
      INVALID_REQUEST: 'Invalid request payload.',
      PROMPT_REQUIRED: 'Please provide a question.',
      PROMPT_TOO_LONG: 'The question is too long.',
      INVALID_ATTACHMENTS: 'Attachments must be provided as arrays.',
      TOO_MANY_ATTACHMENTS: 'Too many attachments. Maximum is 8.',
      UNSUPPORTED_ATTACHMENT_TYPE: 'One of the attached file types is not supported.',
      INVALID_ATTACHMENT_DATA: 'An attachment is empty or has invalid data.',
      INVALID_IMAGE_TYPE: 'An image attachment has an invalid media type.',
      INVALID_AUDIO_TYPE: 'The audio attachment has an invalid media type.',
      INVALID_IMAGE: 'Invalid image attachment.',
      INVALID_FILE: 'Invalid file attachment.',
      INVALID_AUDIO: 'Invalid audio attachment.'
    };

    return response(400, {
      success: false,
      error: messages[error.message] || 'Invalid attachment or request.'
    }, requestId);
  }

  let decodedUser;
  try {
    decodedUser = await verifyUser(event);
  } catch (error) {
    const status = error.statusCode || 503;
    const code = error.message === 'AUTH_NOT_CONFIGURED' ? 'AUTH_NOT_CONFIGURED' :
      (status === 401 ? 'AUTH_REQUIRED' : 'AUTH_ERROR');
    return response(status, {
      success: false,
      error: status === 401
        ? 'Please sign in to use AskScreen AI.'
        : 'Account verification is temporarily unavailable.',
      code
    }, requestId);
  }

  const feature = featureForRequest(raw);

  // Count only valid, authenticated requests so malformed requests do not use a trial.
  const retryAfterSeconds = getRateLimitRetryAfterSeconds(requestIp(event));
  if (retryAfterSeconds > 0) {
    return response(429, {
      success: false,
      error: 'Too many requests. Please wait and try again.',
      code: 'RATE_LIMITED',
      retryAfterSeconds
    }, requestId, { 'Retry-After': String(retryAfterSeconds) });
  }

  let reservation;
  try {
    reservation = await reserveFeatureUse(decodedUser.uid, feature, requestId);
  } catch (error) {
    if (error.message === 'SUBSCRIPTION_REQUIRED') {
      return response(402, {
        success: false,
        error: 'Your two free uses for this feature have been used. Subscribe to continue.',
        code: 'SUBSCRIPTION_REQUIRED',
        feature
      }, requestId);
    }
    console.error('[AskScreen Access] Usage reservation failed', { requestId, code: error.message || 'USAGE_ERROR' });
    return response(503, {
      success: false,
      error: 'We could not verify your usage allowance. Please try again.',
      code: 'USAGE_CHECK_UNAVAILABLE'
    }, requestId);
  }

  try {
    const result = await routeRequest(payload);
    return response(200, result, requestId);
  } catch (error) {
    try { await releaseFeatureUse(decodedUser.uid, reservation); }
    catch (rollbackError) {
      console.error('[AskScreen Access] Usage rollback failed', { requestId, code: rollbackError.message || 'ROLLBACK_ERROR' });
    }
    console.error('[AskScreen Router] Request failed', { requestId, code: error.code || 'UPSTREAM_FAILURE' });

    if (error.code === 'NO_PROVIDER_CONFIGURED') {
      return response(503, {
        success: false,
        error: 'No AI provider is configured on the server.',
        code: 'NO_PROVIDER_CONFIGURED', requestId
      }, requestId);
    }

    if (error.code === 'NO_MULTIMODAL_PROVIDER') {
      return response(503, {
        success: false,
        error: 'Image, audio and file questions require a configured Gemini API key. Please try again later.',
        code: 'NO_MULTIMODAL_PROVIDER', requestId
      }, requestId);
    }

    return response(503, {
      success: false,
      error: 'AskScreen AI is temporarily unable to answer. Please try again shortly.',
      code: 'UPSTREAM_UNAVAILABLE', requestId
    }, requestId);
  }
};
