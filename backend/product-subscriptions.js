'use strict';

const { DEFAULT_PRODUCTS } = require('../pricing-catalog');

function timestampMillis(value) {
  if (value && typeof value.toDate === 'function') {
    const date = value.toDate();
    return date instanceof Date ? date.getTime() : NaN;
  }
  if (value instanceof Date) return value.getTime();
  if (typeof value === 'number') return value;
  if (typeof value === 'string') return Date.parse(value);
  return NaN;
}

function evaluateProductSubscription(record, now = Date.now()) {
  const item = record && typeof record === 'object' ? record : {};
  const end = timestampMillis(item.currentPeriodEnd);
  const active = item.status === 'active' && Number.isFinite(end) && end > now;
  const status = active ? 'active' : (
    item.status === 'active' ? 'expired' :
    (['cancelled', 'canceled', 'past_due', 'pending'].includes(item.status) ? item.status : 'inactive')
  );
  return {
    status,
    currentPeriodEnd: Number.isFinite(end) ? new Date(end).toISOString() : null,
    cancelAtPeriodEnd: item.cancelAtPeriodEnd === true
  };
}

function summarizeProductSubscriptions(products, now = Date.now()) {
  const source = products && typeof products === 'object' && !Array.isArray(products) ? products : {};
  return DEFAULT_PRODUCTS
    .filter(product => product.independent === true)
    .map(product => ({
      id: product.id,
      name: product.name,
      ...evaluateProductSubscription(source[product.id], now)
    }));
}

module.exports = { timestampMillis, evaluateProductSubscription, summarizeProductSubscriptions };
