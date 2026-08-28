const test = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const { loadScripts, makeVisible } = require('./helpers');

// The fixtures reproduce the structure and attributes these adapters key off, not a
// byte-for-byte capture of either product's markup. That is the honest limit of this
// file: it verifies the traversal, the fallback ordering and the failure behaviour, and
// it cannot tell you that Gmail still uses `div.a3s` today. Only opening a real inbox
// can. What it does buy is that a refactor of the adapters has to keep working.
function withDom(html, hostname, run) {
  const dom = new JSDOM(html, { url: 'https://' + hostname + '/mail/u/0/' });
  const previous = {
    document: globalThis.document,
    location: globalThis.location,
    window: globalThis.window,
  };
  globalThis.document = dom.window.document;
  globalThis.window = dom.window;
  Object.defineProperty(globalThis, 'location', {
    value: dom.window.location,
    configurable: true,
    writable: true,
  });
  try {
    return run(dom);
  } finally {
    globalThis.document = previous.document;
    globalThis.window = previous.window;
    Object.defineProperty(globalThis, 'location', {
      value: previous.location,
      configurable: true,
      writable: true,
    });
  }
}

const ns = loadScripts([
  'src/content/adapters/gmail.js',
  'src/content/adapters/outlook.js',
  'src/content/adapters/registry.js',
]);

const GMAIL_FIXTURE = `
  <div role="main">
    <h2 class="hP">Your invoice is overdue</h2>
    <div class="adn ads" data-legacy-message-id="18f2c9a0b1">
      <span class="gD" email="billing@acme-invoices.ru" name="Acme Billing">Acme Billing</span>
      <span class="g3" title="Mon, 12 Aug 2024 09:14:00 +0000">Aug 12</span>
      <div class="ii gt">
        <div class="a3s aiL">
          <p>Pay now: <a href="http://acme-invoices.ru/pay">acme.com/billing</a></p>
        </div>
      </div>
    </div>
  </div>`;

const OUTLOOK_FIXTURE = `
  <div role="main">
    <div role="heading" aria-level="1">Security alert</div>
    <div aria-label="Reading Pane">
      <span title="Microsoft Account Team &lt;alerts@m365-security.top&gt;">Microsoft Account Team</span>
      <div aria-label="Message body">
        <p>Verify at <a href="http://m365-security.top/verify">microsoft.com</a></p>
      </div>
    </div>
  </div>`;

test('the registry picks the adapter matching the current host', () => {
  withDom('<p></p>', 'mail.google.com', () => {
    assert.strictEqual(ns.registry.active().id, 'gmail');
  });
  withDom('<p></p>', 'outlook.office.com', () => {
    assert.strictEqual(ns.registry.active().id, 'outlook');
  });
  withDom('<p></p>', 'outlook.live.com', () => {
    assert.strictEqual(ns.registry.active().id, 'outlook');
  });
  withDom('<p></p>', 'example.com', () => {
    assert.strictEqual(ns.registry.active(), null, 'no adapter should claim an unrelated site');
  });
});

test('gmail: sender, subject, date, body and message id are extracted', () => {
  withDom(GMAIL_FIXTURE, 'mail.google.com', (dom) => {
    const container = dom.window.document.querySelector('div.adn.ads');
    const parts = ns.adapters.gmail.extract(container);

    assert.strictEqual(parts.fromEmail, 'billing@acme-invoices.ru');
    assert.strictEqual(parts.fromName, 'Acme Billing');
    assert.strictEqual(parts.subject, 'Your invoice is overdue');
    assert.strictEqual(parts.date, 'Mon, 12 Aug 2024 09:14:00 +0000');
    assert.strictEqual(parts.providerMessageId, '18f2c9a0b1');
    assert.strictEqual(parts.client, 'gmail');
    // The href must survive as HTML, since anchor-vs-text mismatch is the whole point.
    assert.match(parts.html, /href="http:\/\/acme-invoices\.ru\/pay"/);
    assert.match(parts.html, />acme\.com\/billing</);
  });
});

test('gmail: the last expanded message wins, not the first collapsed one', () => {
  // Gmail keeps the whole thread in the DOM. Scanning an older collapsed message while
  // the user reads a newer one would be a quietly wrong answer.
  const thread = `
    <div role="main">
      <h2 class="hP">Thread</h2>
      <div class="adn ads" id="old">
        <span class="gD" email="old@example.com" name="Old">Old</span>
        <div class="a3s aiL">old body</div>
      </div>
      <div class="adn ads" id="new">
        <span class="gD" email="new@example.com" name="New">New</span>
        <div class="a3s aiL">new body</div>
      </div>
    </div>`;
  withDom(thread, 'mail.google.com', (dom) => {
    const containers = dom.window.document.querySelectorAll('div.adn.ads');
    containers.forEach((element) => makeVisible(element));
    const open = ns.adapters.gmail.findOpenMessage();
    assert.strictEqual(open.id, 'new');
  });
});

test('gmail: a collapsed or zero-height message is not treated as open', () => {
  withDom(GMAIL_FIXTURE, 'mail.google.com', (dom) => {
    const container = dom.window.document.querySelector('div.adn.ads');
    makeVisible(container, { width: 0, height: 0 });
    assert.strictEqual(ns.adapters.gmail.findOpenMessage(), null);
  });
});

test('gmail: falls back to a mailto link when the sender chip is missing', () => {
  const noChip = `
    <div role="main">
      <h2 class="hP">Subject</h2>
      <div class="adn ads">
        <a href="mailto:fallback@example.com">fallback@example.com</a>
        <div class="a3s aiL">body</div>
      </div>
    </div>`;
  withDom(noChip, 'mail.google.com', (dom) => {
    const parts = ns.adapters.gmail.extract(dom.window.document.querySelector('div.adn.ads'));
    assert.strictEqual(parts.fromEmail, 'fallback@example.com');
    assert.strictEqual(parts.fromName, '', 'a name identical to the address is not a display name');
  });
});

test('gmail: an unreadable container returns null instead of an empty scan', () => {
  // The failure that matters: scanning "" would come back a confident, meaningless 0.
  withDom('<div role="main"><div class="adn ads"></div></div>', 'mail.google.com', (dom) => {
    assert.strictEqual(ns.adapters.gmail.extract(dom.window.document.querySelector('div.adn.ads')), null);
  });
});

test('outlook: sender is split out of the combined title attribute', () => {
  withDom(OUTLOOK_FIXTURE, 'outlook.office.com', (dom) => {
    const pane = dom.window.document.querySelector('div[aria-label="Reading Pane"]');
    const parts = ns.adapters.outlook.extract(pane);

    assert.strictEqual(parts.fromEmail, 'alerts@m365-security.top');
    assert.strictEqual(parts.fromName, 'Microsoft Account Team');
    assert.strictEqual(parts.subject, 'Security alert');
    assert.strictEqual(parts.client, 'outlook');
    assert.match(parts.html, /href="http:\/\/m365-security\.top\/verify"/);
  });
});

test('outlook: a reading pane with no message body is not an open message', () => {
  const empty = '<div role="main"><div aria-label="Reading Pane"></div></div>';
  withDom(empty, 'outlook.office.com', () => {
    assert.strictEqual(ns.adapters.outlook.findOpenMessage(), null);
  });
});

test('outlook declares no raw-source support, gmail declares it', () => {
  // The panel keys its "headers were not available" warning off this flag.
  assert.strictEqual(ns.adapters.outlook.supportsRawFetch, false);
  assert.strictEqual(ns.adapters.gmail.supportsRawFetch, true);
});
