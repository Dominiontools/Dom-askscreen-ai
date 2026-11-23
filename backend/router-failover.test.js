'use strict';

const assert = require('node:assert/strict');
const { routeRequest, _test } = require('./netlify/legacy-handlers/ask-screen');

async function run() {
  assert.equal(_test.PROVIDER_TIMEOUT_MS, 4_500);
  assert.equal(_test.ROUTER_DEADLINE_MS, 38_000);
  assert.equal(_test.classifyProviderFailure({ status: 429 }), 'QUOTA_LIMIT');
  assert.equal(_test.classifyProviderFailure({ status: 403, message: 'Quota exceeded' }), 'QUOTA_LIMIT');
  assert.equal(_test.classifyProviderFailure({ status: 402 }), 'BILLING_REQUIRED');
  assert.equal(_test.classifyProviderFailure({ status: 410 }), 'MODEL_OR_ENDPOINT_UNAVAILABLE');
  assert.equal(_test.classifyProviderFailure({ code: 'TIMEOUT' }), 'TEMPORARILY_UNAVAILABLE');

  const normalPayload = _test.normalizePayload({ prompt: 'What is this?' });
  assert.equal(normalPayload.preferredProvider, '');

  const previous = {
    GEMINI_API_KEY: process.env.GEMINI_API_KEY,
    GEMINI_API_KEY2: process.env.GEMINI_API_KEY2,
    GEMINI_API_KEY3: process.env.GEMINI_API_KEY3,
    OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY,
    OPENROUTER_MODEL: process.env.OPENROUTER_MODEL,
    GROQ_API_KEY: process.env.GROQ_API_KEY,
    COHERE_API_KEY: process.env.COHERE_API_KEY,
    NVIDIA_API_KEY: process.env.NVIDIA_API_KEY,
    ZAI_API_KEY: process.env.ZAI_API_KEY,
    CLOUDFLARE_API_TOKEN: process.env.CLOUDFLARE_API_TOKEN,
    CLOUDFLARE_API_KEY: process.env.CLOUDFLARE_API_KEY,
    CLOUDFLARE_ACCOUNT_ID: process.env.CLOUDFLARE_ACCOUNT_ID,
    CUSTOM1_API_KEY: process.env.CUSTOM1_API_KEY,
    CUSTOM1_BASE_URL: process.env.CUSTOM1_BASE_URL,
    CUSTOM1_MODEL: process.env.CUSTOM1_MODEL
  };
  const originalFetch = global.fetch;
  process.env.GEMINI_API_KEY = 'test-gemini-key';
  process.env.GEMINI_API_KEY2 = 'test-gemini-key-2';
  process.env.GEMINI_API_KEY3 = 'test-gemini-key-3';
  process.env.OPENROUTER_API_KEY = 'test-openrouter-key';
  process.env.OPENROUTER_MODEL = 'openai/gpt-4o-mini';
  for (const key of ['GROQ_API_KEY', 'COHERE_API_KEY', 'NVIDIA_API_KEY', 'ZAI_API_KEY', 'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_API_KEY', 'CLOUDFLARE_ACCOUNT_ID', 'CUSTOM1_API_KEY', 'CUSTOM1_BASE_URL', 'CUSTOM1_MODEL']) delete process.env[key];

  let quotaCalls = 0;
  global.fetch = async (url, options) => {
    if (!String(url).includes('generativelanguage.googleapis.com')) throw new Error('Unexpected provider URL: ' + url);
    quotaCalls++;
    const key = options.headers['x-goog-api-key'];
    if (key === 'test-gemini-key') {
      return new Response(JSON.stringify({ error: { message: 'Quota exceeded' } }), {
        status: 429, headers: { 'Content-Type': 'application/json' }
      });
    }
    return new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: 'Gemini key two answered.' }] }, finishReason: 'STOP' }]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  try {
    const quotaResult = await routeRequest({
      prompt: 'Test Gemini key rotation',
      history: [],
      images: [],
      files: [],
      audio: null,
      requestedModel: ''
    });
    assert.equal(quotaResult.provider, 'Gemini');
    assert.equal(quotaResult.answer, 'Gemini key two answered.');
    assert.equal(quotaCalls, 2, 'A quota-limited Gemini key must immediately rotate to the next key');
    assert.equal(quotaResult.diagnostics.providerAttempts[0].keySlot, 1);
    assert.equal(quotaResult.diagnostics.providerAttempts[0].category, 'QUOTA_LIMIT');
    assert.equal(quotaResult.diagnostics.activeKeySlot, 2, 'The response must identify the Gemini key slot that succeeded');
    console.log('PASS Gemini quota errors rotate to the next API key and report the limited key slot');
  } finally {
    global.fetch = originalFetch;
  }

  process.env.NVIDIA_API_KEY = 'test-nvidia-key';
  let geminiCalls = 0;
  let openRouterCalls = 0;
  let nvidiaCalls = 0;
  let recoverGemini = false;
  let failOpenRouter = false;
  global.fetch = async url => {
    if (String(url).includes('generativelanguage.googleapis.com')) {
      geminiCalls++;
      if (recoverGemini) return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text: 'Gemini recovered.' }] }, finishReason: 'STOP' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      return new Response(JSON.stringify({ error: { message: 'temporary upstream outage' } }), {
        status: 503, headers: { 'Content-Type': 'application/json' }
      });
    }
    if (String(url).includes('openrouter.ai')) {
      openRouterCalls++;
      if (failOpenRouter) return new Response(JSON.stringify({ error: { message: 'temporary outage' } }), {
        status: 503, headers: { 'Content-Type': 'application/json' }
      });
      return new Response(JSON.stringify({
        choices: [{ message: { content: 'The image shows a sample classroom slide.' }, finish_reason: 'stop' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    if (String(url).includes('integrate.api.nvidia.com')) {
      nvidiaCalls++;
      return new Response(JSON.stringify({ error: { message: 'model retired' } }), {
        status: 410, headers: { 'Content-Type': 'application/json' }
      });
    }
    throw new Error('Unexpected provider URL: ' + url);
  };

  try {
    const result = await routeRequest({
      prompt: 'Describe this screen',
      history: [],
      images: [{ mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') }],
      files: [],
      audio: null,
      requestedModel: ''
    });
    assert.equal(result.provider, 'OpenRouter');
    assert.match(result.answer, /classroom slide/);
    assert.equal(geminiCalls, 1, 'A provider-wide 503 must not waste time retrying every Gemini key');
    assert.equal(openRouterCalls, 1, 'The image must be passed to the compatible vision fallback');
    const manualResult = await routeRequest({
      prompt: 'Retry using the selected provider',
      history: [],
      images: [],
      files: [],
      audio: null,
      requestedModel: '',
      preferredProvider: 'OpenRouter'
    });
    assert.equal(manualResult.provider, 'OpenRouter', 'A manual provider choice must be attempted first');
    assert.equal(geminiCalls, 1, 'Manual provider switching must not start with the failed default provider');
    assert.equal(openRouterCalls, 2);
    console.log('PASS manual provider preference is attempted first without starting a new request context');

    recoverGemini = true;
    const recovered = await routeRequest({
      prompt: 'Try a provider again after a temporary failure',
      history: [],
      images: [{ mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') }],
      files: [],
      audio: null,
      requestedModel: ''
    });
    assert.equal(recovered.provider, 'Gemini', 'A stale cooldown must not permanently skip a recovered compatible provider');
    assert.equal(geminiCalls, 2);
    console.log('PASS a provider on cooldown is still probed when the next request needs a working provider');

    recoverGemini = false;
    failOpenRouter = true;
    const beforeNvidia = nvidiaCalls;
    let allFailed;
    try {
      await routeRequest({
        prompt: 'Exercise a retired NVIDIA model and continue routing',
        history: [],
        images: [{ mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') }],
        files: [],
        audio: null,
        requestedModel: ''
      });
    } catch (error) {
      allFailed = error;
    }
    assert.equal(nvidiaCalls, beforeNvidia + 1, 'A retired NVIDIA model must be recorded and must not abort the remaining router flow');
    assert.equal(allFailed?.code, 'ALL_PROVIDERS_FAILED');
    assert.ok(allFailed.providerFailures.some(item => item.provider === 'NVIDIA' && item.category === 'MODEL_OR_ENDPOINT_UNAVAILABLE'));
    console.log('PASS NVIDIA HTTP 410 is classified and does not prematurely abort provider failover');
  } finally {
    global.fetch = originalFetch;
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
