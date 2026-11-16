'use strict';

const crypto = require('crypto');

function isAdminAccessCodeValid(event) {
  const configured = process.env.ADMIN_ACCESS_CODE || '';
  const supplied = String(
    event.headers?.['x-admin-access-code'] ||
    event.headers?.['X-Admin-Access-Code'] || ''
  );
  if (configured.length < 16 || supplied.length !== configured.length) return false;
  return crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(configured));
}

module.exports = { isAdminAccessCodeValid };
