'use strict';

const { verifyUser } = require('./ask-screen');

let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) {
    adminPromise = (async () => {
      const admin = require('firebase-admin');
      if (!admin.apps.length) {
        const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
        admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
      }
      return admin;
    })();
  }
  return adminPromise;
}

function json(statusCode, data) {
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(data)
  };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'POST') return json(405, { error: 'Method not allowed.' });
  if (!String(event.headers && (event.headers['content-type'] || event.headers['Content-Type']) || '')
    .toLowerCase().includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 4000) return json(413, { error: 'Request is too large.' });

  try {
    const decoded = await verifyUser(event);
    const email = String(decoded.email || '').trim().toLowerCase();
    const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
    if (decoded.email_verified !== true || !email || !allowed.includes(email)) {
      return json(403, { error: 'This account is not authorized to manage users.' });
    }

    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const uid = typeof body.uid === 'string' ? body.uid.trim() : '';
    if (!uid || uid.length > 128) return json(400, { error: 'A valid user ID is required.' });
    if (uid === decoded.uid) return json(400, { error: 'You cannot disable your own administrator account.' });
    if (typeof body.disabled !== 'boolean') return json(400, { error: 'Choose whether the account should be enabled or disabled.' });

    const admin = await getAdmin();
    const target = await admin.auth().getUser(uid);
    await admin.auth().updateUser(uid, { disabled: body.disabled });
    await admin.firestore().collection('askScreenAdminAuditLogs').add({
      action: body.disabled ? 'account_disabled' : 'account_enabled',
      targetUid: uid,
      targetEmail: target.email || '',
      adminUid: decoded.uid,
      adminEmail: email,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return json(200, { success: true, uid, disabled: body.disabled });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 400);
    console.error('[Admin Users] Request failed:', error.message);
    return json(status, { error: status === 400 ? 'The selected account could not be updated.' : 'Could not update the account.' });
  }
};
