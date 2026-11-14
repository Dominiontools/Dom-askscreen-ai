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
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store'
  }, body: JSON.stringify(data) };
}
function adminEmail(decoded) {
  const email = String(decoded.email || '').trim().toLowerCase();
  const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(v => v.trim().toLowerCase()).filter(Boolean);
  return decoded.email_verified === true && email && allowed.includes(email) ? email : '';
}
function validReleaseAssetUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname === 'github.com' &&
      !url.username && !url.password &&
      url.pathname.startsWith('/Dominiontools/Dom-askscreen-ai/releases/download/') &&
      url.pathname.toLowerCase().endsWith('.apk') && !url.search && !url.hash;
  } catch (_) { return false; }
}
async function latestRelease(db) {
  const snap = await db.collection('askScreenAppReleases').doc('current').get();
  return snap.exists ? snap.data() : null;
}
exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Method not allowed.' });
  try {
    const admin = await getAdmin();
    const db = admin.firestore();
    if (event.httpMethod === 'GET') {
      const release = await latestRelease(db);
      if (!release || release.published !== true) return json(200, { updateAvailable: false, release: null });
      return json(200, { updateAvailable: true, release: {
        versionCode: release.versionCode, versionName: release.versionName,
        releaseNotes: release.releaseNotes, packageName: 'com.dominion.askscreenai',
        sizeBytes: release.sizeBytes || 0, md5Hash: '',
        downloadUrl: release.downloadUrl,
        publishedAt: release.publishedAt?.toDate ? release.publishedAt.toDate().toISOString() : null
      } });
    }
    const decoded = await verifyUser(event);
    const email = adminEmail(decoded);
    if (!email) return json(403, { error: 'This verified account is not authorized to publish app updates.' });
    const type = String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase();
    if (!type.includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) return json(413, { error: 'Update request is too large.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    if (body.action !== 'publish') return json(400, { error: 'Choose publish.' });
    const versionCode = Number(body.versionCode);
    const versionName = typeof body.versionName === 'string' ? body.versionName.trim() : '';
    const downloadUrl = typeof body.downloadUrl === 'string' ? body.downloadUrl.trim() : '';
    const sizeBytes = Number(body.sizeBytes || 0);
    const releaseNotes = typeof body.releaseNotes === 'string' ? body.releaseNotes.trim() : '';
    if (!Number.isSafeInteger(versionCode) || versionCode < 2) return json(400, { error: 'Version code must be a whole number greater than 1.' });
    if (!versionName || versionName.length > 40) return json(400, { error: 'Enter a valid version name (maximum 40 characters).' });
    if (!validReleaseAssetUrl(downloadUrl)) return json(400, { error: 'Use the HTTPS APK asset link from a GitHub Release in Dominiontools/Dom-askscreen-ai.' });
    if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 0 || sizeBytes > 2 * 1024 * 1024 * 1024) return json(400, { error: 'APK size must be 0 or a valid size up to 2 GB.' });
    if (releaseNotes.length > 2000) return json(400, { error: 'Release notes must not exceed 2,000 characters.' });
    const current = await latestRelease(db);
    if (current && versionCode <= Number(current.versionCode || 0)) return json(409, { error: 'The new version code must be higher than the published version.' });
    const release = { packageName: 'com.dominion.askscreenai', versionCode, versionName, releaseNotes,
      downloadUrl, sizeBytes, published: true, publishedBy: email, publishedByUid: decoded.uid,
      publishedAt: admin.firestore.FieldValue.serverTimestamp() };
    await db.collection('askScreenAppReleases').doc('current').set(release);
    await db.collection('askScreenAdminAuditLogs').add({ action: 'app_update_published',
      targetEmail: 'DOM Ask AI release', adminUid: decoded.uid, adminEmail: email,
      status: 'published', versionCode, versionName, createdAt: admin.firestore.FieldValue.serverTimestamp() });
    return json(200, { success: true, release: { versionCode, versionName, releaseNotes, downloadUrl } });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[App Update] Request failed:', error.message);
    const message = status === 503 ? 'Firebase server configuration is missing. Add FIREBASE_SERVICE_ACCOUNT_JSON in Netlify.'
      : status === 401 ? 'Please sign in again.' : 'App update request could not be completed.';
    return json(status, { error: message });
  }
};
