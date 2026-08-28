const test = require('node:test');
const assert = require('node:assert');
const { JSDOM } = require('jsdom');
const { loadScripts } = require('./helpers');

// A real ScanResponse, captured from the running server by scanning a message this
// extension synthesized. Using a recorded response rather than a hand-written one keeps
// the panel honest about the shape it actually receives -- including the detail that the
// three "(claimed)" checks pass with "no header to evaluate" on the DOM path, which is
// exactly what the warning banner exists to explain.
const SCAN_RESULT = {
  score: 100,
  checks: [
    { name: 'SPF authentication (claimed)', passed: true, weight: 22, detail: 'No Authentication-Results header present to evaluate SPF' },
    { name: 'Sender domain edit-distance', passed: false, weight: 65, detail: 'Domain paypa1.com is 1 character from paypal.com' },
    { name: 'Sender display name impersonation', passed: false, weight: 45, detail: 'From display name "PayPal Service" names paypal.com, but the message was actually sent from paypa1.com' },
    { name: 'Raw IP address as link host', passed: false, weight: 65, detail: 'Link(s) use a raw IP address instead of a domain: http://192.168.44.9/verify' },
    { name: 'Sender domain TLD swap', passed: true, weight: 45, detail: 'No TLD swap detected' },
  ],
  aiAnalysis: null,
};

function setupDom() {
  const dom = new JSDOM('<!doctype html><body><div id="page">webmail</div></body>', {
    url: 'https://mail.google.com/mail/u/0/',
  });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.CSSStyleSheet = dom.window.CSSStyleSheet;
  globalThis.chrome = { runtime: { getURL: (p) => 'chrome-extension://test/' + p } };
  // panel.js fetches its stylesheet; jsdom has no extension origin, so let it fail and
  // exercise the documented <link> fallback path.
  globalThis.fetch = () => Promise.reject(new Error('no extension origin under test'));
  return dom;
}

function panelRoot(dom) {
  return dom.window.document.getElementById('mailsentinel-root').shadowRoot;
}

const ns = loadScripts(['src/lib/score.js', 'src/content/panel.js']);

test('mount injects a launcher and starts collapsed', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});

  const root = panelRoot(dom);
  assert.ok(root.querySelector('.launcher'), 'launcher must exist');
  assert.strictEqual(root.querySelector('.launcher').hidden, false);
  assert.strictEqual(root.querySelector('.panel').hidden, true, 'panel starts closed');
});

test('the host is attached outside the page body so client re-renders cannot destroy it', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  const host = dom.window.document.getElementById('mailsentinel-root');
  assert.strictEqual(host.parentNode, dom.window.document.documentElement);
  assert.ok(host.shadowRoot, 'styles must be isolated in a shadow root');
});

test('a result renders the score, band and every check', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  ns.panel.showResult(SCAN_RESULT, { headersAvailable: false });

  const root = panelRoot(dom);
  assert.strictEqual(root.querySelector('.panel').getAttribute('data-band'), 'high');
  assert.match(root.querySelector('.verdict-score').textContent, /^100/);
  assert.strictEqual(root.querySelector('.verdict-band').textContent, 'High risk');
  assert.strictEqual(root.querySelectorAll('.check').length, SCAN_RESULT.checks.length);
});

test('failed checks are listed before passed ones', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  ns.panel.showResult(SCAN_RESULT, { headersAvailable: false });

  const classes = [...panelRoot(dom).querySelectorAll('.check')]
    .map((node) => (node.classList.contains('failed') ? 'failed' : 'passed'));
  const firstPassed = classes.indexOf('passed');
  assert.ok(firstPassed > 0, 'expected failures first');
  assert.ok(!classes.slice(firstPassed).includes('failed'), 'no failure may appear after a pass');
});

test('the header warning appears only when the scan had no raw source', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});

  ns.panel.showResult(SCAN_RESULT, { headersAvailable: false });
  const warning = panelRoot(dom).querySelector('.banner.warn');
  assert.ok(warning, 'DOM-path scans must be labelled');
  assert.match(warning.textContent, /claimed/, 'must name which checks were affected');
  assert.match(warning.textContent, /Live DNS checks ran normally/, 'must not overstate the limitation');

  ns.panel.showResult(SCAN_RESULT, { headersAvailable: true, sourceLabel: 'Scanned the raw message source.' });
  assert.strictEqual(panelRoot(dom).querySelector('.banner.warn'), null, 'raw scans carry no warning');
});

test('attacker-controlled text is set as text, never parsed as markup', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  const hostile = {
    score: 70,
    checks: [{
      name: '<img src=x onerror="globalThis.PWNED=1">',
      passed: false,
      weight: 65,
      detail: '<script>globalThis.PWNED=1</script>',
    }],
  };
  ns.panel.showResult(hostile, { headersAvailable: true });

  const root = panelRoot(dom);
  assert.strictEqual(root.querySelector('img'), null, 'no element may be created from check text');
  assert.strictEqual(root.querySelector('script'), null);
  assert.strictEqual(dom.window.PWNED, undefined);
  assert.ok(root.textContent.includes('<img src=x'), 'the text itself is still shown, escaped');
});

test('an error replaces any previous verdict rather than layering under it', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  ns.panel.showResult(SCAN_RESULT, { headersAvailable: true });
  ns.panel.showError('Could not reach MailSentinel.');

  const root = panelRoot(dom);
  assert.strictEqual(root.querySelector('.verdict'), null, 'stale score must be cleared');
  assert.strictEqual(root.querySelector('.banner.error').textContent, 'Could not reach MailSentinel.');
  assert.strictEqual(root.querySelector('.panel').hasAttribute('data-band'), false);
});

test('loading state disables re-scan and clears the previous result', () => {
  const dom = setupDom();
  ns.panel.destroy();
  ns.panel.mount({});
  ns.panel.showResult(SCAN_RESULT, { headersAvailable: true });
  ns.panel.showLoading('Scanning...');

  const root = panelRoot(dom);
  assert.ok(root.querySelector('.spinner'));
  assert.strictEqual(root.querySelector('.verdict'), null);
  assert.strictEqual(root.querySelector('.foot .button').disabled, true);
});
