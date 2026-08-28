// Gmail DOM adapter.
//
// Gmail ships obfuscated, unstable class names, so every selector here is a guess that
// Google can invalidate without notice. Two things keep that from becoming silent
// breakage: each field is looked up through an ordered list of candidates rather than a
// single selector, and a failed extraction returns null so the panel can say "couldn't
// read this message" instead of scanning an empty string and reporting a confident 0.
//
// The classes that have proven most durable (a3s for the body, gD for the sender chip,
// hP for the subject) are listed first, with structural/attribute-based fallbacks after
// them -- an attribute like [email] survives a restyle that a class name does not.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  const MESSAGE_CONTAINERS = ['div.adn.ads', 'div.adn', 'div[data-legacy-message-id]'];
  const SENDER_CHIPS = ['span.gD[email]', 'span[email][name]', 'span[email]', 'a[href^="mailto:"]'];
  const SUBJECT_NODES = ['h2.hP', 'h2[data-legacy-thread-id]', 'div.ha h2', 'h2'];
  const BODY_NODES = ['div.a3s.aiL', 'div.a3s', 'div.ii.gt div[dir]', 'div.ii.gt'];
  const DATE_NODES = ['span.g3[title]', 'span[data-tooltip][role="gridcell"]', 'span.g3'];

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

  /**
   * Gmail keeps every message of a thread in the DOM and collapses the ones you are not
   * reading, so "the open message" is the last expanded container rather than the first
   * one found. Scanning a collapsed quote instead of the message on screen would be a
   * quietly wrong answer, not an obvious failure.
   */
  function findOpenMessage() {
    for (const selector of MESSAGE_CONTAINERS) {
      const all = Array.from(document.querySelectorAll(selector));
      const visible = all.filter((el) => {
        if (el.offsetParent === null) {
          return false;
        }
        const rect = el.getBoundingClientRect();
        return rect.height > 40 && rect.width > 40;
      });
      if (visible.length) {
        return visible[visible.length - 1];
      }
    }
    return null;
  }

  function extractSender(container) {
    const chip = firstMatch(container, SENDER_CHIPS);
    if (!chip) {
      return { fromEmail: '', fromName: '' };
    }
    let email = chip.getAttribute('email') || '';
    if (!email) {
      const href = chip.getAttribute('href') || '';
      if (href.startsWith('mailto:')) {
        email = decodeURIComponent(href.slice('mailto:'.length)).split('?')[0];
      }
    }
    // The `name` attribute is Gmail's own copy of the display name; textContent is the
    // rendered chip, which may be an abbreviation or the bare address.
    const name = chip.getAttribute('name') || textOf(chip);
    return {
      fromEmail: email.trim(),
      fromName: name === email ? '' : name,
    };
  }

  /**
   * The subject lives in the thread header, outside the per-message container, so this
   * one deliberately searches the document rather than the container.
   */
  function extractSubject() {
    const node = firstMatch(document, SUBJECT_NODES);
    return textOf(node);
  }

  function extractDate(container) {
    const node = firstMatch(container, DATE_NODES);
    if (!node) {
      return '';
    }
    return node.getAttribute('title') || node.getAttribute('data-tooltip') || textOf(node);
  }

  /**
   * Gmail's message id, when present, is what lets the OAuth path fetch the true raw
   * source for this exact message. Absent, the scan silently stays on the DOM path.
   */
  function extractMessageId(container) {
    const holder = container.closest('[data-legacy-message-id]')
      || container.querySelector('[data-legacy-message-id]');
    return holder ? holder.getAttribute('data-legacy-message-id') : '';
  }

  function extract(container) {
    const body = firstMatch(container, BODY_NODES);
    const sender = extractSender(container);
    const html = body ? body.innerHTML : '';
    const text = body ? textOf(body) : '';

    // Nothing worth sending: no sender to attribute it to and no body to analyse.
    if (!sender.fromEmail && !html && !text) {
      return null;
    }

    return {
      client: 'gmail',
      fromEmail: sender.fromEmail,
      fromName: sender.fromName,
      subject: extractSubject(),
      date: extractDate(container),
      html,
      text,
      providerMessageId: extractMessageId(container),
    };
  }

  ns.adapters = ns.adapters || {};
  ns.adapters.gmail = {
    id: 'gmail',
    label: 'Gmail',
    supportsRawFetch: true,
    matches() {
      return location.hostname === 'mail.google.com';
    },
    findOpenMessage,
    extract,
  };
})(globalThis.MailSentinel);
