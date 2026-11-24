'use strict';

const assert = require('node:assert/strict');
const { Readable } = require('node:stream');
const { handler, isEligibleForFreeTrial, featureForRequest, _test: askScreenTests } = require('./netlify/legacy-handlers/ask-screen');
const { handler: healthHandler } = require('./netlify/legacy-handlers/health');
const { handler: transcriptionHandler } = require('./netlify/legacy-handlers/record-transcription');
const { handler: adminHandler } = require('./netlify/legacy-handlers/admin-dashboard');
const { handler: adminUsersHandler } = require('./netlify/legacy-handlers/admin-users');
const { handler: adminProductSubscriptionsHandler } = require('./netlify/legacy-handlers/admin-product-subscriptions');
const { handler: adminSettingsHandler } = require('./netlify/legacy-handlers/admin-settings');
const { handler: appUpdateHandler, validReleaseAssetUrl, _test: appUpdateTests } = require('./netlify/legacy-handlers/app-update');
const { handler: supportHandler } = require('./netlify/legacy-handlers/support-tickets');
const { handler: supportAdminHandler } = require('./netlify/legacy-handlers/support-admin');
const { handler: accountStatusHandler } = require('./netlify/legacy-handlers/account-status');
const { handler: cryptoPaymentsHandler, _test: cryptoPaymentTests } = require('./netlify/legacy-handlers/crypto-payments');
const { handler: adminCryptoPaymentsHandler, _test: adminCryptoPaymentTests } = require('./netlify/legacy-handlers/admin-crypto-payments');
const { DEFAULT_PRODUCTS, normalizeCatalog, calculateBundle } = require('./pricing-catalog');
const { evaluateProductSubscription, summarizeProductSubscriptions } = require('./product-subscriptions');
const adminAuthTests = require('./netlify/legacy-handlers/admin-auth');
const { handler: adminLoginHandler } = require('./netlify/legacy-handlers/admin-login');
const { handler: workServiceHandler, _test: workServiceTests } = require('./netlify/legacy-handlers/work-service');

async function run() {
  const oldVisionEnv = {
    CLOUDFLARE_API_TOKEN: process.env.CLOUDFLARE_API_TOKEN,
    CLOUDFLARE_API_KEY: process.env.CLOUDFLARE_API_KEY,
    CLOUDFLARE_ACCOUNT_ID: process.env.CLOUDFLARE_ACCOUNT_ID,
    GEMINI_API_KEY: process.env.GEMINI_API_KEY,
    GEMINI_API_KEY2: process.env.GEMINI_API_KEY2,
    GEMINI_API_KEY3: process.env.GEMINI_API_KEY3,
    GEMINI_API_KEY_4: process.env.GEMINI_API_KEY_4,
    GEMINI_API_KEY_5: process.env.GEMINI_API_KEY_5,
    GEMINI_API_KEY_6: process.env.GEMINI_API_KEY_6
  };
  process.env.CLOUDFLARE_API_TOKEN = 'test-cloudflare-token';
  process.env.CLOUDFLARE_ACCOUNT_ID = 'test-cloudflare-account';
  delete process.env.CLOUDFLARE_API_KEY;
  for (const key of ['GEMINI_API_KEY', 'GEMINI_API_KEY2', 'GEMINI_API_KEY3', 'GEMINI_API_KEY_4', 'GEMINI_API_KEY_5', 'GEMINI_API_KEY_6']) {
    delete process.env[key];
  }
  const textPayload = { images: [], files: [], audio: null };
  const textProviders = askScreenTests.getProviders(textPayload);
  assert.equal(textProviders.length, 1, 'Only one provider should be active in the isolated test');
  assert.equal(textProviders[0].name, 'Cloudflare');
  assert.equal(textProviders[0].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
  const visionPayload = { images: [{ mimeType: 'image/jpeg', data: Buffer.from('frame').toString('base64') }], files: [], audio: null };
  const visionProviders = askScreenTests.getProviders(visionPayload);
  assert.equal(visionProviders.length, 1, 'Cloudflare should be the only image provider in the isolated test');
  assert.equal(visionProviders[0].model, '@cf/mistralai/mistral-small-3.1-24b-instruct');
  const visionMessages = askScreenTests.openAiMessages({ prompt: 'Describe this frame', history: [], images: visionPayload.images });
  assert.equal(visionMessages[1].content[1].type, 'image_url');
  assert.match(visionMessages[1].content[1].image_url.url, /^data:image\\/jpeg;base64,/);
  const audioOnlyPayload = { images: [], files: [], audio: { mimeType: 'audio/mpeg', data: Buffer.from('audio').toString('base64') } };
  assert.equal(askScreenTests.getProviders(audioOnlyPayload).length, 0, 'Audio is excluded from the text-and-image provider test');
  const pdfPayload = { images: [], files: [{ mimeType: 'application/pdf', data: Buffer.from('pdf').toString('base64') }], audio: null };
  assert.equal(askScreenTests.getProviders(pdfPayload).length, 0, 'PDF is excluded from the text-and-image provider test');
  for (const [key, value] of Object.entries(oldVisionEnv)) {
    if (value === undefined) delete process.env[key]; else process.env[key] = value;
  }
  assert.equal(askScreenTests.PROVIDER_TIMEOUT_MS, 4_500);
  assert.equal(askScreenTests.ROUTER_DEADLINE_MS, 38_000);
  console.log('PASS Cloudflare-only text and image routing; unsupported media is held out of this diagnostic branch');
  const oldAdminCode = process.env.ADMIN_ACCESS_CODE;
  const oldAdminEmails = process.env.ADMIN_EMAILS;
  process.env.ADMIN_ACCESS_CODE = 'a-test-only-admin-code-12345';
  process.env.ADMIN_EMAILS = 'owner@example.com';
  assert.equal(adminAuthTests.isAdminAccessCodeValid({ headers: { 'x-admin-access-code': 'a-test-only-admin-code-12345' } }), true);
  assert.equal(adminAuthTests.isAdminAccessCodeValid({ headers: { 'x-admin-access-code': 'wrong' } }), false);
  assert.equal(adminAuthTests.isAdminAccessCodeValid({ headers: { 'x-admin-access-code': '  a-test-only-admin-code-12345  ' } }), true);
  process.env.ADMIN_ACCESS_CODE = '  a-test-only-admin-code-12345  ';
  assert.equal(adminAuthTests.isAdminAccessCodeValid({ headers: { 'x-admin-access-code': 'a-test-only-admin-code-12345' } }), true);
  const testSession = adminAuthTests.createAdminSession({ uid: 'test-uid', email: 'owner@example.com' });
  const verifiedSession = adminAuthTests.verifyAdminSession({ headers: { authorization: 'Bearer ' + testSession } });
  assert.equal(verifiedSession.email, 'owner@example.com');
  assert.throws(() => adminAuthTests.verifyAdminSession({ headers: { authorization: 'Bearer ' + testSession + 'x' } }));
  const adminLoginOptions = await adminLoginHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminLoginOptions.statusCode, 204);
  const adminLoginWrongMethod = await adminLoginHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(adminLoginWrongMethod.statusCode, 405);
  if (oldAdminEmails === undefined) delete process.env.ADMIN_EMAILS; else process.env.ADMIN_EMAILS = oldAdminEmails;
  console.log('PASS administrator access-code and signed-session validation');

  let lookedUpUid = '';
  let lookedUpEmail = '';
  const paymentCustomer = await adminCryptoPaymentTests.findPaymentCustomer({
    getUser: async uid => { lookedUpUid = uid; return { uid, email: 'customer@example.com', emailVerified: true }; },
    getUserByEmail: async email => { lookedUpEmail = email; return { uid: 'email-fallback', email, emailVerified: true }; }
  }, { uid: 'firebase-customer-uid', email: 'CUSTOMER@example.com' });
  assert.equal(paymentCustomer.uid, 'firebase-customer-uid');
  assert.equal(lookedUpUid, 'firebase-customer-uid');
  assert.equal(lookedUpEmail, '', 'Approval must prefer the authenticated UID saved at payment submission');
  const fallbackCustomer = await adminCryptoPaymentTests.findPaymentCustomer({
    getUser: async () => { const error = new Error('not found'); error.code = 'auth/user-not-found'; throw error; },
    getUserByEmail: async email => ({ uid: 'email-fallback', email, emailVerified: true })
  }, { uid: 'old-or-missing-uid', email: 'CUSTOMER@example.com' });
  assert.equal(fallbackCustomer.uid, 'email-fallback');
  assert.equal(fallbackCustomer.email, 'customer@example.com');
  const unverifiedCustomer = await adminCryptoPaymentTests.findPaymentCustomer({
    getUser: async uid => ({ uid, email: 'customer@example.com', emailVerified: false }),
    getUserByEmail: async () => { throw new Error('email fallback must not override an unverified UID'); }
  }, { uid: 'unverified-uid', email: 'customer@example.com' });
  assert.equal(unverifiedCustomer, null);
  console.log('PASS payment approval resolves the submitting Firebase account by UID with safe email fallback');

  assert.equal(workServiceTests.PRODUCTS.has('email_management'), false);
  assert.equal(workServiceTests.PRODUCTS.has('core'), false);
  assert.ok(workServiceTests.SERVICE_GUIDES.marketing_social.includes('Publishing'));
  const tinyImage = Buffer.from('image').toString('base64');
  const normalizedImage = workServiceTests.normalizeWorkAttachments({ images: [{ mimeType: 'image/png', data: tinyImage }] });
  assert.equal(normalizedImage.images.length, 1);
  assert.equal(normalizedImage.images[0].mimeType, 'image/png');
  assert.equal(normalizedImage.totalBytes, 5);
  const normalizedAudio = workServiceTests.normalizeWorkAttachments({ audio: { mimeType: 'audio/mpeg', data: Buffer.from('audio').toString('base64') } });
  assert.equal(normalizedAudio.audio.mimeType, 'audio/mpeg');
  const normalizedDocument = workServiceTests.normalizeWorkAttachments({ files: [{ mimeType: 'application/pdf', data: Buffer.from('%PDF').toString('base64') }] });
  assert.equal(normalizedDocument.files.length, 1);
  assert.throws(() => workServiceTests.normalizeWorkAttachments({ images: [{ mimeType: 'text/plain', data: tinyImage }] }), /INVALID_IMAGE_TYPE/);
  assert.throws(() => workServiceTests.normalizeWorkAttachments({ files: [{ mimeType: 'application/zip', data: tinyImage }] }), /UNSUPPORTED_ATTACHMENT_TYPE/);
  assert.throws(() => workServiceTests.normalizeWorkAttachments({ audio: { mimeType: 'audio/mpeg', data: 'bad!' } }), /INVALID_ATTACHMENT_DATA/);
  assert.throws(() => workServiceTests.normalizeWorkAttachments({ images: Array(4).fill({ mimeType: 'image/png', data: tinyImage }) }), /TOO_MANY_ATTACHMENTS/);
  assert.throws(() => workServiceTests.normalizeWorkAttachments({ files: [{ mimeType: 'text/plain', data: Buffer.alloc(workServiceTests.MAX_UPLOAD_BYTES + 1).toString('base64') }] }), /ATTACHMENTS_TOO_LARGE/);
  console.log('PASS Work Centre image, audio and document upload validation');

  const workOptions = await workServiceHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(workOptions.statusCode, 204);
  const workWrongMethod = await workServiceHandler({ httpMethod: 'PUT', headers: {} });
  assert.equal(workWrongMethod.statusCode, 405);
  const workUnauthenticated = await workServiceHandler({ httpMethod: 'GET', headers: {}, queryStringParameters: { productId: 'crm' } });
  assert.equal(workUnauthenticated.statusCode, 401);
  console.log('PASS Work Centre workspace authentication and method gates');

  const defaultPricing = normalizeCatalog(DEFAULT_PRODUCTS);
  assert.equal(defaultPricing.length, 22, 'The catalogue must include core, specialist products and separate email management');
  assert.equal(defaultPricing.find(item => item.id === 'core').independent, undefined, 'DOM AI Core remains its own core subscription');
  const attemptedCorePriceChange = normalizeCatalog(DEFAULT_PRODUCTS.map(item =>
    item.id === 'core' ? { ...item, monthlyPriceCents: 100 } : item
  ));
  assert.equal(attemptedCorePriceChange.find(item => item.id === 'core').monthlyPriceCents, 900,
    'DOM AI Core must remain fixed at USD 9/month even if a client submits another price');
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
  const apkHash = await appUpdateTests.sha256OfStream(Readable.from([Buffer.from('DOM AskScreen APK test payload')]));
  assert.equal(apkHash, require('node:crypto').createHash('sha256').update('DOM AskScreen APK test payload').digest('hex'));
  const apkMetadata = appUpdateTests.validApkMetadata({
    versionCode: 3, versionName: '1.2.0', sizeBytes: 1024,
    sha256: 'a'.repeat(64), releaseNotes: 'Direct APK upload test'
  });
  assert.equal(apkMetadata.versionCode, 3);
  assert.equal(apkMetadata.sizeBytes, 1024);
  assert.throws(() => appUpdateTests.validApkMetadata({
    versionCode: 3, versionName: '1.2.0', sizeBytes: 0,
    sha256: 'a'.repeat(64), releaseNotes: ''
  }), /APK size/);
  assert.throws(() => appUpdateTests.validApkMetadata({
    versionCode: 3, versionName: '1.2.0', sizeBytes: 1024,
    sha256: 'bad', releaseNotes: ''
  }), /SHA-256/);

  assert.equal(validReleaseAssetUrl('https://github.com/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.apk'), true,
    'Only APK assets hosted in this repository release path should be accepted');
  assert.equal(validReleaseAssetUrl('http://github.com/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.apk'), false,
    'Release assets must use HTTPS');
  assert.equal(validReleaseAssetUrl('https://github.com.evil.example/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.apk'), false,
    'Lookalike GitHub hosts must be rejected');
  assert.equal(validReleaseAssetUrl('https://evil.example/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.apk'), false,
    'External APK hosts must be rejected');
  assert.equal(validReleaseAssetUrl('https://github.com/Dominiontools/Other-App/releases/download/v1.2.0/app.apk'), false,
    'APK assets from another repository must be rejected');
  assert.equal(validReleaseAssetUrl('https://github.com/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.apk?redirect=evil'), false,
    'Release asset URLs with query parameters must be rejected');
  assert.equal(validReleaseAssetUrl('https://github.com/Dominiontools/Dom-askscreen-ai/releases/download/v1.2.0/app.zip'), false,
    'Non-APK release assets must be rejected');
  console.log('PASS App updater accepts only trusted GitHub Release APK URLs');
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
  assert.equal(featureForRequest({ queryType: 'TRANSCRIPTION', feature: 'transcription' }), 'screen',
    'AI requests cannot consume the separate transcript-only allowance by changing their request label');
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

  const cryptoOptions = await cryptoPaymentsHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(cryptoOptions.statusCode, 204, 'Crypto payment endpoint should allow CORS preflight');
  const cryptoGet = await cryptoPaymentsHandler({ httpMethod: 'GET', headers: {} });
  assert.equal(cryptoGet.statusCode, 200, 'Crypto payment instructions should be publicly readable');
  const cryptoConfig = JSON.parse(cryptoGet.body);
  assert.equal(cryptoConfig.networks.find(item => item.id === 'BEP20').address, '0xc0C830d8165E9ff2Da899397128dB9247d64154F');
  assert.equal(cryptoConfig.networks.find(item => item.id === 'TRC20').address, 'TSmksTL3haP4iSQC49j5mMpF9xw6feoXH');
  assert.equal(cryptoConfig.paystack.enabled, false, 'Paystack remains disabled until approval and integration');
  assert.equal(cryptoPaymentTests.validTxHash('BEP20', '0x' + 'a'.repeat(64)), true);
  assert.equal(cryptoPaymentTests.validTxHash('TRC20', 'a'.repeat(64)), true);
  assert.equal(cryptoPaymentTests.validTxHash('TRC20', '0x' + 'a'.repeat(64)), false);
  assert.equal(cryptoPaymentTests.paymentId('BEP20', '0x' + 'A'.repeat(64)), cryptoPaymentTests.paymentId('BEP20', '0x' + 'a'.repeat(64)));
  assert.equal(cryptoPaymentTests.purchasableProduct('core').id, 'core', 'DOM AI Core must be purchasable with USDT');
  assert.equal(cryptoPaymentTests.purchasableProduct('video_annotation').id, 'video_annotation', 'Independent Work Centre services must remain purchasable');
  assert.equal(cryptoPaymentTests.purchasableProduct('unknown'), null, 'Unknown products must not be purchasable');
  const cryptoBadSubmission = await cryptoPaymentsHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ productId: 'core', network: 'BEP20', transactionHash: 'not-a-valid-hash' })
  });
  assert.equal(cryptoBadSubmission.statusCode, 400, 'Crypto payment submission must reject invalid transaction hashes before authentication');
  const cryptoUnauthenticatedSubmission = await cryptoPaymentsHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ productId: 'core', network: 'BEP20', transactionHash: '0x' + 'a'.repeat(64) })
  });
  assert.equal(cryptoUnauthenticatedSubmission.statusCode, 401, 'USDT payment submission must require a signed-in customer');
  const adminCryptoOptions = await adminCryptoPaymentsHandler({ httpMethod: 'OPTIONS', headers: {} });
  assert.equal(adminCryptoOptions.statusCode, 204);
  const adminCryptoUnauthenticated = await adminCryptoPaymentsHandler({
    httpMethod: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ paymentId: 'bep20_test', action: 'approve' })
  });
  assert.equal(adminCryptoUnauthenticated.statusCode, 401, 'Crypto payment approval must require authenticated admin verification');
  console.log('PASS USDT payment networks, Core and Work Centre product eligibility, customer authentication and admin approval gates');

  if (oldAdminCode === undefined) delete process.env.ADMIN_ACCESS_CODE; else process.env.ADMIN_ACCESS_CODE = oldAdminCode;
  console.log('All backend validation and authentication-gate tests passed.');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
