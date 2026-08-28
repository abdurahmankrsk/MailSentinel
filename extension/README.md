# MailSentinel browser extension

Scan the email you are reading, in the client you are reading it in. The extension
extracts the open message, sends it to a MailSentinel server, and renders the same
0–100 score and check-by-check breakdown the web app shows — without you having to copy
a message out and paste it somewhere.

Works in **Gmail** (`mail.google.com`) and **Outlook on the web** (`outlook.office.com`,
`outlook.office365.com`, `outlook.live.com`).

## Install

There is no build step. The extension is plain scripts, loaded as-is.

1. Start a MailSentinel server — by default the extension expects the local one from
   the project README (`java -jar target/mailsentinel-0.1.0.jar`, serving `:8080`).
2. Open `chrome://extensions`, turn on **Developer mode**.
3. **Load unpacked** → select this `extension/` directory.
4. Open Gmail or Outlook, open a message, click **Scan this email** at the bottom right.

To point at a server other than `http://localhost:8080`, set it in the extension's
options. Chrome will ask for permission for that address when you save — the manifest
requests no broad host access at install time.

## The one thing worth understanding: two ways to read a message

MailSentinel's strongest checks read headers — `Authentication-Results`, `Received`,
`DKIM-Signature`. **Those headers do not exist in the rendered page.** What Gmail and
Outlook put in the DOM is the message as displayed: sender, subject, body, links.

So the extension has two paths, and it always tells you which one produced a result.

| | Reads | SPF/DKIM/DMARC **(claimed)** | SPF/DMARC **(live DNS)** | Lookalike domain, links, anchor mismatch, display-name impersonation | Setup |
|---|---|---|---|---|---|
| **Page** (default) | Rendered DOM | Nothing to evaluate | Runs normally | Yes | None |
| **Raw** (Gmail only) | True RFC 5322 source via Gmail API | Checked properly | Runs normally | Yes | Google OAuth client ID |

The distinction in those two middle columns is the one worth internalising. The
`(claimed)` checks read the `Authentication-Results` header a receiving server wrote —
which the rendered page simply does not contain, so on the page path they report *"no
Authentication-Results header present to evaluate SPF"* and pass as **neutral**. The
live-DNS checks query the sender's domain themselves and are unaffected either way.

The panel says exactly this when a scan ran without headers, rather than letting three
"passed" rows imply an authentication result that was never obtained. A clean scan that
never looked is not the same as a clean scan that looked and found nothing, and
conflating the two is how a tool like this earns misplaced trust.

The DOM path does not merely dump visible text: it rebuilds a real MIME message
(`src/lib/rfc822.js`) with a properly quoted `From` and the body as a `text/html` part,
because the server needs both — the display name for the impersonation check, and the
HTML for anchor-text-versus-`href` comparison.

Outlook is page-only. Reading raw Outlook mail means Microsoft Graph and a separate app
registration; the adapter reports `supportsRawFetch: false` and the panel warns accordingly.

### Enabling raw Gmail access

The Google client ID is **yours**, supplied in options, not bundled with the extension —
a client ID committed to a public repository is one anybody can burn the quota of.

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials),
   enable the **Gmail API**.
2. Create an **OAuth client ID**. Add the redirect URI shown on the extension's options
   page — it is derived from your extension ID, so it differs per install.
3. Paste the client ID into options and save. The scope requested is
   `gmail.readonly`.

If consent is declined or the grant expires, the scan **falls back to the page path**
rather than failing. A degraded scan beats no scan.

## Privacy

Scanning sends message content to the server you configured. Two defaults follow from that:

- **Scans happen when you ask.** Auto-scan-on-open exists as a setting and ships **off**,
  because turning it on means every message you open is sent, not just the ones you
  wondered about.
- **Nothing is stored by the extension.** Settings live in `chrome.storage.sync`; results
  live in the panel until you close it. What the *server* does with a scan is covered in
  the main README's privacy section — notably, an AI-powered scan on a PREMIUM account
  forwards content to a third-party AI provider.

## Layout

```
manifest.json                     MV3
src/lib/settings.js               shared defaults, loaded by all three contexts
src/lib/score.js                  score → band, mirrors the web app's ScoreDisplay
src/lib/rfc822.js                 rebuilds a MIME message from extracted parts
src/lib/api.js                    MailSentinel client (service worker only)
src/background/service-worker.js  the only code that touches the network
src/content/adapters/gmail.js     Gmail DOM extraction + raw-source message id
src/content/adapters/outlook.js   Outlook DOM extraction
src/content/adapters/registry.js  host → adapter
src/content/panel.js              shadow-DOM verdict panel
src/content/content.js            orchestration, message-change detection
src/options/                      options page
tests/                            node --test
```

Two structural notes, both deliberate:

**No build step.** Every file is a classic script hanging its exports off
`globalThis.MailSentinel`. That is the one module format MV3 content scripts, an
`importScripts` service worker, and a plain options page can all share, so the same
`settings.js` is loaded by all three with no bundler and no duplicated defaults. The
manifest's `js` array is therefore also the dependency order, which `tests/manifest.test.js`
asserts.

**All network calls go through the service worker.** A content script's `fetch` is subject
to the *page's* CSP, so calling `localhost:8080` from inside Gmail is blocked before it
leaves the tab. The worker has its own origin and the extension's host permissions.

## Tests

```bash
cd extension
npm install
npm test
```

Covers the RFC 5322 builder (quoting, RFC 2047 encoding, folding, header-injection
resistance, multipart structure), the score bands against the web app's thresholds, the
manifest's internal consistency, and the adapters against jsdom fixtures.

**What the tests cannot tell you:** the fixtures reproduce the *structure* these adapters
key off, not a capture of Gmail's or Outlook's live markup. They prove the traversal,
fallback ordering and failure behaviour are correct; they cannot prove `div.a3s` is still
Gmail's body class today. Only opening a real inbox can. Both adapters return `null` on a
failed extraction so that shows up as "could not read this message" rather than a
confident score of 0 on an empty string.
