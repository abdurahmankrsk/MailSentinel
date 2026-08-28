// Background service worker: the only place that talks to the network.
//
// Classic worker rather than an ES module so that importScripts can pull in the very
// same settings/api files the content scripts and options page load as plain scripts --
// one definition of the settings shape across all three contexts, with no build step.
importScripts('/src/lib/settings.js', '/src/lib/api.js');

const ns = globalThis.MailSentinel;

const GMAIL_SCOPE = 'https://www.googleapis.com/auth/gmail.readonly';

// Access tokens last an hour; holding one in worker memory avoids bouncing the user
// through an auth window on every scan. The worker being evicted just means the next
// scan re-authorises, which is silent when the Google session is still valid.
let cachedToken = null;

function tokenIsUsable() {
  return cachedToken && cachedToken.expiresAt > Date.now() + 60_000;
}

/**
 * Implicit-flow OAuth against a client ID the user supplies in options.
 *
 * launchWebAuthFlow rather than getAuthToken because getAuthToken requires the client ID
 * to be baked into the manifest, and a client ID committed to a public repository is one
 * anybody can burn the quota of. This way the credential stays the user's own.
 */
async function getGoogleToken(clientId, interactive) {
  if (tokenIsUsable()) {
    return cachedToken.value;
  }
  const redirectUri = chrome.identity.getRedirectURL();
  const authUrl = 'https://accounts.google.com/o/oauth2/v2/auth'
    + '?client_id=' + encodeURIComponent(clientId)
    + '&response_type=token'
    + '&redirect_uri=' + encodeURIComponent(redirectUri)
    + '&scope=' + encodeURIComponent(GMAIL_SCOPE)
    + '&prompt=' + (interactive ? 'consent' : 'none');

  const redirect = await chrome.identity.launchWebAuthFlow({ url: authUrl, interactive });
  if (!redirect) {
    throw new Error('Google sign-in was dismissed.');
  }
  const fragment = redirect.split('#')[1] || '';
  const params = new URLSearchParams(fragment);
  const token = params.get('access_token');
  if (!token) {
    throw new Error('Google did not return an access token: ' + (params.get('error') || 'unknown error'));
  }
  const expiresIn = Number(params.get('expires_in') || 3600);
  cachedToken = { value: token, expiresAt: Date.now() + expiresIn * 1000 };
  return token;
}

function base64UrlToUtf8(value) {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder('utf-8').decode(bytes);
}

/**
 * Fetch the true RFC 5322 source of one Gmail message.
 *
 * This is the whole point of the OAuth path: format=raw returns the message with its
 * Authentication-Results, Received and DKIM-Signature headers intact, which is what
 * the server's SPF/DKIM/DMARC checks actually read. The DOM can never supply these.
 */
async function fetchGmailRaw(messageId, clientId, interactive) {
  const token = await getGoogleToken(clientId, interactive);
  const url = 'https://gmail.googleapis.com/gmail/v1/users/me/messages/'
    + encodeURIComponent(messageId) + '?format=raw';
  const response = await fetch(url, { headers: { Authorization: 'Bearer ' + token } });

  if (response.status === 401) {
    cachedToken = null;
    throw new Error('Google access expired. Try the scan again to re-authorise.');
  }
  if (!response.ok) {
    throw new Error('Gmail API returned ' + response.status + ' for that message.');
  }
  const payload = await response.json();
  if (!payload.raw) {
    throw new Error('Gmail returned no raw source for that message.');
  }
  return base64UrlToUtf8(payload.raw);
}

const handlers = {
  async GET_SETTINGS() {
    return ns.settings.load();
  },

  async OPEN_OPTIONS() {
    // A content script cannot open the options page itself; only the extension's own
    // pages and the worker can.
    await chrome.runtime.openOptionsPage();
    return { opened: true };
  },

  async GMAIL_RAW(message) {
    const settings = await ns.settings.load();
    if (!settings.googleClientId) {
      throw new Error('No Google client ID configured.');
    }
    return {
      raw: await fetchGmailRaw(message.messageId, settings.googleClientId, message.interactive !== false),
    };
  },

  async SCAN(message) {
    const settings = await ns.settings.load();
    return ns.api.scan({
      apiBase: settings.apiBase,
      authToken: settings.authToken,
      type: message.scanType || 'email',
      content: message.content,
      idempotencyKey: message.idempotencyKey,
    });
  },
};

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const handler = handlers[message && message.type];
  if (!handler) {
    sendResponse({ ok: false, error: 'Unknown request: ' + (message && message.type) });
    return false;
  }
  // Errors are returned as data rather than thrown, so the content script gets one
  // uniform { ok, ... } envelope instead of having to read chrome.runtime.lastError.
  handler(message)
    .then((data) => sendResponse({ ok: true, data }))
    .catch((error) => sendResponse({ ok: false, error: error.message || String(error) }));
  return true; // keeps the message channel open for the async reply
});
