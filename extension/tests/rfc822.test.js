const test = require('node:test');
const assert = require('node:assert');
const { loadScripts } = require('./helpers');

const ns = loadScripts(['src/lib/rfc822.js']);
const { build, formatAddress, encodeHeaderValue, foldHeader } = ns.rfc822;

test('a display name is quoted and the address bracketed', () => {
  assert.strictEqual(
    formatAddress('Acme Support', 'support@acme.com'),
    '"Acme Support" <support@acme.com>'
  );
});

test('a bare address is emitted unquoted', () => {
  assert.strictEqual(formatAddress('', 'support@acme.com'), 'support@acme.com');
  assert.strictEqual(formatAddress('support@acme.com', 'support@acme.com'), 'support@acme.com');
});

test('quotes inside a display name are escaped, not left to break the header', () => {
  // This is the shape of the attack the escaping exists for: an unescaped quote would
  // close the display name early and let the rest be read as another address.
  const formatted = formatAddress('Security" <real@paypal.com> "', 'phisher@evil.ru');
  assert.ok(formatted.endsWith('<phisher@evil.ru>'), 'real address must remain the address');
  assert.ok(formatted.startsWith('"'), 'display name must stay quoted');
  const inner = formatted.slice(1, formatted.lastIndexOf('"'));
  assert.ok(!/(^|[^\\])"/.test(inner), 'no unescaped quote may survive inside the name: ' + formatted);
});

test('non-ASCII header values become RFC 2047 encoded words', () => {
  const encoded = encodeHeaderValue('Paypäl Sicherheit');
  assert.match(encoded, /^=\?UTF-8\?B\?[A-Za-z0-9+/=]+\?=$/);
  const base64 = encoded.slice('=?UTF-8?B?'.length, -'?='.length);
  assert.strictEqual(Buffer.from(base64, 'base64').toString('utf8'), 'Paypäl Sicherheit');
});

test('ASCII header values are left alone', () => {
  assert.strictEqual(encodeHeaderValue('Plain Subject'), 'Plain Subject');
});

test('long headers fold onto continuation lines under 78 columns', () => {
  const value = 'word '.repeat(40).trim();
  const folded = foldHeader('Subject', value);
  const lines = folded.split('\r\n');
  assert.ok(lines.length > 1, 'expected folding');
  for (const line of lines) {
    assert.ok(line.length <= 78, 'line too long: ' + line.length);
  }
  for (const line of lines.slice(1)) {
    assert.ok(line.startsWith('\t'), 'continuation lines must be indented');
  }
});

test('embedded newlines are flattened so a value cannot inject a header', () => {
  const folded = foldHeader('Subject', 'hello\r\nBcc: attacker@evil.ru');
  assert.ok(!folded.includes('\r\n'), 'must not emit a bare CRLF from the value');
  assert.ok(folded.includes('Bcc: attacker@evil.ru'), 'content is kept, just not as a new header');
});

test('html-only message is built as a text/html part', () => {
  const message = build({
    fromName: 'Acme',
    fromEmail: 'no-reply@acme.com',
    subject: 'Invoice',
    html: '<p>Hello <a href="http://evil.ru">acme.com</a></p>',
  });
  assert.match(message, /^From: "Acme" <no-reply@acme\.com>\r\n/);
  assert.match(message, /Content-Type: text\/html; charset="UTF-8"/);
  // The server splits headers from body on the first blank line; anchors must land after it.
  const [headers, body] = message.split('\r\n\r\n');
  assert.ok(!headers.includes('<a href'), 'body must not leak into the header block');
  assert.ok(body.includes('<a href="http://evil.ru">'), 'anchor must survive for link analysis');
});

test('having both bodies produces a multipart/alternative with a matching boundary', () => {
  const message = build({
    fromEmail: 'a@b.com',
    html: '<p>rich</p>',
    text: 'plain',
  });
  const boundary = /boundary="([^"]+)"/.exec(message)[1];
  assert.ok(message.includes('--' + boundary + '\r\n'), 'parts must open with the boundary');
  assert.ok(message.includes('--' + boundary + '--'), 'must be terminated by the closing boundary');
  assert.ok(message.includes('Content-Type: text/plain'), 'plain part present');
  assert.ok(message.includes('Content-Type: text/html'), 'html part present');
});

test('an absent field simply omits its header rather than emitting an empty one', () => {
  const message = build({ fromEmail: 'a@b.com', text: 'body' });
  assert.ok(!message.includes('Subject:'), 'no subject header when there is no subject');
  assert.ok(!message.includes('To:'), 'no to header when there is no recipient');
  assert.ok(!/Authentication-Results:/.test(message), 'DOM path must not invent auth headers');
});

test('authentication results are included when a raw source supplied them', () => {
  const message = build({
    fromEmail: 'a@b.com',
    text: 'body',
    authenticationResults: 'mx.example.com; spf=fail; dkim=none',
  });
  assert.match(message, /Authentication-Results: mx\.example\.com; spf=fail; dkim=none/);
});
