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
  process.env.GROQ_API_KEY = 'test-groq-key';
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
    assert.deepEqual(imageProviders.map(p => p.name), ['Groq', 'Cloudflare', 'Gemini']);
    assert.equal(imageProviders[0].model, 'qwen/qwen3.8-27b');
    assert.equal(imageProviders[1].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
    const textProviders = _test.getProviders({ images: [], files: [], audio: null });
    assert.deepEqual(textProviders.map(p => p.name), ['Groq', 'Cloudflare', 'Gemini']);
    assert.equal(textProviders[0].model, 'qwen/qwen3.8-27b');
    assert.equal(textProviders[1].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
    const pdfProviders = _test.getProviders({ images: [], files: [{ mimeType: 'application/pdf', data: 'cGRm' }], audio: null });
    assert.deepEqual(pdfProviders.map(p => p.name), ['Gemini']);
    console.log('PASS free provider order is Groq then Cloudflare, with Gemini retained for PDF/audio fallback');

    // Isolate the Gemini key-rotation test from the two non-Google providers.
    delete process.env.GROQ_API_KEY;
    delete process.env.CLOUDFLARE_API_TOKEN;
    delete process.env.CLOUDFLARE_ACCOUNT_ID;
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

    process.env.GROQ_API_KEY = 'test-groq-key';
    process.env.CLOUDFLARE_API_TOKEN = 'test-cloudflare-token';
    process.env.CLOUDFLARE_ACCOUNT_ID = 'test-cloudflare-account';
    let groqTextCalls = 0;
    global.fetch = async (url, options) => {
      assert.ok(String(url).includes('api.groq.com/openai/v1/chat/completions'));
      const body = JSON.parse(options.body);
      assert.equal(body.model, 'qwen/qwen3.8-27b');
      groqTextCalls++;
      return new Response(JSON.stringify({
        choices: [{ message: { content: 'Groq answered the text request.' }, finish_reason: 'stop' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const groqTextResult = await routeRequest({
      prompt: 'Test free text route', history: [], images: [], files: [], audio: null, requestedModel: ''
    });
    assert.equal(groqTextResult.provider, 'Groq');
    assert.equal(groqTextCalls, 1);
    console.log('PASS Groq handles text requests before fallback providers');

    let geminiCalls = 0;
    let groqCalls = 0;
    let cloudflareCalls = 0;
    global.fetch = async (url, options) => {
      if (String(url).includes('api.groq.com')) {
        groqCalls++;
        return new Response(JSON.stringify({ error: { message: 'Temporary Groq outage' } }), {
          status: 503, headers: { 'Content-Type': 'application/json' }
        });
      }
      if (String(url).includes('api.cloudflare.com')) {
        cloudflareCalls++;
        const body = JSON.parse(options.body);
        assert.equal(body.model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
        assert.ok(body.messages.some(message => Array.isArray(message.content) &&
          message.content.some(part => part.type === 'image_url')));
        return new Response(JSON.stringify({
          choices: [{ message: { content: 'The screen contains a sample document.' }, finish_reason: 'stop' }]
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      if (String(url).includes('generativelanguage.googleapis.com')) {
        geminiCalls++;
        return new Response(JSON.stringify({ error: { message: 'Gemini should not be reached after Cloudflare succeeds' } }), {
          status: 503, headers: { 'Content-Type': 'application/json' }
        });
      }
      throw new Error('Unexpected provider URL: ' + url);
    };
    const visionResult = await routeRequest({
      prompt: 'Describe this screen', history: [], images: [image], files: [], audio: null, requestedModel: ''
    });
    assert.equal(groqCalls, 1, 'Groq should be attempted first for image input');
    assert.equal(cloudflareCalls, 1, 'Cloudflare should be tried after Groq fails');
    assert.equal(geminiCalls, 0, 'Gemini should not be reached after Cloudflare succeeds');
    assert.equal(visionResult.provider, 'Cloudflare');
    assert.match(visionResult.answer, /sample document/);
    console.log('PASS Groq vision failure falls through to Cloudflare vision without calling Gemini');

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
