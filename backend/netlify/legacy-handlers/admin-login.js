'use strict';

const { isAdminAccessCodeValid, createAdminSession } = require('./admin-auth');

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
      'Access-Control-Allow-Headers': 'Content-Type',
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
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 2000) return json(413, { error: 'Request is too large.' });

  try {
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const email = String(body.email || '').trim().toLowerCase();
    const accessCode = String(body.accessCode || '').trim();
    const configuredCode = String(process.env.ADMIN_ACCESS_CODE || '').trim();
    const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
    if (!allowed.length) return json(503, { error: 'Administrator email access is not configured.' });
    if (!email || email.length > 254 || !allowed.includes(email)) {
      return json(403, { error: 'This email is not authorized for the Admin Centre.' });
    }
    if (configuredCode.length < 16) {
      return json(503, { error: 'Admin access code is missing or must contain at least 16 characters.' });
    }
    const fakeEvent = { headers: { 'x-admin-access-code': accessCode } };
    if (!isAdminAccessCodeValid(fakeEvent)) return json(403, { error: 'Administrator access code is incorrect.' });

    const admin = await getAdmin();
    let user;
    try { user = await admin.auth().getUserByEmail(email); }
    catch (_) { return json(403, { error: 'The administrator email must belong to a registered, verified account.' }); }
    // The allowlisted administrator has already proved possession of the
    // server-side admin access code. If their Firebase email is not verified,
    // mark only this allowlisted account as verified so login does not depend
    // on a verification email being delivered. Never re-enable a disabled user.
    if (!user.emailVerified) {
      user = await admin.auth().updateUser(user.uid, { emailVerified: true });
    }
    if (user.disabled) {
      return json(403, { error: 'The administrator account is disabled in Firebase. Enable it in Authentication to continue.' });
    }
    const token = createAdminSession(user);
    return json(200, { success: true, token, expiresIn: 28800, email });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' || error.message === 'ADMIN_ACCESS_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Admin Login] Request failed:', error.message);
    return json(status, { error: status === 503 ? 'Administrator login is not configured on the server.' : 'Administrator sign-in failed.' });
  }
};
