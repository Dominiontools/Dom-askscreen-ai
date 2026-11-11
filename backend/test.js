'use strict';

const assert = require('node:assert/strict');
const { handler } = require('./netlify/functions/ask-screen');
const { handler: healthHandler } = require('./netlify/functions/health');

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


  // Use a unique IP so this test is isolated from other requests in this process.
  const quotaIp = '198.51.100.77';
  for (let i = 0; i < 65; i++) {
    const invalid = await handler({
      httpMethod: 'POST',
      headers: { 'content-type': 'application/json', 'client-ip': quotaIp },
      body: JSON.stringify({ prompt: '   ' })
    });
    assert.equal(invalid.statusCode, 400, 'Invalid requests must not consume quota');
  }
  const firstValid = await handler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json', 'client-ip': quotaIp },
    body: JSON.stringify({ prompt: 'A valid test question' })
  });
  assert.equal(firstValid.statusCode, 503, 'Valid request should reach provider routing, not be rate-limited');
  console.log('PASS Invalid requests do not consume request quota');

  for (let i = 1; i < 60; i++) {
    await handler({
      httpMethod: 'POST',
      headers: { 'content-type': 'application/json', 'client-ip': quotaIp },
      body: JSON.stringify({ prompt: 'Valid quota test ' + i })
    });
  }
  const limited = await handler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json', 'client-ip': quotaIp },
    body: JSON.stringify({ prompt: 'One request over the limit' })
  });
  assert.equal(limited.statusCode, 429, 'Requests over the configured default limit should be rejected');
  assert.equal(limited.headers['Retry-After'], '60', 'Rate limit should provide Retry-After');
  assert.equal(JSON.parse(limited.body).code, 'RATE_LIMITED');
  console.log('PASS Rate limit returns retry guidance after 60 valid requests');


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

  console.log('All backend request-validation, quota, and health tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
