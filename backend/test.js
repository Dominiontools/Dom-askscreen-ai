'use strict';

const assert = require('node:assert/strict');
const { handler, isEligibleForFreeTrial, featureForRequest } = require('./netlify/functions/ask-screen');
const { handler: healthHandler } = require('./netlify/functions/health');
const { handler: transcriptionHandler } = require('./netlify/functions/record-transcription');
const { handler: adminHandler } = require('./netlify/functions/admin-dashboard');
const { handler: adminUsersHandler } = require('./netlify/functions/admin-users');
const { handler: adminProductSubscriptionsHandler } = require('./netlify/functions/admin-product-subscriptions');
const { handler: adminSettingsHandler } = require('./netlify/functions/admin-settings');
const { handler: appUpdateHandler } = require('./netlify/functions/app-update');
const { handler: supportHandler } = require('./netlify/functions/support-tickets');
const { handler: supportAdminHandler } = require('./netlify/functions/support-admin');
const { handler: accountStatusHandler } = require('./netlify/functions/account-status');
const { DEFAULT_PRODUCTS, normalizeCatalog, calculateBundle } = require('./pricing-catalog');
const { evaluateProductSubscription, summarizeProductSubscriptions } = require('./product-subscriptions');

async function run() {
  const defaultPricing = normalizeCatalog(DEFAULT_PRODUCTS);
  assert.equal(defaultPricing.length, 22, 'The catalogue must include core, specialist products and separate email management');
  assert.equal(defaultPricing.find(item => item.id === 'core').independent, undefined, 'DOM AI Core remains its own core subscription');
  assert.equal(defaultPricing.find(item => item.id === 'video_annotation').independent, true, 'Work services can be subscribed to independently of Core');
  assert.equal(defaultPricing.find(item => item.id === 'email_management').independent, true, 'Email Management remains an independent monthly product');
  const defaultBundles = calculateBundle(defaultPricing, 10);
  assert.equal(defaultBundles.aiSuite.regularPriceCents, 52500, 'AI suite regular total should be $525.00');
  assert.equal(defaultBundles.aiSuite.priceCents, 47250, 'AI suite should apply the configured 10% discount');
  assert.equal(defaultBundles.completeBusiness.regularPriceCents, 54500, 'Complete bundle regular total should be $545.00');
  assert.equal(defaultBundles.completeBusiness.priceCents, 49050, 'Complete bundle should apply the configured 10% discount');
  assert.equal(defaultBundles.completeBusiness.savingsCents, 5450, 'Complete bundle should save $54.50');
  assert.throws(() => normalizeCatalog([{ id: 'core', monthlyPriceCents: 900 }]), /every product/);
  assert.throws(() => calculateBundle(defaultPricing, 31), /0 to 30/);
  console.log('PASS Product catalogue validation and 10% bundle calculations');
  const activeProduct = evaluateProductSubscription({ status: 'active', currentPeriodEnd: '2026-12-01T00:00:00.000Z' }, Date.parse('2026-11-15T00:00:00.000Z'));
  assert.equal(activeProduct.status, 'active', 'A product subscription with a future expiry grants active status');
  const expiredProduct = evaluateProductSubscription({ status: 'active', currentPeriodEnd: '2026-11-01T00:00:00.000Z' }, Date.parse('2026-11-15T00:00:00.000Z'));
  assert.equal(expiredProduct.status, 'expired', 'An expired product subscription never remains active');
  assert.equal(evaluateProductSubscription({ status: 'active' }, Date.now()).status, 'expired',
    'An active product record without an expiry fails closed');
  const products = summarizeProductSubscriptions({ video_annotation: { status: 'active', currentPeriodEnd: '2026-12-01T00:00:00.000Z' } }, Date.parse('2026-11-15T00:00:00.000Z'));
  assert.equal(products.length, 21, 'All independent specialist and email products are reported separately');
  assert.equal(products.find(item => item.id === 'video_annotation').status, 'active');
  assert.equal(products.find(item => item.id === 'core'), undefined, 'Core is not treated as a Work Centre product');
  console.log('PASS Independent product subscription status and expiry checks');
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
  assert.equal(featureForRequest({ queryType: 'SCREEN', feature: 'spoken' }), 'screen',
    'A client cannot relabel a screen request as spoken to change usage accounting');
  assert.equal(featureForRequest({ queryType: 'AUDIO', feature: 'screen' }), 'spoken',
    'Audio request types are classified as spoken regardless of the client feature label');
  assert.equal(featureForRequest({ queryType: 'TRANSCRIPTION', feature: 'screen' }), 'transcription',
    'Transcription request types retain their own usage category');
  assert.equal(featureForRequest({ queryType: 'TEXT', feature: 'spoken' }), 'screen',
    'Unknown and text-only request types fail closed to screen usage');
  console.log('PASS Usage accounting ignores client-supplied feature labels');

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

  const supportAdminOptions = await supportAdminHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(supportAdminOptions.statusCode, 204, 'Support admin endpoint should allow CORS preflight');
  console.log('PASS Support admin endpoint OPTIONS preflight');

  const supportAdminGet = await supportAdminHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(supportAdminGet.statusCode, 405, 'Support admin endpoint should reject non-POST requests');
  console.log('PASS Support admin endpoint rejects unsupported methods');

  const supportAdminUnauthenticated = await supportAdminHandler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ticketId: 'test-ticket', reply: 'We are checking this.', status: 'in_progress' })
  });
  assert.equal(supportAdminUnauthenticated.statusCode, 401, 'Support admin updates must require Firebase authentication');
  console.log('PASS Support admin updates require Firebase authentication');

  const adminSettingsOptions = await adminSettingsHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminSettingsOptions.statusCode, 204, 'Admin settings endpoint should allow CORS preflight');
  console.log('PASS Admin settings endpoint OPTIONS preflight');

  const adminSettingsPut = await adminSettingsHandler({ httpMethod: 'PUT', headers: {} });
  assert.equal(adminSettingsPut.statusCode, 405, 'Admin settings endpoint should reject unsupported methods');
  console.log('PASS Admin settings endpoint rejects unsupported methods');

  const previousFirebaseServiceAccount = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  delete process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  const adminSettingsPublicRead = await adminSettingsHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(adminSettingsPublicRead.statusCode, 503, 'Public app-settings read must fail closed when Firebase server configuration is missing');
  console.log('PASS Public app-settings read fails safely when Firebase is not configured');

  const adminSettingsUnauthenticated = await adminSettingsHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ announcement: '', features: { screen: true, spoken: true, transcription: true }, freeTrialLimit: 2 })
  });
  assert.equal(adminSettingsUnauthenticated.statusCode, 401, 'Settings writes must require a signed-in Firebase administrator');
  if (previousFirebaseServiceAccount !== undefined) process.env.FIREBASE_SERVICE_ACCOUNT_JSON = previousFirebaseServiceAccount;
  console.log('PASS Admin settings updates require authenticated administrator access');

  const appUpdateOptions = await appUpdateHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(appUpdateOptions.statusCode, 204, 'App update endpoint should allow CORS preflight');
  console.log('PASS App update endpoint OPTIONS preflight');

  const appUpdatePut = await appUpdateHandler({ httpMethod: 'PUT', headers: {} });
  assert.equal(appUpdatePut.statusCode, 405, 'App update endpoint should reject unsupported methods');
  console.log('PASS App update endpoint rejects unsupported methods');

  const appUpdateGetUnconfigured = await appUpdateHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(appUpdateGetUnconfigured.statusCode, 503, 'App update metadata must fail closed when Firebase server configuration is missing');
  console.log('PASS App update metadata fails safely when Firebase is not configured');

  const appUpdatePostUnconfigured = await appUpdateHandler({
    httpMethod: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ action: 'publish', versionCode: 2, versionName: '1.1', downloadUrl: 'https://github.com/Dominiontools/Dom-askscreen-ai/releases/download/v1.1/dom-ask-ai.apk' })
  });
  assert.equal(appUpdatePostUnconfigured.statusCode, 503, 'Release publishing must fail closed without Firebase server configuration');
  console.log('PASS Release publishing fails safely without Firebase server configuration');

  const adminProductOptions = await adminProductSubscriptionsHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminProductOptions.statusCode, 204, 'Admin product subscriptions endpoint should allow CORS preflight');
  console.log('PASS Admin product subscriptions endpoint OPTIONS preflight');

  const adminProductGet = await adminProductSubscriptionsHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(adminProductGet.statusCode, 405, 'Admin product subscriptions endpoint should reject non-POST requests');
  console.log('PASS Admin product subscriptions endpoint rejects unsupported methods');

  const adminProductUnauthenticated = await adminProductSubscriptionsHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ uid: 'customer-uid', productId: 'video_annotation', action: 'grant', currentPeriodEnd: '2027-01-01T00:00:00.000Z' })
  });
  assert.equal(adminProductUnauthenticated.statusCode, 401, 'Product access grants must require verified administrator authentication');
  console.log('PASS Product access grants require administrator authentication');

  const adminUsersOptions = await adminUsersHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminUsersOptions.statusCode, 204, 'Admin users endpoint should allow CORS preflight');
  console.log('PASS Admin users endpoint OPTIONS preflight');

  const adminUsersGet = await adminUsersHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(adminUsersGet.statusCode, 405, 'Admin users endpoint should reject non-POST requests');
  console.log('PASS Admin users endpoint rejects unsupported methods');

  const adminUsersUnauthenticated = await adminUsersHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ uid: 'customer-uid', disabled: true })
  });
  assert.equal(adminUsersUnauthenticated.statusCode, 401, 'Account management must require Firebase authentication');
  console.log('PASS Account management requires Firebase authentication');


  const accountStatusOptions = await accountStatusHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(accountStatusOptions.statusCode, 204, 'Account status endpoint should allow CORS preflight');
  console.log('PASS Account status endpoint OPTIONS preflight');

  const accountStatusPost = await accountStatusHandler({ httpMethod: 'POST', headers: {} });
  assert.equal(accountStatusPost.statusCode, 405, 'Account status endpoint should reject non-GET requests');
  console.log('PASS Account status endpoint rejects unsupported methods');

  const accountStatusUnauthenticated = await accountStatusHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(accountStatusUnauthenticated.statusCode, 401, 'Customer account status must require Firebase authentication');
  console.log('PASS Customer account status requires Firebase authentication');

  console.log('All backend validation and authentication-gate tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
