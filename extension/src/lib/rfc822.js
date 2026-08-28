// Rebuild an RFC 5322 message from the parts a webmail page actually exposes.
//
// This is the DOM path's half of the bargain. The server's EmailParserService wants a
// real MIME message: it reads the From header through Jakarta Mail's address parser
// (so a display name is available to the impersonation check), and it wants the body
// as a text/html part (so LinkAnalysisService can compare anchor text against href).
// Handing it a flat string of visible text would silently disable both.
//
// What this cannot invent is the trust-bearing headers -- Authentication-Results,
// Received, DKIM-Signature. Those exist only in the raw message, which is why the
// Gmail OAuth path is worth having and why the panel says plainly when a scan ran
// without them rather than presenting a partial result as a complete one.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  const CRLF = '\r\n';

  function isAscii(value) {
    return /^[\x20-\x7E]*$/.test(value);
  }

  function base64Utf8(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = '';
    for (const byte of bytes) {
      binary += String.fromCharCode(byte);
    }
    return btoa(binary);
  }

  /**
   * Headers are ASCII by definition, so anything else becomes an RFC 2047 encoded-word.
   * Jakarta Mail decodes these back, which keeps a non-Latin display name intact for
   * the impersonation check instead of arriving as mojibake that matches no brand.
   */
  function encodeHeaderValue(value) {
    const text = String(value == null ? '' : value);
    if (isAscii(text)) {
      return text;
    }
    return '=?UTF-8?B?' + base64Utf8(text) + '?=';
  }

  /**
   * RFC 5322 quoted-string for the display name. The escaping matters more than it
   * looks: a name containing a quote is exactly how you would try to smuggle a second
   * address into the header and shadow the real one.
   */
  function formatAddress(name, email) {
    const address = String(email || '').trim();
    const display = String(name || '').trim();
    if (!address) {
      return '';
    }
    if (!display || display === address) {
      return address;
    }
    const encoded = encodeHeaderValue(display);
    const quoted = '"' + encoded.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
    return quoted + ' <' + address + '>';
  }

  /**
   * Fold a long header onto continuation lines at 78 columns. Unfolded headers are
   * legal-ish and usually tolerated, but a multi-kilobyte single line is the kind of
   * thing a strict parser rejects outright.
   */
  function foldHeader(name, value) {
    const full = name + ': ' + value;
    if (full.length <= 78 || /[\r\n]/.test(value)) {
      return full.replace(/[\r\n]+/g, ' ');
    }
    const words = full.split(' ');
    const lines = [];
    let current = '';
    for (const word of words) {
      if (current && (current + ' ' + word).length > 78) {
        lines.push(current);
        current = '\t' + word;
      } else {
        current = current ? current + ' ' + word : word;
      }
    }
    if (current) {
      lines.push(current);
    }
    return lines.join(CRLF);
  }

  /**
   * @param {object} parts extracted by a client adapter
   * @returns {string} a MIME message the server can parse
   */
  function build(parts) {
    const headers = [];
    const from = formatAddress(parts.fromName, parts.fromEmail);
    if (from) {
      headers.push(foldHeader('From', from));
    }
    const to = formatAddress(parts.toName, parts.toEmail);
    if (to) {
      headers.push(foldHeader('To', to));
    }
    if (parts.subject) {
      headers.push(foldHeader('Subject', encodeHeaderValue(parts.subject)));
    }
    if (parts.date) {
      headers.push(foldHeader('Date', parts.date));
    }
    // Present only when the page exposed it, which today means never on the DOM path.
    // Kept here so the shape is identical whichever path produced the message.
    if (parts.authenticationResults) {
      headers.push(foldHeader('Authentication-Results', parts.authenticationResults));
    }
    headers.push('MIME-Version: 1.0');

    const html = parts.html || '';
    const text = parts.text || '';

    if (html && text) {
      const boundary = 'ms-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
      headers.push('Content-Type: multipart/alternative; boundary="' + boundary + '"');
      const body = [
        '--' + boundary,
        'Content-Type: text/plain; charset="UTF-8"',
        '',
        text,
        '--' + boundary,
        'Content-Type: text/html; charset="UTF-8"',
        '',
        html,
        '--' + boundary + '--',
        '',
      ].join(CRLF);
      return headers.join(CRLF) + CRLF + CRLF + body;
    }

    if (html) {
      headers.push('Content-Type: text/html; charset="UTF-8"');
      return headers.join(CRLF) + CRLF + CRLF + html + CRLF;
    }

    headers.push('Content-Type: text/plain; charset="UTF-8"');
    return headers.join(CRLF) + CRLF + CRLF + text + CRLF;
  }

  ns.rfc822 = { build, formatAddress, encodeHeaderValue, foldHeader };
})(globalThis.MailSentinel);
