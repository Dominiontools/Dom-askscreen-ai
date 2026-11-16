'use strict';

const crypto = require('crypto');

function configuredCode() {
  return String(process.env.ADMIN_ACCESS_CODE || '');
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left), 'utf8');
  const b = Buffer.from(String(right), 'utf8');
  return a.length === b.length && a.length > 0 && crypto.timingSafeEqual(a, b);
}

function isAdminAccessCodeValid(event) {
  const configured = configuredCode();
  const supplied = String(
    event.headers?.['x-admin-access-code'] ||
    event.headers?.['X-Admin-Access-Code'] || ''
  );
  return configured.length >= 16 && safeEqual(supplied, configured);
}

function createAdminSession(user) {
  const secret = configuredCode();
  if (secret.length < 16) throw new Error('ADMIN_ACCESS_NOT_CONFIGURED');
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    uid: String(user.uid),
    email: String(user.email).trim().toLowerCase(),
    email_verified: true,
    role: 'admin',
    iat: now,
    exp: now + 8 * 60 * 60
  };
  const encoded = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signature = crypto.createHmac('sha256', secret).update(encoded).digest('base64url');
  return encoded + '.' + signature;
}

function verifyAdminSession(event) {
  const secret = configuredCode();
  if (secret.length < 16) {
    const error = new Error('ADMIN_ACCESS_NOT_CONFIGURED');
    error.statusCode = 503;
    throw error;
  }
  const authorization = String(event.headers?.authorization || event.headers?.Authorization || '');
  const match = authorization.match(/^Bearer ([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)$/);
  if (!match) {
    const error = new Error('ADMIN_SESSION_REQUIRED');
    error.statusCode = 401;
    throw error;
  }
  const expected = crypto.createHmac('sha256', secret).update(match[1]).digest('base64url');
  if (!safeEqual(match[2], expected)) {
    const error = new Error('ADMIN_SESSION_INVALID');
    error.statusCode = 401;
    throw error;
  }
  let payload;
  try {
    payload = JSON.parse(Buffer.from(match[1], 'base64url').toString('utf8'));
  } catch (_) {
    const error = new Error('ADMIN_SESSION_INVALID');
    error.statusCode = 401;
    throw error;
  }
  const email = String(payload.email || '').trim().toLowerCase();
  const allowed = (process.env.ADMIN_EMAILS || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean);
  if (payload.role !== 'admin' || payload.email_verified !== true || !payload.uid ||
      !email || !allowed.includes(email) || !Number.isInteger(payload.exp) ||
      payload.exp <= Math.floor(Date.now() / 1000)) {
    const error = new Error('ADMIN_SESSION_EXPIRED_OR_REVOKED');
    error.statusCode = 401;
    throw error;
  }
  return payload;
}

module.exports = { isAdminAccessCodeValid, createAdminSession, verifyAdminSession };
