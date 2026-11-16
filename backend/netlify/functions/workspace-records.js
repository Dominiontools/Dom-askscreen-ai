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

const WORKSPACES = {
  crm: { productId: 'crm', collection: 'crmRecords', fields: ['name', 'company', 'email', 'phone', 'stage', 'notes', 'followUpDate'] },
  projects: { productId: 'project_tasks', collection: 'projectTasks', fields: ['title', 'project', 'description', 'status', 'priority', 'dueDate', 'assignee'] }
};

function json(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store'
  }, body: JSON.stringify(data) };
}

function cleanRecord(workspace, raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Enter valid record details.');
  const allowed = WORKSPACES[workspace].fields;
  const record = {};
  for (const field of allowed) {
    const value = raw[field];
    if (value === undefined || value === null) continue;
    if (typeof value !== 'string') throw new Error('Record fields must be text.');
    const max = field === 'notes' || field === 'description' ? 4000 : 240;
    const clean = value.trim();
    if (clean.length > max) throw new Error('One of the fields is too long.');
    record[field] = clean;
  }
  const required = workspace === 'crm' ? 'name' : 'title';
  if (!record[required]) throw new Error(workspace === 'crm' ? 'Contact name is required.' : 'Task title is required.');
  return record;
}

async function hasEntitlement(db, uid, productId) {
  const snapshot = await db.collection('askScreenAccounts').doc(uid).get();
  const subscriptions = snapshot.exists ? snapshot.data().productSubscriptions || {} : {};
  const item = subscriptions[productId];
  if (!item || item.status !== 'active' || !item.currentPeriodEnd ||
      typeof item.currentPeriodEnd.toDate !== 'function') return false;
  return item.currentPeriodEnd.toDate().getTime() > Date.now();
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST', 'PATCH', 'DELETE'].includes(event.httpMethod)) return json(405, { error: 'Method not allowed.' });
  try {
    const user = await verifyUser(event);
    const workspace = String(event.queryStringParameters?.workspace || (() => {
      try { return JSON.parse(event.body || '{}').workspace; } catch (_) { return ''; }
    })() || '').toLowerCase();
    const config = WORKSPACES[workspace];
    if (!config) return json(400, { error: 'Choose a valid workspace.' });
    const admin = await getAdmin();
    const db = admin.firestore();
    if (!await hasEntitlement(db, user.uid, config.productId)) {
      return json(402, { error: 'An active subscription is required to open this workspace.', code: 'SUBSCRIPTION_REQUIRED' });
    }
    const collection = db.collection('askScreenWorkspaces').doc(user.uid).collection(config.collection);

    if (event.httpMethod === 'GET') {
      const snapshot = await collection.orderBy('updatedAt', 'desc').limit(300).get();
      return json(200, { records: snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() })) });
    }

    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) return json(413, { error: 'Request is too large.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const now = admin.firestore.FieldValue.serverTimestamp();

    if (event.httpMethod === 'POST') {
      const record = cleanRecord(workspace, body.record);
      const ref = await collection.add({ ...record, createdAt: now, updatedAt: now });
      return json(201, { success: true, record: { id: ref.id, ...record } });
    }

    const id = String(body.id || event.queryStringParameters?.id || '');
    if (!/^[A-Za-z0-9_-]{10,80}$/.test(id)) return json(400, { error: 'Choose a valid record.' });
    const ref = collection.doc(id);
    const existing = await ref.get();
    if (!existing.exists) return json(404, { error: 'Record not found.' });

    if (event.httpMethod === 'DELETE') {
      await ref.delete();
      return json(200, { success: true, id });
    }
    const record = cleanRecord(workspace, body.record);
    await ref.set({ ...record, updatedAt: now }, { merge: true });
    return json(200, { success: true, record: { id, ...record } });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Work Centre Workspace] Request failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' :
      (status === 503 ? 'Account verification is not configured.' : 'Workspace request could not be completed.') });
  }
};

exports._test = { cleanRecord, WORKSPACES };
