'use strict';

const assert = require('node:assert/strict');
const { handler } = require('./netlify/legacy-handlers/delete-account');

async function run() {
  const options = await handler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(options.statusCode, 204, 'Delete endpoint supports CORS preflight');

  const get = await handler({ httpMethod: 'GET', headers: {} });
  assert.equal(get.statusCode, 405, 'Delete endpoint rejects GET');

  const unauthenticated = await handler({ httpMethod: 'POST', headers: {} });
  assert.equal(unauthenticated.statusCode, 401, 'Delete endpoint requires Firebase authentication');

  console.log('PASS Account deletion endpoint method and authentication guards');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
