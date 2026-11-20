'use strict';

const assert = require('node:assert/strict');
const { _test } = require('./netlify/legacy-handlers/instagram-oauth');

const key = Buffer.alloc(32, 11);
const payload = { access_token: 'meta-token', expires_at: null };
const encrypted = _test.encryptJson(payload, key);
assert.notEqual(encrypted.ciphertext, Buffer.from(JSON.stringify(payload)).toString('base64'));
assert.deepEqual(_test.decryptJson(encrypted, key), payload);
assert.throws(() => _test.decryptJson(encrypted, Buffer.alloc(32, 12)));
assert.equal(_test.stateDigest('same-state'), _test.stateDigest('same-state'));
assert.notEqual(_test.stateDigest('state-one'), _test.stateDigest('state-two'));

const config = {
  appId: '123456789',
  configId: '987654321',
  redirectUri: 'https://domaskscreenai.netlify.app/.netlify/functions/instagram-oauth-callback'
};
const url = new URL(_test.authorizationUrl(config, 'random-state'));
assert.equal(url.origin, 'https://www.facebook.com');
assert.equal(url.pathname, '/v27.0/dialog/oauth');
assert.equal(url.searchParams.get('client_id'), config.appId);
assert.equal(url.searchParams.get('config_id'), config.configId);
assert.equal(url.searchParams.get('redirect_uri'), config.redirectUri);
assert.equal(url.searchParams.get('response_type'), 'code');
assert.equal(url.searchParams.get('override_default_response_type'), 'true');
assert.equal(url.searchParams.get('state'), 'random-state');

console.log('PASS Instagram Unified Login URL, one-time state hashing and encrypted token storage');
