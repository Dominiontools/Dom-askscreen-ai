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
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(data)
  };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Method not allowed.' });

  try {
    const decoded = await verifyUser(event);
    if (!decoded.uid || decoded.email_verified !== true) {
      return json(403, { error: 'Verify your email address before using in-app support.' });
    }
    const admin = await getAdmin();
    const db = admin.firestore();
    const collection = db.collection('askScreenSupportTickets');

    if (event.httpMethod === 'GET') {
      const snapshot = await collection.where('uid', '==', decoded.uid).limit(50).get();
      const tickets = snapshot.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          subject: data.subject || '',
          message: data.message || '',
          status: data.status || 'open',
          createdAt: data.createdAt && typeof data.createdAt.toDate === 'function'
            ? data.createdAt.toDate().toISOString() : null,
          reply: data.reply || '',
          repliedAt: data.repliedAt && typeof data.repliedAt.toDate === 'function'
            ? data.repliedAt.toDate().toISOString() : null
        };
      }).sort((a, b) => Date.parse(b.createdAt || '') - Date.parse(a.createdAt || ''));
      return json(200, { tickets });
    }

    if (!event.headers || !String(event.headers['content-type'] || event.headers['Content-Type'] || '').toLowerCase().includes('application/json')) {
      return json(415, { error: 'Content-Type must be application/json.' });
    }
    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) {
      return json(413, { error: 'Support message is too large.' });
    }
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const subject = typeof body.subject === 'string' ? body.subject.trim() : '';
    const message = typeof body.message === 'string' ? body.message.trim() : '';
    if (subject.length < 3 || subject.length > 120 || message.length < 10 || message.length > 8000) {
      return json(400, { error: 'Enter a subject (3–120 characters) and message (10–8000 characters).' });
    }

    const ref = await collection.add({
      uid: decoded.uid,
      email: decoded.email || '',
      subject,
      message,
      status: 'open',
      reply: '',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return json(201, { ticket: { id: ref.id, subject, message, status: 'open' } });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 401);
    console.error('[Support Tickets] Request failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' : 'Support service is temporarily unavailable.' });
  }
};
