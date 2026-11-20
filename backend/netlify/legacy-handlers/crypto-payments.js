'use strict';

const { DEFAULT_PRODUCTS, normalizeCatalog } = require('../../pricing-catalog');
const { verifyUser } = require('./ask-screen');
const WALLETS = {
  BEP20: process.env.USDT_BEP20_ADDRESS || '0xc0C830d8165E9ff2Da899397128dB9247d64154F',
  TRC20: process.env.USDT_TRC20_ADDRESS || 'TSmksTL3haP4iSQC49j5mMpF9xw6feoXH'
};
function json(statusCode, data) {
  return { statusCode, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type',
    'Cache-Control': 'no-store' }, body: JSON.stringify(data) };
}
function validTxHash(network, hash) {
  if (typeof hash !== 'string') return false;
  const value = hash.trim();
  return network === 'TRC20' ? /^([A-Fa-f0-9]{64})$/.test(value) :
    network === 'BEP20' ? /^0x[a-fA-F0-9]{64}$/.test(value) : false;
}
function paymentId(network, hash) {
  return network.toLowerCase() + '_' + hash.trim().toLowerCase().replace(/^0x/, '');
}
let adminPromise;
async function getAdmin() {
  if (!process.env.FIREBASE_SERVICE_ACCOUNT_JSON) throw new Error('AUTH_NOT_CONFIGURED');
  if (!adminPromise) adminPromise = (async () => {
    const admin = require('firebase-admin');
    if (!admin.apps.length) admin.initializeApp({ credential: admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON)) });
    return admin;
  })();
  return adminPromise;
}
exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (event.httpMethod === 'GET') return json(200, {
    provider: 'USDT_MANUAL_REVIEW',
    networks: [
      { id: 'BEP20', name: 'BNB Smart Chain (BEP20)', address: WALLETS.BEP20, token: 'USDT' },
      { id: 'TRC20', name: 'TRON (TRC20)', address: WALLETS.TRC20, token: 'USDT' }
    ],
    paystack: { status: 'coming_soon', enabled: false },
    note: 'Send USDT only on the network selected. Access is not activated by a screenshot or submission. An administrator must verify the transaction on the blockchain and approve it.'
  });
  if (event.httpMethod !== 'POST') return json(405, { error: 'Method not allowed.' });
  if (!String(event.headers?.['content-type'] || event.headers?.['Content-Type'] || '').toLowerCase().includes('application/json')) return json(415, { error: 'Content-Type must be application/json.' });
  if (!event.body || Buffer.byteLength(event.body, 'utf8') > 5000) return json(413, { error: 'Request is too large.' });
  let body;
  try { body = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
  const productId = typeof body.productId === 'string' ? body.productId.trim() : '';
  const network = typeof body.network === 'string' ? body.network.trim().toUpperCase() : '';
  const txHash = typeof body.transactionHash === 'string' ? body.transactionHash.trim() : '';
  const product = DEFAULT_PRODUCTS.find(item => item.id === productId && (item.id === 'core' || item.independent === true));
  if (!product) return json(400, { error: 'Choose DOM AI Core or an available Work Centre service.' });
  if (!['BEP20', 'TRC20'].includes(network) || !validTxHash(network, txHash)) return json(400, { error: 'Choose the correct network and enter a valid transaction hash.' });
  try {
    const decoded = await verifyUser(event);
    const admin = await getAdmin();
    const customer = await admin.auth().getUser(decoded.uid);
    if (!customer.emailVerified || !customer.email) return json(403, { error: 'Verify your email address before submitting a payment.' });
    const email = String(customer.email).trim().toLowerCase();
    const db = admin.firestore();
    const configSnapshot = await db.collection('askScreenAppConfig').doc('public').get();
    const savedPricing = configSnapshot.exists ? configSnapshot.data().pricing : null;
    const catalog = normalizeCatalog(Array.isArray(savedPricing) ? savedPricing : DEFAULT_PRODUCTS);
    const pricedProduct = catalog.find(item => item.id === productId);
    if (!pricedProduct) return json(400, { error: 'The selected subscription is not available.' });
    const amount = Number((pricedProduct.monthlyPriceCents / 100).toFixed(2));
    if (!Number.isFinite(amount) || amount <= 0) return json(400, { error: 'This subscription does not have a valid monthly price.' });
    const id = paymentId(network, txHash);
    const ref = db.collection('askScreenPayments').doc(id);
    const existing = await ref.get();
    if (existing.exists) return json(409, { error: 'This transaction hash has already been submitted. Contact support if you believe this is an error.' });
    await ref.create({
      provider: 'USDT_MANUAL',
      uid: null,
      email,
      productId,
      productName: pricedProduct.name,
      amount: amount.toFixed(2),
      currency: 'USDT',
      expectedAmount: amount.toFixed(2),
      network,
      destinationAddress: WALLETS[network],
      reference: txHash,
      transactionHash: txHash,
      status: 'pending',
      verificationMethod: 'manual_admin_review',
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return json(201, { success: true, status: 'pending', message: 'Payment details submitted. Access remains locked until an administrator verifies and approves the transaction.' });
  } catch (error) {
    console.error('[Crypto Payments] Submission failed:', error.message);
    return json(error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500, { error: 'Payment submission is temporarily unavailable.' });
  }
};
exports._test = { validTxHash, paymentId, WALLETS };
