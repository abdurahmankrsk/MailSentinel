# MailSentinel

MailSentinel is a phishing-detection web app: paste in a raw email or a URL, and
it returns a 0–100 risk score with a full breakdown of exactly which signals
fired, which passed, and why. Nothing about the design is a black box — every
check is a small, independently-explainable piece of logic, which is the
point: a score you can't justify isn't much better than a guess.

It is built as a single-folder Java 21 / Spring Boot 3 application with an
embedded React/Vite web interface, packaged into a single self-contained executable
JAR. This README doubles as a reference for explaining the detection techniques out
loud (e.g. in an interview) — each section below is written to teach the idea, not
just describe the code.

## Privacy: what is and isn't persisted

Anonymous, unauthenticated scanning (the FREE experience) works exactly as
before: the app never writes submitted content to disk or to logs.

- Everything happens in memory, for the lifetime of a single request.
- No logging call anywhere in the scan pipeline touches request content.
- Baseline web logs only record the method, path, and status code of a request —
  never the body.
- The frontend keeps scan results in local component state; closing or
  refreshing the tab discards them.

A PREMIUM account changes this in a few specific, limited ways:

- Creating an account stores your email and a hashed password — never the raw
  password (BCrypt), and login sessions are opaque, hashed, revocable tokens, not
  passwords or plaintext secrets.
- AI-powered analyses send the scanned email/URL content to a third-party AI
  provider (Groq or Gemini, depending on configuration) over the network, so the
  provider processes that content — this is a real, deliberate expansion of the
  trust boundary for PREMIUM users specifically, and doesn't happen on the FREE
  path at all.
- Only usage *counts* are stored (how many AI scans you've used this billing
  period, and when it resets) — never scan content.
- Idempotency records (used to make retried requests safe) store a one-way hash
  of the request and the AI's output text, never the raw email or URL you
  submitted.

If you paste a real, sensitive email into this tool as an anonymous or FREE user,
that content lives only in the request that scans it, exactly as always. As a
PREMIUM user running an AI-powered analysis, that content is also sent to the
configured AI provider for that one request.

## How scoring works

Every check in `app/src/main/java/com/mailsentinel/service/ScoringService.java` returns
a `passed: boolean` and a fixed `weight: int`. The score is the simplest thing that
could work:

```java
int score = Math.min(100, checks.stream()
    .filter(c -> !c.passed())
    .mapToInt(CheckResult::weight)
    .sum());
```

No logistic curve, no diminishing returns — just a capped sum. What makes
that still behave sensibly (a single obvious signal can be enough to call
something high-risk, while a few ambiguous signals need to add up) is that
the *weights themselves* are tiered by how hard each signal is to trigger by
accident, not the formula:

| Tier | Weight range | Signals |
|---|---|---|
| Solo-red | 55–70 | domain homoglyph, domain edit-distance match, raw IP as a link host, anchor-text/href mismatch, character substitution |
| Strong | 45–55 | a lookalike link elsewhere in the body, a TLD swap, a display name claiming a brand the sending domain doesn't back up |
| Medium/weak | 9–30 | missing or unenforced DMARC, missing SPF, a claimed auth failure, a claimed-vs-live disagreement, a URL shortener |

A single "solo-red" hit alone crosses the 60-point red threshold. Several
medium/weak signals stacking (e.g. a claimed SPF failure + a claimed DKIM
failure + a claimed DMARC failure — see the header example above) also
crosses it — which is exactly the "weak signals should stack, not each spike
the score alone" requirement this project started from. Deliberately *not*
enough on its own: a domain simply missing both SPF and DMARC with nothing
else wrong (worth 20 points, still "low risk") — that's what keeps an
ordinary, unauthenticated-but-otherwise-unremarkable domain from defaulting
to "medium" on every scan. The full numbers are
in `app/src/main/java/com/mailsentinel/config/ScoringConstants.java`.

Every check that's applicable to the input type is always present in the
response, whether it passed or failed — a clean scan shows *why* it's clean
("SPF: valid") instead of just showing an empty list of complaints.

## Detection techniques

### 1. Authentication-Results header (claimed)

*Code: `AuthHeaderService.java`*

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

*Code: `DnsCheckService.java` (via dnsjava)*

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

One distinction matters more than it looks: **a lookup that didn't complete is not
the same as a domain that publishes nothing.** A timeout, a SERVFAIL, or a resolver
hiccup all produce an empty answer that is indistinguishable, at the call site, from
a genuine "no such record" — and scoring the two the same way means a legitimate
sender gets penalised for our own transient network trouble, non-deterministically.
So `LiveDnsResult` carries an explicit *resolved* flag per lookup, and an unresolved
lookup scores as neutral (the check passes, with a detail saying why) rather than as
a missing record. The same flag stops the agreement check above from manufacturing a
"header claims pass but DNS disagrees" finding out of a query that never got an answer.

### 3. Lookalike domain detection

*Code: `LookalikeDetector.java`, target list in `BrandConstants.java`*

This is the core of the project: given a domain (the email sender's, or a
submitted URL's), check it against ~30 high-value brand domains
using four independent techniques, because attackers disguise a domain in
different ways and no single technique catches all of them:

- **Edit distance.** Levenshtein distance (via Apache Commons Text) to every brand
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
  Latin a/e/o/p/c. `Character.UnicodeScript` detects when a hostname
  mixes scripts (a red flag on its own), and a confusables table
  transliterates the domain back to Latin before checking it against the
  brand list. Critically, real attacks don't arrive as raw Cyrillic text —
  internationalized domains are punycode-encoded on the wire (`xn--...`),
  and only get rendered as readable Unicode by the victim's mail client or
  browser. So this check decodes punycode labels (`IDN.toUnicode()`) *before*
  looking for homoglyphs; without that step, `xn--pple-43d.com` would sail through as
  meaningless ASCII, and the whole technique would only catch homoglyph
  domains that had already been decoded for us somewhere upstream.
- **TLD swaps.** The exact brand name with the wrong top-level domain —
  `paypal.co` or `paypal.net` when the real domain is `paypal.com`. This has
  to be separate from edit distance: `chase.net` is 3 edits away from
  `chase.com` (too far for the distance-2 threshold) but is an obvious swap
  once you compare the brand label on its own.

A fifth technique covers the case the four above structurally cannot:

- **Display-name impersonation.** All four techniques above compare *domains*, so an
  attacker who registers something that resembles no brand at all — `m365-account-
  security.com`, `secure-docs-exchange.com` — scores zero on every one of them, while
  the recipient's mail client happily shows them the words "Microsoft 365". So the
  From display name is checked against the brand list independently: naming a brand
  the sending domain doesn't back up is the finding. Mail that names the brand it is
  actually sent from (`GitHub <notifications@github.com>`) is the normal case and
  passes. Short brand labels are matched only on whole-word boundaries so "Pineapple"
  doesn't read as `apple` and "Groups" doesn't read as `ups`, while longer labels are
  also matched across separators so "Bank of America" still resolves to
  `bankofamerica.com`.

Domain parsing throughout uses Guava's `InternetDomainName` against the Public Suffix List
rather than a naive `.split(".")`, which correctly strips subdomains and resolves
**subdomain-confusion** tricks (e.g. `paypal.com.verify-account.ru` is recognized as
`verify-account.ru`).

### 4. Link analysis

*Code: `LinkAnalysisService.java`*

For email input, every link in the body gets extracted and analyzed. HTML
bodies are parsed with `Jsoup` so the visible anchor **text** is available
separately from the actual `href` **target** — which is what makes the
classic "the link says `paypal.com` but actually points somewhere else"
trick detectable at all. Plaintext bodies don't have that distinction (a
link is just its own text), so they're extracted with a URL regex instead,
and the anchor-mismatch check simply never fires for them — that's expected,
not a gap.

Three things are checked per link, each aggregated into a single result
across *all* links in the body:

- Does the link's domain match any of the four lookalike techniques above?
- Is the link's domain a known URL shortener (`bit.ly`, `tinyurl.com`,
  `t.co`, `goo.gl`, and a few others)? This is a weak signal on its own —
  legitimate marketing email uses shorteners constantly — but it stacks.
- Does the visible anchor text look like a URL or domain name that doesn't
  match where the link actually goes?

### 5. IP-as-hostname

*Code: `UrlUtils.isIpLiteral()`*

If a URL's host is a raw IPv4 or IPv6 address instead of a domain name —
`http://192.0.2.55/login` — that's flagged directly. Legitimate services are
essentially never linked to by bare IP in end-user-facing email or marketing;
this is one of the more reliable "solo-red" signals in the whole project.

## API and CORS

A single endpoint: `POST /api/scan` with `{ "type": "email" | "url",
"content": "<raw string>" }`, returning `{ score, checks: [...] }`.

CORS is wide open (`@CrossOrigin(origins = "*")`) on purpose. That's safe
because there are no cookies, sessions, or credentials anywhere in this API,
and it allows browser extensions to call it directly in the future.

## Sign-in and Google OAuth

Accounts are optional: anonymous scanning is unchanged and needs no login. The header
carries `Log in` / `Sign up` controls that open the auth dialog; the page stays a plain
scanner until you ask for it.

Alongside email/password there is a **Continue with Google** button, which serves as both
sign-in and sign-up — an unrecognised Google account is created on first use, and a
Google address that matches an existing account signs into that account rather than
duplicating it. Linking by email address is safe here specifically because the server
has verified with Google that the user owns that mailbox.

**The browser is never trusted about who it is.** Google Identity Services hands the
page a signed ID token, that token is the only thing sent to `POST /api/auth/google`,
and `GoogleTokenVerifier` verifies it server-side before it may name a user — checking
the audience matches this application's client ID, the issuer is Google, the token has
not expired, and the email is one Google itself has marked verified. A hand-crafted JWT
asserting `email_verified: true` is rejected.

### Enabling it

Google sign-in is **off** unless configured, and the button hides itself when the server
reports it unconfigured — there is no button that can only fail.

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials),
   create an **OAuth 2.0 Client ID** of type **Web application**.
2. Add your origin under **Authorised JavaScript origins** — `http://localhost:8080`
   for local runs, plus your real origin for a deployment.
3. Copy the generated **Client ID** into the `GOOGLE_CLIENT_ID` environment variable
   (see `.env.example`). Restart the app; the button appears.

No client secret is required, and none should be added. This app uses the ID-token
flow, so the authorisation-code exchange that would need a secret never happens. The
client ID is public by design — it identifies the application to Google and is visible
in the browser — which is why it is the one auth value safe to send to the frontend
(via `GET /api/auth/config`).

## Single-Folder Setup and Run

Everything lives inside `app/`.

Configuration is read from environment variables only — copy `.env.example` and fill in
what you need. Every variable is optional for a basic local run except the datasource.

### Run Locally (Development / Production JAR)

```bash
cd app

# 1. Build the React client (outputs directly into Spring static resources)
cd client
npm install
npm run build
cd ..

# 2. Package and run the Spring Boot app
./mvnw clean package
java -jar target/mailsentinel-0.1.0.jar
```

Now open **http://localhost:8080** in your browser. Both the web interface and the
`/api/scan` REST endpoint are served from the single running application.

### Run Tests

```bash
cd app
./mvnw test
```

### Docker Deployment

```bash
cd app
docker build -t mailsentinel .
docker run -p 8080:8080 mailsentinel
```

## CI

`.github/workflows/ci.yml` builds the React frontend, runs linter checks, and executes the
full Maven test suite on every push and pull request against `main`.
