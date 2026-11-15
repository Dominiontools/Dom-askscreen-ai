'use strict';

const { verifyUser } = require('./ask-screen');
const { summarizeProductSubscriptions } = require('../../product-subscriptions');
const { DEFAULT_PRODUCTS } = require('../../pricing-catalog');

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
    // A customer may pay and receive admin approval before creating an account.
    // Claim approved entitlements only after signing in with the same verified email.
    if (authUser.emailVerified && authUser.email) {
      const claimSnapshot = await db.collection('askScreenPayments')
        .where('email', '==', String(authUser.email).trim().toLowerCase()).limit(50).get();
      const claimablePayments = claimSnapshot.docs.filter(doc => {
        const data = doc.data();
        return data.status === 'approved' && data.claimRequired === true;
      });
      if (claimablePayments.length) {
        const claimed = {};
        for (const paymentDoc of claimablePayments) {
          const payment = paymentDoc.data();
          const product = DEFAULT_PRODUCTS.find(item => item.id === payment.productId && item.independent === true);
          if (!product || !payment.entitlementExpiresAt || typeof payment.entitlementExpiresAt.toDate !== 'function') continue;
          const expiry = payment.entitlementExpiresAt.toDate();
          if (expiry.getTime() <= Date.now()) continue;
          claimed[product.id] = {
            status: 'active', currentPeriodEnd: payment.entitlementExpiresAt,
            source: 'verified_usdt_payment', paymentId: paymentDoc.id,
            network: payment.network || '', transactionHash: payment.transactionHash || '',
            verifiedByEmail: payment.reviewedByEmail || '', updatedAt: admin.firestore.FieldValue.serverTimestamp()
          };
          await paymentDoc.ref.update({ uid: decoded.uid, claimRequired: false, claimedAt: admin.firestore.FieldValue.serverTimestamp() });
        }
        if (Object.keys(claimed).length) {
          await db.collection('askScreenAccounts').doc(decoded.uid).set({ productSubscriptions: claimed, updatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
          Object.assign(account.productSubscriptions || (account.productSubscriptions = {}), claimed);
        }
      }
    }
    const usage = account.usage || {};
    const subscription = account.subscription || {};
    const productSubscriptions = summarizeProductSubscriptions(account.productSubscriptions || {});
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
      productSubscriptions,
      features: { screen: features.screen !== false, spoken: features.spoken !== false,
        transcription: features.transcription !== false }
    });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Account Status] Request failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' : 'Account status is temporarily unavailable.' });
  }
};
