// MailSentinel API client. Runs in the service worker only.
//
// It lives there rather than in the content script for a reason that is easy to miss:
// Gmail serves a strict Content-Security-Policy, and a content script's fetch is subject
// to the *page's* CSP, so a call to http://localhost:8080 from inside mail.google.com is
// blocked before it leaves the tab. The service worker has its own origin and the
// extension's host permissions, so the request succeeds there.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  /**
   * Mirrors the web client's parseErrorMessage: the server speaks RFC 7807 problem
   * details for ResponseStatusException and { error, message } for named exceptions,
   * where `error` is a machine code. Prose first, code only as a last resort.
   */
  async function errorMessageFrom(response) {
    let body = null;
    try {
      body = await response.json();
    } catch (ignored) {
      body = null;
    }
    const candidates = [body && body.message, body && body.detail, body && body.error, body && body.title];
    const prose = candidates.find((value) => typeof value === 'string' && value.trim() !== '');
    return prose || 'Request failed (' + response.status + ')';
  }

  async function scan(options) {
    const base = ns.settings.normalizeApiBase(options.apiBase);
    if (!base) {
      throw new Error('No MailSentinel server configured. Open the extension options to set one.');
    }

    const headers = {
      'Content-Type': 'application/json',
      // The server uses this to make a retried scan safe rather than double-charging an
      // AI quota. One value per scan attempt, so a retry of the same click reuses it.
      'Idempotency-Key': options.idempotencyKey || crypto.randomUUID(),
    };
    if (options.authToken) {
      headers.Authorization = 'Bearer ' + options.authToken;
    }

    let response;
    try {
      response = await fetch(base + '/api/scan', {
        method: 'POST',
        headers,
        body: JSON.stringify({ type: options.type, content: options.content }),
      });
    } catch (cause) {
      // A bare "Failed to fetch" is the single most likely thing a new user hits, and it
      // has exactly two causes worth naming for them.
      throw new Error(
        'Could not reach MailSentinel at ' + base + '. Is the server running, and has the '
        + 'extension been granted access to that address in its options?'
      );
    }

    if (!response.ok) {
      throw new Error(await errorMessageFrom(response));
    }
    return response.json();
  }

  ns.api = { scan, errorMessageFrom };
})(globalThis.MailSentinel);
