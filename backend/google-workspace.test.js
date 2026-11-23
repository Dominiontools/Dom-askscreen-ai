'use strict';

const assert = require('node:assert/strict');
const { _test } = require('./netlify/legacy-handlers/google-workspace');

assert.equal(_test.encodeBase64Url('hello'), Buffer.from('hello').toString('base64url'));
assert.doesNotThrow(() => _test.requireScopes({ scopes: ['https://www.googleapis.com/auth/gmail.send'] }, ['https://www.googleapis.com/auth/gmail.send']));
assert.throws(
  () => _test.requireScopes({ scopes: [] }, ['https://www.googleapis.com/auth/calendar.events']),
  error => error.message === 'GOOGLE_SCOPE_MISSING' && error.statusCode === 403
);
console.log('PASS Google workspace MIME encoding and granted-scope enforcement');
