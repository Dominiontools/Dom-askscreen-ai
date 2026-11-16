'use strict';

const { verifyUser, routeRequest } = require('./ask-screen');
const { DEFAULT_PRODUCTS } = require('../../pricing-catalog');
const { timestampMillis } = require('../../product-subscriptions');

const PRODUCTS = new Map(DEFAULT_PRODUCTS.filter(p => p.independent && p.id !== 'email_management').map(p => [p.id, p]));
const MAX_INPUT = 24000;

function json(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store'
  }, body: JSON.stringify(data) };
}

let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) adminPromise = (async () => {
    const admin = require('firebase-admin');
    if (!admin.apps.length) admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON))
    });
    return admin;
  })();
  return adminPromise;
}

async function requireEntitlement(db, uid, productId) {
  const account = await db.collection('askScreenAccounts').doc(uid).get();
  const subscriptions = account.exists ? account.data().productSubscriptions || {} : {};
  const sub = subscriptions[productId];
  if (!sub || sub.status !== 'active' || timestampMillis(sub.currentPeriodEnd) <= Date.now()) {
    const error = new Error('SUBSCRIPTION_REQUIRED');
    error.statusCode = 402;
    throw error;
  }
}

function buildInstruction(product, task, context) {
  return [
    'WORK CENTRE SERVICE: ' + product.name,
    'Perform the requested work, not merely explain how the customer could do it.',
    'Be accurate. Never claim an external action, message, upload, purchase, booking, or publication was completed unless a connected tool actually performed it. If an external integration is unavailable, produce the usable draft or steps and clearly identify the action that still needs connection or approval.',
    'Return a finished, well-structured result that the customer can copy or save. Include assumptions and missing information only when necessary.',
    'CUSTOMER TASK:\n' + task,
    context ? 'CUSTOMER MATERIAL / CONTEXT:\n' + context : ''
  ].filter(Boolean).join('\n\n');
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Use GET to view recent jobs or POST to run a task.' });

  try {
    const user = await verifyUser(event);
    const admin = await getAdmin();
    const db = admin.firestore();
    const productId = String(event.queryStringParameters?.productId || (() => {
      try { return JSON.parse(event.body || '{}').productId; } catch (_) { return ''; }
    })() || '');
    const product = PRODUCTS.get(productId);
    if (!product) return json(400, { error: 'Choose a valid Work Centre service.' });
    await requireEntitlement(db, user.uid, productId);
    const jobs = db.collection('askScreenWorkspaces').doc(user.uid).collection('serviceJobs');

    if (event.httpMethod === 'GET') {
      const snapshot = await jobs.orderBy('createdAt', 'desc').limit(100).get();
      const recent = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() })).filter(job => job.productId === productId).slice(0, 50);
      return json(200, { jobs: recent });
    }

    if (!event.body || Buffer.byteLength(event.body, 'utf8') > MAX_INPUT + 4000) {
      return json(413, { error: 'The task is too large. Reduce the text and try again.' });
    }
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const task = typeof body.task === 'string' ? body.task.trim() : '';
    const context = typeof body.context === 'string' ? body.context.trim() : '';
    if (!task) return json(400, { error: 'Describe the work you want completed.' });
    if (task.length > 8000 || context.length > MAX_INPUT) return json(413, { error: 'Task or supplied text is too long.' });

    const jobRef = jobs.doc();
    const createdAt = admin.firestore.FieldValue.serverTimestamp();
    await jobRef.set({
      productId, productName: product.name, task, status: 'processing',
      createdAt, updatedAt: createdAt
    });

    try {
      const result = await routeRequest({
        prompt: buildInstruction(product, task, context),
        history: [], images: [], files: [], audio: null
      });
      const completedAt = admin.firestore.FieldValue.serverTimestamp();
      const saved = {
        status: 'completed', result: result.answer,
        provider: result.provider, model: result.model,
        diagnostics: result.diagnostics || {}, updatedAt: completedAt
      };
      await jobRef.set(saved, { merge: true });
      return json(200, { success: true, job: {
        id: jobRef.id, productId, productName: product.name,
        status: 'completed', result: result.answer,
        provider: result.provider, model: result.model
      }});
    } catch (error) {
      await jobRef.set({
        status: 'failed',
        error: error.code === 'NO_PROVIDER_CONFIGURED'
          ? 'No AI provider is configured. Contact the administrator.'
          : 'The task could not be completed. Your request was saved; please retry.',
        errorCode: error.code || 'WORK_FAILED',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
      const unavailable = error.code === 'NO_PROVIDER_CONFIGURED' || error.code === 'NO_MULTIMODAL_PROVIDER';
      return json(unavailable ? 503 : 502, {
        success: false, jobId: jobRef.id,
        error: unavailable ? 'The AI service is not configured for this task.' : 'The task could not be completed. Please retry.',
        code: error.code || 'WORK_FAILED'
      });
    }
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    return json(status, { error: status === 401 ? 'Please sign in again.' :
      (status === 402 ? 'An active subscription is required to use this service.' :
      'Work Centre is temporarily unavailable.') });
  }
};

exports._test = { buildInstruction, PRODUCTS, requireEntitlement };
