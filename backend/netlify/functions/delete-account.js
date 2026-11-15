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

async function deleteMatching(db, collectionName, field, value) {
  const collection = db.collection(collectionName);
  let removed = 0;
  while (true) {
    const snapshot = await collection.where(field, '==', value).limit(400).get();
    if (snapshot.empty) break;
    const batch = db.batch();
    snapshot.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
    removed += snapshot.size;
    if (snapshot.size < 400) break;
  }
  return removed;
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'POST') return json(405, { error: 'Method not allowed.' });

  try {
    const decoded = await verifyUser(event);
    if (!decoded.uid) return json(401, { error: 'Please sign in again.' });

    // Require a recent sign-in before an irreversible account deletion.
    const authTime = Number(decoded.auth_time || 0);
    const ageSeconds = Math.floor(Date.now() / 1000) - authTime;
    if (!authTime || ageSeconds < 0 || ageSeconds > 300) {
      return json(401, {
        error: 'For your security, sign in again and immediately retry account deletion.',
        code: 'RECENT_LOGIN_REQUIRED'
      });
    }

    const admin = await getAdmin();
    const db = admin.firestore();
    const uid = decoded.uid;
    const auth = admin.auth();

    // Remove customer-owned records from each user-scoped collection.
    // Global app configuration is intentionally never touched.
    const removals = await Promise.all([
      db.recursiveDelete(db.collection('askScreenAccounts').doc(uid)).then(() => 1),
      deleteMatching(db, 'askScreenSupportTickets', 'uid', uid),
      deleteMatching(db, 'askScreenActivityLogs', 'uid', uid),
      deleteMatching(db, 'askScreenPayments', 'uid', uid),
      deleteMatching(db, 'askScreenAdminAuditLogs', 'uid', uid),
      deleteMatching(db, 'askScreenAdminAuditLogs', 'targetUid', uid),
      deleteMatching(db, 'askScreenAdminAuditLogs', 'affectedUid', uid)
    ]);

    await auth.deleteUser(uid);
    return json(200, {
      success: true,
      message: 'Your account and associated customer records have been deleted.',
      deletedRecords: removals.reduce((sum, count) => sum + count, 0)
    });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Delete Account] Request failed:', error.message);
    return json(status, {
      error: status === 401
        ? 'Your sign-in has expired. Sign in again and retry.'
        : 'We could not complete account deletion. Please contact support before retrying.'
    });
  }
};

exports.deleteMatching = deleteMatching;
