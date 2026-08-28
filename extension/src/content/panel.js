// The in-page panel.
//
// Two decisions shape this file.
//
// It renders into a shadow root, so Gmail's and Outlook's stylesheets cannot reach in
// and our CSS cannot leak out and restyle their application.
//
// And it floats, anchored to the viewport, rather than being injected into the client's
// own message toolbar. Both clients re-render their chrome constantly -- switching
// messages, arriving mail, pane resizes -- and a node grafted into that subtree gets
// destroyed without warning. A fixed host outside their tree survives all of it, at the
// cost of not looking quite native.
//
// Everything is built with createElement/textContent rather than innerHTML: the content
// here includes attacker-controlled strings (sender names, check details echoing a
// hostile domain), and this panel is not the place to introduce an injection sink.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  let host = null;
  let root = null;
  let refs = {};
  let handlers = {};

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text != null) {
      node.textContent = text;
    }
    return node;
  }

  async function loadStyles(shadow) {
    const url = chrome.runtime.getURL('src/content/panel.css');
    try {
      const response = await fetch(url);
      const css = await response.text();
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      shadow.adoptedStyleSheets = [sheet];
    } catch (ignored) {
      // Constructable stylesheets are the fast path; a <link> still renders correctly
      // if this browser or a policy blocks the fetch, just with a brief unstyled frame.
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = url;
      shadow.appendChild(link);
    }
  }

  function build() {
    host = document.createElement('div');
    host.id = 'mailsentinel-root';
    root = host.attachShadow({ mode: 'open' });
    loadStyles(root);

    const launcher = el('button', 'launcher');
    launcher.appendChild(el('span', 'dot'));
    launcher.appendChild(el('span', null, 'Scan this email'));
    launcher.addEventListener('click', () => handlers.onScan && handlers.onScan());

    const panel = el('div', 'panel');
    panel.hidden = true;

    const head = el('div', 'head');
    head.appendChild(el('span', 'title', 'MailSentinel'));
    const headActions = el('div', 'head-actions');
    const optionsButton = el('button', 'icon-button', '⚙');
    optionsButton.title = 'Options';
    optionsButton.addEventListener('click', () => handlers.onOptions && handlers.onOptions());
    const closeButton = el('button', 'icon-button', '✕');
    closeButton.title = 'Close';
    closeButton.addEventListener('click', () => collapse());
    headActions.appendChild(optionsButton);
    headActions.appendChild(closeButton);
    head.appendChild(headActions);

    const body = el('div', 'body');

    const foot = el('div', 'foot');
    const rescan = el('button', 'button', 'Scan again');
    rescan.addEventListener('click', () => handlers.onScan && handlers.onScan());
    foot.appendChild(rescan);

    panel.appendChild(head);
    panel.appendChild(body);
    panel.appendChild(foot);

    root.appendChild(launcher);
    root.appendChild(panel);
    document.documentElement.appendChild(host);

    refs = { launcher, panel, body, rescan };
  }

  function ensure() {
    if (!host || !host.isConnected) {
      build();
    }
    return refs;
  }

  function expand() {
    ensure();
    refs.launcher.hidden = true;
    refs.panel.hidden = false;
  }

  function collapse() {
    ensure();
    refs.panel.hidden = true;
    refs.launcher.hidden = false;
  }

  function clearBody() {
    while (refs.body.firstChild) {
      refs.body.removeChild(refs.body.firstChild);
    }
  }

  function showLoading(message) {
    expand();
    clearBody();
    refs.panel.removeAttribute('data-band');
    refs.rescan.disabled = true;
    const row = el('div', 'loading');
    row.appendChild(el('div', 'spinner'));
    row.appendChild(el('span', null, message || 'Scanning...'));
    refs.body.appendChild(row);
  }

  function showError(message) {
    expand();
    clearBody();
    refs.panel.removeAttribute('data-band');
    refs.rescan.disabled = false;
    refs.body.appendChild(el('p', 'banner error', message));
  }

  function renderCheck(check) {
    const passed = check.passed === true;
    const item = el('li', 'check ' + (passed ? 'passed' : 'failed'));
    const head = el('div', 'check-head');
    head.appendChild(el('span', 'check-mark', passed ? '✓' : '!'));
    head.appendChild(el('span', null, check.name || 'Check'));
    if (!passed && check.weight) {
      head.appendChild(el('span', 'check-weight', '+' + check.weight));
    }
    item.appendChild(head);
    if (check.detail) {
      item.appendChild(el('p', 'check-detail', check.detail));
    }
    return item;
  }

  /**
   * @param {object} result the server's ScanResponse
   * @param {object} context { headersAvailable, sourceLabel }
   */
  function showResult(result, context) {
    expand();
    clearBody();
    refs.rescan.disabled = false;

    const score = Number(result.score) || 0;
    const band = ns.score.bandFor(score);
    refs.panel.setAttribute('data-band', band.key);

    const verdict = el('div', 'verdict');
    const scoreLine = el('p', 'verdict-score', String(score));
    scoreLine.appendChild(el('span', 'verdict-max', '/100'));
    verdict.appendChild(scoreLine);
    verdict.appendChild(el('span', 'verdict-band', band.label));
    refs.body.appendChild(verdict);

    const meter = el('div', 'meter');
    const fill = el('div', 'meter-fill');
    fill.style.transform = 'scaleX(' + score / 100 + ')';
    meter.appendChild(fill);
    refs.body.appendChild(meter);

    refs.body.appendChild(el('p', 'note', band.note));

    // Naming the limitation is the honest thing to do, and naming it precisely matters
    // more: the three "(claimed)" checks read the Authentication-Results header, which
    // the page never exposes, so they report "no header to evaluate" and pass as neutral.
    // The live-DNS checks query the sender's domain directly and are unaffected -- saying
    // all of it was inconclusive would be its own kind of wrong.
    if (context && !context.headersAvailable) {
      refs.body.appendChild(el('p', 'banner warn',
        'Read from the page, which carries no Authentication-Results header. The three '
        + '"(claimed)" checks below had nothing to evaluate and passed as neutral, not as '
        + 'verified. Live DNS checks ran normally. Connect Gmail access in options to scan '
        + 'the raw source.'));
    } else if (context && context.sourceLabel) {
      refs.body.appendChild(el('p', 'banner', context.sourceLabel));
    }

    const checks = Array.isArray(result.checks) ? result.checks : [];
    if (checks.length) {
      const failed = checks.filter((check) => check.passed !== true);
      const passed = checks.filter((check) => check.passed === true);
      const list = el('ul', 'checks');
      // Failures first: the reason someone opened this panel is at the top, and the
      // clean checks stay visible underneath as evidence rather than being hidden.
      failed.concat(passed).forEach((check) => list.appendChild(renderCheck(check)));
      refs.body.appendChild(el('p', 'section-label',
        failed.length ? failed.length + ' of ' + checks.length + ' checks flagged' : 'All checks passed'));
      refs.body.appendChild(list);
    }

    const ai = result.aiAnalysis;
    if (ai && ai.summary) {
      refs.body.appendChild(el('p', 'section-label', 'AI analysis'));
      refs.body.appendChild(el('p', 'note', ai.summary));
    }
  }

  function mount(callbacks) {
    handlers = callbacks || {};
    ensure();
    collapse();
  }

  function destroy() {
    if (host && host.isConnected) {
      host.remove();
    }
    host = null;
    root = null;
    refs = {};
  }

  ns.panel = { mount, showLoading, showResult, showError, collapse, expand, destroy };
})(globalThis.MailSentinel);
