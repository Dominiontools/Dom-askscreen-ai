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
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(data)
  };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod !== 'GET') return json(405, { error: 'Method not allowed.' });

  const allowed = (process.env.ADMIN_EMAILS || '')
    .split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
  if (!allowed.length) return json(503, {
    error: 'Admin access is not configured. Set ADMIN_EMAILS in Netlify.'
  });

  try {
    const decoded = await verifyUser(event);
    const email = String(decoded.email || '').toLowerCase();
    if (!email || !allowed.includes(email) || decoded.email_verified !== true) {
      return json(403, { error: 'This account is not authorized to access the admin dashboard.' });
    }

    const admin = await getAdmin();
    const db = admin.firestore();
    const users = [];
    let pageToken;
    do {
      const page = await admin.auth().listUsers(1000, pageToken);
      users.push(...page.users);
      pageToken = page.pageToken;
    } while (pageToken && users.length < 10000);

    const accountSnapshot = await db.collection('askScreenAccounts').get();
    const accounts = new Map(accountSnapshot.docs.map(doc => [doc.id, doc.data()]));
    const now = Date.now();
    const rows = users.map(user => {
      const account = accounts.get(user.uid) || {};
      const usage = account.usage || {};
      const subscription = account.subscription || {};
      const expiry = subscription.currentPeriodEnd && typeof subscription.currentPeriodEnd.toDate === 'function'
        ? subscription.currentPeriodEnd.toDate().toISOString()
        : null;
      const active = subscription.status === 'active' && expiry && Date.parse(expiry) > now;
      const trialStartAt = Date.parse(process.env.FREE_TRIAL_START_AT || '');
      const createdAtMs = Date.parse(user.metadata.creationTime || '');
      const freeTrialEligible = Number.isFinite(trialStartAt) &&
        Number.isFinite(createdAtMs) && createdAtMs >= trialStartAt;
      return {
        uid: user.uid,
        email: user.email || '',
        displayName: user.displayName || '',
        createdAt: user.metadata.creationTime || null,
        lastSignInAt: user.metadata.lastSignInTime || null,
        disabled: Boolean(user.disabled),
        emailVerified: Boolean(user.emailVerified),
        usage: {
          screen: Number(usage.screenAnswersUsed || 0),
          spoken: Number(usage.spokenAnswersUsed || 0),
          transcription: Number(usage.transcriptionsUsed || 0)
        },
        freeTrial: {
          eligible: freeTrialEligible,
          screenRemaining: freeTrialEligible ? Math.max(0, 2 - Number(usage.screenAnswersUsed || 0)) : 0,
          spokenRemaining: freeTrialEligible ? Math.max(0, 2 - Number(usage.spokenAnswersUsed || 0)) : 0,
          transcriptionRemaining: freeTrialEligible ? Math.max(0, 2 - Number(usage.transcriptionsUsed || 0)) : 0
        },
        subscription: {
          status: active ? 'active' : (subscription.status === 'active' ? 'expired' : (subscription.status || 'inactive')),
          currentPeriodEnd: expiry
        }
      };
    }).sort((a, b) => (Date.parse(b.createdAt || '') || 0) - (Date.parse(a.createdAt || '') || 0));

    const activeSubscribers = rows.filter(user =>
      user.subscription.status === 'active' &&
      user.subscription.currentPeriodEnd &&
      Date.parse(user.subscription.currentPeriodEnd) > now
    ).length;

    let payments = [];
    try {
      const paymentSnapshot = await db.collection('askScreenPayments')
        .orderBy('createdAt', 'desc').limit(100).get();
      payments = paymentSnapshot.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          uid: data.uid || '',
          email: data.email || '',
          reference: data.reference || '',
          amount: data.amount ?? null,
          currency: data.currency || 'NGN',
          status: data.status || 'unknown',
          createdAt: data.createdAt && typeof data.createdAt.toDate === 'function'
            ? data.createdAt.toDate().toISOString()
            : null
        };
      });
    } catch (error) {
      console.warn('[Admin Dashboard] Payment records unavailable:', error.message);
    }

    let supportTickets = [];
    try {
      const ticketSnapshot = await db.collection('askScreenSupportTickets')
        .orderBy('createdAt', 'desc').limit(100).get();
      supportTickets = ticketSnapshot.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          uid: data.uid || '',
          email: data.email || '',
          subject: data.subject || '',
          message: data.message || '',
          status: data.status || 'open',
          reply: data.reply || '',
          createdAt: data.createdAt && typeof data.createdAt.toDate === 'function'
            ? data.createdAt.toDate().toISOString() : null
        };
      });
    } catch (error) {
      console.warn('[Admin Dashboard] Support tickets unavailable:', error.message);
    }

    let auditLogs = [];
    try {
      const auditSnapshot = await db.collection('askScreenAdminAuditLogs')
        .orderBy('createdAt', 'desc').limit(100).get();
      auditLogs = auditSnapshot.docs.map(doc => {
        const data = doc.data();
        return {
          id: doc.id,
          action: data.action || 'admin_action',
          targetUid: data.targetUid || '',
          targetEmail: data.targetEmail || '',
          adminUid: data.adminUid || '',
          adminEmail: data.adminEmail || '',
          ticketId: data.ticketId || '',
          ticketSubject: data.ticketSubject || '',
          status: data.status || '',
          replySent: typeof data.replySent === 'boolean' ? data.replySent : null,
          createdAt: data.createdAt && typeof data.createdAt.toDate === 'function'
            ? data.createdAt.toDate().toISOString() : null
        };
      });
    } catch (error) {
      console.warn('[Admin Dashboard] Audit log records unavailable:', error.message);
    }

    return json(200, {
      generatedAt: new Date().toISOString(),
      totals: {
        users: rows.length,
        activeSubscribers,
        disabledUsers: rows.filter(user => user.disabled).length,
        trialUsers: rows.filter(user => user.freeTrial.eligible).length
      },
      users: rows,
      payments,
      supportTickets,
      auditLogs
    });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 401);
    console.error('[Admin Dashboard] Request failed:', error.message);
    return json(status, {
      error: status === 401
        ? 'Please sign in again.'
        : 'The dashboard could not load its data.'
    });
  }
};
