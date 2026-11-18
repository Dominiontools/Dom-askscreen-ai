'use strict';

const crypto = require('crypto');
const { verifyUser } = require('./ask-screen');

const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const GOOGLE_REVOKE_URL = 'https://oauth2.googleapis.com/revoke';
const GOOGLE_USERINFO_URL = 'https://openidconnect.googleapis.com/v1/userinfo';
const STATE_TTL_MS = 10 * 60 * 1000;
const TOKEN_COLLECTION = 'askScreenIntegrations';
const STATE_COLLECTION = 'askScreenOAuthStates';

// Deliberately limited to the Google features currently planned for DomAskScreenAI.
// Gmail readonly is a restricted scope and may require Google's verification before public release.
const GOOGLE_SCOPES = [
  'openid',
  'email',
  'profile',
  'https://www.googleapis.com/auth/gmail.readonly',
  'https://www.googleapis.com/auth/gmail.compose',
  'https://www.googleapis.com/auth/gmail.send',
  'https://www.googleapis.com/auth/calendar.events'
];

function response(statusCode, data, headers = {}) {
  return {
    statusCode,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers },
    body: JSON.stringify(data)
  };
}

function html(statusCode, title, message) {
  const safeTitle = String(title).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const safeMessage = String(message).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  return {
    statusCode,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
    body: '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>' +
      safeTitle + '</title></head><body style="font-family:system-ui;max-width:620px;margin:12vh auto;padding:24px;line-height:1.5">' +
      '<h2>' + safeTitle + '</h2><p>' + safeMessage + '</p><p>You can close this window and return to DomAskScreenAI.</p></body></html>'
  };
}

function getConfig() {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
  const redirectUri = process.env.GOOGLE_REDIRECT_URI;
  const encryptionKey = process.env.INTEGRATION_TOKEN_ENCRYPTION_KEY;
  if (!clientId || !clientSecret || !redirectUri || !encryptionKey) {
    const error = new Error('GOOGLE_INTEGRATION_NOT_CONFIGURED');
    error.statusCode = 503;
    throw error;
  }
  const key = Buffer.from(encryptionKey, 'base64');
  if (key.length !== 32) {
    const error = new Error('INVALID_INTEGRATION_ENCRYPTION_KEY');
    error.statusCode = 503;
    throw error;
  }
  const redirect = new URL(redirectUri);
  if (redirect.protocol !== 'https:' || redirect.hostname !== 'domaskscreenai.netlify.app') {
    const error = new Error('INVALID_GOOGLE_REDIRECT_URI');
    error.statusCode = 503;
    throw error;
  }
  return { clientId, clientSecret, redirectUri, key };
}

function encryptJson(value, key) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return {
    version: 1,
    iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
    ciphertext: ciphertext.toString('base64')
  };
}

function decryptJson(envelope, key) {
  if (!envelope || envelope.version !== 1) throw new Error('INVALID_ENCRYPTED_TOKEN');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64'));
  decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
  const clear = Buffer.concat([
    decipher.update(Buffer.from(envelope.ciphertext, 'base64')),
    decipher.final()
  ]).toString('utf8');
  return JSON.parse(clear);
}

function stateDigest(state) {
  return crypto.createHash('sha256').update(state).digest('hex');
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

async function start(event) {
  let user;
  try { user = await verifyUser(event); }
  catch (error) { return response(error.statusCode || 401, { success: false, code: 'AUTH_REQUIRED' }); }

  let config;
  try { config = getConfig(); }
  catch (error) { return response(error.statusCode || 503, { success: false, code: error.message }); }

  try {
    const admin = await getAdmin();
    const state = crypto.randomBytes(32).toString('base64url');
    const expiresAt = Date.now() + STATE_TTL_MS;
    await admin.firestore().collection(STATE_COLLECTION).doc(stateDigest(state)).create({
      uid: user.uid,
      provider: 'google',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      expiresAt
    });

    const url = new URL(GOOGLE_AUTH_URL);
    url.searchParams.set('client_id', config.clientId);
    url.searchParams.set('redirect_uri', config.redirectUri);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('scope', GOOGLE_SCOPES.join(' '));
    url.searchParams.set('state', state);
    url.searchParams.set('access_type', 'offline');
    url.searchParams.set('prompt', 'consent');
    url.searchParams.set('include_granted_scopes', 'true');
    return response(200, { success: true, authorizationUrl: url.toString(), expiresInSeconds: STATE_TTL_MS / 1000 });
  } catch (error) {
    console.error('[Google OAuth] Start failed:', error.message);
    return response(503, { success: false, code: 'GOOGLE_OAUTH_START_FAILED' });
  }
}

async function status(event) {
  let user;
  try { user = await verifyUser(event); }
  catch (error) { return response(error.statusCode || 401, { success: false, code: 'AUTH_REQUIRED' }); }
  try {
    const admin = await getAdmin();
    const snapshot = await admin.firestore().collection(TOKEN_COLLECTION).doc(user.uid + '_google').get();
    if (!snapshot.exists) return response(200, { success: true, connected: false, provider: 'google' });
    const data = snapshot.data() || {};
    return response(200, {
      success: true,
      connected: true,
      provider: 'google',
      email: data.email || null,
      scopes: Array.isArray(data.scopes) ? data.scopes : [],
      connectedAt: data.connectedAt || null
    });
  } catch (error) {
    console.error('[Google OAuth] Status failed:', error.message);
    return response(503, { success: false, code: 'GOOGLE_STATUS_UNAVAILABLE' });
  }
}

async function disconnect(event) {
  let user;
  try { user = await verifyUser(event); }
  catch (error) { return response(error.statusCode || 401, { success: false, code: 'AUTH_REQUIRED' }); }
  try {
    const config = getConfig();
    const admin = await getAdmin();
    const db = admin.firestore();
    const ref = db.collection(TOKEN_COLLECTION).doc(user.uid + '_google');
    const snapshot = await ref.get();
    if (!snapshot.exists) return response(200, { success: true, connected: false });
    const saved = snapshot.data() || {};
    try {
      const tokens = decryptJson(saved.encryptedTokens, config.key);
      const token = tokens.refresh_token || tokens.access_token;
      if (token) {
        await fetch(GOOGLE_REVOKE_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ token })
        });
      }
    } catch (revokeError) {
      console.warn('[Google OAuth] Remote revoke did not complete; local credentials will still be removed.');
    }
    await ref.delete();
    return response(200, { success: true, connected: false });
  } catch (error) {
    console.error('[Google OAuth] Disconnect failed:', error.message);
    return response(503, { success: false, code: 'GOOGLE_DISCONNECT_FAILED' });
  }
}

async function callback(event) {
  const params = event.queryStringParameters || {};
  if (params.error) return html(400, 'Google connection not completed', 'Google did not grant access. Return to DomAskScreenAI and try again if you still want to connect.');
  const code = params.code;
  const state = params.state;
  if (!code || !state || typeof state !== 'string' || state.length > 256) {
    return html(400, 'Google connection failed', 'The authorization response was incomplete. Return to DomAskScreenAI and start again.');
  }

  let config;
  try { config = getConfig(); }
  catch (error) { return html(503, 'Google connection not ready', 'The server integration settings are not complete yet. Please try again later.'); }

  try {
    const admin = await getAdmin();
    const db = admin.firestore();
    const stateRef = db.collection(STATE_COLLECTION).doc(stateDigest(state));
    const stateSnapshot = await stateRef.get();
    if (!stateSnapshot.exists) return html(400, 'Google connection expired', 'This connection request is invalid or has already been used. Return to DomAskScreenAI and start again.');
    const stateData = stateSnapshot.data() || {};
    await stateRef.delete();
    if (stateData.provider !== 'google' || !stateData.uid || !Number.isFinite(stateData.expiresAt) || stateData.expiresAt < Date.now()) {
      return html(400, 'Google connection expired', 'This connection request has expired. Return to DomAskScreenAI and start again.');
    }

    const tokenResponse = await fetch(GOOGLE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: config.clientId,
        client_secret: config.clientSecret,
        redirect_uri: config.redirectUri,
        grant_type: 'authorization_code'
      })
    });
    const tokenData = await tokenResponse.json().catch(() => ({}));
    if (!tokenResponse.ok || !tokenData.access_token) {
      console.error('[Google OAuth] Token exchange rejected:', tokenData.error || tokenResponse.status);
      return html(400, 'Google connection failed', 'Google could not complete the connection. Check the OAuth client settings and try again.');
    }

    const userInfoResponse = await fetch(GOOGLE_USERINFO_URL, {
      headers: { Authorization: 'Bearer ' + tokenData.access_token }
    });
    const userInfo = await userInfoResponse.json().catch(() => ({}));
    if (!userInfoResponse.ok || !userInfo.email) {
      return html(400, 'Google connection failed', 'Google did not return the account email. Please try connecting again.');
    }

    const tokenRef = db.collection(TOKEN_COLLECTION).doc(stateData.uid + '_google');
    const previous = await tokenRef.get();
    let previousRefreshToken = null;
    if (previous.exists) {
      try {
        previousRefreshToken = decryptJson(previous.data().encryptedTokens, config.key).refresh_token || null;
      } catch (_) { /* A new refresh token will replace an unreadable old envelope. */ }
    }
    const storedTokens = {
      access_token: tokenData.access_token,
      refresh_token: tokenData.refresh_token || previousRefreshToken,
      token_type: tokenData.token_type || 'Bearer',
      expires_at: Date.now() + Math.max(0, Number(tokenData.expires_in || 0)) * 1000
    };
    await tokenRef.set({
      provider: 'google',
      email: userInfo.email,
      scopes: String(tokenData.scope || GOOGLE_SCOPES.join(' ')).split(' ').filter(Boolean),
      encryptedTokens: encryptJson(storedTokens, config.key),
      connectedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    return html(200, 'Google connected successfully', 'Your Google account (' + userInfo.email + ') is now connected to DomAskScreenAI.');
  } catch (error) {
    console.error('[Google OAuth] Callback failed:', error.message);
    return html(503, 'Google connection could not be saved', 'The server could not safely save the connection. Return to DomAskScreenAI and try again later.');
  }
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return response(204, {});
  const action = String((event.queryStringParameters || {}).action || '');
  if (event.httpMethod === 'GET' && action === 'status') return status(event);
  if (event.httpMethod === 'POST' && action === 'start') return start(event);
  if (event.httpMethod === 'POST' && action === 'disconnect') return disconnect(event);
  return response(405, { success: false, code: 'METHOD_NOT_ALLOWED' }, { Allow: 'GET, POST, OPTIONS' });
};

exports.callbackHandler = async event => {
  if (event.httpMethod !== 'GET') return html(405, 'Method not allowed', 'This endpoint accepts Google OAuth callbacks only.');
  return callback(event);
};

exports._test = { GOOGLE_SCOPES, encryptJson, decryptJson, stateDigest };
