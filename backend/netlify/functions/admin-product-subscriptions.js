'use strict';

const { verifyUser } = require('./ask-screen');
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
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Admin-Access-Code',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(data)
  };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'POST') return json(405, { error: 'Method not allowed.' });
  const contentType = String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase();
  if (!contentType.includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 4000) return json(413, { error: 'Request is too large.' });

  try {
    const decoded = await verifyUser(event);
    const email = String(decoded.email || '').trim().toLowerCase();
    const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
    if (decoded.email_verified !== true || !email || !allowed.includes(email)) {
      return json(403, { error: 'This verified account is not authorized to manage product access.' });
    }
    if (!require('./admin-auth').isAdminAccessCodeValid(event)) return json(403, { error: 'Administrator access code is incorrect.' });

    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const uid = typeof body.uid === 'string' ? body.uid.trim() : '';
    const productId = typeof body.productId === 'string' ? body.productId.trim() : '';
    const action = body.action;
    if (!uid || uid.length > 128) return json(400, { error: 'A valid customer user ID is required.' });
    if (!['grant', 'revoke'].includes(action)) return json(400, { error: 'Choose grant or revoke.' });
    const product = DEFAULT_PRODUCTS.find(item => item.id === productId && item.independent === true);
    if (!product) return json(400, { error: 'Choose a valid independent product. DOM AI Core is managed separately.' });

    let expiryDate = null;
    if (action === 'grant') {
      const expiry = typeof body.currentPeriodEnd === 'string' ? body.currentPeriodEnd.trim() : '';
      const millis = Date.parse(expiry);
      if (!expiry || !Number.isFinite(millis) || millis <= Date.now() || millis > Date.now() + 2 * 366 * 24 * 60 * 60 * 1000) {
        return json(400, { error: 'Enter a future expiry date within the next two years.' });
      }
      expiryDate = new Date(millis);
    }

    const admin = await getAdmin();
    const customer = await admin.auth().getUser(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();
    const record = action === 'grant'
      ? {
          status: 'active',
          currentPeriodEnd: admin.firestore.Timestamp.fromDate(expiryDate),
          grantedByUid: decoded.uid,
          grantedByEmail: email,
          updatedAt: now,
          source: 'administrator_manual_grant'
        }
      : {
          status: 'cancelled',
          currentPeriodEnd: admin.firestore.Timestamp.fromDate(new Date()),
          cancelledByUid: decoded.uid,
          cancelledByEmail: email,
          cancelledAt: now,
          updatedAt: now,
          source: 'administrator_manual_revoke'
        };

    await admin.firestore().collection('askScreenAccounts').doc(uid).set({
      productSubscriptions: { [productId]: record },
      updatedAt: now
    }, { merge: true });

    await admin.firestore().collection('askScreenAdminAuditLogs').add({
      action: action === 'grant' ? 'product_access_granted' : 'product_access_revoked',
      targetUid: uid,
      targetEmail: customer.email || '',
      productId,
      productName: product.name,
      currentPeriodEnd: expiryDate ? expiryDate.toISOString() : null,
      adminUid: decoded.uid,
      adminEmail: email,
      createdAt: now
    });

    return json(200, {
      success: true,
      uid,
      productId,
      status: action === 'grant' ? 'active' : 'cancelled',
      currentPeriodEnd: expiryDate ? expiryDate.toISOString() : null
    });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 400);
    console.error('[Admin Product Subscriptions] Request failed:', error.message);
    return json(status, { error: status === 400 ? 'The selected product access could not be updated.' : 'Could not update product access.' });
  }
};
