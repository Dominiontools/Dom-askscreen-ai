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
    .toLowerCase().includes('application/json')) {
    return json(415, { error: 'Content-Type must be application/json.' });
  }
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) {
    return json(413, { error: 'Request is too large.' });
  }

  try {
    const decoded = await verifyUser(event);
    const email = String(decoded.email || '').trim().toLowerCase();
    const allowed = (process.env.ADMIN_EMAILS || '')
      .split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
    if (decoded.email_verified !== true || !email || !allowed.includes(email)) {
      return json(403, { error: 'This account is not authorized to manage support requests.' });
    }

    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const ticketId = typeof body.ticketId === 'string' ? body.ticketId.trim() : '';
    const reply = typeof body.reply === 'string' ? body.reply.trim() : '';
    const status = typeof body.status === 'string' ? body.status.trim().toLowerCase() : '';
    if (!ticketId || ticketId.length > 150) return json(400, { error: 'A valid support ticket ID is required.' });
    if (reply.length > 8000) return json(400, { error: 'Reply must not exceed 8000 characters.' });
    if (reply && reply.length < 2) return json(400, { error: 'Reply must contain at least 2 characters.' });
    if (!reply && !['open', 'in_progress', 'resolved'].includes(status)) {
      return json(400, { error: 'Provide a reply or a valid ticket status.' });
    }
    if (status && !['open', 'in_progress', 'resolved'].includes(status)) {
      return json(400, { error: 'Invalid ticket status.' });
    }

    const admin = await getAdmin();
    const ref = admin.firestore().collection('askScreenSupportTickets').doc(ticketId);
    const snapshot = await ref.get();
    if (!snapshot.exists) return json(404, { error: 'Support ticket not found.' });

    const update = {
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedBy: email
    };
    if (reply) {
      update.reply = reply;
      update.repliedAt = admin.firestore.FieldValue.serverTimestamp();
      update.repliedBy = email;
    }
    if (status) update.status = status;
    await ref.update(update);
    const ticket = snapshot.data() || {};
    await admin.firestore().collection('askScreenAdminAuditLogs').add({
      action: 'support_ticket_updated',
      ticketId,
      targetUid: ticket.uid || '',
      targetEmail: ticket.email || '',
      ticketSubject: ticket.subject || '',
      adminUid: decoded.uid,
      adminEmail: email,
      status: status || (ticket.status || 'open'),
      replySent: Boolean(reply),
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return json(200, { success: true, ticketId, status: status || (ticket.status || 'open') });
  } catch (error) {
    const code = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 401);
    console.error('[Support Admin] Request failed:', error.message);
    return json(code, { error: code === 401 ? 'Please sign in again.' : 'Could not update the support request.' });
  }
};
