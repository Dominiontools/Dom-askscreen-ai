'use strict';

const { verifyUser } = require('./ask-screen');
const { DEFAULT_PRODUCTS, DEFAULT_BUNDLE_DISCOUNT_PERCENT, normalizeCatalog, calculateBundle } = require('../../pricing-catalog');

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

const DEFAULTS = {
  announcement: '',
  features: { screen: true, spoken: true, transcription: true },
  freeTrialLimit: 2,
  pricing: DEFAULT_PRODUCTS,
  bundleDiscountPercent: DEFAULT_BUNDLE_DISCOUNT_PERCENT
};

function json(statusCode, data) {
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Admin-Access-Code',
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify(data)
  };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Method not allowed.' });
  try {
    // Public GET exposes only customer-safe display and feature availability settings.
    if (event.httpMethod === 'GET') {
      const admin = await getAdmin();
      const db = admin.firestore();
      const ref = db.collection('askScreenAppConfig').doc('public');
      const snapshot = await ref.get();
      const saved = snapshot.exists ? snapshot.data() : {};
      return json(200, {
        settings: {
          announcement: typeof saved.announcement === 'string' ? saved.announcement : DEFAULTS.announcement,
          features: {
            screen: saved.features?.screen !== false,
            spoken: saved.features?.spoken !== false,
            transcription: saved.features?.transcription !== false
          },
          freeTrialLimit: Number.isInteger(saved.freeTrialLimit) ? saved.freeTrialLimit : DEFAULTS.freeTrialLimit,
          pricing: normalizeCatalog(Array.isArray(saved.pricing) ? saved.pricing : DEFAULTS.pricing),
          bundleDiscountPercent: Number.isInteger(saved.bundleDiscountPercent) ? saved.bundleDiscountPercent : DEFAULTS.bundleDiscountPercent,
          bundles: calculateBundle(Array.isArray(saved.pricing) ? saved.pricing : DEFAULTS.pricing, Number.isInteger(saved.bundleDiscountPercent) ? saved.bundleDiscountPercent : DEFAULTS.bundleDiscountPercent)
        }
      });
    }

    const decoded = await verifyUser(event);
    const email = String(decoded.email || '').trim().toLowerCase();
    const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(v => v.trim().toLowerCase()).filter(Boolean);
    if (decoded.email_verified !== true || !email || !allowed.includes(email)) {
      return json(403, { error: 'This account is not authorized to change app settings.' });
    }
    if (!require('./admin-auth').isAdminAccessCodeValid(event)) return json(403, { error: 'Administrator access code is incorrect.' });

    const admin = await getAdmin();
    const db = admin.firestore();
    const ref = db.collection('askScreenAppConfig').doc('public');
    const contentType = String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase();
    if (!contentType.includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 4000) return json(413, { error: 'Settings request is too large.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    const announcement = typeof body.announcement === 'string' ? body.announcement.trim() : '';
    const features = body.features || {};
    const freeTrialLimit = body.freeTrialLimit;
    let pricing;
    let bundleDiscountPercent;
    try {
      pricing = body.pricing === undefined ? null : normalizeCatalog(body.pricing);
      bundleDiscountPercent = body.bundleDiscountPercent === undefined ? null : body.bundleDiscountPercent;
      if (bundleDiscountPercent !== null && (!Number.isInteger(bundleDiscountPercent) || bundleDiscountPercent < 0 || bundleDiscountPercent > 30)) {
        return json(400, { error: 'Bundle discount must be a whole number from 0 to 30 percent.' });
      }
    } catch (pricingError) {
      return json(400, { error: pricingError.message || 'Invalid pricing catalogue.' });
    }
    if (announcement.length > 240) return json(400, { error: 'Announcement must not exceed 240 characters.' });
    if (typeof features.screen !== 'boolean' || typeof features.spoken !== 'boolean' || typeof features.transcription !== 'boolean') {
      return json(400, { error: 'Choose enabled or disabled for every feature.' });
    }
    if (!Number.isInteger(freeTrialLimit) || freeTrialLimit < 0 || freeTrialLimit > 10) {
      return json(400, { error: 'Free-trial limit must be a whole number from 0 to 10.' });
    }
    const settings = { announcement, features, freeTrialLimit, ...(pricing ? { pricing } : {}), ...(bundleDiscountPercent !== null ? { bundleDiscountPercent } : {}), updatedAt: admin.firestore.FieldValue.serverTimestamp(), updatedBy: email };
    await ref.set(settings, { merge: true });
    await db.collection('askScreenAdminAuditLogs').add({
      action: 'app_settings_updated',
      targetEmail: 'App configuration',
      adminUid: decoded.uid,
      adminEmail: email,
      status: 'saved',
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    const updatedSnapshot = await ref.get();
    const savedSettings = updatedSnapshot.data() || {};
    const finalPricing = normalizeCatalog(savedSettings.pricing || DEFAULTS.pricing);
    const finalDiscount = Number.isInteger(savedSettings.bundleDiscountPercent) ? savedSettings.bundleDiscountPercent : DEFAULTS.bundleDiscountPercent;
    return json(200, { success: true, settings: { announcement, features, freeTrialLimit, pricing: finalPricing, bundleDiscountPercent: finalDiscount, bundles: calculateBundle(finalPricing, finalDiscount) } });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[Admin Settings] Request failed:', error.message);
    return json(status, { error: status === 401 ? 'Please sign in again.' : 'App settings could not be loaded or saved.' });
  }
};
