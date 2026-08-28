// Outlook on the web adapter (outlook.office.com, outlook.office365.com, outlook.live.com).
//
// Outlook's markup is harder to pin down than Gmail's: the class names are hashed and
// change between releases, so this leans almost entirely on ARIA and data-* attributes,
// which Microsoft has to keep stable for screen readers even when the styling churns.
//
// There is no raw-source path here. Reading the true MIME source of an Outlook message
// means Microsoft Graph and a separate app registration, so this adapter is DOM-only and
// says so via supportsRawFetch -- the panel then reports the scan as header-limited
// rather than implying auth checks ran.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  const READING_PANES = [
    'div[aria-label="Reading Pane"]',
    'div[data-app-section="ConversationContainer"]',
    'div[data-app-section="ReadingPaneContainer"]',
    'div[role="main"] div[role="document"]',
    'div[role="main"]',
  ];
  const SENDER_NODES = [
    'span[data-lpc-hover-target-id]',
    'span[title*="@"]',
    'span[aria-label*="@"]',
    'a[href^="mailto:"]',
  ];
  const SUBJECT_NODES = [
    'div[role="heading"][aria-level="1"]',
    'div[role="heading"][aria-level="2"]',
    'span[data-testid="message-subject"]',
    'h1',
  ];
  const BODY_NODES = [
    'div[aria-label="Message body"]',
    'div[data-app-section="MessageBody"]',
    'div[id^="UniqueMessageBody"]',
    'div[role="document"]',
  ];

  const EMAIL_PATTERN = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;

  function firstMatch(root, selectors) {
    for (const selector of selectors) {
      const found = root.querySelector(selector);
      if (found) {
        return found;
      }
    }
    return null;
  }

  function textOf(node) {
    return node ? (node.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }

  function findOpenMessage() {
    const pane = firstMatch(document, READING_PANES);
    if (!pane) {
      return null;
    }
    // A reading pane exists even with nothing selected; require an actual body before
    // claiming there is a message to scan.
    return firstMatch(pane, BODY_NODES) ? pane : null;
  }

  /**
   * Outlook exposes the sender as "Display Name <addr@host>" in a title or aria-label
   * more reliably than it exposes the address as its own element, so the address is
   * pulled out by pattern and whatever remains is treated as the display name.
   */
  function extractSender(pane) {
    const node = firstMatch(pane, SENDER_NODES);
    if (!node) {
      return { fromEmail: '', fromName: '' };
    }

    const href = node.getAttribute('href') || '';
    if (href.startsWith('mailto:')) {
      const address = decodeURIComponent(href.slice('mailto:'.length)).split('?')[0];
      return { fromEmail: address.trim(), fromName: textOf(node) === address ? '' : textOf(node) };
    }

    const haystack = node.getAttribute('title')
      || node.getAttribute('aria-label')
      || textOf(node);
    const match = EMAIL_PATTERN.exec(haystack || '');
    const email = match ? match[0] : '';
    const name = (haystack || '')
      .replace(email, '')
      .replace(/[<>()]/g, '')
      .replace(/\s+/g, ' ')
      .trim();

    return { fromEmail: email, fromName: name === email ? '' : name };
  }

  function extract(pane) {
    const body = firstMatch(pane, BODY_NODES);
    const sender = extractSender(pane);
    const html = body ? body.innerHTML : '';
    const text = body ? textOf(body) : '';

    if (!sender.fromEmail && !html && !text) {
      return null;
    }

    return {
      client: 'outlook',
      fromEmail: sender.fromEmail,
      fromName: sender.fromName,
      subject: textOf(firstMatch(document, SUBJECT_NODES)),
      date: '',
      html,
      text,
      providerMessageId: '',
    };
  }

  ns.adapters = ns.adapters || {};
  ns.adapters.outlook = {
    id: 'outlook',
    label: 'Outlook',
    supportsRawFetch: false,
    matches() {
      return /^outlook\.(office|office365|live)\.com$/.test(location.hostname);
    },
    findOpenMessage,
    extract,
  };
})(globalThis.MailSentinel);
