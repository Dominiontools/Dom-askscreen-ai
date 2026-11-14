'use strict';

const { verifyUser } = require('./ask-screen');

let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) adminPromise = (async () => {
    const admin = require('firebase-admin');
    if (!admin.apps.length) {
      const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
      admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    }
    return admin;
  })();
  return adminPromise;
}

function json(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store'
  }, body: JSON.stringify(data) };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'GET') return json(405, { error: 'Method not allowed.' });
  try {
    const decoded = await verifyUser(event);
    const admin = await getAdmin();
    const db = admin.firestore();
    const [authUser, accountSnapshot, configSnapshot] = await Promise.all([
      admin.auth().getUser(decoded.uid),
      db.collection('askScreenAccounts').doc(decoded.uid).get(),
      db.collection('askScreenAppConfig').doc('public').get()
    ]);
    const account = accountSnapshot.exists ? accountSnapshot.data() : {};
    const usage = account.usage || {};
    const subscription = account.subscription || {};
    const config = configSnapshot.exists ? configSnapshot.data() : {};
    const limit = Number.isInteger(config.freeTrialLimit) && config.freeTrialLimit >= 0 && config.freeTrialLimit <= 10
      ? config.freeTrialLimit : 2;
    const trialStart = Date.parse(process.env.FREE_TRIAL_START_AT || '');
    const createdAt = Date.parse(authUser.metadata && authUser.metadata.creationTime || '');
    const eligible = Number.isFinite(trialStart) && Number.isFinite(createdAt) && createdAt >= trialStart;
    const end = subscription.currentPeriodEnd && typeof subscription.currentPeriodEnd.toDate === 'function'
      ? subscription.currentPeriodEnd.toDate().getTime() : 0;
    const paid = subscription.status === 'active' && Number.isFinite(end) && end > Date.now();
    const features = config.features || {};
    const count = field => Math.max(0, Number(usage[field] || 0));
    const remaining = field => paid ? null : (eligible ? Math.max(0, limit - count(field)) : 0);
    return json(200, {
      email: authUser.email || '',
      emailVerified: Boolean(authUser.emailVerified),
      trial: { eligible, limit, screenRemaining: remaining('screenAnswersUsed'),
        spokenRemaining: remaining('spokenAnswersUsed'), transcriptionRemaining: remaining('transcriptionsUsed') },
      usage: { screen: count('screenAnswersUsed'), spoken: count('spokenAnswersUsed'),
        transcription: count('transcriptionsUsed') },
      subscription: { status: paid ? 'active' : (subscription.status === 'active' ? 'expired' : (subscription.status || 'inactive')),
        currentPeriodEnd: end ? new Date(end).toISOString() : null },
      features: { screen: features.screen !== false, spoken: features.spoken !== false,
        transcription: features.transcription !== false }
    });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Account Status] Request failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' : 'Account status is temporarily unavailable.' });
  }
};
