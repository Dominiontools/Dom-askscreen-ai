'use strict';

const assert = require('node:assert/strict');
const { _test } = require('./netlify/legacy-handlers/google-oauth');

const key = Buffer.alloc(32, 7);
const payload = { access_token: 'test-access', refresh_token: 'test-refresh', expires_at: 12345 };
const encrypted = _test.encryptJson(payload, key);
assert.notEqual(encrypted.ciphertext, Buffer.from(JSON.stringify(payload)).toString('base64'));
assert.deepEqual(_test.decryptJson(encrypted, key), payload);
assert.throws(() => _test.decryptJson(encrypted, Buffer.alloc(32, 8)));
assert.equal(_test.stateDigest('same-state'), _test.stateDigest('same-state'));
assert.notEqual(_test.stateDigest('state-one'), _test.stateDigest('state-two'));
assert.ok(_test.GOOGLE_SCOPES.includes('https://www.googleapis.com/auth/gmail.readonly'));
assert.ok(_test.GOOGLE_SCOPES.includes('https://www.googleapis.com/auth/gmail.compose'));
assert.ok(_test.GOOGLE_SCOPES.includes('https://www.googleapis.com/auth/gmail.send'));
assert.ok(_test.GOOGLE_SCOPES.includes('https://www.googleapis.com/auth/calendar.events'));
console.log('PASS Google OAuth token encryption, state hashing and least-privilege scope configuration');
