'use strict';
const crypto = require('node:crypto');
const { verifyAdminSession } = require('./admin-auth');
let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) adminPromise = (async () => {
    const admin = require('firebase-admin');
    if (!admin.apps.length) {
      const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
      const bucketName = String(process.env.FIREBASE_STORAGE_BUCKET || '').trim() ||
        (serviceAccount.project_id ? serviceAccount.project_id + '.firebasestorage.app' : '');
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
        ...(bucketName ? { storageBucket: bucketName } : {})
      });
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
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Admin-Access-Code',
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
exports.validReleaseAssetUrl = validReleaseAssetUrl;
exports._test = { validReleaseAssetUrl, validApkMetadata };

function storageBucket(admin) {
  const bucketName = String(process.env.FIREBASE_STORAGE_BUCKET || '').trim() ||
    String(admin.app().options.storageBucket || '').trim() ||
    (admin.app().options.projectId ? admin.app().options.projectId + '.firebasestorage.app' : '');
  if (!bucketName) throw new Error('STORAGE_BUCKET_NOT_CONFIGURED');
  return admin.storage().bucket(bucketName);
}
function validApkMetadata(body) {
  const versionCode = Number(body.versionCode);
  const versionName = typeof body.versionName === 'string' ? body.versionName.trim() : '';
  const sizeBytes = Number(body.sizeBytes);
  const sha256 = typeof body.sha256 === 'string' ? body.sha256.trim().toLowerCase() : '';
  const releaseNotes = typeof body.releaseNotes === 'string' ? body.releaseNotes.trim() : '';
  if (!Number.isSafeInteger(versionCode) || versionCode < 2) throw new Error('Version code must be a whole number greater than 1.');
  if (!versionName || versionName.length > 40) throw new Error('Enter a valid version name (maximum 40 characters).');
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1 || sizeBytes > 300 * 1024 * 1024) throw new Error('APK size must be between 1 byte and 300 MB.');
  if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error('Provide the APK SHA-256 checksum (64 hexadecimal characters).');
  if (releaseNotes.length > 2000) throw new Error('Release notes must not exceed 2,000 characters.');
  return { versionCode, versionName, sizeBytes, sha256, releaseNotes };
}
async function prepareUpload(admin, db, decoded, body) {
  const metadata = validApkMetadata(body);
  const current = await latestRelease(db);
  if (current && metadata.versionCode <= Number(current.versionCode || 0)) {
    return { statusCode: 409, body: { error: 'The new version code must be higher than the published version.' } };
  }
  const bucket = storageBucket(admin);
  const uploadId = crypto.randomUUID();
  const objectPath = 'app-releases/' + metadata.versionCode + '-' + uploadId + '.apk';
  const file = bucket.file(objectPath);
  const expiresAt = Date.now() + 15 * 60 * 1000;
  const [uploadUrl] = await file.getSignedUrl({
    version: 'v4', action: 'write', expires: expiresAt,
    contentType: 'application/vnd.android.package-archive',
    queryParams: { ifGenerationMatch: '0' }
  });
  await db.collection('askScreenAppUploadSessions').doc(uploadId).create({
    uploadId, objectPath, bucketName: bucket.name, uid: decoded.uid, email: decoded.email,
    ...metadata, status: 'awaiting_upload',
    expiresAt: admin.firestore.Timestamp.fromMillis(expiresAt),
    createdAt: admin.firestore.FieldValue.serverTimestamp()
  });
  return { statusCode: 200, body: { success: true, uploadId, uploadUrl, objectPath,
    expiresAt: new Date(expiresAt).toISOString(), requiredContentType: 'application/vnd.android.package-archive' } };
}
async function publishUploadedApk(admin, db, decoded, body) {
  const uploadId = typeof body.uploadId === 'string' ? body.uploadId.trim() : '';
  if (!/^[a-f0-9-]{36}$/i.test(uploadId)) return { statusCode: 400, body: { error: 'Invalid APK upload session.' } };
  const sessionRef = db.collection('askScreenAppUploadSessions').doc(uploadId);
  const sessionSnap = await sessionRef.get();
  if (!sessionSnap.exists) return { statusCode: 404, body: { error: 'APK upload session not found.' } };
  const session = sessionSnap.data();
  if (session.uid !== decoded.uid || session.status !== 'awaiting_upload') return { statusCode: 403, body: { error: 'This APK upload session is not available to this administrator.' } };
  if (!session.expiresAt || session.expiresAt.toMillis() <= Date.now()) return { statusCode: 410, body: { error: 'The APK upload session expired. Select the APK and try again.' } };
  const bucket = storageBucket(admin);
  if (bucket.name !== session.bucketName) return { statusCode: 409, body: { error: 'The APK storage destination changed. Start the upload again.' } };
  const file = bucket.file(session.objectPath);
  const [exists] = await file.exists();
  if (!exists) return { statusCode: 400, body: { error: 'The APK has not finished uploading.' } };
  const [metadata] = await file.getMetadata();
  if (Number(metadata.size || 0) !== Number(session.sizeBytes) ||
      metadata.contentType !== 'application/vnd.android.package-archive') {
    return { statusCode: 400, body: { error: 'The uploaded APK size or file type does not match the selected file.' } };
  }
  const current = await latestRelease(db);
  if (current && Number(session.versionCode) <= Number(current.versionCode || 0)) {
    return { statusCode: 409, body: { error: 'A newer or equal version has already been published. Increase the version code.' } };
  }
  const token = crypto.randomUUID();
  await file.setMetadata({
    contentType: 'application/vnd.android.package-archive',
    cacheControl: 'public, max-age=31536000, immutable',
    metadata: { firebaseStorageDownloadTokens: token, sha256: session.sha256 }
  });
  const downloadUrl = 'https://firebasestorage.googleapis.com/v0/b/' +
    encodeURIComponent(bucket.name) + '/o/' + encodeURIComponent(session.objectPath) +
    '?alt=media&token=' + encodeURIComponent(token);
  const release = {
    packageName: 'com.dominion.askscreenai',
    versionCode: session.versionCode, versionName: session.versionName,
    releaseNotes: session.releaseNotes, downloadUrl, sizeBytes: session.sizeBytes,
    sha256: session.sha256, published: true, storagePath: session.objectPath,
    publishedBy: String(decoded.email || '').trim().toLowerCase(), publishedByUid: decoded.uid,
    publishedAt: admin.firestore.FieldValue.serverTimestamp()
  };
  await db.collection('askScreenAppReleases').doc('current').set(release);
  await sessionRef.update({ status: 'published', publishedAt: admin.firestore.FieldValue.serverTimestamp() });
  await db.collection('askScreenAdminAuditLogs').add({
    action: 'app_update_published', targetEmail: 'DOM Ask AI release',
    adminUid: decoded.uid, adminEmail: release.publishedBy, status: 'published',
    versionCode: release.versionCode, versionName: release.versionName,
    storagePath: session.objectPath, createdAt: admin.firestore.FieldValue.serverTimestamp()
  });
  return { statusCode: 200, body: { success: true, release: {
    versionCode: release.versionCode, versionName: release.versionName,
    releaseNotes: release.releaseNotes, downloadUrl, sha256: release.sha256, sizeBytes: release.sizeBytes
  } } };
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
        sizeBytes: release.sizeBytes || 0, sha256: release.sha256 || '',
        downloadUrl: release.downloadUrl,
        publishedAt: release.publishedAt?.toDate ? release.publishedAt.toDate().toISOString() : null
      } });
    }
    const decoded = verifyAdminSession(event);
    const email = adminEmail(decoded);
    if (!email) return json(403, { error: 'This verified account is not authorized to publish app updates.' });
    const type = String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase();
    if (!type.includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) return json(413, { error: 'Update request is too large.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    if (body.action === 'prepare_upload' || body.action === 'publish_uploaded') {
      if (body.action === 'prepare_upload') {
        const prepared = await prepareUpload(admin, db, decoded, body);
        return json(prepared.statusCode, prepared.body);
      }
      const published = await publishUploadedApk(admin, db, decoded, body);
      return json(published.statusCode, published.body);
    }
    if (body.action !== 'publish') return json(400, { error: 'Choose an APK upload action.' });
    const versionCode = Number(body.versionCode);
    const versionName = typeof body.versionName === 'string' ? body.versionName.trim() : '';
    const downloadUrl = typeof body.downloadUrl === 'string' ? body.downloadUrl.trim() : '';
    const sizeBytes = Number(body.sizeBytes || 0);
    const sha256 = typeof body.sha256 === 'string' ? body.sha256.trim().toLowerCase() : '';
    const releaseNotes = typeof body.releaseNotes === 'string' ? body.releaseNotes.trim() : '';
    if (!Number.isSafeInteger(versionCode) || versionCode < 2) return json(400, { error: 'Version code must be a whole number greater than 1.' });
    if (!versionName || versionName.length > 40) return json(400, { error: 'Enter a valid version name (maximum 40 characters).' });
    if (!validReleaseAssetUrl(downloadUrl)) return json(400, { error: 'Use the HTTPS APK asset link from a GitHub Release in Dominiontools/Dom-askscreen-ai.' });
    if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 0 || sizeBytes > 2 * 1024 * 1024 * 1024) return json(400, { error: 'APK size must be 0 or a valid size up to 2 GB.' });
    if (!/^[a-f0-9]{64}$/.test(sha256)) return json(400, { error: 'Provide the APK SHA-256 checksum (64 hexadecimal characters).' });
    if (releaseNotes.length > 2000) return json(400, { error: 'Release notes must not exceed 2,000 characters.' });
    const current = await latestRelease(db);
    if (current && versionCode <= Number(current.versionCode || 0)) return json(409, { error: 'The new version code must be higher than the published version.' });
    const release = { packageName: 'com.dominion.askscreenai', versionCode, versionName, releaseNotes,
      downloadUrl, sizeBytes, sha256, published: true, publishedBy: email, publishedByUid: decoded.uid,
      publishedAt: admin.firestore.FieldValue.serverTimestamp() };
    await db.collection('askScreenAppReleases').doc('current').set(release);
    await db.collection('askScreenAdminAuditLogs').add({ action: 'app_update_published',
      targetEmail: 'DOM Ask AI release', adminUid: decoded.uid, adminEmail: email,
      status: 'published', versionCode, versionName, createdAt: admin.firestore.FieldValue.serverTimestamp() });
    return json(200, { success: true, release: { versionCode, versionName, releaseNotes, downloadUrl, sha256 } });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    console.error('[App Update] Request failed:', error.message);
    const message = status === 503 ? 'Firebase server configuration is missing. Add FIREBASE_SERVICE_ACCOUNT_JSON in Netlify.'
      : status === 401 ? 'Please sign in again.' : 'App update request could not be completed.';
    return json(status, { error: message });
  }
};
