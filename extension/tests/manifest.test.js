const test = require('node:test');
const assert = require('node:assert');
const { readJson, exists } = require('./helpers');

const manifest = readJson('manifest.json');

// Chrome fails a broken path at load time with a message that does not always name the
// file. Catching a typo here is cheaper than catching it in the extensions page.
test('every file the manifest references exists', () => {
  const referenced = [manifest.background.service_worker, manifest.options_page];
  for (const script of manifest.content_scripts.flatMap((entry) => entry.js || [])) {
    referenced.push(script);
  }
  for (const resource of manifest.web_accessible_resources.flatMap((entry) => entry.resources)) {
    referenced.push(resource);
  }
  for (const file of referenced) {
    assert.ok(exists(file), 'manifest references a missing file: ' + file);
  }
});

test('content scripts load their dependencies before their dependents', () => {
  // These are classic scripts sharing one global, so load order is the dependency graph.
  const scripts = manifest.content_scripts[0].js;
  const indexOf = (name) => scripts.findIndex((script) => script.endsWith(name));

  assert.ok(indexOf('rfc822.js') < indexOf('content.js'), 'content.js calls ns.rfc822');
  assert.ok(indexOf('score.js') < indexOf('panel.js'), 'panel.js calls ns.score');
  assert.ok(indexOf('gmail.js') < indexOf('registry.js'), 'registry enumerates the adapters');
  assert.ok(indexOf('outlook.js') < indexOf('registry.js'), 'registry enumerates the adapters');
  assert.ok(indexOf('registry.js') < indexOf('content.js'), 'content.js calls ns.registry');
  assert.ok(indexOf('panel.js') < indexOf('content.js'), 'content.js calls ns.panel');
});

test('the panel stylesheet is web accessible to the pages that need it', () => {
  // panel.js fetches this by extension URL; without a matching resource entry the fetch
  // is blocked and the panel renders unstyled.
  const resources = manifest.web_accessible_resources.flatMap((entry) => entry.resources);
  assert.ok(resources.includes('src/content/panel.css'));

  const warMatches = new Set(manifest.web_accessible_resources.flatMap((entry) => entry.matches));
  for (const match of manifest.content_scripts[0].matches) {
    assert.ok(warMatches.has(match), 'stylesheet is not accessible on ' + match);
  }
});

test('permissions stay minimal and cover what the code actually calls', () => {
  assert.strictEqual(manifest.manifest_version, 3);
  // storage: settings. identity: launchWebAuthFlow for the Gmail raw path.
  assert.deepStrictEqual([...manifest.permissions].sort(), ['identity', 'storage']);
  assert.ok(!manifest.permissions.includes('tabs'), 'tabs is not needed and is broad');
  assert.ok(
    manifest.host_permissions.includes('https://gmail.googleapis.com/*'),
    'the worker fetches the Gmail API directly'
  );
  // A wide grant belongs in optional_host_permissions, requested from the options page
  // on a user gesture -- not handed over at install time.
  assert.ok(!manifest.host_permissions.some((pattern) => pattern.includes('*://*/*')));
  assert.ok(manifest.optional_host_permissions.includes('https://*/*'));
});

test('content scripts are scoped to the supported webmail hosts only', () => {
  const matches = manifest.content_scripts[0].matches;
  assert.ok(matches.includes('https://mail.google.com/*'));
  assert.ok(matches.some((match) => match.includes('outlook.office.com')));
  assert.ok(matches.some((match) => match.includes('outlook.live.com')));
  for (const match of matches) {
    assert.ok(!match.startsWith('*://*/'), 'must not inject into every site: ' + match);
  }
});
