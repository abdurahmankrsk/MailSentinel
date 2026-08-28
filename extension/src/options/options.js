(function (ns) {
  'use strict';

  const fields = {
    apiBase: document.getElementById('apiBase'),
    authToken: document.getElementById('authToken'),
    googleClientId: document.getElementById('googleClientId'),
    autoScan: document.getElementById('autoScan'),
  };
  const status = document.getElementById('status');

  function setStatus(message, kind) {
    status.textContent = message;
    status.className = 'status' + (kind ? ' ' + kind : '');
  }

  /**
   * Chrome derives this from the extension ID, so it differs per install (and changes
   * between an unpacked load and a published one). Showing the live value beats
   * documenting a placeholder that would be wrong for everybody.
   */
  function showRedirectUri() {
    const node = document.getElementById('redirectUri');
    try {
      node.textContent = chrome.identity.getRedirectURL();
    } catch (error) {
      node.textContent = 'Unavailable - the identity permission is missing.';
    }
  }

  /**
   * A custom server address needs its own host permission, and Chrome only grants one
   * from a user gesture -- which is why this runs on Save rather than on input. The
   * manifest already covers the localhost default, so the common case asks for nothing.
   */
  async function ensureHostPermission(apiBase) {
    if (!apiBase) {
      return true;
    }
    let origin;
    try {
      origin = new URL(apiBase).origin + '/*';
    } catch (error) {
      throw new Error('That does not look like a valid URL.');
    }
    if (await chrome.permissions.contains({ origins: [origin] })) {
      return true;
    }
    const granted = await chrome.permissions.request({ origins: [origin] });
    if (!granted) {
      throw new Error('Permission for ' + origin + ' was declined, so scans there would fail.');
    }
    return true;
  }

  async function restore() {
    const settings = await ns.settings.load();
    fields.apiBase.value = settings.apiBase;
    fields.authToken.value = settings.authToken;
    fields.googleClientId.value = settings.googleClientId;
    fields.autoScan.checked = Boolean(settings.autoScan);
    showRedirectUri();
  }

  async function save() {
    const apiBase = ns.settings.normalizeApiBase(fields.apiBase.value);
    try {
      await ensureHostPermission(apiBase);
    } catch (error) {
      setStatus(error.message, 'error');
      return;
    }
    await ns.settings.save({
      apiBase,
      authToken: fields.authToken.value.trim(),
      googleClientId: fields.googleClientId.value.trim(),
      autoScan: fields.autoScan.checked,
    });
    fields.apiBase.value = apiBase;
    setStatus('Saved.', 'ok');
  }

  document.getElementById('save').addEventListener('click', save);
  restore();
})(globalThis.MailSentinel);
