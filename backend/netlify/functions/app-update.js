'use strict';

const { randomUUID } = require('crypto');
const { verifyUser } = require('./ask-screen');
const {
  S3Client,
  PutObjectCommand,
  GetObjectCommand,
  HeadObjectCommand
} = require('@aws-sdk/client-s3');
const { getSignedUrl } = require('@aws-sdk/s3-request-presigner');

let adminPromise;
let s3Client;
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

function getR2() {
  const accountId = process.env.R2_ACCOUNT_ID;
  const accessKeyId = process.env.R2_ACCESS_KEY_ID;
  const secretAccessKey = process.env.R2_SECRET_ACCESS_KEY;
  const bucket = process.env.R2_BUCKET_NAME;
  if (!accountId || !accessKeyId || !secretAccessKey || !bucket) {
    throw new Error('R2_NOT_CONFIGURED');
  }
  if (!s3Client) {
    s3Client = new S3Client({
      region: 'auto',
      endpoint: 'https://' + accountId + '.r2.cloudflarestorage.com',
      credentials: { accessKeyId, secretAccessKey }
    });
  }
  return { client: s3Client, bucket };
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

function adminEmail(decoded) {
  const email = String(decoded.email || '').trim().toLowerCase();
  const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(v => v.trim().toLowerCase()).filter(Boolean);
  return decoded.email_verified === true && email && allowed.includes(email) ? email : '';
}

async function latestRelease(db) {
  const snap = await db.collection('askScreenAppReleases').doc('current').get();
  return snap.exists ? snap.data() : null;
}

function md5Base64FromEtag(etag) {
  const hex = String(etag || '').replace(/"/g, '');
  return /^[a-f0-9]{32}$/i.test(hex) ? Buffer.from(hex, 'hex').toString('base64') : '';
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Method not allowed.' });

  try {
    const admin = await getAdmin();
    const db = admin.firestore();
    const { client, bucket } = getR2();

    if (event.httpMethod === 'GET') {
      const release = await latestRelease(db);
      if (!release || release.published !== true) return json(200, { updateAvailable: false, release: null });
      const downloadUrl = await getSignedUrl(
        client,
        new GetObjectCommand({ Bucket: bucket, Key: release.storagePath }),
        { expiresIn: 24 * 60 * 60 }
      );
      return json(200, {
        updateAvailable: true,
        release: {
          versionCode: release.versionCode,
          versionName: release.versionName,
          releaseNotes: release.releaseNotes,
          packageName: 'com.dominion.askscreenai',
          sizeBytes: release.sizeBytes,
          md5Hash: release.md5Hash || '',
          downloadUrl,
          publishedAt: release.publishedAt?.toDate ? release.publishedAt.toDate().toISOString() : null
        }
      });
    }

    const decoded = await verifyUser(event);
    const email = adminEmail(decoded);
    if (!email) return json(403, { error: 'This verified account is not authorized to publish app updates.' });
    const contentType = String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase();
    if (!contentType.includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 12000) return json(413, { error: 'Update request is too large.' });
    let body;
    try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }

    if (body.action === 'prepare-upload') {
      const versionCode = Number(body.versionCode);
      const versionName = typeof body.versionName === 'string' ? body.versionName.trim() : '';
      const sizeBytes = Number(body.sizeBytes);
      const fileName = typeof body.fileName === 'string' ? body.fileName.trim() : '';
      if (!Number.isSafeInteger(versionCode) || versionCode < 2) return json(400, { error: 'Version code must be a whole number greater than 1.' });
      if (!versionName || versionName.length > 40) return json(400, { error: 'Enter a valid version name (maximum 40 characters).' });
      if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1024 || sizeBytes > 200 * 1024 * 1024) return json(400, { error: 'APK size must be between 1 KB and 200 MB.' });
      if (!fileName.toLowerCase().endsWith('.apk')) return json(400, { error: 'Choose an APK file.' });
      const current = await latestRelease(db);
      if (current && versionCode <= Number(current.versionCode || 0)) return json(409, { error: 'The new version code must be a whole number higher than the published version.' });
      const uploadId = randomUUID();
      const storagePath = 'app-releases/' + versionCode + '/' + uploadId + '.apk';
      const expiresAt = Date.now() + 10 * 60 * 1000;
      const uploadUrl = await getSignedUrl(
        client,
        new PutObjectCommand({
          Bucket: bucket,
          Key: storagePath,
          ContentType: 'application/vnd.android.package-archive'
        }),
        { expiresIn: 10 * 60 }
      );
      await db.collection('askScreenAppUpdateUploads').doc(uploadId).set({
        storagePath, versionCode, versionName, sizeBytes, adminUid: decoded.uid,
        adminEmail: email, expiresAt: admin.firestore.Timestamp.fromMillis(expiresAt),
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return json(200, { uploadId, storagePath, uploadUrl, contentType: 'application/vnd.android.package-archive', expiresAt: new Date(expiresAt).toISOString() });
    }

    if (body.action === 'publish') {
      const uploadId = String(body.uploadId || '').trim();
      const releaseNotes = typeof body.releaseNotes === 'string' ? body.releaseNotes.trim() : '';
      if (!/^[0-9a-f-]{36}$/i.test(uploadId)) return json(400, { error: 'Invalid upload reference.' });
      if (releaseNotes.length > 2000) return json(400, { error: 'Release notes must not exceed 2,000 characters.' });
      const uploadRef = db.collection('askScreenAppUpdateUploads').doc(uploadId);
      const uploadSnap = await uploadRef.get();
      if (!uploadSnap.exists) return json(404, { error: 'Upload session was not found. Prepare the upload again.' });
      const pending = uploadSnap.data();
      if (pending.adminUid !== decoded.uid || pending.adminEmail !== email) return json(403, { error: 'This upload session belongs to another administrator.' });
      if (!pending.expiresAt || pending.expiresAt.toMillis() < Date.now()) return json(410, { error: 'Upload session expired. Prepare the upload again.' });
      const currentRelease = await latestRelease(db);
      if (currentRelease && Number(pending.versionCode) <= Number(currentRelease.versionCode || 0)) {
        return json(409, { error: 'A newer or equal version has already been published. Build a higher version code.' });
      }
      const metadata = await client.send(new HeadObjectCommand({ Bucket: bucket, Key: pending.storagePath })).catch(() => null);
      if (!metadata) return json(400, { error: 'APK upload was not found in Cloudflare R2.' });
      const actualSize = Number(metadata.ContentLength || 0);
      if (actualSize !== Number(pending.sizeBytes) || metadata.ContentType !== 'application/vnd.android.package-archive') {
        return json(400, { error: 'Uploaded APK size or file type does not match the prepared upload.' });
      }
      const release = {
        packageName: 'com.dominion.askscreenai',
        versionCode: pending.versionCode,
        versionName: pending.versionName,
        releaseNotes,
        storagePath: pending.storagePath,
        sizeBytes: actualSize,
        md5Hash: md5Base64FromEtag(metadata.ETag),
        published: true,
        publishedBy: email,
        publishedByUid: decoded.uid,
        publishedAt: admin.firestore.FieldValue.serverTimestamp()
      };
      await db.collection('askScreenAppReleases').doc('current').set(release);
      await uploadRef.delete();
      await db.collection('askScreenAdminAuditLogs').add({
        action: 'app_update_published', targetEmail: 'DOM Ask AI release',
        adminUid: decoded.uid, adminEmail: email,
        status: 'published', versionCode: release.versionCode, versionName: release.versionName,
        createdAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return json(200, { success: true, release: { versionCode: release.versionCode, versionName: release.versionName, releaseNotes } });
    }

    return json(400, { error: 'Choose prepare-upload or publish.' });
  } catch (error) {
    const status = error.statusCode || (
      error.message === 'AUTH_NOT_CONFIGURED' || error.message === 'R2_NOT_CONFIGURED' ? 503 : 500
    );
    console.error('[App Update] Request failed:', error.message);
    const message = error.message === 'R2_NOT_CONFIGURED'
      ? 'Cloudflare R2 is not configured yet. Add the four R2 environment variables in Netlify.'
      : status === 401 ? 'Please sign in again.' : 'App update request could not be completed.';
    return json(status, { error: message });
  }
};
