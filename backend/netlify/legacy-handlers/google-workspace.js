'use strict';

const { verifyUser } = require('./ask-screen');
const { _internal } = require('./google-oauth');

const GMAIL_READ = 'https://www.googleapis.com/auth/gmail.readonly';
const GMAIL_COMPOSE = 'https://www.googleapis.com/auth/gmail.compose';
const GMAIL_SEND = 'https://www.googleapis.com/auth/gmail.send';
const CALENDAR_EVENTS = 'https://www.googleapis.com/auth/calendar.events';

function json(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type'
  }, body: JSON.stringify(data) };
}

function parseBody(event) {
  if (!event.body) return {};
  try {
    const value = JSON.parse(event.body);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    return value;
  } catch (_) { throw Object.assign(new Error('INVALID_JSON'), { statusCode: 400 }); }
}

function requireScopes(connection, required) {
  const granted = new Set(Array.isArray(connection.scopes) ? connection.scopes : []);
  const missing = required.filter(scope => !granted.has(scope));
  if (missing.length) throw Object.assign(new Error('GOOGLE_SCOPE_MISSING'), { statusCode: 403 });
}

async function accessTokenFor(uid) {
  const config = _internal.getConfig();
  const admin = await _internal.getAdmin();
  const ref = admin.firestore().collection(_internal.TOKEN_COLLECTION).doc(uid + '_google');
  const snapshot = await ref.get();
  if (!snapshot.exists) throw Object.assign(new Error('GOOGLE_NOT_CONNECTED'), { statusCode: 409 });
  const connection = snapshot.data() || {};
  let tokens = _internal.decryptJson(connection.encryptedTokens, config.key);

  if (!tokens.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    if (!tokens.refresh_token) throw Object.assign(new Error('GOOGLE_RECONNECT_REQUIRED'), { statusCode: 409 });
    const refreshed = await fetch(_internal.GOOGLE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: config.clientId,
        client_secret: config.clientSecret,
        refresh_token: tokens.refresh_token,
        grant_type: 'refresh_token'
      })
    });
    const data = await refreshed.json().catch(() => ({}));
    if (!refreshed.ok || !data.access_token) {
      if (data.error === 'invalid_grant') throw Object.assign(new Error('GOOGLE_RECONNECT_REQUIRED'), { statusCode: 409 });
      throw Object.assign(new Error('GOOGLE_TOKEN_REFRESH_FAILED'), { statusCode: 503 });
    }
    tokens = {
      ...tokens,
      access_token: data.access_token,
      refresh_token: data.refresh_token || tokens.refresh_token,
      token_type: data.token_type || 'Bearer',
      expires_at: Date.now() + Math.max(0, Number(data.expires_in || 0)) * 1000
    };
    await ref.set({
      encryptedTokens: _internal.encryptJson(tokens, config.key),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
  }
  return { accessToken: tokens.access_token, connection };
}

async function googleApi(path, accessToken, options = {}) {
  const response = await fetch('https://www.googleapis.com' + path, {
    ...options,
    headers: { Authorization: 'Bearer ' + accessToken, ...(options.headers || {}) }
  });
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = {}; }
  if (!response.ok) {
    const status = response.status === 401 ? 409 : response.status === 403 ? 403 : 502;
    const error = new Error(response.status === 401 ? 'GOOGLE_RECONNECT_REQUIRED' :
      response.status === 403 ? 'GOOGLE_PERMISSION_DENIED' : 'GOOGLE_API_REQUEST_FAILED');
    error.statusCode = status;
    throw error;
  }
  return data;
}

function encodeBase64Url(value) {
  return Buffer.from(value).toString('base64').replace(/=/g, '').replaceAll('+', '-').replaceAll('/', '_');
}

async function listGmail(uid, event) {
  const { accessToken, connection } = await accessTokenFor(uid);
  requireScopes(connection, [GMAIL_READ]);
  const params = new URLSearchParams();
  const query = String(event.queryStringParameters && event.queryStringParameters.q || '').trim().slice(0, 200);
  const max = Math.min(20, Math.max(1, Number.parseInt(event.queryStringParameters && event.queryStringParameters.maxResults || '10', 10) || 10));
  if (query) params.set('q', query);
  params.set('maxResults', String(max));
  const listing = await googleApi('/gmail/v1/users/me/messages?' + params.toString(), accessToken);
  const messages = await Promise.all((listing.messages || []).slice(0, max).map(async item => {
    const message = await googleApi('/gmail/v1/users/me/messages/' + encodeURIComponent(item.id) +
      '?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date', accessToken);
    const headers = message.payload && message.payload.headers || [];
    const header = name => (headers.find(x => String(x.name).toLowerCase() === name.toLowerCase()) || {}).value || '';
    return { id: message.id, threadId: message.threadId, snippet: message.snippet || '',
      from: header('From'), subject: header('Subject'), date: header('Date') };
  }));
  return { success: true, messages, nextPageToken: listing.nextPageToken || null };
}

async function createGmail(uid, parsed, send) {
  const to = typeof parsed.to === 'string' ? parsed.to.trim() : '';
  const subject = typeof parsed.subject === 'string' ? parsed.subject.trim() : '';
  const body = typeof parsed.body === 'string' ? parsed.body : '';
  if (!to || to.length > 254 || /[\r\n]/.test(to) || !/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(to)) {
    throw Object.assign(new Error('INVALID_RECIPIENT'), { statusCode: 400 });
  }
  if (!subject || subject.length > 200 || /[\r\n]/.test(subject) || !body.trim() || body.length > 100_000) {
    throw Object.assign(new Error('INVALID_EMAIL_CONTENT'), { statusCode: 400 });
  }
  const { accessToken, connection } = await accessTokenFor(uid);
  requireScopes(connection, send ? [GMAIL_SEND] : [GMAIL_COMPOSE]);
  const raw = encodeBase64Url('To: ' + to + '\r\nSubject: ' + subject +
    '\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n' + body);
  const data = await googleApi('/gmail/v1/users/me/' + (send ? 'messages/send' : 'drafts'), accessToken, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(send ? { raw } : { message: { raw } })
  });
  return { success: true, id: data.id || data.message && data.message.id || null,
    threadId: data.threadId || data.message && data.message.threadId || null,
    status: send ? 'sent' : 'draft_saved' };
}

async function listCalendar(uid, event) {
  const { accessToken, connection } = await accessTokenFor(uid);
  requireScopes(connection, [CALENDAR_EVENTS]);
  const params = new URLSearchParams();
  params.set('maxResults', '25');
  params.set('singleEvents', 'true');
  params.set('orderBy', 'startTime');
  const now = new Date();
  params.set('timeMin', String(event.queryStringParameters && event.queryStringParameters.timeMin || now.toISOString()));
  const defaultEnd = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString();
  params.set('timeMax', String(event.queryStringParameters && event.queryStringParameters.timeMax || defaultEnd));
  const data = await googleApi('/calendar/v3/calendars/primary/events?' + params.toString(), accessToken);
  return { success: true, events: (data.items || []).map(item => ({
    id: item.id, summary: item.summary || '(No title)', description: item.description || '',
    start: item.start && (item.start.dateTime || item.start.date) || '',
    end: item.end && (item.end.dateTime || item.end.date) || '',
    htmlLink: item.htmlLink || ''
  })) };
}

async function createCalendarEvent(uid, parsed) {
  const summary = typeof parsed.summary === 'string' ? parsed.summary.trim() : '';
  const description = typeof parsed.description === 'string' ? parsed.description : '';
  const start = typeof parsed.start === 'string' ? parsed.start : '';
  const end = typeof parsed.end === 'string' ? parsed.end : '';
  const startMs = Date.parse(start), endMs = Date.parse(end);
  if (!summary || summary.length > 200 || !Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) {
    throw Object.assign(new Error('INVALID_CALENDAR_EVENT'), { statusCode: 400 });
  }
  const { accessToken, connection } = await accessTokenFor(uid);
  requireScopes(connection, [CALENDAR_EVENTS]);
  const eventBody = { summary, description: description.slice(0, 5000),
    start: { dateTime: new Date(startMs).toISOString() },
    end: { dateTime: new Date(endMs).toISOString() } };
  const created = await googleApi('/calendar/v3/calendars/primary/events', accessToken, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(eventBody)
  });
  return { success: true, event: { id: created.id, summary: created.summary, htmlLink: created.htmlLink || '' } };
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  try {
    const user = await verifyUser(event);
    const action = String(event.queryStringParameters && event.queryStringParameters.action || '');
    if (event.httpMethod === 'GET' && action === 'gmail-list') return json(200, await listGmail(user.uid, event));
    if (event.httpMethod === 'GET' && action === 'calendar-list') return json(200, await listCalendar(user.uid, event));
    if (event.httpMethod !== 'POST') return json(405, { success: false, error: 'METHOD_NOT_ALLOWED' });
    const parsed = parseBody(event);
    if (action === 'gmail-draft') return json(200, await createGmail(user.uid, parsed, false));
    if (action === 'gmail-send') return json(200, await createGmail(user.uid, parsed, true));
    if (action === 'calendar-create') return json(200, await createCalendarEvent(user.uid, parsed));
    return json(400, { success: false, error: 'UNKNOWN_GOOGLE_ACTION' });
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    const known = new Set(['GOOGLE_NOT_CONNECTED','GOOGLE_RECONNECT_REQUIRED','GOOGLE_SCOPE_MISSING','GOOGLE_TOKEN_REFRESH_FAILED',
      'GOOGLE_PERMISSION_DENIED','INVALID_RECIPIENT','INVALID_EMAIL_CONTENT','INVALID_CALENDAR_EVENT','INVALID_JSON']);
    const code = known.has(error.message) ? error.message : status === 401 ? 'AUTH_REQUIRED' : 'GOOGLE_WORKSPACE_UNAVAILABLE';
    return json(status, { success: false, error: code });
  }
};

exports._test = { encodeBase64Url, requireScopes };
