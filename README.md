# Lookalike

Lookalike is a phishing-detection web app: paste in a raw email or a URL, and
it returns a 0–100 risk score with a full breakdown of exactly which signals
fired, which passed, and why. Nothing about the design is a black box — every
check is a small, independently-explainable piece of logic, which is the
point: a score you can't justify isn't much better than a guess.

It's a single-endpoint FastAPI backend plus a minimal React/Vite frontend,
built as a portfolio project. This README doubles as a reference for
explaining the detection techniques out loud (e.g. in an interview) — each
section below is written to teach the idea, not just describe the code.

## Privacy: nothing is persisted

There is no database, and the app never writes submitted content to disk or
to logs. This is a deliberate design decision, not an oversight:

- Everything happens in memory, for the lifetime of a single request.
- No `logging` call anywhere in the scan pipeline touches request content.
- Uvicorn's default access log only records the method, path, and status
  code of a request — never the body — so even the server's own baseline
  logs never see what was pasted in.
- The frontend keeps scan results in local component state; closing or
  refreshing the tab discards them. There's no history, no accounts, no
  saved scans.

If you paste a real, sensitive email into this tool, that content lives only
in the request that scans it.

## How scoring works

Every check in `backend/app/scoring.py` returns a `passed: bool` and a fixed
`weight`. The score is the simplest thing that could work:

```python
score = min(100, sum(weight for check in checks if not check.passed))
```

No logistic curve, no diminishing returns — just a capped sum. What makes
that still behave sensibly (a single obvious signal can be enough to call
something high-risk, while a few ambiguous signals need to add up) is that
the *weights themselves* are tiered by how hard each signal is to trigger by
accident, not the formula:

| Tier | Weight range | Signals |
|---|---|---|
| Solo-red | 55–70 | domain homoglyph, domain edit-distance match, raw IP as a link host, anchor-text/href mismatch, character substitution |
| Strong | 45–55 | a lookalike link elsewhere in the body, a TLD swap |
| Medium/weak | 10–30 | missing or unenforced DMARC, missing SPF, a claimed auth failure, a claimed-vs-live disagreement, a URL shortener |

A single "solo-red" hit alone crosses the 60-point red threshold. Several
medium/weak signals stacking (e.g. no DMARC + no SPF + a shortener) also
crosses it — which is exactly the "weak signals should stack, not each spike
the score alone" requirement this project started from. The full numbers are
in `backend/app/constants.py`.

Every check that's applicable to the input type is always present in the
response, whether it passed or failed — a clean scan shows *why* it's clean
("SPF: valid") instead of just showing an empty list of complaints.

## Detection techniques

### 1. Authentication-Results header (claimed)

*Code: `backend/app/auth_headers.py`*

When a mail server receives a message, it can run SPF/DKIM/DMARC checks
itself and record the verdicts in an `Authentication-Results` header, e.g.:

```
Authentication-Results: mx.example.com; spf=fail smtp.mailfrom=paypa1.com;
  dkim=none; dmarc=fail
```

This is useful — but it's a **claim**, not a verification we perform
ourselves. The header could be absent (plenty of legitimate mail flows don't
add one), or, in principle, forged by a malicious or compromised upstream
hop. So its absence is treated as neutral here, never suspicious on its own;
only an explicit non-`pass` result counts against the score. The header's
claims only become fully trustworthy once corroborated — which is exactly
what the next check does.

### 2. Live SPF/DMARC verification

*Code: `backend/app/dns_checks.py`*

Instead of trusting the header, this check asks DNS directly: does the
sender's domain publish an SPF TXT record? Does it publish a DMARC record at
`_dmarc.<domain>`, and if so, what policy does it declare (`p=none` /
`p=quarantine` / `p=reject`)? A domain with **no** SPF or DMARC record at all
— or a DMARC record that only monitors (`p=none`) without enforcing anything
— is a real, independent risk signal, regardless of what any header claims.

If the header (check 1) claims a pass that the live DNS state can't actually
support — e.g. `dmarc=pass` but the domain has no DMARC record, or it's set
to `p=none` — that disagreement is surfaced as its own signal. That
combination shouldn't be possible from a legitimate receiving server, so it's
a meaningful tell that the header itself may not be trustworthy.

### 3. Lookalike domain detection

*Code: `backend/app/lookalike.py`, target list in `backend/app/brands.py`*

This is the core of the project: given a domain (the email sender's, or a
submitted URL's), check it against ~30 hardcoded high-value brand domains
using four independent techniques, because attackers disguise a domain in
different ways and no single technique catches all of them:

- **Edit distance.** Levenshtein distance (via `rapidfuzz`) to every brand
  domain; anything within distance 2 that isn't an exact match gets flagged.
  `paypa1.com` is one substitution away from `paypal.com`.
- **Character substitution.** Digits and letter-runs that visually resemble
  real letters (`0`→`o`, `1`→`l`, `rn`→`m`) are normalized before comparing
  again. This exists because *multiple* substitutions at once can push the
  raw edit distance past 2 while still reading as an obvious fake to a human
  — `rnicrosoft.com` is only 1 real substitution (`rn` for `m`) but costs 2
  edit operations, and something like `faceb00k.com` needs this technique to
  match cleanly against `facebook.com`.
- **Homoglyphs.** Some letters from other scripts are visually
  indistinguishable from Latin ones — at minimum, Cyrillic а/е/о/р/с versus
  Latin a/e/o/p/c. `unicodedata.name()` is used to detect when a hostname
  mixes scripts (a red flag on its own), and a small confusables table
  transliterates the domain back to Latin before checking it against the
  brand list. Critically, real attacks don't arrive as raw Cyrillic text —
  internationalized domains are punycode-encoded on the wire (`xn--...`),
  and only get rendered as readable Unicode by the victim's mail client or
  browser. So this check decodes punycode labels *before* looking for
  homoglyphs; without that step, `xn--pple-43d.com` would sail through as
  meaningless ASCII, and the whole technique would only catch homoglyph
  domains that had already been decoded for us somewhere upstream.
- **TLD swaps.** The exact brand name with the wrong top-level domain —
  `paypal.co` or `paypal.net` when the real domain is `paypal.com`. This has
  to be separate from edit distance: `chase.net` is 3 edits away from
  `chase.com` (too far for the distance-2 threshold) but is an obvious swap
  once you compare the brand label on its own.

Domain parsing throughout uses `tldextract` rather than a naive `.split(".")`
, which matters for two reasons: it correctly strips subdomains (so
`accounts.google.com` is recognized as the real `google.com`, not flagged),
and it correctly resolves **subdomain-confusion** tricks — a domain like
`paypal.com.verify-account.ru` is not `paypal.com` with an extra path, it's
`verify-account.ru` with a deceptive subdomain, and `tldextract` gets that
right where a naive split wouldn't.

It's expected, not a bug, for more than one technique to fire on the same
domain — an obvious fake often trips several independent checks at once.

### 4. Link analysis

*Code: `backend/app/link_analysis.py`*

For email input, every link in the body gets extracted and analyzed. HTML
bodies are parsed with `lxml` so the visible anchor **text** is available
separately from the actual `href` **target** — which is what makes the
classic "the link says `paypal.com` but actually points somewhere else"
trick detectable at all. Plaintext bodies don't have that distinction (a
link is just its own text), so they're extracted with a URL regex instead,
and the anchor-mismatch check simply never fires for them — that's expected,
not a gap.

Three things are checked per link, each aggregated into a single result
across *all* links in the body (so an email with five links to the same bad
domain isn't scored any higher than one with a single bad link — it's the
pattern that matters, not the count):

- Does the link's domain match any of the four lookalike techniques above?
- Is the link's domain a known URL shortener (`bit.ly`, `tinyurl.com`,
  `t.co`, `goo.gl`, and a few others)? This is a weak signal on its own —
  legitimate marketing email uses shorteners constantly — but it stacks.
- Does the visible anchor text look like a URL or domain name that doesn't
  match where the link actually goes?

### 5. IP-as-hostname

*Code: `is_ip_literal()` in `backend/app/url_utils.py`*

If a URL's host is a raw IPv4 or IPv6 address instead of a domain name —
`http://192.0.2.55/login` — that's flagged directly. Legitimate services are
essentially never linked to by bare IP in end-user-facing email or marketing;
this is one of the more reliable "solo-red" signals in the whole project.

## API and CORS

A single endpoint: `POST /api/scan` with `{ "type": "email" | "url",
"content": "<raw string>" }`, returning `{ score, checks: [...] }`.

CORS is wide open (`allow_origins=["*"]`) on purpose. That's only safe
because there's nothing to protect — no cookies, no sessions, no
credentials anywhere in this API, so there's no CSRF-style risk in allowing
any origin to call it. That also happens to be exactly what's needed to
support a browser extension later (which would call the API from a
`chrome-extension://` origin) without ever having to touch the backend
again. The extension itself is intentionally not built here — this just
avoids painting the API into a corner that would make it harder later.

## Setup

### Backend

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate      # Windows
# source .venv/bin/activate   # macOS/Linux

pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000

ruff check app/   # lint (same check CI runs)
```

### Frontend

```bash
cd frontend
npm install
npm run dev

npm run lint    # eslint (same check CI runs)
npm run build   # production build
```

By default the frontend talks to `http://localhost:8000`. To point it
elsewhere, set `VITE_API_BASE` (e.g. in a `frontend/.env.local`).

### CI

`.github/workflows/ci.yml` runs both of the lint/build steps above on every
push and pull request against `main` — no pytest suite, by the same
"`test_samples/` is the testing story" decision explained below.

## Test samples

`test_samples/` contains four **synthetic emails constructed by hand for
this project** — not real captured phishing mail. They exist to sanity-check
the scoring end-to-end:

| Sample | Score | What it demonstrates |
|---|---|---|
| `phishing_multi_signal.eml` | 100 | An obvious attempt hitting nearly every check at once: lookalike sender domain, failed claimed authentication, a shortened link, an anchor/href mismatch, and a raw-IP link. |
| `legitimate_clean.eml` | 0 | A real, well-configured sender domain with consistent authentication and no lookalike or link tricks — everything passes. |
| `borderline_newsletter.eml` | 50 | No brand impersonation at all, but missing SPF, missing DMARC, and a shortened link stack together into the yellow "medium risk" band — a good illustration of weak signals adding up rather than any one of them spiking the score. |
| `phishing_homoglyph_only.eml` | 100 | A sender domain using a Cyrillic homoglyph of `apple.com`. It also happens to demonstrate check 2's value directly: the header claims SPF/DKIM/DMARC all pass, but live DNS shows no such records exist for that domain at all — a combination a legitimate receiving server should never produce. |

Two of the checks (live SPF/DMARC verification) query real DNS, so their
exact outcome for these made-up domains reflects whatever the real-world DNS
state is at scan time. The deterministic checks — lookalike detection, link
analysis, claimed-header parsing — behave the same on every run.

## Scope

**Out of scope for this build:** a browser extension (the CORS setup is
ready for one, but it isn't built here), WHOIS/domain-age lookups, and any
form of user accounts, scan history, or persistence.

**Known limitation:** the lookalike techniques are intentionally narrow to
avoid false-flagging unrelated domains — edit distance only flags within 2
characters of a brand domain, and TLD-swap only matches an *exact* brand
label. A domain that combines a trick with an added word, like
`paypal-account-verify.com`, falls outside both nets even though a human
would recognize it instantly. Catching that class of lookalike would need a
fuzzier "does this contain a brand name" scan, which trades away a lot of
precision for that recall — a reasonable v2 direction, not something this
version does.
