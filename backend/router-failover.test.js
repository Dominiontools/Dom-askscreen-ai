'use strict';

const assert = require('node:assert/strict');
const { routeRequest, _test } = require('./netlify/legacy-handlers/ask-screen');

async function run() {
  assert.equal(_test.PROVIDER_TIMEOUT_MS, 4_500);
  assert.equal(_test.ROUTER_DEADLINE_MS, 38_000);
  assert.equal(_test.classifyProviderFailure({ status: 429 }), 'QUOTA_LIMIT');
  assert.equal(_test.classifyProviderFailure({ status: 503 }), 'TEMPORARILY_UNAVAILABLE');
  assert.equal(_test.classifyProviderFailure({ status: 402 }), 'BILLING_REQUIRED');
  assert.equal(_test.isGeminiKeyRotationError({ status: 503 }), true);
  assert.equal(_test.isGeminiKeyRotationError({ code: 'TIMEOUT' }), true);

  const envNames = [
    'GEMINI_API_KEY', 'GEMINI_API_KEY2', 'GEMINI_API_KEY3',
    'GEMINI_API_KEY_4', 'GEMINI_API_KEY_5', 'GEMINI_API_KEY_6',
    'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_API_KEY', 'CLOUDFLARE_ACCOUNT_ID',
    'CLOUDFLARE_MODEL', 'CLOUDFLARE_VISION_MODEL',
    'OPENROUTER_API_KEY', 'GROQ_API_KEY', 'COHERE_API_KEY',
    'NVIDIA_API_KEY', 'ZAI_API_KEY', 'CUSTOM1_API_KEY',
    'CUSTOM1_BASE_URL', 'CUSTOM1_MODEL'
  ];
  const previous = Object.fromEntries(envNames.map(key => [key, process.env[key]]));
  const originalFetch = global.fetch;

  process.env.GEMINI_API_KEY = 'test-gemini-key-1';
  process.env.GEMINI_API_KEY2 = 'test-gemini-key-2';
  process.env.GEMINI_API_KEY3 = 'test-gemini-key-3';
  process.env.GEMINI_API_KEY_4 = 'test-gemini-key-4';
  process.env.GEMINI_API_KEY_5 = 'test-gemini-key-5';
  process.env.GEMINI_API_KEY_6 = 'test-gemini-key-6';
  process.env.CLOUDFLARE_API_TOKEN = 'test-cloudflare-token';
  process.env.CLOUDFLARE_ACCOUNT_ID = 'test-cloudflare-account';
  delete process.env.CLOUDFLARE_API_KEY;
  delete process.env.CLOUDFLARE_MODEL;
  delete process.env.CLOUDFLARE_VISION_MODEL;
  for (const key of ['OPENROUTER_API_KEY', 'GROQ_API_KEY', 'COHERE_API_KEY',
    'NVIDIA_API_KEY', 'ZAI_API_KEY', 'CUSTOM1_API_KEY', 'CUSTOM1_BASE_URL', 'CUSTOM1_MODEL']) {
    delete process.env[key];
  }

  try {
    const image = { mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') };
    const imageProviders = _test.getProviders({ images: [image], files: [], audio: null });
    assert.deepEqual(imageProviders.map(p => p.name), ['Gemini', 'Cloudflare']);
    assert.equal(imageProviders[1].model, '@cf/qwen/qwen3.8-27b');
    const textProviders = _test.getProviders({ images: [], files: [], audio: null });
    assert.deepEqual(textProviders.map(p => p.name), ['Gemini', 'Cloudflare']);
    assert.equal(textProviders[1].model, '@cf/zai-org/glm-4.7-flash');
    console.log('PASS only free Gemini and Cloudflare providers are enabled; Cloudflare uses free-tier text and vision models');

    let quotaCalls = 0;
    global.fetch = async (url, options) => {
      if (!String(url).includes('generativelanguage.googleapis.com')) throw new Error('Unexpected provider URL: ' + url);
      quotaCalls++;
      const key = options.headers['x-goog-api-key'];
      if (key !== 'test-gemini-key-6') {
        return new Response(JSON.stringify({ error: { message: 'Quota exceeded' } }), {
          status: 429, headers: { 'Content-Type': 'application/json' }
        });
      }
      return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text: 'Gemini key six answered.' }] }, finishReason: 'STOP' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const quotaResult = await routeRequest({
      prompt: 'Test Gemini key rotation', history: [], images: [], files: [], audio: null, requestedModel: ''
    });
    assert.equal(quotaResult.provider, 'Gemini');
    assert.equal(quotaResult.answer, 'Gemini key six answered.');
    assert.equal(quotaCalls, 6);
    assert.equal(quotaResult.diagnostics.activeKeySlot, 6);
    console.log('PASS quota errors rotate through all six Gemini keys');

    let geminiCalls = 0;
    let cloudflareCalls = 0;
    global.fetch = async (url, options) => {
      if (String(url).includes('generativelanguage.googleapis.com')) {
        geminiCalls++;
        return new Response(JSON.stringify({ error: { message: 'Temporary Gemini service outage' } }), {
          status: 503, headers: { 'Content-Type': 'application/json' }
        });
      }
      if (String(url).includes('api.cloudflare.com')) {
        cloudflareCalls++;
        const body = JSON.parse(options.body);
        assert.equal(body.model, '@cf/qwen/qwen3.8-27b');
        assert.ok(body.messages.some(message => Array.isArray(message.content) &&
          message.content.some(part => part.type === 'image_url')));
        return new Response(JSON.stringify({
          choices: [{ message: { content: 'The screen contains a sample document.' }, finish_reason: 'stop' }]
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      throw new Error('Unexpected provider URL: ' + url);
    };
    const visionResult = await routeRequest({
      prompt: 'Describe this screen', history: [], images: [image], files: [], audio: null, requestedModel: ''
    });
    assert.equal(geminiCalls, 6, 'Temporary Gemini failures should rotate through all six account keys');
    assert.equal(cloudflareCalls, 1, 'Cloudflare vision should be tried after all Gemini keys fail');
    assert.equal(visionResult.provider, 'Cloudflare');
    assert.match(visionResult.answer, /sample document/);
    console.log('PASS six Gemini 503 failures fall through to Cloudflare vision');

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
