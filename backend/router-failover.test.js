'use strict';

const assert = require('node:assert/strict');
const { routeRequest, _test } = require('./netlify/legacy-handlers/ask-screen');

async function run() {
  assert.equal(_test.PROVIDER_TIMEOUT_MS, 4_500);
  assert.equal(_test.ROUTER_DEADLINE_MS, 38_000);
  assert.equal(_test.classifyProviderFailure({ status: 503 }), 'TEMPORARILY_UNAVAILABLE');

  const names = [
    'GEMINI_API_KEY', 'GEMINI_API_KEY2', 'GEMINI_API_KEY3',
    'GEMINI_API_KEY_4', 'GEMINI_API_KEY_5', 'GEMINI_API_KEY_6',
    'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_API_KEY', 'CLOUDFLARE_ACCOUNT_ID',
    'OPENROUTER_API_KEY', 'GROQ_API_KEY', 'COHERE_API_KEY', 'NVIDIA_API_KEY',
    'ZAI_API_KEY', 'CUSTOM1_API_KEY', 'CUSTOM1_BASE_URL', 'CUSTOM1_MODEL'
  ];
  const previous = Object.fromEntries(names.map(key => [key, process.env[key]]));
  const originalFetch = global.fetch;

  for (const [index, key] of [
    'GEMINI_API_KEY', 'GEMINI_API_KEY2', 'GEMINI_API_KEY3',
    'GEMINI_API_KEY_4', 'GEMINI_API_KEY_5', 'GEMINI_API_KEY_6'
  ].entries()) process.env[key] = 'test-gemini-key-' + (index + 1);
  for (const key of names.filter(key => !key.startsWith('GEMINI_'))) delete process.env[key];

  try {
    const image = { mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') };
    const imageProviders = _test.getProviders({ images: [image], files: [], audio: null });
    const textProviders = _test.getProviders({ images: [], files: [], audio: null });
    assert.deepEqual(imageProviders.map(p => p.name), ['Gemini']);
    assert.deepEqual(textProviders.map(p => p.name), ['Gemini']);
    assert.deepEqual(imageProviders[0].keys, ['test-gemini-key-1']);
    assert.deepEqual(textProviders[0].keys, ['test-gemini-key-1']);
    console.log('PASS only Gemini key 1 is active for text and screen-image requests');

    let calls = 0;
    global.fetch = async (url, options) => {
      calls++;
      assert.ok(String(url).includes('generativelanguage.googleapis.com'));
      assert.equal(options.headers['x-goog-api-key'], 'test-gemini-key-1');
      return new Response(JSON.stringify({
        candidates: [{ content: { parts: [{ text: 'Gemini key one answered.' }] }, finishReason: 'STOP' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const result = await routeRequest({
      prompt: 'Test first Gemini key', history: [], images: [], files: [], audio: null, requestedModel: ''
    });
    assert.equal(result.provider, 'Gemini');
    assert.equal(result.answer, 'Gemini key one answered.');
    assert.equal(calls, 1);
    assert.equal(result.diagnostics.activeKeySlot, 1);
    console.log('PASS request uses Gemini key 1 only and does not silently fall through');

  } finally {
    global.fetch = originalFetch;
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
  }
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
