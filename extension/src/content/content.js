// Content script entry point: pick an adapter, watch for the open message, scan on request.
(function (ns) {
  'use strict';

  const adapter = ns.registry.active();
  if (!adapter) {
    return;
  }

  let currentKey = null;
  let scanning = false;

  function send(message) {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage(message, (response) => {
        if (chrome.runtime.lastError) {
          resolve({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        resolve(response || { ok: false, error: 'No response from the extension background worker.' });
      });
    });
  }

  /**
   * Cheap identity for "the message currently on screen". Used only to notice that the
   * user navigated to a different message so a stale verdict can be cleared -- a wrong
   * score attached to the wrong email is the worst thing this extension could do.
   */
  function messageKey(parts) {
    if (!parts) {
      return null;
    }
    return [parts.providerMessageId, parts.fromEmail, parts.subject, (parts.text || '').slice(0, 120)].join('|');
  }

  /**
   * Try the raw source first when the client and configuration both allow it, and fall
   * back to the reconstructed message otherwise. The fallback is deliberate rather than
   * fatal: a user without a Google client ID still gets lookalike, link and display-name
   * analysis, just not the header-based checks.
   */
  async function buildScanContent(parts) {
    if (adapter.supportsRawFetch && parts.providerMessageId) {
      const settings = await send({ type: 'GET_SETTINGS' });
      if (settings.ok && settings.data.googleClientId) {
        const raw = await send({
          type: 'GMAIL_RAW',
          messageId: parts.providerMessageId,
          interactive: true,
        });
        if (raw.ok && raw.data.raw) {
          return { content: raw.data.raw, headersAvailable: true, sourceLabel: 'Scanned the raw message source, including authentication headers.' };
        }
        // Falling through rather than surfacing this as a failure: a declined consent
        // screen or an expired grant should degrade the scan, not block it.
      }
    }
    return {
      content: ns.rfc822.build(parts),
      headersAvailable: false,
      sourceLabel: '',
    };
  }

  async function runScan() {
    if (scanning) {
      return;
    }
    const container = adapter.findOpenMessage();
    if (!container) {
      ns.panel.showError('No open message found. Open an email first, then scan.');
      return;
    }
    const parts = adapter.extract(container);
    if (!parts) {
      ns.panel.showError(
        'Could not read this message from the page. ' + adapter.label
        + ' may have changed its layout -- please report this.'
      );
      return;
    }

    scanning = true;
    ns.panel.showLoading('Scanning...');
    try {
      const source = await buildScanContent(parts);
      const response = await send({
        type: 'SCAN',
        scanType: 'email',
        content: source.content,
        idempotencyKey: crypto.randomUUID(),
      });
      if (!response.ok) {
        ns.panel.showError(response.error);
        return;
      }
      ns.panel.showResult(response.data, {
        headersAvailable: source.headersAvailable,
        sourceLabel: source.sourceLabel,
      });
    } catch (error) {
      ns.panel.showError(error.message || String(error));
    } finally {
      scanning = false;
    }
  }

  ns.panel.mount({
    onScan: runScan,
    onOptions: () => send({ type: 'OPEN_OPTIONS' }),
  });

  /**
   * Both clients are single-page apps: opening another email swaps the DOM without a
   * navigation, so there is no load event to hook. Polling the extracted identity on a
   * debounced MutationObserver is cruder than listening for a real signal, but neither
   * client exposes one, and the cost of missing the change is a stale verdict.
   */
  let debounce = null;
  const observer = new MutationObserver(() => {
    clearTimeout(debounce);
    debounce = setTimeout(async () => {
      const container = adapter.findOpenMessage();
      const key = container ? messageKey(adapter.extract(container)) : null;
      if (key === currentKey) {
        return;
      }
      currentKey = key;
      ns.panel.collapse();

      // Auto-scan is opt-in and stays that way: it sends the content of every message
      // the user opens to the configured server, which is a materially different privacy
      // posture from scanning on request.
      if (!key) {
        return;
      }
      const settings = await send({ type: 'GET_SETTINGS' });
      if (settings.ok && settings.data.autoScan) {
        runScan();
      }
    }, 350);
  });

  observer.observe(document.body, { childList: true, subtree: true });
})(globalThis.MailSentinel);
