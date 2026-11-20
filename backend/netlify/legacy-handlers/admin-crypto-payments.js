'use strict';

const { verifyAdminSession } = require('./admin-auth');
const { DEFAULT_PRODUCTS } = require('../../pricing-catalog');
function json(statusCode, data) {
  return { statusCode, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS', 'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Admin-Access-Code',
    'Cache-Control': 'no-store' }, body: JSON.stringify(data) };
}
let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) adminPromise = (async () => {
    const admin = require('firebase-admin');
    if (!admin.apps.length) admin.initializeApp({ credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON)) });
    return admin;
  })();
  return adminPromise;
}
exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'POST') return json(405, { error: 'Method not allowed.' });
  if (!String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase().includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 5000) return json(413, { error: 'Request is too large.' });
  try {
    const decoded = verifyAdminSession(event);
    const email = String(decoded.email || '').trim().toLowerCase();
    const allowlist = (process.env.ADMIN_EMAILS || '').split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
    if (decoded.email_verified !== true || !email || !allowlist.includes(email)) return json(403, { error: 'Only a verified administrator can review payments.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const paymentId = typeof body.paymentId === 'string' ? body.paymentId.trim() : '';
    const action = body.action;
    const note = typeof body.note === 'string' ? body.note.trim().slice(0, 500) : '';
    if (!paymentId || paymentId.length > 150 || !['approve', 'reject'].includes(action)) return json(400, { error: 'Provide a valid payment and choose approve or reject.' });
    const admin = await getAdmin();
    const db = admin.firestore();
    const paymentRef = db.collection('askScreenPayments').doc(paymentId);
    const paymentSnap = await paymentRef.get();
    if (!paymentSnap.exists) return json(404, { error: 'Payment record not found.' });
    const payment = paymentSnap.data();
    if (payment.provider !== 'USDT_MANUAL' || payment.status !== 'pending') return json(409, { error: 'Only pending USDT payments can be reviewed.' });
    if (action === 'approve') {
      const product = DEFAULT_PRODUCTS.find(item => item.id === payment.productId && (item.id === 'core' || item.independent === true));
      if (!product) return json(400, { error: 'The payment does not reference a valid subscription product.' });
      let customer = null;
      try { customer = await admin.auth().getUserByEmail(String(payment.email || '')); }
      catch (lookupError) { if (lookupError.code !== 'auth/user-not-found') throw lookupError; }
      const verifiedCustomer = customer && customer.emailVerified ? customer : null;
      const periodEnd = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
      const expiresAt = admin.firestore.Timestamp.fromDate(periodEnd);
      const now = admin.firestore.FieldValue.serverTimestamp();
      const accountRef = verifiedCustomer ? db.collection('askScreenAccounts').doc(verifiedCustomer.uid) : null;
      const record = {
        status: 'active', currentPeriodEnd: expiresAt, source: 'verified_usdt_payment',
        paymentId, network: payment.network, transactionHash: payment.transactionHash,
        verifiedByUid: decoded.uid, verifiedByEmail: email, verifiedAt: now, updatedAt: now
      };
      await db.runTransaction(async transaction => {
        const current = await transaction.get(paymentRef);
        if (!current.exists || current.data().provider !== 'USDT_MANUAL' || current.data().status !== 'pending') {
          const conflict = new Error('PAYMENT_ALREADY_REVIEWED');
          conflict.code = 'PAYMENT_ALREADY_REVIEWED';
          throw conflict;
        }
        if (accountRef) {
          const account = await transaction.get(accountRef);
          if (product.id === 'core') {
            transaction.set(accountRef, { subscription: record, updatedAt: now }, { merge: true });
          } else {
            const existing = account.exists ? account.data().productSubscriptions || {} : {};
            transaction.set(accountRef, {
              productSubscriptions: { ...existing, [product.id]: record },
              updatedAt: now
            }, { merge: true });
          }
        }
        transaction.update(paymentRef, {
          status: 'approved', uid: verifiedCustomer ? verifiedCustomer.uid : null,
          customerEmail: verifiedCustomer ? (verifiedCustomer.email || payment.email) : payment.email,
          claimRequired: !verifiedCustomer, reviewedAt: now, reviewedByUid: decoded.uid,
          reviewedByEmail: email, reviewNote: note, entitlementExpiresAt: expiresAt
        });
      });
      await db.collection('askScreenAdminAuditLogs').add({
        action: 'crypto_payment_approved', targetUid: verifiedCustomer ? verifiedCustomer.uid : '',
        targetEmail: payment.email || '', paymentId, productId: product.id, productName: product.name,
        adminUid: decoded.uid, adminEmail: email, status: 'approved',
        claimRequired: !verifiedCustomer, createdAt: now
      });
      return json(200, { success: true, paymentId, status: 'approved',
        uid: verifiedCustomer ? verifiedCustomer.uid : null, claimRequired: !verifiedCustomer,
        productId: product.id, currentPeriodEnd: periodEnd.toISOString() });
    }
    await db.runTransaction(async transaction => {
      const current = await transaction.get(paymentRef);
      if (!current.exists || current.data().provider !== 'USDT_MANUAL' || current.data().status !== 'pending') {
        const conflict = new Error('PAYMENT_ALREADY_REVIEWED');
        conflict.code = 'PAYMENT_ALREADY_REVIEWED';
        throw conflict;
      }
      transaction.update(paymentRef, { status: 'rejected', reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
        reviewedByUid: decoded.uid, reviewedByEmail: email, reviewNote: note });
    });
    await db.collection('askScreenAdminAuditLogs').add({ action: 'crypto_payment_rejected', paymentId,
      targetEmail: payment.email || '', productId: payment.productId || '', adminUid: decoded.uid, adminEmail: email,
      status: 'rejected', reviewNote: note, createdAt: admin.firestore.FieldValue.serverTimestamp() });
    return json(200, { success: true, paymentId, status: 'rejected' });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : (error.code === 'PAYMENT_ALREADY_REVIEWED' ? 409 : 400));
    console.error('[Admin Crypto Payments] Review failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' : (status === 409 ? 'This payment has already been reviewed. Refresh the queue.' : 'Payment review could not be completed.') });
  }
};
