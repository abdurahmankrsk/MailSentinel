// Adapter selection. The seam exists so that supporting another webmail client is a new
// file plus a manifest match, not a change to the content script's control flow.
globalThis.MailSentinel = globalThis.MailSentinel || {};

(function (ns) {
  'use strict';

  function active() {
    const adapters = ns.adapters || {};
    for (const key of Object.keys(adapters)) {
      const adapter = adapters[key];
      if (adapter && typeof adapter.matches === 'function' && adapter.matches()) {
        return adapter;
      }
    }
    return null;
  }

  ns.registry = { active };
})(globalThis.MailSentinel);
