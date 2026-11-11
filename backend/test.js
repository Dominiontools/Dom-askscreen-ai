'use strict';

const assert = require('node:assert/strict');
const { handler } = require('./netlify/functions/ask-screen');

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

  console.log('All backend request-validation tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
