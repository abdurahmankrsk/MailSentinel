# MailSentinel — QA Findings (Platform)

QA pass over the **web platform only** (the browser extension was explicitly out of
scope). Covers authentication, session lifecycle, signup abuse controls, subscription
and payment, detection accuracy, input handling, and the API's security boundary.

**Environment.** Built at `b609013` with Temurin 21.0.11, packaged to
`app/target/mailsentinel-0.1.0.jar`, run against a local H2 file database with
`ADMIN_EMAILS` and `BYOK_ENCRYPTION_KEY` configured so the admin and
bring-your-own-key paths were reachable. UI driven in a real browser against the
served frontend. `./mvnw test` passes in full — every finding below is behaviour the
existing suite does not cover.

**Legend.** 🔴 Blocker · 🟠 High · 🟡 Medium · ⚪ Low

---

## Summary

| # | Severity | Area | Finding |
|---|---|---|---|
| 1 | 🔴 | Detection | Legitimate regional brand mail (`amazon.co.uk`) scores **100/100 "HIGH RISK"** |
| 2 | 🔴 | Security | **SSRF** — user-supplied AI base URL is fetched server-side, works as an internal port scanner |
| 3 | 🔴 | Payment | **No payment system exists**; Premium is unobtainable and the advertised Enterprise tier isn't implemented |
| 4 | 🟠 | Auth | No server-side email-format validation — defeats the disposable-address gate |
| 5 | 🟠 | Detection | "Brand + word" domains (`paypal-secure.com`) score **0/100 clean** |
| 6 | 🟠 | Abuse | No rate limiting anywhere — credential brute force, signup flood, scan abuse |
| 7 | 🟠 | Auth | No password reset, email verification, password change, or account deletion |
| 8 | 🟡 | Auth | Over-long email returns **HTTP 500** instead of 400 |
| 9 | 🟡 | Auth | BCrypt silently truncates passwords at 72 bytes |
| 10 | 🟡 | UI | Auth dialog title desyncs from the selected tab |
| 11 | 🟡 | Detection | Unparseable email scores 20 and leaks the internal `unknown` placeholder |
| 12 | 🟡 | UI | Plans/"Upgrade to PREMIUM" are dead ends; signup CTA shown to signed-in users |
| 13 | 🟡 | Detection | 33-brand watch list — anything outside it is silently reported as clean |
| 14 | 🟡 | Perf | Email link extraction is unbounded (URL scans cap at 50, email scans don't) |
| 15 | 🟡 | Perf | DNS lookups uncached; first scan of a domain takes ~9 s, with no client timeout |
| 16 | ⚪ | Legal | No Terms, Privacy Policy, or contact anywhere, despite EUR pricing |
| 17 | ⚪ | Privacy | Scan results stay on screen after logout |
| 18 | ⚪ | Security | No CSP; session token in `localStorage` |
| 19 | ⚪ | Frontend | `plan` returned by the auth API is discarded |

---

## 🔴 1 — Legitimate regional brand email scores 100/100 "HIGH RISK"

**Area:** `LookalikeDetector.checkTldSwap` / `BrandConstants`

`checkTldSwap` flags any domain whose first label matches a brand label but whose full
domain isn't the exact brand domain. Every legitimate country-specific domain of a
watched brand therefore trips it.

**Reproduction — URL scans:**

| Input | Score | Flagged as |
|---|---|---|
| `https://www.amazon.co.uk/deals` | **45** | URL domain TLD swap |
| `https://www.amazon.de` | **45** | URL domain TLD swap |
| `https://www.google.co.uk` | **45** | URL domain TLD swap |
| `https://www.apple.co.uk` | **45** | URL domain TLD swap |
| `https://www.paypal.co.uk` | **45** | URL domain TLD swap |
| `https://www.microsoft.co.jp` | **45** | URL domain TLD swap |

**Reproduction — email scan.** A genuine Amazon UK dispatch notice with `spf=pass`,
`dkim=pass`, `dmarc=pass` and only `amazon.co.uk` links:

```
From: Amazon.co.uk <no-reply@amazon.co.uk>
Authentication-Results: mx.example.com; spf=pass smtp.mailfrom=amazon.co.uk; dkim=pass; dmarc=pass
<a href="https://www.amazon.co.uk/orders">Track your parcel</a>
```

```
SCORE = 100
  FAIL Sender domain TLD swap (w=45)              amazon.co.uk … real domain is amazon.com
  FAIL Sender display name impersonation (w=45)   display name names amazon.com, sent from amazon.co.uk
  FAIL Suspicious links in body (w=55)            amazon.co.uk … wrong top-level domain
```

Three checks that are supposed to be independent all fire on the same underlying fact,
so 45 + 45 + 55 = 145 → capped at **100/100, "Treat this as hostile."** This is the
worst possible output on some of the most common legitimate mail in Europe, and it
discredits every other verdict the tool produces.

**Recommended fix.** Make a brand a *set* of legitimate domains rather than one string.
In `BrandConstants`, replace `List<String> BRAND_DOMAINS` with a
`Map<String, Set<String>>` of brand label → owned domains
(`amazon` → `{amazon.com, amazon.co.uk, amazon.de, amazon.fr, amazon.co.jp, …}`), and
derive `BRAND_SET` as the union of all values. Then:

- `checkTldSwap` returns `null` when the candidate is in the brand's owned set.
- `checkDisplayNameImpersonation` treats *any* domain in the named brand's set as
  backing the name up.
- `checkEditDistance` / `checkCharSubstitution` / `checkHomoglyph` already short-circuit
  on `BRAND_SET.contains(...)`, so they inherit the fix for free.

Add a regression test asserting `amazon.co.uk`, `google.co.uk`, and `apple.co.uk`
score 0. Longer term, consider suppressing the TLD-swap signal when the sending domain
passes DMARC alignment — an enforced `p=reject` that passes is strong evidence the
sender is who they claim to be.

---

## 🔴 2 — SSRF via the bring-your-own-key base URL

**Area:** `AiKeyService.validateAgainstEndpoint` / `AiAnalysisService.analyzeWithOwnKey`

`POST /api/account/ai-key` accepts an arbitrary `baseUrl` and immediately issues a
server-side POST to it, then repeats that request on every subsequent scan. The only
validation is `startsWith("http://" | "https://")` — there is no check that the host is
public, so the request can be aimed at loopback, link-local, or RFC1918 addresses.

**Reproduction** (any authenticated account; accounts are free and unlimited):

```bash
curl -X POST localhost:8099/api/account/ai-key -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"label":"x","baseUrl":"http://169.254.169.254/latest/meta-data","model":"x","key":"0123456789abc"}'
```

The upstream error is reflected verbatim in the 400 body, which turns the endpoint into
a reliable **internal port scanner** — open and closed ports are cleanly distinguishable:

| `baseUrl` | Response detail |
|---|---|
| `http://127.0.0.1:8099/api` (open) | `401 : [no body]` |
| `http://127.0.0.1:1/api` (closed) | `I/O error on POST request …` |
| `http://192.168.1.1/admin` (filtered) | `Connection timed out: getsockopt` |
| `http://169.254.169.254/latest/meta-data` | `Network is unreachable` |

On the cloud hosts the README targets (Render / Railway / Fly / Heroku, with Supabase),
`169.254.169.254` is the instance-metadata service — reachable there, and a route to
instance credentials.

**Recommended fix.** Add an allowlist-first egress guard in `AiKeyService` before
`validateAgainstEndpoint` and reuse it in `AiProviderFactory.create`:

1. Require `https://` (reject plaintext `http://` outright).
2. Resolve the host and reject any address where
   `isLoopbackAddress() || isLinkLocalAddress() || isSiteLocalAddress() || isAnyLocalAddress() || isMulticastAddress()`,
   plus the IPv6 unique-local range `fc00::/7`.
3. Re-validate at request time, not just at save time, and pin the resolved address —
   otherwise a DNS entry that is public at save and private at scan time (DNS rebinding)
   walks straight through.
4. Do not reflect the upstream error. Return a fixed
   `"That API key or endpoint could not be reached"` and log the detail server-side.

Strongly consider an explicit provider allowlist (`api.openai.com`, `api.groq.com`,
`api.together.xyz`, `api.deepseek.com`, …) with self-hosted endpoints behind a separate
opt-in setting — that removes the entire class rather than filtering it.

---

## 🔴 3 — There is no payment system

**Area:** `subscription/`, `PlanTeaser.jsx`, `UsagePanel.jsx`

The landing page sells three tiers — Free €0, **Premium €3/mo**, **Enterprise €50/mo** —
but nothing behind them exists:

- No checkout, billing, or subscription endpoint. `/api/billing`, `/api/checkout`,
  `/api/payment`, `/api/subscribe`, `/api/plans`, `/api/subscription/upgrade` → all 404.
- No payment provider dependency in `pom.xml`; no webhook handler.
- The **only** way to obtain Premium is an admin calling `POST /api/admin/grant-premium`
  by hand, and `ADMIN_EMAILS` defaults to empty — so in a default deployment *nobody*
  can grant it and the paid tier is entirely unreachable.
- **Enterprise does not exist in the backend at all.** `Plan` is a closed enum of
  `FREE, PREMIUM`, and `PlanCatalog.configFor` switches exhaustively over those two.
  The €50/mo card advertises 50,000 analyses, pooled workspace usage, and custom watch-list
  domains — none of which is implemented anywhere.
- In the UI, `UsagePanel` renders **"Upgrade to PREMIUM for AI-powered analysis"** as
  plain text with no link or button, and the plans section's only CTA is
  "Create a free account". A DOM sweep of the signed-in page found no
  upgrade/checkout/subscribe/pay control anywhere.

Advertising a specific price for a product that cannot be bought is a
consumer-protection problem, not just an incomplete feature.

**Recommended fix.** Short term, stop advertising what doesn't exist: remove the
Enterprise card (or relabel it "Contact us" with a real mailto), and replace the €3
Premium price with "Coming soon" until checkout ships.

To ship it properly: add Stripe Checkout, create a `POST /api/billing/checkout-session`
returning a session URL, and a `POST /api/billing/webhook` that verifies the Stripe
signature and calls the existing `SubscriptionService.activatePremium` /
`deactivatePremium` — those methods are already the right seam, as their Javadoc says.
Persist `stripe_customer_id` / `stripe_subscription_id` on `subscriptions` and make the
webhook idempotent on Stripe's event ID. Add `ENTERPRISE` to `Plan` and `PlanProperties`
only alongside the features it promises. Wire the "Upgrade" text to the checkout
session, and hide the plans section's signup CTA for signed-in users.

---

## 🟠 4 — No server-side email-format validation

**Area:** `AuthController.validate` / `AuthService.register`

`validate()` checks only `isBlank()`. Every one of these creates a real account with
**HTTP 201**:

```
notanemail · no-at-sign.com · a@ · @example.com · spaces in@example.com
a@b · a@@b.com · user@.com · user@com · <script>alert(1)</script>@x.com
```

This matters most because it **defeats the disposable-address gate** — the feature the
README devotes a whole section to. `DisposableEmailDomainService` matches on the text
after the last `@`, so an address with no domain at all has nothing to match and sails
through. The signup policy is only as strong as the guarantee that an address *has* a
domain.

It also means the address on file may be undeliverable, so any future password reset or
billing receipt has nowhere to go.

The React form uses `<input type="email" required>`, so a browser blocks these — but
`/api/auth/register` is a public, CORS-open endpoint and the check is client-side only.

**Recommended fix.** Add `spring-boot-starter-validation`, annotate
`RegisterRequest.email` with `@Email @NotBlank @Size(max = 254)`, and put `@Valid` on the
controller parameter. Validate in `AuthService.register` too, so the rule holds for the
Google path and any future caller. Normalize and require exactly one `@` with a
non-empty local part and a domain containing a dot before the disposable check runs.

---

## 🟠 5 — "Brand + word" domains score 0/100 clean

**Area:** `LookalikeDetector`

The four domain techniques only match near-exact forms, and `checkBrandSubdomain` only
fires when a brand sits in a *subdomain* label. A brand name inside the registrable
label itself — the single most common real phishing pattern — matches nothing:

| Input | Score |
|---|---|
| `http://paypal-secure.com` | **0** |
| `http://apple-support.com` | **0** |
| `http://microsoft-login.com` | **0** |
| `http://amazon-billing.com` | **0** |
| `http://netflix-payment.com` | **0** |
| `http://chase-verify.com` | **0** |
| `http://secure-paypal.com` | **0** |
| `http://dhl-tracking-parcel.com` | **0** |

In an email the display-name check partly compensates — but only if the attacker names
the brand. They simply don't have to:

| Sample | Score | Verdict |
|---|---|---|
| `PayPal Service <security@paypal-secure.com>` | 45 | Medium risk |
| `Account Services <security@paypal-secure.com>` | **11** | **Low risk** |

A textbook PayPal phishing domain is reported as low risk because the display name was
neutral.

**Recommended fix.** Add a sixth technique — brand-label-embedded-in-registrable-domain.
Take the eTLD+1's first label, split on `-` and `_`, and flag when any token equals a
brand label while the domain isn't in that brand's owned set (see finding #1). Weight it
in the **Strong** tier (45), not solo-red: legitimate affiliates occasionally do this, so
it should stack rather than convict alone. `paypal-secure.com` → tokens
`{paypal, secure}` → contains `paypal` → flag. Also match unhyphenated concatenations for
labels ≥ 8 characters, reusing the existing `MIN_COMPACT_LABEL_LENGTH` rule that already
guards against "Pineapple" matching `apple`.

---

## 🟠 6 — No rate limiting anywhere

**Area:** whole API

Measured directly:

- **25 consecutive failed logins** on one account → 25 × HTTP 401, no throttle, no
  lockout, no delay; the correct password still returns 200 immediately after. Unlimited
  online password guessing.
- **30 registrations in a burst** → 30 × HTTP 201. Unlimited automated account creation.
- `/api/scan` is unauthenticated with `Access-Control-Allow-Origin: *`, so any website
  can drive it from visitors' browsers at no cost to the attacker. Combined with
  findings #14 and #15 (unbounded per-scan work, uncached 3 s DNS lookups) this is a
  cheap resource-exhaustion path.

The signup flood also compounds #2: SSRF needs an account, and accounts are free,
instant, unverified, and unlimited.

**Recommended fix.** Add Bucket4j (`bucket4j-spring-boot-starter`) or a small
`OncePerRequestFilter` in front of the sensitive paths:

- `/api/auth/login` — per-IP **and** per-email counters, e.g. 5 failures / 15 min, then
  exponential backoff. Count failures only; a success resets. Per-email matters because
  credential stuffing rotates IPs.
- `/api/auth/register` — 3 / hour / IP.
- `/api/scan` — a generous anonymous per-IP quota (e.g. 60 / min) purely as an abuse
  ceiling, since free unlimited scanning is a deliberate product promise.
- `/api/account/ai-key` — tight, e.g. 5 / hour, to blunt SSRF scanning.

Back it with the existing datastore (or Redis if multi-instance) so limits survive a
restart and apply across replicas. Return 429 with `Retry-After`.

---

## 🟠 7 — No password reset, email verification, or account deletion

**Area:** `auth/`

Confirmed absent by both endpoint probing (all 404) and source grep — no
`resetpassword`, `forgotpassword`, `verifyemail`, or `changepassword` code exists:

- **No password reset.** A user who forgets their password is permanently locked out;
  there is no recovery path whatsoever.
- **No email verification.** Addresses are never proved to belong to the registrant.
  Combined with #4 and #6, accounts can be mass-created against addresses that may not
  even be deliverable.
- **No password change** for a signed-in user.
- **No account deletion or data export.** Given the app stores emails, password hashes,
  and usage records for EU-priced customers, GDPR erasure and portability requests
  cannot currently be honoured.

**Recommended fix.** Add a `password_reset_tokens` table (hashed token, single-use,
~30 min TTL) mirroring the existing `AuthToken` design, plus
`POST /api/auth/forgot-password` and `POST /api/auth/reset-password`. Return **204
regardless of whether the address exists**, so the endpoint isn't an account-enumeration
oracle — matching the care already taken in `AuthService.register`'s ordering comment.
Revoke all of that user's `auth_tokens` on a successful reset. Add
`POST /api/auth/change-password` requiring the current password. Add
`DELETE /api/account` cascading across `subscriptions`, `usage_periods`,
`idempotency_records`, `user_ai_keys`, and `auth_tokens`. Email delivery is the new
dependency here — it is also the prerequisite for verification, so it is worth adding
once and using for both.

---

## 🟡 8 — Over-long email returns HTTP 500

**Area:** `ApiExceptionHandler`

Registering with a 10,000-character local part returns **HTTP 500** and logs a full
stack trace. The `users.email` column is `varchar(320)`, so Hibernate throws
`DataIntegrityViolationException`, which no handler catches.

```
org.h2.jdbc.JdbcSQLDataException: Value too long for column "EMAIL CHARACTER VARYING(320)"
```

A validation failure surfacing as a server error is misleading to the client and noisy in
the logs.

**Recommended fix.** Covered by the `@Size(max = 254)` in finding #4 (254 is the RFC 5321
maximum, comfortably inside the 320-char column). Additionally add a
`@ExceptionHandler(DataIntegrityViolationException.class)` to `ApiExceptionHandler`
returning 400 with a generic message, so no future column-constraint breach becomes a 500.

---

## 🟡 9 — BCrypt silently truncates passwords at 72 bytes

**Area:** `SecurityConfig.passwordEncoder`

BCrypt ignores input past 72 bytes. A 200-character password is accepted at registration,
and login then succeeds with **only the first 72 characters**, or with any 200-character
string sharing that prefix — both verified returning HTTP 200.

Not remotely exploitable, but a user who deliberately chose a long passphrase gets far
less entropy than they believe, silently.

**Recommended fix.** Reject passwords longer than 72 **bytes** (UTF-8, not characters) in
`AuthController.validate` with a clear message, so the limit is explicit rather than
silent. If longer passphrases are wanted, switch to
`PasswordEncoderFactories.createDelegatingPasswordEncoder()` and store new hashes with
Argon2id or scrypt — the `{id}` prefix lets existing BCrypt hashes keep verifying and
upgrade on next login. Add a `@Size(max = 72)` alongside the min-length rule.

---

## 🟡 10 — Auth dialog title desyncs from the selected tab

**Area:** `AuthModal.jsx` / `AuthPanel.jsx`

`AuthModal` derives its title from `initialMode`, but `AuthPanel` owns the live `mode`
in its own state. Switching tabs inside the dialog leaves the heading behind.

Verified in the browser — open via **Sign up**, then click the **Log in** tab:

```json
{ "modalTitle": "Create your account", "activeTab": "Log in", "submitButton": "Log in" }
```

The heading contradicts both the active tab and the submit button.

**Recommended fix.** Lift `mode` into `AuthModal` and pass it down with a setter, letting
the title derive from live state. That also removes the `key={initialMode}` remount hack.
Keep the `<h2>` as the single source of truth for the accessible name, since it is
referenced by `aria-labelledby`.

---

## 🟡 11 — Unparseable email scores 20 and leaks an internal placeholder

**Area:** `ScoringService.scanEmail` / `DnsCheckService`

Input with no parseable `From` (e.g. pasting a message body without headers, an easy
user mistake given the "Show original" instruction) falls back to the literal string
`"unknown"`, which `verifySpfDmarc` reports as *resolved with no records*:

```
score = 20
  FAIL SPF record (live DNS)     "No SPF TXT record found for unknown"
  FAIL DMARC record & policy     "No DMARC record found at _dmarc.unknown"
```

Two problems. The internal sentinel is shown to the user as though `unknown` were a real
domain. And a parse failure is scored as two genuine findings — the user is penalised 20
points for the tool's inability to read their input, exactly the mistake the codebase is
careful to avoid for unresolved DNS lookups (`LiveDnsResult.resolved`).

**Recommended fix.** Make "no sender domain" its own outcome rather than a fake domain.
Have `verifySpfDmarc(null)` mark both lookups **unresolved**, so the existing neutral
path produces passing checks. In `toCheckResults`, when the domain is absent, emit
`"No sender domain could be parsed from this message, so SPF/DMARC were not checked"`.
Better still, surface a parse warning on the response so the UI can prompt
"This doesn't look like a full email source — did you include the headers?".

---

## 🟡 12 — Plans and "Upgrade" are dead ends

**Area:** `App.jsx`, `PlanTeaser.jsx`, `UsagePanel.jsx`

Verified in the browser while signed in:

- `PlanTeaser` renders unconditionally, so a signed-in user is still told to
  **"Create a free account"** — clicking it opens the registration dialog for an account
  they already have.
- `UsagePanel` shows **"Upgrade to PREMIUM for AI-powered analysis"** as inert text.
- A DOM sweep for any `upgrade|checkout|subscribe|buy|pay|billing` control returned `[]`.

**Recommended fix.** Pass `email` into `PlanTeaser` and swap the CTA for an upgrade
action when signed in (hiding the section entirely for Premium users). Make the
`UsagePanel` upgrade text a real button. Both depend on finding #3 — until checkout
exists, point them at whatever the interim path is rather than leaving inert text.

---

## 🟡 13 — 33-brand watch list, with silent misses presented as clean

**Area:** `BrandConstants.BRAND_DOMAINS`

Detection is entirely relative to 33 hard-coded domains. Convincing typosquats of
anything outside the list score **0** — no signal, no caveat:

| Input | Score |
|---|---|
| `http://santand3r.com` | 0 |
| `http://barc1ays.com` | 0 |
| `http://revo1ut.com` | 0 |
| `http://wh4tsapp.com` | 0 |
| `http://bookinq.com` | 0 |
| `http://steamcornmunity.com` | 0 |

The list is also US-centric (`chase`, `wellsfargo`, `usbank`, `irs.gov`) while the
product is priced in EUR, so major European banks and services are unwatched.

The real risk is the presentation, not the coverage: a 0 renders as **"Low risk —
Nothing here crosses the threshold for concern"**, which reads as a clean bill of health
rather than "no brand on our list resembles this". For a security tool, a confident
false negative is worse than an admitted gap.

**Recommended fix.** Two parts. (a) Expand the list, ideally loading it from a resource
file the way `disposable-email-domains.txt` already is, so it can be refreshed without a
rebuild — that pattern and its CI refresh workflow are already established. Add major
EU banks and the commonly-phished consumer brands above. (b) Qualify the verdict: when a
scan produces no failing signals, say what was actually checked — *"No signals fired.
This domain doesn't resemble any of the N brands MailSentinel watches; that isn't the
same as it being safe."* Honest scope is more useful than an unqualified green light.

---

## 🟡 14 — Email link extraction is unbounded

**Area:** `LinkAnalysisService.extractLinks` vs `ScoringService.splitUrls`

URL scans cap work at `MAX_URLS_PER_SCAN = 50`. Email scans apply no cap at all — every
anchor in the body is extracted and each runs `analyzeDomain`, which loops all 33 brands
across five techniques including Levenshtein.

Measured: a 1.4 MB email with 20,000 unique lookalike anchors → HTTP 200 in **1.17 s** of
near-pure CPU on one request thread, returning a 1.4 MB response (every hit is
concatenated into the aggregated `detail` strings). On an unauthenticated,
unrate-limited, CORS-open endpoint, a handful of concurrent requests saturates the pool.

**Recommended fix.** Apply the same cap on the email path — `.limit(MAX_URLS_PER_SCAN)`
in `extractLinks` after deduplication — and promote the constant somewhere both services
share. Truncate the aggregated `detail` to the first N hits with an "…and 19,950 more"
suffix so the response stays bounded regardless. Also set an explicit
`spring.codec.max-in-memory-size` / `server.tomcat.max-http-post-size` so request bodies
are capped deliberately rather than by whatever the container happens to default to.

---

## 🟡 15 — DNS lookups are uncached and slow, with no client timeout

**Area:** `DnsCheckService.getTxtRecords` / `api.js`

Each call constructs a fresh `SimpleResolver` and explicitly disables caching
(`lookup.setCache(null)`), with a 3 s timeout and two lookups (SPF + DMARC) per email
scan, run synchronously on the request thread.

Measured on a cold domain: **9.06 s** for the first scan (retries × timeout), then
~0.13 s once the OS resolver had cached it. The frontend's `fetch` in `scanContent` has
no timeout or `AbortController`, so the user sits on a "Scanning..." button for nine
seconds with no feedback and no way to cancel.

Separately, `github.com`'s TXT set is large enough that the lookup consistently failed
here (6/6 attempts) and degraded to *"Could not complete an SPF lookup"* — correct
fail-safe behaviour, but it means the SPF signal is quietly unavailable for exactly the
large senders it matters most for.

**Recommended fix.** Re-enable dnsjava's cache (drop `setCache(null)`) or wrap lookups in
a small Caffeine cache keyed on name+type with a TTL honouring the record's own, and hold
one `SimpleResolver` as a field rather than constructing per call. Run the SPF and DMARC
lookups concurrently and bound the pair with a single overall budget (~2 s), since they
are independent. On the client, add an `AbortController` with a timeout to `scanContent`
and show elapsed progress. Ensure TCP fallback is exercised for truncated responses so
large TXT sets resolve.

---

## ⚪ 16 — No Terms, Privacy Policy, or contact information

A DOM sweep of the full page found **zero `<a>` elements** — no Terms of Service, no
Privacy Policy, no contact, no company identification. Meanwhile the app prices in EUR
(implying EU consumers), stores personal data (email, password hash, usage records), and
— on the Premium path — transmits pasted email content to a third-party AI provider.

The footer asserts *"Anonymous scans are never logged, stored, or shown to anyone"*, a
privacy commitment with no policy document behind it. The README documents the trust
boundary carefully; none of that reaches the user.

**Recommended fix.** Add a footer with Privacy Policy, Terms, and contact links before
any paid launch. The Privacy Policy must disclose the third-party AI processing that
`AiAnalysisService` performs for Premium and BYOK users, name the provider, and state
retention. The README's "Privacy: what is and isn't persisted" section is already an
excellent first draft of it.

---

## ⚪ 17 — Scan results persist after logout

`App.handleLogout` clears `email`, `usage`, and part of `aiKeyStatus`, but not `result`.
Verified: after logging out, the full scan verdict and every check detail remain on
screen. Minor, but the results panel can contain content pasted from a private mailbox,
and logout is the moment a user expects that to be gone — particularly on a shared
machine.

**Recommended fix.** Add `setResult(null)` and `setError(null)` to `handleLogout`.

---

## ⚪ 18 — No CSP; session token in `localStorage`

Spring Security's defaults are present and correct (`X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Cache-Control: no-store`), and no actuator or
H2 console is exposed. But there is **no `Content-Security-Policy`**, and `auth.js`
keeps the bearer token in `localStorage`, which is readable by any injected script. The
combination means a single XSS anywhere yields a 30-day token (`token-ttl=P30D`).

**Recommended fix.** Add a CSP via
`http.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(...)))` — the app
serves its own bundled assets from one origin, so a strict
`default-src 'self'; frame-ancestors 'none'; object-src 'none'` is achievable (allow
`https://accounts.google.com` for the sign-in script). Longer term, moving the session to
a `HttpOnly; Secure; SameSite=Lax` cookie removes the token from JavaScript's reach
entirely; that requires CSRF protection, currently disabled — reasonable while auth is a
bearer header, but it has to change together.

---

## ⚪ 19 — `plan` returned by the auth API is discarded

`AuthController` returns `plan` on register, login, and Google sign-in, but
`App.handleAuthenticated` calls `setSession(data.token, data.email)` and drops it. The
UI then re-fetches the same fact from `/api/usage/me`. Harmless today, but it is a
field the server maintains and no client reads — either use it to render the plan badge
immediately (avoiding a round trip and a flash of the wrong state) or remove it from
`AuthResponse`.

---

## Things that were tested and work correctly

Worth recording, so the report isn't read as uniformly negative:

- **Detection true positives are strong.** `paypa1.com`, `rnicrosoft.com`, `faceb00k.com`,
  `arnazon.com`, `app1e.com`, punycode `xn--pple-43d.com`, subdomain confusion
  `paypal.com.verify-account.ru`, and raw-IP hosts were all correctly flagged with
  accurate, readable explanations. The four sample `.eml` files score exactly as
  documented (0 / 21 / 100 / 100).
- **Admin authorization is correct.** `/api/admin/**` returns 401 with no token, 403 with
  a normal user's token, and 200 only for an `ADMIN_EMAILS` account. Defaults are
  fail-closed.
- **Session lifecycle is sound.** Tokens are hashed at rest, logout revokes server-side,
  and a revoked or garbage token is rejected with 401 rather than silently treated as
  logged out.
- **Credential errors don't leak account existence** — wrong password and unknown user
  both return an identical `INVALID_CREDENTIALS`.
- **Disposable-email blocking works**, including the parent-suffix walk
  (`inbox.mailinator.com` caught by the `mailinator.com` entry), returning 422 with an
  actionable message.
- **Scan input validation is thorough** — empty, whitespace, null, missing type, wrong
  type, wrong case, malformed JSON, and array bodies all return 400.
- **AI failure handling is correct.** A provider error returns `AI_PROVIDER_ERROR` and the
  usage counter is properly refunded (verified: 0 scans charged after a failed analysis).
- **Idempotency works** — reusing a key with different content correctly returns 409.
- **The core UI flows work**: signup, auto-detection of URL vs email input, scan,
  results rendering, and logout all behaved correctly in the browser.
- `./mvnw test` passes in full.

---

## Suggested order of work

1. **#1** — the false-positive cascade is actively harmful and is a small, contained fix.
2. **#2** — SSRF; ship the egress guard before any public deployment.
3. **#4 + #8** — one validation change closes both.
4. **#6 + #7** — abuse controls and account recovery; both need doing before real users.
5. **#3** — decide: build checkout, or stop advertising prices. Don't leave it as is.
6. **#5 + #13** — the detection-coverage work, best done together.
7. The remaining medium and low items as cleanup.
