'use strict';

const DEFAULT_PRODUCTS = [
  { id: 'core', name: 'DOM AI Core', category: 'Core AI', monthlyPriceCents: 900 },
  { id: 'data_annotation', name: 'General Data Annotation AI', category: 'Annotation', monthlyPriceCents: 2900, independent: true },
  { id: 'image_annotation', name: 'Image Annotation AI', category: 'Annotation', monthlyPriceCents: 2000, independent: true },
  { id: 'video_annotation', name: 'Video Annotation AI', category: 'Annotation', monthlyPriceCents: 3500, independent: true },
  { id: 'audio_annotation', name: 'Audio Annotation AI', category: 'Annotation', monthlyPriceCents: 2000, independent: true },
  { id: 'text_annotation', name: 'Text Annotation AI', category: 'Annotation', monthlyPriceCents: 1800, independent: true },
  { id: 'ai_training', name: 'AI Training & Evaluation', category: 'AI Training', monthlyPriceCents: 3000, independent: true },
  { id: 'virtual_assistant', name: 'Virtual Assistant AI', category: 'Business', monthlyPriceCents: 3900, independent: true },
  { id: 'lead_generation', name: 'Lead Generation AI', category: 'Business', monthlyPriceCents: 2900, independent: true },
  { id: 'customer_support', name: 'Customer Support AI', category: 'Business', monthlyPriceCents: 2900, independent: true },
  { id: 'inventory', name: 'Inventory Management AI', category: 'Business', monthlyPriceCents: 2500, independent: true },
  { id: 'procurement', name: 'Purchasing & Procurement AI', category: 'Business', monthlyPriceCents: 2500, independent: true },
  { id: 'calendar', name: 'Calendar & Scheduling AI', category: 'Productivity', monthlyPriceCents: 1500, independent: true },
  { id: 'documents_spreadsheets', name: 'Spreadsheet & Document AI', category: 'Productivity', monthlyPriceCents: 2000, independent: true },
  { id: 'crm', name: 'Customer & CRM Management AI', category: 'Business', monthlyPriceCents: 2900, independent: true },
  { id: 'project_tasks', name: 'Project & Task Management AI', category: 'Productivity', monthlyPriceCents: 2000, independent: true },
  { id: 'business_admin', name: 'Business Administration AI', category: 'Business', monthlyPriceCents: 2500, independent: true },
  { id: 'marketing_social', name: 'Marketing & Social Media AI', category: 'Business', monthlyPriceCents: 2900, independent: true },
  { id: 'research', name: 'Research Assistant AI', category: 'Productivity', monthlyPriceCents: 2000, independent: true },
  { id: 'translation_transcription', name: 'Translation & Transcription AI', category: 'Productivity', monthlyPriceCents: 2000, independent: true },
  { id: 'technical_assistant', name: 'Technical Assistant AI', category: 'Technical', monthlyPriceCents: 3900, independent: true },
  { id: 'email_management', name: 'DOM Email Management System', category: 'Separate product', monthlyPriceCents: 2000, independent: true }
];

const DEFAULT_BUNDLE_DISCOUNT_PERCENT = 10;

function normalizeCatalog(input) {
  if (!Array.isArray(input) || input.length !== DEFAULT_PRODUCTS.length) {
    throw new Error('Pricing must include every product in the published catalogue.');
  }
  const byId = new Map(input.map(item => [String(item?.id || ''), item]));
  const normalized = DEFAULT_PRODUCTS.map(product => {
    const supplied = byId.get(product.id);
    if (!supplied) throw new Error('Missing pricing for ' + product.name + '.');
    const monthlyPriceCents = supplied.monthlyPriceCents;
    if (!Number.isInteger(monthlyPriceCents) || monthlyPriceCents < 0 || monthlyPriceCents > 1000000) {
      throw new Error('Price for ' + product.name + ' must be between $0 and $10,000 per month.');
    }
    return { ...product, monthlyPriceCents };
  });
  if (byId.size !== DEFAULT_PRODUCTS.length) throw new Error('Pricing contains an unknown or duplicate product.');
  return normalized;
}

function calculateBundle(pricing, discountPercent = DEFAULT_BUNDLE_DISCOUNT_PERCENT) {
  const products = normalizeCatalog(pricing);
  if (!Number.isInteger(discountPercent) || discountPercent < 0 || discountPercent > 30) {
    throw new Error('Bundle discount must be a whole number from 0 to 30 percent.');
  }
  const core = products.find(item => item.id === 'core').monthlyPriceCents;
  const specialists = products.filter(item => item.id !== 'core' && item.id !== 'email_management');
  const email = products.find(item => item.id === 'email_management').monthlyPriceCents;
  const aiSuiteCents = core + specialists.reduce((sum, item) => sum + item.monthlyPriceCents, 0);
  const fullBundleCents = aiSuiteCents + email;
  const applyDiscount = total => Math.round(total * (100 - discountPercent) / 100);
  return {
    discountPercent,
    aiSuite: { regularPriceCents: aiSuiteCents, priceCents: applyDiscount(aiSuiteCents), savingsCents: aiSuiteCents - applyDiscount(aiSuiteCents) },
    completeBusiness: { regularPriceCents: fullBundleCents, priceCents: applyDiscount(fullBundleCents), savingsCents: fullBundleCents - applyDiscount(fullBundleCents) }
  };
}

module.exports = {
  DEFAULT_PRODUCTS,
  DEFAULT_BUNDLE_DISCOUNT_PERCENT,
  normalizeCatalog,
  calculateBundle
};
