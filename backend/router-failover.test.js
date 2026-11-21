'use strict';

const assert = require('node:assert/strict');
const { routeRequest, _test } = require('./netlify/legacy-handlers/ask-screen');

async function run() {
  assert.equal(_test.PROVIDER_TIMEOUT_MS, 8_000);
  assert.equal(_test.ROUTER_DEADLINE_MS, 18_000);

  const previous = {
    GEMINI_API_KEY: process.env.GEMINI_API_KEY,
    GEMINI_API_KEY2: process.env.GEMINI_API_KEY2,
    GEMINI_API_KEY3: process.env.GEMINI_API_KEY3,
    OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY,
    OPENROUTER_MODEL: process.env.OPENROUTER_MODEL
  };
  const originalFetch = global.fetch;
  process.env.GEMINI_API_KEY = 'test-gemini-key';
  process.env.GEMINI_API_KEY2 = 'test-gemini-key-2';
  process.env.GEMINI_API_KEY3 = 'test-gemini-key-3';
  process.env.OPENROUTER_API_KEY = 'test-openrouter-key';
  process.env.OPENROUTER_MODEL = 'openai/gpt-4o-mini';

  let geminiCalls = 0;
  let openRouterCalls = 0;
  global.fetch = async url => {
    if (String(url).includes('generativelanguage.googleapis.com')) {
      geminiCalls++;
      return new Response(JSON.stringify({ error: { message: 'temporary upstream outage' } }), {
        status: 503, headers: { 'Content-Type': 'application/json' }
      });
    }
    if (String(url).includes('openrouter.ai')) {
      openRouterCalls++;
      return new Response(JSON.stringify({
        choices: [{ message: { content: 'The image shows a sample classroom slide.' }, finish_reason: 'stop' }]
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
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
    console.log('PASS image requests fail over from Gemini outage to OpenRouter vision without retry delays');
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
