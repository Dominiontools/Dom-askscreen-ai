'use strict';

const { verifyUser, routeRequest } = require('./ask-screen');
const { DEFAULT_PRODUCTS } = require('../../pricing-catalog');
const { timestampMillis } = require('../../product-subscriptions');

const PRODUCTS = new Map(DEFAULT_PRODUCTS.filter(p => p.independent && p.id !== 'email_management').map(p => [p.id, p]));
const MAX_INPUT = 24000;
const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
const MAX_ATTACHMENTS = 3;
const ALLOWED_MEDIA_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/ogg', 'audio/webm', 'audio/aac', 'audio/flac', 'application/pdf', 'text/plain']);

const SERVICE_GUIDES = {
  data_annotation: 'Act as a professional data annotator. Produce clear labels, categories, entities, quality checks and an uncertainty list. Follow the customer instructions exactly.',
  image_annotation: 'Act as an image annotation assistant. If image content was not supplied, request the image instead of inventing observations. When supplied, describe visible objects, attributes, positions and bounding-box guidance precisely.',
  video_annotation: 'Act as a video annotation assistant. If video frames or a transcript were not supplied, request them. Never invent timestamps or events. Return timestamped segments when evidence is available.',
  audio_annotation: 'Act as an audio annotation assistant. If audio or a transcript was not supplied, request it. Do not invent speakers or words. Return speaker turns, timestamps and uncertainty where supported.',
  text_annotation: 'Annotate the supplied text according to the requested labels. Return structured labels, spans, and quality notes.',
  ai_training: 'Perform model-response evaluation. Apply the requested rubric, score each criterion, cite evidence from the response, and provide a concise rationale.',
  virtual_assistant: 'Act as an executive virtual assistant. Produce the requested finished draft, plan, schedule, checklist or organized information. Do not claim to send or book anything.',
  lead_generation: 'Build a qualified lead research output. Clearly separate verified information from assumptions and include source URLs only when supplied or actually available.',
  customer_support: 'Act as a customer support agent. Draft a helpful, policy-safe response and classify intent, urgency and next action. Do not claim a ticket was updated unless connected.',
  inventory: 'Act as an inventory operations assistant. Calculate stock levels, reorder quantities and low-stock alerts from supplied data. Show formulas and flag missing data.',
  procurement: 'Act as a procurement assistant. Compare supplied vendors, specifications, prices and delivery terms; provide a recommendation and purchasing checklist.',
  calendar: 'Act as a scheduling assistant. Build a clear schedule from supplied availability. Do not claim to create calendar events without a connected calendar.',
  documents_spreadsheets: 'Create the requested document or spreadsheet-ready content. Use clear headings, tables and formulas where useful.',
  crm: 'Act as a CRM assistant. Organize customer information, classify leads, propose follow-ups and produce concise CRM-ready records. Do not claim to contact customers.',
  project_tasks: 'Act as a project manager. Break work into tasks, owners, priorities, dependencies, milestones and due dates. Do not claim to create external tasks.',
  business_admin: 'Act as a business operations administrator. Produce completed business documents, procedures, trackers and operational plans from the supplied facts.',
  marketing_social: 'Act as a social media manager. Produce platform-specific content calendars, captions, hooks, hashtags and campaign plans. Publishing and account analytics require a connected platform integration; never claim to publish.',
  research: 'Act as a research assistant. Separate evidence from inference, identify gaps and provide source references when available.',
  translation_transcription: 'Translate or transcribe the supplied text faithfully. Preserve meaning, names and formatting; mark unclear passages instead of guessing.',
  technical_assistant: 'Act as a technical support specialist. Diagnose the supplied issue, provide tested-looking but not fabricated commands, and clearly distinguish checks from actions actually performed.'
};

function json(statusCode, data) {
  return { statusCode, headers: {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Cache-Control': 'no-store'
  }, body: JSON.stringify(data) };
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

async function requireEntitlement(db, uid, productId) {
  const account = await db.collection('askScreenAccounts').doc(uid).get();
  const subscriptions = account.exists ? account.data().productSubscriptions || {} : {};
  const sub = subscriptions[productId];
  const expiry = timestampMillis(sub && sub.currentPeriodEnd);
  if (!sub || sub.status !== 'active' || !Number.isFinite(expiry) || expiry <= Date.now()) {
    const error = new Error('SUBSCRIPTION_REQUIRED');
    error.statusCode = 402;
    throw error;
  }
}


function normalizeWorkAttachments(parsed) {
  const rawImages = parsed.images == null ? [] : parsed.images;
  const rawFiles = parsed.files == null ? [] : parsed.files;
  const rawAudio = parsed.audio == null ? null : parsed.audio;
  if (!Array.isArray(rawImages) || !Array.isArray(rawFiles)) throw new Error('INVALID_ATTACHMENTS');
  if (rawImages.length + rawFiles.length + (rawAudio ? 1 : 0) > MAX_ATTACHMENTS) throw new Error('TOO_MANY_ATTACHMENTS');
  let totalBytes = 0;
  function normalize(item, expected) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('INVALID_ATTACHMENT');
    const mimeType = String(item.mimeType || '').toLowerCase().split(';')[0].trim();
    const data = item.data;
    if (!ALLOWED_MEDIA_TYPES.has(mimeType)) throw new Error('UNSUPPORTED_ATTACHMENT_TYPE');
    if (typeof data !== 'string' || !data.length || data.length % 4 !== 0 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data)) throw new Error('INVALID_ATTACHMENT_DATA');
    const bytes = Buffer.from(data, 'base64');
    if (!bytes.length || bytes.toString('base64') !== data) throw new Error('INVALID_ATTACHMENT_DATA');
    if (expected === 'image' && !mimeType.startsWith('image/')) throw new Error('INVALID_IMAGE_TYPE');
    if (expected === 'audio' && !mimeType.startsWith('audio/')) throw new Error('INVALID_AUDIO_TYPE');
    if (expected === 'file' && !['application/pdf', 'text/plain'].includes(mimeType)) throw new Error('INVALID_FILE_TYPE');
    totalBytes += bytes.length;
    if (totalBytes > MAX_UPLOAD_BYTES) throw new Error('ATTACHMENTS_TOO_LARGE');
    return { mimeType, data, sizeBytes: bytes.length };
  }
  return {
    images: rawImages.map(item => normalize(item, 'image')),
    files: rawFiles.map(item => normalize(item, 'file')),
    audio: rawAudio ? normalize(rawAudio, 'audio') : null,
    totalBytes
  };
}

function buildInstruction(product, task, context, attachments = []) {
  const guide = SERVICE_GUIDES[product.id] || 'Perform the requested specialist work accurately.';
  return [
    'You are the working assistant inside ' + product.name + '.',
    guide,
    'Complete the requested work. Do not merely explain how the customer could do it.',
    'Never claim to send emails, publish social posts, change CRM records, schedule meetings, purchase items, upload files, or operate an external account unless an integration actually performed that action.',
    'When an external connection is needed, provide the finished draft or structured output and state exactly which connection or approval is still required.',
    'Return a usable result with headings, tables or structured lists where appropriate.',
    'CUSTOMER REQUEST:\n' + task,
    context ? 'CUSTOMER MATERIAL:\n' + context : '',
    attachments.length ? 'The customer supplied ' + attachments.join(', ') + '. Inspect the actual attachment content and base every observation on it. Do not invent unreadable details.' : ''
  ].filter(Boolean).join('\n\n');
}

exports.handler = async event => {
  if (event.httpMethod === 'OPTIONS') return json(204, {});
  if (!['GET', 'POST'].includes(event.httpMethod)) return json(405, { error: 'Use GET for job history or POST to perform work.' });
  try {
    const user = await verifyUser(event);
    const admin = await getAdmin();
    const db = admin.firestore();
    let parsed = {};
    if (event.body) {
      try { parsed = JSON.parse(event.body); } catch (_) { return json(400, { error: 'Invalid JSON.' }); }
    }
    const productId = String(event.queryStringParameters && event.queryStringParameters.productId || parsed.productId || '');
    const product = PRODUCTS.get(productId);
    if (!product) return json(400, { error: 'Choose a valid Work Centre service.' });
    await requireEntitlement(db, user.uid, productId);
    const jobs = db.collection('askScreenWorkspaces').doc(user.uid).collection('serviceJobs');

    if (event.httpMethod === 'GET') {
      const snapshot = await jobs.orderBy('createdAt', 'desc').limit(100).get();
      const recent = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }))
        .filter(job => job.productId === productId).slice(0, 40);
      return json(200, { jobs: recent });
    }

    if (!event.body || Buffer.byteLength(event.body, 'utf8') > 3 * 1024 * 1024) {
      return json(413, { error: 'The request or uploaded files are too large. Each workspace request supports up to 2 MB of attachments.' });
    }
    const task = typeof parsed.task === 'string' ? parsed.task.trim() : '';
    const context = typeof parsed.context === 'string' ? parsed.context.trim() : '';
    if (!task) return json(400, { error: 'Describe the work you want completed.' });
    if (task.length > 8000 || context.length > MAX_INPUT) return json(413, { error: 'Task or supplied material is too long.' });
    let attachments;
    try { attachments = normalizeWorkAttachments(parsed); }
    catch (error) {
      const messages = {
        INVALID_ATTACHMENTS: 'Attachments must be supplied in the correct format.',
        TOO_MANY_ATTACHMENTS: 'Attach no more than three files at a time.',
        INVALID_ATTACHMENT: 'One of the attachments is invalid.',
        UNSUPPORTED_ATTACHMENT_TYPE: 'Supported uploads are images, audio, PDF and plain text.',
        INVALID_ATTACHMENT_DATA: 'An uploaded file is empty or could not be read.',
        INVALID_IMAGE_TYPE: 'Image uploads must use an image format.',
        INVALID_AUDIO_TYPE: 'Audio uploads must use an audio format.',
        INVALID_FILE_TYPE: 'Document uploads must be PDF or plain text.',
        ATTACHMENTS_TOO_LARGE: 'The combined upload size must not exceed 2 MB.'
      };
      return json(400, { error: messages[error.message] || 'The attachment could not be processed.', code: error.message });
    }
    const attachmentLabels = [
      ...attachments.images.map((item, index) => 'image ' + (index + 1) + ' (' + item.mimeType + ')'),
      ...attachments.files.map((item, index) => 'document ' + (index + 1) + ' (' + item.mimeType + ')'),
      ...(attachments.audio ? ['audio (' + attachments.audio.mimeType + ')'] : [])
    ];

    const jobRef = jobs.doc();
    const now = admin.firestore.FieldValue.serverTimestamp();
    await jobRef.set({ productId, productName: product.name, task, attachmentCount: attachmentLabels.length, attachmentTypes: attachmentLabels, status: 'processing', createdAt: now, updatedAt: now });

    try {
      const result = await routeRequest({
        prompt: buildInstruction(product, task, context, attachmentLabels),
        history: [], images: attachments.images, files: attachments.files, audio: attachments.audio
      });
      await jobRef.set({
        status: 'completed', result: result.answer,
        provider: result.provider, model: result.model,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
      return json(200, { success: true, job: {
        id: jobRef.id, productId, productName: product.name,
        status: 'completed', result: result.answer, provider: result.provider, model: result.model
      }});
    } catch (error) {
      await jobRef.set({
        status: 'failed',
        error: 'The task could not be completed. Please retry.',
        errorCode: error.code || 'WORK_FAILED',
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
      const unavailable = error.code === 'NO_PROVIDER_CONFIGURED' || error.code === 'NO_MULTIMODAL_PROVIDER';
      return json(unavailable ? 503 : 502, {
        success: false, jobId: jobRef.id,
        error: unavailable ? 'The AI service is not configured for this task.' : 'The task could not be completed. Please retry.',
        code: error.code || 'WORK_FAILED'
      });
    }
  } catch (error) {
    const status = error.statusCode || (error.message === 'AUTH_NOT_CONFIGURED' ? 503 : 500);
    return json(status, { error: status === 401 ? 'Please sign in again.' :
      status === 402 ? 'An active subscription is required to use this service.' :
      'Work Centre is temporarily unavailable.' });
  }
};

exports._test = { PRODUCTS, SERVICE_GUIDES, buildInstruction, requireEntitlement, normalizeWorkAttachments, MAX_UPLOAD_BYTES };
