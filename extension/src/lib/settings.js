// Shared settings access. Loaded as a classic script by the content scripts, the
// options page, and (via importScripts) the service worker, so all three read and
// write exactly one shape and one set of defaults.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  const DEFAULTS = {
    // No hosted MailSentinel exists yet, so the default points at the local JAR
    // (see the project README: `java -jar target/mailsentinel-0.1.0.jar` serves 8080).
    apiBase: 'http://localhost:8080',

    // Optional MailSentinel account token (mst_...). Anonymous scans work fine and
    // return the deterministic result; a token is only needed to draw on a plan's
    // AI analysis quota.
    authToken: '',

    // Optional Google OAuth client ID. Empty means the Gmail adapter stays on DOM
    // extraction; supplying one unlocks the raw-message upgrade (see gmailRaw in the
    // service worker). Deliberately user-supplied rather than baked into the manifest:
    // a client ID committed to a public repo is one anyone can burn the quota of.
    googleClientId: '',

    // Off by default, and that is a privacy decision rather than a performance one.
    // Scanning sends message content to the configured server, so it happens when the
    // user asks for it, not silently on every message they happen to open.
    autoScan: false,
  };

  async function load() {
    const stored = await chrome.storage.sync.get(DEFAULTS);
    return { ...DEFAULTS, ...stored };
  }

  async function save(patch) {
    await chrome.storage.sync.set(patch);
  }

  /**
   * Trim a trailing slash so callers can concatenate '/api/scan' without producing a
   * double slash, which some proxies treat as a different path.
   */
  function normalizeApiBase(value) {
    const trimmed = String(value || '').trim();
    return trimmed.replace(/\/+$/, '');
  }

  ns.settings = { DEFAULTS, load, save, normalizeApiBase };
})(globalThis.MailSentinel);
