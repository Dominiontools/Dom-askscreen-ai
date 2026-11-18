const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Content-Type': 'application/json'
};

function providerConfiguration() {
  const providers = {
    gemini: Boolean(process.env.GEMINI_API_KEY || process.env.GEMINI_API_KEY_2 || process.env.GEMINI_API_KEY_3),
    groq: Boolean(process.env.GROQ_API_KEY),
    openrouter: Boolean(process.env.OPENROUTER_API_KEY),
    cohere: Boolean(process.env.COHERE_API_KEY),
    nvidia: Boolean(process.env.NVIDIA_API_KEY),
    zai: Boolean(process.env.ZAI_API_KEY),
    cloudflare: Boolean(
      (process.env.CLOUDFLARE_API_TOKEN || process.env.CLOUDFLARE_API_KEY) &&
      process.env.CLOUDFLARE_ACCOUNT_ID
    ),
    custom: Boolean(
      process.env.CUSTOM1_API_KEY &&
      process.env.CUSTOM1_BASE_URL &&
      process.env.CUSTOM1_MODEL
    )
  };

  return {
    providers,
    configuredProviderCount: Object.values(providers).filter(Boolean).length,
    multimodalConfigured: providers.gemini
  };
}

exports.handler = async function handler(event) {
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 204, headers: CORS_HEADERS, body: '' };
  }

  if (event.httpMethod !== 'GET') {
    return {
      statusCode: 405,
      headers: { ...CORS_HEADERS, Allow: 'GET, OPTIONS' },
      body: JSON.stringify({ status: 'method_not_allowed' })
    };
  }

  const configuration = providerConfiguration();
  return {
    statusCode: 200,
    headers: {
      ...CORS_HEADERS,
      'Cache-Control': 'no-store'
    },
    body: JSON.stringify({
      status: 'ok',
      service: 'DOM Ask AI backend',
      timestamp: new Date().toISOString(),
      ...configuration
    })
  };
};
