'use strict';

const assert = require('node:assert/strict');
const { handler } = require('./netlify/functions/ask-screen');
const { handler: healthHandler } = require('./netlify/functions/health');
const { handler: transcriptionHandler } = require('./netlify/functions/record-transcription');

async function run() {
  const cases = [
    {
      name: 'OPTIONS preflight',
      event: { httpMethod: 'OPTIONS', headers: {} },
      status: 204
    },
    {
      name: 'Reject non-POST requests',
      event: { httpMethod: 'GET', headers: {} },
      status: 405
    },
    {
      name: 'Reject non-JSON requests',
      event: { httpMethod: 'POST', headers: { 'content-type': 'text/plain' }, body: 'hello' },
      status: 415
    },
    {
      name: 'Reject malformed JSON',
      event: { httpMethod: 'POST', headers: { 'content-type': 'application/json' }, body: '{bad json' },
      status: 400
    },
    {
      name: 'Reject empty prompts',
      event: { httpMethod: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ prompt: '   ' }) },
      status: 400
    },
    {
      name: 'Reject unsupported attachment types',
      event: { httpMethod: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ prompt: 'Read this', images: [{ mimeType: 'image/tiff', data: 'AAAA' }] }) },
      status: 400
    }
  ];

  for (const test of cases) {
    const result = await handler(test.event);
    assert.equal(result.statusCode, test.status, test.name);
    console.log('PASS ' + test.name);
  }

  const tooLarge = await handler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: ' '.repeat(4 * 1024 * 1024 + 1)
  });
  assert.equal(tooLarge.statusCode, 413, 'Reject oversized request bodies');
  console.log('PASS Reject oversized request bodies');

  const unauthenticated = await handler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ prompt: 'A valid test question' })
  });
  assert.equal(unauthenticated.statusCode, 401, 'Valid AI requests must require Firebase sign-in');
  assert.equal(JSON.parse(unauthenticated.body).code, 'AUTH_REQUIRED');
  console.log('PASS Valid requests require Firebase authentication');

  const transcriptionOptions = await transcriptionHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(transcriptionOptions.statusCode, 204, 'Transcription endpoint should allow CORS preflight');
  console.log('PASS Transcription endpoint OPTIONS preflight');

  const transcriptionGet = await transcriptionHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(transcriptionGet.statusCode, 405, 'Transcription endpoint should reject non-POST requests');
  console.log('PASS Transcription endpoint rejects non-POST requests');

  const transcriptionUnauthenticated = await transcriptionHandler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: '{}'
  });
  assert.equal(transcriptionUnauthenticated.statusCode, 401, 'Transcription endpoint must require Firebase sign-in');
  assert.equal(JSON.parse(transcriptionUnauthenticated.body).code, 'AUTH_REQUIRED');
  console.log('PASS Transcription endpoint requires Firebase authentication');

  const health = await healthHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(health.statusCode, 200, 'Health endpoint should respond successfully');
  const healthBody = JSON.parse(health.body);
  assert.equal(healthBody.status, 'ok');
  assert.equal(typeof healthBody.configuredProviderCount, 'number');
  assert.equal(typeof healthBody.providers.gemini, 'boolean');
  assert.equal(health.headers['Cache-Control'], 'no-store');
  console.log('PASS Health endpoint reports safe provider configuration status');

  const healthPost = await healthHandler({ httpMethod: 'POST', headers: {} });
  assert.equal(healthPost.statusCode, 405, 'Health endpoint should reject POST');
  console.log('PASS Health endpoint rejects unsupported methods');

  console.log('All backend validation and authentication-gate tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
