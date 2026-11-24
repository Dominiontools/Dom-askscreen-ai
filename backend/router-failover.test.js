'use strict';

const assert = require('node:assert/strict');
const { routeRequest, _test } = require('./netlify/legacy-handlers/ask-screen');

async function run() {
  assert.equal(_test.PROVIDER_TIMEOUT_MS, 4_500);
  assert.equal(_test.ROUTER_DEADLINE_MS, 38_000);
  assert.equal(_test.classifyProviderFailure({ status: 429 }), 'QUOTA_LIMIT');
  assert.equal(_test.classifyProviderFailure({ status: 503 }), 'TEMPORARILY_UNAVAILABLE');
  assert.equal(_test.classifyProviderFailure({ status: 402 }), 'BILLING_REQUIRED');

  const envNames = [
    'GEMINI_API_KEY', 'GEMINI_API_KEY2', 'GEMINI_API_KEY3',
    'GEMINI_API_KEY_4', 'GEMINI_API_KEY_5', 'GEMINI_API_KEY_6',
    'CLOUDFLARE_API_TOKEN', 'CLOUDFLARE_API_KEY', 'CLOUDFLARE_ACCOUNT_ID',
    'CLOUDFLARE_MODEL', 'CLOUDFLARE_VISION_MODEL'
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

  try {
    const image = { mimeType: 'image/jpeg', data: Buffer.from('test image').toString('base64') };
    const imageProviders = _test.getProviders({ images: [image], files: [], audio: null });
    assert.deepEqual(imageProviders.map(p => p.name), ['Cloudflare']);
    assert.equal(imageProviders[0].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
    const textProviders = _test.getProviders({ images: [], files: [], audio: null });
    assert.deepEqual(textProviders.map(p => p.name), ['Cloudflare']);
    assert.equal(textProviders[0].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
    console.log('PASS only Cloudflare Mistral is active for text and image requests');

    let textCalls = 0;
    global.fetch = async (url, options) => {
      assert.ok(String(url).includes('/ai/v1/chat/completions'));
      assert.equal(options.headers.Authorization, 'Bearer test-cloudflare-token');
      const body = JSON.parse(options.body);
      assert.equal(body.model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
      assert.ok(body.messages.some(message => message.content === 'Answer this short test prompt.'));
      textCalls++;
      return new Response(JSON.stringify({
        choices: [{ message: { content: 'Cloudflare text test passed.' }, finish_reason: 'stop' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const textResult = await routeRequest({
      prompt: 'Answer this short test prompt.', history: [], images: [], files: [], audio: null, requestedModel: ''
    });
    assert.equal(textResult.provider, 'Cloudflare');
    assert.equal(textResult.answer, 'Cloudflare text test passed.');
    assert.equal(textCalls, 1);
    console.log('PASS Cloudflare text request succeeds with the isolated Mistral model');

    let imageCalls = 0;
    global.fetch = async (url, options) => {
      assert.ok(String(url).includes('/ai/v1/chat/completions'));
      const body = JSON.parse(options.body);
      assert.equal(body.model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
      assert.ok(body.messages.some(message => Array.isArray(message.content) &&
        message.content.some(part => part.type === 'image_url' &&
          part.image_url.url.startsWith('data:image/jpeg;base64,'))));
      imageCalls++;
      return new Response(JSON.stringify({
        choices: [{ message: { content: 'The image contains a sample document.' }, finish_reason: 'stop' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const imageResult = await routeRequest({
      prompt: 'Describe this screen', history: [], images: [image], files: [], audio: null, requestedModel: ''
    });
    assert.equal(imageResult.provider, 'Cloudflare');
    assert.match(imageResult.answer, /sample document/);
    assert.equal(imageCalls, 1);
    console.log('PASS Cloudflare image request sends a vision payload and returns an answer');

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
