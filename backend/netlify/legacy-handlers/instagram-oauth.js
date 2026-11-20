'use strict';

const crypto = require('crypto');
const { verifyUser } = require('./ask-screen');

const GRAPH_VERSION = process.env.META_GRAPH_API_VERSION || 'v27.0';
const GRAPH_BASE = 'https://graph.facebook.com/' + GRAPH_VERSION;
const AUTH_URL = 'https://www.facebook.com/' + GRAPH_VERSION + '/dialog/oauth';
const TOKEN_COLLECTION = 'askScreenIntegrations';
const STATE_COLLECTION = 'askScreenOAuthStates';
const STATE_TTL_MS = 10 * 60 * 1000;
const PROVIDER = 'instagram';

function response(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type'
  }, body: JSON.stringify(data) };
}

function html(statusCode, title, message) {
  const escape = value => String(value).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
  return {
    statusCode,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
    body: '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>' +
      escape(title) + '</title></head><body style="font-family:system-ui;max-width:620px;margin:12vh auto;padding:24px;line-height:1.5">' +
      '<h2>' + escape(title) + '</h2><p>' + escape(message) + '</p><p>You can close this window and return to DomAskScreenAI.</p></body></html>'
  };
}

function getConfig() {
  const appId = process.env.META_APP_ID;
  const appSecret = process.env.META_APP_SECRET;
  const configId = process.env.META_INSTAGRAM_CONFIG_ID;
  const redirectUri = process.env.META_INSTAGRAM_REDIRECT_URI;
  const encryptionKey = process.env.INTEGRATION_TOKEN_ENCRYPTION_KEY;
  if (!appId || !appSecret || !configId || !redirectUri || !encryptionKey) {
    const error = new Error('INSTAGRAM_INTEGRATION_NOT_CONFIGURED');
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
  if (redirect.protocol !== 'https:' ||
      redirect.hostname !== 'domaskscreenai.netlify.app' ||
      redirect.pathname !== '/.netlify/functions/instagram-oauth-callback' ||
      redirect.search || redirect.hash) {
    const error = new Error('INVALID_INSTAGRAM_REDIRECT_URI');
    error.statusCode = 503;
    throw error;
  }
  if (!/^\\d+$/.test(String(configId))) {
    const error = new Error('INVALID_INSTAGRAM_CONFIGURATION_ID');
    error.statusCode = 503;
    throw error;
  }
  return { appId, appSecret, configId, redirectUri, key };
}

function encryptJson(value, key) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return { version: 1, iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') };
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

function authorizationUrl(config, state) {
  const url = new URL(AUTH_URL);
  url.searchParams.set('client_id', config.appId);
  url.searchParams.set('redirect_uri', config.redirectUri);
  url.searchParams.set('config_id', config.configId);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('override_default_response_type', 'true');
  url.searchParams.set('state', state);
  return url.toString();
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
    await admin.firestore().collection(STATE_COLLECTION).doc(stateDigest(state)).create({
      uid: user.uid,
      provider: PROVIDER,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      expiresAt: Date.now() + STATE_TTL_MS
    });
    return response(200, {
      success: true,
      authorizationUrl: authorizationUrl(config, state),
      expiresInSeconds: STATE_TTL_MS / 1000
    });
  } catch (error) {
    console.error('[Instagram OAuth] Start failed:', error.message);
    return response(503, { success: false, code: 'INSTAGRAM_OAUTH_START_FAILED' });
  }
}

async function graphGet(path, params) {
  const url = new URL(GRAPH_BASE + path);
  for (const [key, value] of Object.entries(params || {})) url.searchParams.set(key, String(value));
  const result = await fetch(url.toString(), { headers: { Accept: 'application/json' } });
  const data = await result.json().catch(() => ({}));
  if (!result.ok || data.error) {
    const error = new Error('META_GRAPH_REQUEST_FAILED');
    error.metaCode = data.error && data.error.code;
    throw error;
  }
  return data;
}

async function inspectToken(token, config) {
  const debug = await graphGet('/debug_token', {
    input_token: token,
    access_token: config.appId + '|' + config.appSecret
  });
  const tokenData = debug.data || {};
  if (!tokenData.is_valid || String(tokenData.app_id || '') !== String(config.appId)) {
    throw new Error('INVALID_META_ACCESS_TOKEN');
  }

  const scopes = Array.isArray(tokenData.scopes) ? tokenData.scopes : [];
  const granular = Array.isArray(tokenData.granular_scopes) ? tokenData.granular_scopes : [];
  const instagramScope = granular.find(item => item.scope === 'instagram_basic');
  const targetIds = instagramScope && Array.isArray(instagramScope.target_ids) ? instagramScope.target_ids : [];
  const accounts = [];

  // Unified Login system-user tokens can be asset-scoped. Use only Instagram IDs
  // explicitly returned by Meta's token debugger; never guess an account ID.
  for (const id of targetIds.slice(0, 10)) {
    try {
      const account = await graphGet('/' + encodeURIComponent(id), {
        fields: 'id,username,name,profile_picture_url',
        access_token: token
      });
      accounts.push({
        id: String(account.id || id),
        username: typeof account.username === 'string' ? account.username : null,
        name: typeof account.name === 'string' ? account.name : null,
        profilePictureUrl: typeof account.profile_picture_url === 'string' ? account.profile_picture_url : null
      });
    } catch (error) {
      console.warn('[Instagram OAuth] Could not read one granted Instagram asset:', error.message);
    }
  }
  return { scopes, accounts, tokenExpiresAt: Number(tokenData.expires_at || 0) || null };
}

async function status(event) {
  let user;
  try { user = await verifyUser(event); }
  catch (error) { return response(error.statusCode || 401, { success: false, code: 'AUTH_REQUIRED' }); }
  try {
    const admin = await getAdmin();
    const snapshot = await admin.firestore().collection(TOKEN_COLLECTION).doc(user.uid + '_instagram').get();
    if (!snapshot.exists) return response(200, { success: true, connected: false, provider: PROVIDER });
    const data = snapshot.data() || {};
    return response(200, {
      success: true,
      connected: true,
      provider: PROVIDER,
      accounts: Array.isArray(data.accounts) ? data.accounts : [],
      scopes: Array.isArray(data.scopes) ? data.scopes : [],
      tokenExpiresAt: data.tokenExpiresAt || null,
      connectedAt: data.connectedAt || null
    });
  } catch (error) {
    console.error('[Instagram OAuth] Status failed:', error.message);
    return response(503, { success: false, code: 'INSTAGRAM_STATUS_UNAVAILABLE' });
  }
}

async function disconnect(event) {
  let user;
  try { user = await verifyUser(event); }
  catch (error) { return response(error.statusCode || 401, { success: false, code: 'AUTH_REQUIRED' }); }
  try {
    const admin = await getAdmin();
    const ref = admin.firestore().collection(TOKEN_COLLECTION).doc(user.uid + '_instagram');
    await ref.delete();
    return response(200, { success: true, connected: false });
  } catch (error) {
    console.error('[Instagram OAuth] Disconnect failed:', error.message);
    return response(503, { success: false, code: 'INSTAGRAM_DISCONNECT_FAILED' });
  }
}

async function callback(event) {
  const params = event.queryStringParameters || {};
  if (params.error) return html(400, 'Instagram connection not completed', 'Meta did not grant access. Return to DomAskScreenAI and try again.');
  const code = params.code;
  const state = params.state;
  if (!code || !state || typeof state !== 'string' || state.length > 256) {
    return html(400, 'Instagram connection failed', 'The authorization response was incomplete. Return to DomAskScreenAI and start again.');
  }

  let config;
  try { config = getConfig(); }
  catch (_) { return html(503, 'Instagram connection not ready', 'The server integration settings are not complete yet. Please try again later.'); }

  try {
    const admin = await getAdmin();
    const db = admin.firestore();
    const stateRef = db.collection(STATE_COLLECTION).doc(stateDigest(state));
    const stateData = await db.runTransaction(async transaction => {
      const snapshot = await transaction.get(stateRef);
      if (!snapshot.exists) return null;
      const data = snapshot.data() || {};
      transaction.delete(stateRef);
      return data;
    });
    if (!stateData || stateData.provider !== PROVIDER || !stateData.uid ||
        !Number.isFinite(stateData.expiresAt) || stateData.expiresAt < Date.now()) {
      return html(400, 'Instagram connection expired', 'This connection request is invalid, expired or has already been used. Return to DomAskScreenAI and start again.');
    }

    const tokenUrl = new URL(GRAPH_BASE + '/oauth/access_token');
    tokenUrl.searchParams.set('client_id', config.appId);
    tokenUrl.searchParams.set('client_secret', config.appSecret);
    tokenUrl.searchParams.set('redirect_uri', config.redirectUri);
    tokenUrl.searchParams.set('code', code);
    const tokenResponse = await fetch(tokenUrl.toString(), { headers: { Accept: 'application/json' } });
    const tokenData = await tokenResponse.json().catch(() => ({}));
    if (!tokenResponse.ok || !tokenData.access_token) {
      console.error('[Instagram OAuth] Token exchange rejected:', tokenData.error && tokenData.error.code || tokenResponse.status);
      return html(400, 'Instagram connection failed', 'Meta could not complete the secure token exchange. Check the app configuration and try again.');
    }

    const inspected = await inspectToken(tokenData.access_token, config);
    const ref = db.collection(TOKEN_COLLECTION).doc(stateData.uid + '_instagram');
    await ref.set({
      provider: PROVIDER,
      tokenType: 'business_integration_system_user',
      encryptedTokens: encryptJson({
        access_token: tokenData.access_token,
        token_type: tokenData.token_type || 'bearer',
        expires_at: inspected.tokenExpiresAt
      }, config.key),
      accounts: inspected.accounts,
      scopes: inspected.scopes,
      tokenExpiresAt: inspected.tokenExpiresAt,
      connectedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    const label = inspected.accounts.length
      ? 'Your Instagram account is now connected to DomAskScreenAI.'
      : 'Meta authorized the connection. The account details will appear after the granted Instagram asset can be read.';
    return html(200, 'Instagram connected', label);
  } catch (error) {
    console.error('[Instagram OAuth] Callback failed:', error.message);
    return html(503, 'Instagram connection could not be saved', 'The server could not safely verify and save the connection. Return to DomAskScreenAI and try again later.');
  }
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return response(204, {});
  const action = String(event.queryStringParameters && event.queryStringParameters.action || '');
  if (event.httpMethod === 'GET' && action === 'status') return status(event);
  if (event.httpMethod === 'POST' && action === 'start') return start(event);
  if (event.httpMethod === 'POST' && action === 'disconnect') return disconnect(event);
  return response(405, { success: false, code: 'METHOD_NOT_ALLOWED' }, { Allow: 'GET, POST, OPTIONS' });
};

exports.callbackHandler = async event => {
  if (event.httpMethod !== 'GET') return html(405, 'Method not allowed', 'This endpoint accepts Instagram authorization callbacks only.');
  return callback(event);
};

exports._test = { encryptJson, decryptJson, stateDigest, authorizationUrl, GRAPH_VERSION };
exports._internal = { getConfig, getAdmin, encryptJson, decryptJson, TOKEN_COLLECTION, inspectToken };
