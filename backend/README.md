# AskScreen AI - Netlify Backend Deployment Guide

This backend allows AskScreen AI to securely process screen content, audio, voice queries, and files without embedding the Gemini API key in the mobile application.

## 1. Quick Deploy to Netlify

1. Go to [Netlify](https://app.netlify.com/).
2. Click **Add new site** > **Import an existing project** (or use the Netlify CLI: `netlify deploy --prod`).
3. Set the base directory to `backend`.
4. The function will be located at:
   `https://<your-site-name>.netlify.app/.netlify/functions/ask-screen`

## 2. Environment Variables

In your Netlify site settings:
1. Navigate to **Site configuration** > **Environment variables**.
2. Add a new variable:
   - **Key:** `GEMINI_API_KEY`
   - **Value:** Your Google Gemini API Key (obtained from Google AI Studio).

## 3. Configure the Android App

1. Open **AskScreen AI** on your Android phone.
2. Go to the **Settings** tab.
3. Enter your Netlify Function URL:
   `https://<your-site-name>.netlify.app/.netlify/functions/ask-screen`
4. Tap **Save & Test Connection**.
5. All requests from the floating bubble, screen captures, audio sessions, and file uploads will route through your secure serverless function.

*Note: For local testing or before Netlify deployment, you can also enter your Gemini API Key in the AI Studio Secrets panel or directly in the app's Settings to test instantly!*
