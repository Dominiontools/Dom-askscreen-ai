'use strict';

const assert = require('node:assert/strict');
const { handler, isEligibleForFreeTrial } = require('./netlify/functions/ask-screen');
const { handler: healthHandler } = require('./netlify/functions/health');
const { handler: transcriptionHandler } = require('./netlify/functions/record-transcription');
const { handler: adminHandler } = require('./netlify/functions/admin-dashboard');
const { handler: supportHandler } = require('./netlify/functions/support-tickets');

async function run() {
  assert.equal(isEligibleForFreeTrial('2026-11-15T10:00:00.000Z', '2026-11-15T00:00:00.000Z'), true,
    'Accounts created after the configured trial launch are eligible');
  assert.equal(isEligibleForFreeTrial('2026-11-14T23:59:59.000Z', '2026-11-15T00:00:00.000Z'), false,
    'Accounts created before the configured trial launch are not eligible');
  assert.equal(isEligibleForFreeTrial('2026-11-15T00:00:00.000Z', '2026-11-15T00:00:00.000Z'), true,
    'Accounts created at the configured trial launch are eligible');
  assert.equal(isEligibleForFreeTrial('2026-11-15T10:00:00.000Z', undefined), false,
    'Free trials fail closed when the launch timestamp is not configured');
  assert.equal(isEligibleForFreeTrial('not-a-date', '2026-11-15T00:00:00.000Z'), false,
    'Invalid account creation timestamps are not eligible');
  console.log('PASS Free-trial eligibility is limited to new accounts after configured launch');

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

  const adminOptions = await adminHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminOptions.statusCode, 204, 'Admin endpoint should allow CORS preflight');
  console.log('PASS Admin endpoint OPTIONS preflight');

  const adminPost = await adminHandler({ httpMethod: 'POST', headers: {} });
  assert.equal(adminPost.statusCode, 405, 'Admin endpoint should reject non-GET requests');
  console.log('PASS Admin endpoint rejects non-GET requests');

  const previousAdminEmails = process.env.ADMIN_EMAILS;
  delete process.env.ADMIN_EMAILS;
  const adminUnconfigured = await adminHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(adminUnconfigured.statusCode, 503, 'Admin endpoint must fail closed until ADMIN_EMAILS is configured');
  if (previousAdminEmails !== undefined) process.env.ADMIN_EMAILS = previousAdminEmails;
  console.log('PASS Admin endpoint fails closed when administrator allowlist is missing');

  const supportOptions = await supportHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(supportOptions.statusCode, 204, 'Support endpoint should allow CORS preflight');
  console.log('PASS Support endpoint OPTIONS preflight');

  const supportPut = await supportHandler({ httpMethod: 'PUT', headers: {} });
  assert.equal(supportPut.statusCode, 405, 'Support endpoint should reject unsupported methods');
  console.log('PASS Support endpoint rejects unsupported methods');

  const supportGetUnauthenticated = await supportHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(supportGetUnauthenticated.statusCode, 401, 'Support ticket history must require Firebase authentication');
  console.log('PASS Support ticket history requires Firebase authentication');

  const supportPostUnauthenticated = await supportHandler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ subject: 'Billing question', message: 'Please help me understand my subscription.' })
  });
  assert.equal(supportPostUnauthenticated.statusCode, 401, 'Support ticket submission must require Firebase authentication');
  console.log('PASS Support ticket submission requires Firebase authentication');

  console.log('All backend validation and authentication-gate tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
