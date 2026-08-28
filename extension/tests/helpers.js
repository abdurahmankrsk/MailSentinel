// The extension source is deliberately build-free: every file is a classic script that
// hangs its exports off globalThis.MailSentinel, because that is the one module format
// content scripts, an importScripts service worker, and a plain options page can all
// share without a bundler.
//
// That costs us `require`, so tests evaluate the sources the same way Chrome does --
// in order, into a shared global -- rather than importing them.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.join(__dirname, '..');

function loadScripts(relativePaths) {
  for (const relativePath of relativePaths) {
    const code = fs.readFileSync(path.join(ROOT, relativePath), 'utf8');
    vm.runInThisContext(code, { filename: relativePath });
  }
  return globalThis.MailSentinel;
}

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, relativePath), 'utf8'));
}

function exists(relativePath) {
  return fs.existsSync(path.join(ROOT, relativePath));
}

/**
 * jsdom performs no layout, so offsetParent is always null and every rect is zero --
 * which would make the adapters' "is this actually on screen" filter reject every
 * fixture. Give the named elements a plausible box so that logic can be exercised.
 */
function makeVisible(element, box) {
  Object.defineProperty(element, 'offsetParent', {
    value: element.parentNode || {},
    configurable: true,
  });
  element.getBoundingClientRect = () => ({
    width: 600,
    height: 400,
    top: 0,
    left: 0,
    right: 600,
    bottom: 400,
    ...(box || {}),
  });
  return element;
}

module.exports = { ROOT, loadScripts, readJson, exists, makeVisible };
