const test = require('node:test');
const assert = require('node:assert');
const { loadScripts } = require('./helpers');

const ns = loadScripts(['src/lib/score.js']);
const { bandFor } = ns.score;

// These boundaries are duplicated from the web app's ScoreDisplay.jsx. Pinning them here
// is the point of this file: if either copy drifts, the same score would show a different
// verdict in the inbox than on the website, and that is worse than showing no band.
test('band boundaries match the web app exactly', () => {
  assert.strictEqual(bandFor(0).key, 'low');
  assert.strictEqual(bandFor(29).key, 'low');
  assert.strictEqual(bandFor(30).key, 'medium');
  assert.strictEqual(bandFor(59).key, 'medium');
  assert.strictEqual(bandFor(60).key, 'high', '60 is the red threshold, not the top of yellow');
  assert.strictEqual(bandFor(100).key, 'high');
});

test('every band carries a label and a note for the panel to render', () => {
  for (const score of [0, 45, 95]) {
    const band = bandFor(score);
    assert.ok(band.label && band.label.length > 0);
    assert.ok(band.note && band.note.length > 0);
  }
});
