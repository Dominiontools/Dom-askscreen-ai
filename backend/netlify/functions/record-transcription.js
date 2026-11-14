'use strict';

const { randomUUID } = require('crypto');
const { verifyUser, reserveFeatureUse } = require('./ask-screen');

const headers = {
  'Content-Type': 'application/json; charset=utf-8',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization'
};

exports.handler = async function handler(event) {
  const requestId = randomUUID();
  const respond = (statusCode, data) => ({
    statusCode, headers, body: JSON.stringify(data)
  });
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers, body: '' };
  if (event.httpMethod !== 'POST') {
    return respond(405, { success: false, error: 'Method not allowed.' });
  }

  let user;
  try {
    user = await verifyUser(event);
  } catch (error) {
    const status = error.statusCode || 503;
    return respond(status, {
      success: false,
      code: status === 401 ? 'AUTH_REQUIRED' : 'AUTH_ERROR',
      error: status === 401 ? 'Please sign in to use transcription.' : 'Account verification is temporarily unavailable.'
    });
  }

  try {
    await reserveFeatureUse(user.uid, 'transcription', requestId);
    return respond(200, { success: true, feature: 'transcription' });
  } catch (error) {
    if (error.message === 'SUBSCRIPTION_REQUIRED') {
      return respond(402, {
        success: false,
        code: 'SUBSCRIPTION_REQUIRED',
        feature: 'transcription',
        error: 'Your two free transcription uses have been used. Subscribe to continue.'
      });
    }
    console.error('[Transcription Access] Usage reservation failed', {
      requestId, code: error.message || 'USAGE_ERROR'
    });
    return respond(503, {
      success: false,
      code: 'USAGE_CHECK_UNAVAILABLE',
      error: 'We could not verify your transcription allowance. Please try again.'
    });
  }
};
