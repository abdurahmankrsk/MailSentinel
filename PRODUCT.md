# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Primary users are anyone who received a suspicious email or link and wants to know whether to trust it — ranging from non-technical people who just want a clear verdict, to technical/security-curious users who want the full signal breakdown. The product is designed for both, but its core differentiator (full transparency of every check) means even non-technical users are shown the breakdown, not just a score.

## Product Purpose

MailSentinel is a phishing-detection tool: paste a raw email or a URL and it returns a 0–100 risk score with a full, independently-explainable breakdown of exactly which signals fired, which passed, and why. The point is that a score you can't justify isn't much better than a guess — nothing about the scoring is a black box.

## Positioning

Full explainability of every check (not just a score) is the mechanism a neighboring "AI-powered" or opaque phishing scanner could not truthfully copy without also exposing its own logic. Detection combines deterministic, auditable signal checks (DNS/SPF/DMARC verification, lookalike-domain detection via four independent techniques, link/anchor-mismatch analysis, IP-literal hosts) with optional AI-assisted analysis for PREMIUM users that only ever contributes additional weighted, capped findings — it never sets the score directly, specifically to resist prompt injection from attacker-controlled content.

## Operating Context

- Anonymous/FREE scanning is fully stateless: request content is processed in memory only, never logged or persisted, and results live only in frontend component state until the tab is closed or refreshed.
- A PREMIUM tier (built, not yet live — see below) adds accounts (email+password or Google OAuth), AI-powered analysis (Groq for testing, Gemini 2.5 Flash-Lite planned for production) with a monthly scan allowance, and usage tracking. Only usage counts and idempotency hashes are stored — never scan content.
- Single Java 21 / Spring Boot 3 backend with an embedded React/Vite frontend, packaged as one self-contained JAR (`app/`). API surface is a single `POST /api/scan` endpoint plus auth/admin endpoints for the premium tier.
- Subscription/AI feature status: **built but not yet live-verified end-to-end**. Implementation exists and is tested against H2 only; still needs a real Supabase connection string and a Groq API key before it can run against real infrastructure. Treat this as active but pre-launch — do not assume it is in front of real users yet.

## Capabilities and Constraints

- Detection techniques: Authentication-Results header parsing (claimed, not trusted alone), live SPF/DMARC DNS verification, lookalike-domain detection (edit distance, character substitution, homoglyphs, TLD swaps, display-name impersonation), link/anchor-text-vs-href mismatch analysis, URL-shortener detection, IP-literal-as-hostname detection.
- Scoring is a capped sum of tiered fixed weights per check (no ML curve for the deterministic path) — see README for the full tier table.
- CORS is intentionally wide open on `/api/scan` (no cookies/sessions/credentials in that path), to allow future browser-extension use.
- Google Sign-In is present but self-disables (hides its button) when `GOOGLE_CLIENT_ID` isn't configured — there is no button that can only fail.
- No Stripe/payment integration yet; premium activation currently goes through a config-driven admin-email allowlist and an admin endpoint, standing in for a future payment webhook.

## Brand Commitments

- Name: **MailSentinel**.
- Voice: serious, credible, trustworthy — a security tool, not a playful consumer app. This applies to copy, tone, and any visual direction chosen later.

## Evidence on Hand

- `test_samples/` contains real `.eml` fixtures (clean, borderline newsletter, homoglyph-only phishing, multi-signal phishing) usable as realistic example content — do not fabricate additional testimonials, benchmarks, or customer claims.
- No customer testimonials, case studies, press, or third-party proof exist; do not invent any.

## Product Principles

1. Explainability over black-box scoring — every check result (pass or fail) is always shown, never hidden behind a single number.
2. Weak signals stack, strong signals spike — scoring tiers are calibrated so no single ambiguous signal (e.g. missing DMARC alone) triggers a high-risk verdict, but one solo-red signal (e.g. a homoglyph domain) can.
3. Privacy by default — the free, anonymous path never persists submitted content; expansions of that trust boundary (AI analysis for PREMIUM) are explicit, scoped, and disclosed.
4. AI assists, never decides alone — AI-derived findings are capped, weighted, and appended alongside deterministic checks rather than allowed to set the score directly, specifically to resist prompt injection from attacker-controlled input.
5. Serious tone throughout — this is a security tool; credibility and clarity outrank playfulness.

## Accessibility & Inclusion

No product-specific accessibility requirement has been established yet.
