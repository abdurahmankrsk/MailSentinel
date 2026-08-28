---
name: MailSentinel
description: A dark operations-console UI for reading hostile mail, where cyan is the only brand voice and verdict colors carry all severity.
colors:
  ground: "#0a0e14"
  surface: "#111823"
  raised: "#17202c"
  line: "#1e2a38"
  line-strong: "#2b3a4b"
  ink: "#e4ebf5"
  ink-2: "#94a5bb"
  ink-3: "#6f8199"
  accent: "#38bdf8"
  accent-deep: "#0ea5e9"
  accent-wash: "rgba(56, 189, 248, 0.12)"
  pass: "#45d483"
  pass-wash: "rgba(69, 212, 131, 0.11)"
  caution: "#f5b544"
  caution-wash: "rgba(245, 181, 68, 0.12)"
  threat: "#ff6b6b"
  threat-wash: "rgba(255, 107, 107, 0.11)"
typography:
  display:
    fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Consolas, monospace"
    fontSize: "3.1rem"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.04em"
  title:
    fontFamily: "Archivo, 'Segoe UI', system-ui, -apple-system, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 700
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Archivo, 'Segoe UI', system-ui, -apple-system, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "Archivo, 'Segoe UI', system-ui, -apple-system, sans-serif"
    fontSize: "0.78rem"
    fontWeight: 600
    letterSpacing: "0.13em"
  data:
    fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Consolas, monospace"
    fontSize: "0.8rem"
    fontWeight: 400
    lineHeight: 1.7
rounded:
  sm: "7px"
  md: "10px"
  pill: "99px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "#04121c"
    rounded: "{rounded.sm}"
    padding: "0.6rem 1.6rem"
  button-primary-hover:
    backgroundColor: "#7dd3fc"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "0.45rem 0.95rem"
  button-secondary-hover:
    textColor: "{colors.accent}"
  input-text:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "1rem 1.1rem"
---

# Design System: MailSentinel

## Overview

**Creative North Star: "The Operations Console"**

MailSentinel reads as a tool for reading hostile mail, used at a desk, next to a terminal — not a consumer security app trying to reassure you with friendly color. The world is near-black, quiet, and instrumented: a faint hairline grid sits fixed behind the content like the surface a console is read against, and the interface holds still except for one deliberate settling animation when results arrive. Cyan is the single brand and interactive voice throughout; the three verdict hues (pass/caution/threat) are held strictly apart from it so a scan's severity is never confused with the chrome around it. Monospace type is reserved for things that are actually data — scores, weights, domains, raw message source — while the UI's own labels and controls stay in a plain sans, sentence case, never dressed up as data they aren't.

The system has explicitly rejected: uppercase-monospace UI labels (tried and reverted — "the interface wearing a lab coat"), decorative shadows or lift, and any color use that isn't either "this is interactive" (cyan) or "this is a verdict" (green/amber/red).

**Key Characteristics:**
- Near-black terminal surfaces with a single cyan signal for everything interactive.
- Verdict severity (pass/caution/threat) is a strictly separate palette from brand/accent.
- Monospace is a data marker, not a stylistic default — it appears only where the content is literally data.
- Flat by default; one authored motion moment (results settling in), everything else is instant or a simple transition.
- Precise, instrumented component language: hairline borders, quiet at rest, color reserved for state.

## Colors

Near-black terminal, single cyan signal: almost the entire surface is desaturated near-black and slate-blue neutrals, with cyan as the only accent hue in the whole system, and the pass/caution/threat trio kept visually and semantically separate from it.

### Primary
- **Signal Cyan** (`#38bdf8`, accent): every interactive and brand moment — links, focus rings, the primary button, active toggle states, the caret color in inputs, the wordmark icon.
- **Deep Signal** (`#0ea5e9`, accent-deep): the resting/border state of interactive elements before hover (input focus borders, active toggle borders), one step darker than Signal Cyan so hover has somewhere to go.
- **Signal Wash** (`rgba(56, 189, 248, 0.12)`, accent-wash): tint fill behind active/selected chrome (active type-toggle, premium plan badge, AI-summary panel) — never used for body text.

### Neutral
- **Ground** (`#0a0e14`): the page background, near-black with a hint of blue.
- **Surface** (`#111823`): raised panels one step above ground — cards, verdicts, the input field, modals.
- **Raised** (`#17202c`): the next step up — meter track background, plan-free badge.
- **Line** (`#1e2a38`): default hairline border between surface and its content.
- **Line Strong** (`#2b3a4b`): emphasized borders — nav buttons, modal edges, scrollbar thumb.
- **Ink** (`#e4ebf5`): primary text.
- **Ink Dim** (`#94a5bb`, ink-2): secondary text — taglines, hints, verdict notes.
- **Ink Faint** (`#6f8199`, ink-3): tertiary text — placeholders, disabled states, auto-detect hints.

### Verdict Colors (severity, held apart from brand)
- **Pass Green** (`#45d483`): low-risk score, band label, meter fill, passing check icons.
- **Caution Amber** (`#f5b544`): medium-risk score, band label, meter fill.
- **Threat Red** (`#ff6b6b`): high-risk score, band label, meter fill, failing check border/icon/weight, error banners.

### Named Rules
**The Separate Severity Rule.** Verdict colors (pass/caution/threat) never do double duty as brand or interactive color, and cyan never appears in a verdict context. A user must be able to tell "this is clickable" from "this is dangerous" from color alone.

**The Data-Is-Mono Rule.** Monospace (`--font-data`) is used only for values that are literally data being reported — scores, weights, domains, raw detail text — never for UI labels, buttons, or prose, even when a "technical" look is tempting.

## Typography

**Display Font:** JetBrains Mono (with ui-monospace, SFMono-Regular, Consolas fallback) — used only for the score itself.
**Body/UI Font:** Archivo (with Segoe UI, system-ui, -apple-system fallback).
**Label/Mono Font:** JetBrains Mono, same as Display — shared between the hero score and all inline data.

**Character:** A geometric grotesque (Archivo) carries every UI label and sentence in plain sentence case, while JetBrains Mono is pulled out only when the content is a number, weight, or literal string worth reading character-by-character — the pairing signals "read this like prose" vs. "read this like a log line."

### Hierarchy
- **Display** (700, 2.1–3.1rem, line-height 1–1.15, tight negative tracking, font-data for the verdict score / font-ui for prose headlines): the verdict score and page/section headlines — the thing the eye lands on first.
- **Title** (700, 1.3–1.5rem, `-0.025em` tracking, font-ui): the app wordmark and sub-section headings.
- **Body** (400, 0.85–0.95rem (15px base), line-height 1.6, font-ui): taglines, hints, verdict notes, check names, section ledes.
- **Label** (600, 0.66–0.78rem, font-ui): two registers by context — uppercase with tracking up to `0.13em` for section/status labels (the signal-ledger heading, verdict band); sentence case with light (`~0.01em`) tracking for named things a user reads as a name, not a state (plan badges).
- **Data** (400, 0.68–0.8rem, line-height 1.6–1.7, tabular-nums where numeric, font-data): scores, weights, domains, raw check detail, prices, account email.

**The Tuned-Step Rule.** This is a fine-grained, per-component-tuned scale, not a small fixed step system: literal sizes range continuously from `0.66rem` to `3.1rem` across roughly two dozen deliberate steps, each sized to its specific control rather than snapped to one of five sizes. The five roles above are anchors for the extremes and recurring moments, not an exhaustive enumeration — a new literal size is fine when it sits inside this range and reads as a deliberate, singular step for its own element, not a duplicate of a step that already exists nearby.

### Named Rules
**The Sentence-Case UI Rule.** Interactive labels (toggles, buttons, hints) are plain sentence case in the UI font — uppercase-tracked type is reserved for section labels and verdict bands, never for a thing the user clicks.

## Layout

A single-column, centered console capped at `760px` (`.app`), with generous top padding (`3.5rem`) that narrows on small screens (`2.25rem`). There is no sidebar, grid, or multi-column composition anywhere — every screen is a vertical stack: header → optional usage strip → input panel → results (verdict meter, then signal ledger). Section rhythm is loose (`1.75–3rem` between major blocks) while in-component rhythm is tight (`0.4–0.85rem`), so the page reads as a small number of dense instruments rather than many loosely-related fragments. A single breakpoint at `560px` tightens padding, shrinks the display score, and lets the auth modal dock to the bottom edge instead of centering.

## Elevation & Depth

Flat by default — there is exactly one `box-shadow` in the entire system (the auth modal's `0 18px 48px rgba(0,0,0,0.55)`, justified because it's the one element that must visually detach from the page). Depth everywhere else is conveyed through tonal layering (`ground` → `surface` → `raised`) and hairline borders, not shadow. The one fixed background layer — a barely-visible cyan grid at `2.8%` opacity — sits behind everything as the "desk" the console is read against; it never moves, so scrolling results over it reinforces that it's environment, not content.

### Shadow Vocabulary
- **Modal Lift** (`box-shadow: 0 18px 48px rgba(0, 0, 0, 0.55)`): reserved exclusively for the auth dialog, the only surface meant to feel detached from the page.

### Named Rules
**The Flat-By-Default Rule.** Surfaces are flat at rest, distinguished only by tonal step (ground/surface/raised) and a hairline border. Shadow is earned only by a surface that must visually float above the whole page, not by ordinary cards or panels.

## Shapes

Two radii cover the whole system: `7px` (`--radius-sm`) for controls — buttons, toggles, badges, list items — and `10px` (`--radius`) for containers — the verdict card, the input field, the modal. Fully round (`99px`) is reserved for pill/track shapes: the meter track and the scrollbar thumb. Borders are hairline (`1px`) everywhere and always one of the neutral line tokens, never black, and corners are never sharp (`0`) or heavily rounded (`>10px`) — the console reads as machined, not soft.

## Components

Precise and instrumented: every control is flat and hairline-bordered at rest, and color is spent only to say "this is interactive" (cyan) or "this is a verdict" (pass/caution/threat) — never as decoration.

### Buttons
- **Shape:** `7px` radius (`--radius-sm`), 1px border throughout every variant.
- **Primary** (`.scan-button`, `.nav-button-primary`): solid `--accent` fill, near-black text (`#04121c`) for contrast against cyan, `600` weight.
- **Hover:** primary lightens to `#7dd3fc`; disabled falls back to a bordered, transparent, muted state rather than dimming the filled color.
- **Secondary/Ghost** (`.nav-button`, `.type-toggle button`): transparent background, neutral border and text at rest; hover shifts border and text to cyan rather than filling the background — the fill is reserved for the one active/primary action per view.
- **Link** (`.link-button`): no border or background at all, cyan text, used only for low-emphasis actions like "Log out."

### Cards / Containers
- **Corner Style:** `10px` radius (`--radius`).
- **Background:** `--surface`, one step above `--ground`.
- **Shadow Strategy:** none — see Elevation & Depth; depth comes from the ground/surface contrast plus the 1px `--line` border.
- **Border:** `1px solid var(--line)` at rest; a failing check item (`.check-fail`) instead gets a tinted threat-red border and a subtle left-to-right threat wash gradient, so a failure reads as a distinct object in the list without needing an icon alone to carry it.

### Inputs / Fields
- **Style:** `--surface` (textarea) or `--ground` (modal form inputs, one step darker to sit inside an already-raised dialog) background, `1px solid var(--line)` border, `10px`/`7px` radius, monospace type for the scan textarea specifically (it holds raw email/URL content — data), sans for auth form fields (they hold names/passwords — not data being analyzed).
- **Focus:** border shifts to `--accent-deep`, caret color set explicitly to `--accent`; no glow or outline duplication.

### Navigation
- **Style:** the header is a single flex row — wordmark on the left, auth controls on the right, nothing else — so it stays a single line at every width instead of wrapping into a second row. No persistent nav chrome beyond the header — this is a single-scroll page, not a multi-page app; wayfinding is scroll position, not a nav bar.

### Hero
A headline plus a one-line promise, then the real scan input immediately beneath it — no illustrative or staged device stands in front of the actual tool. The page proves the mechanism by leading with it, not by demonstrating a synthetic stand-in first.

### Plan Badge
A small pill naming a plan (`Free` / `Premium` / `Enterprise`), sans body font at `600` weight, sentence case, light tracking, `--radius-sm` corners — deliberately not monospace, uppercase, or heavily tracked, which reads as a generic status-chip pattern rather than a considered label. Color escalates within the one accent hue rather than introducing a new one per tier: neutral (`--raised`/ink) for Free, a cyan wash for Premium, a solid cyan fill for Enterprise — weight signals tier, not a rainbow of plan colors.

### Tier Card
A severity-tinted variant of the flat bordered container: `1px` border tinted to the tier's hue plus the same left-to-right hue-wash gradient `.check-fail` uses (never a thick colored `border-left`, which this system does not use anywhere), so a solo-red / strong / medium tier reads at a glance the same way a failing check does.

### Signal Ledger (signature component)
The checks list is the product's core evidence surface: each check is a row with a small icon (pass/fail), a sentence-case name, a monospace weight figure, and monospace detail text explaining why it passed or failed. A failing row is visually distinct via the threat-tinted border/gradient described above. The list header pairs an uppercase tracked label ("Signal Ledger" or similar) with a monospace count (`N failed / M total`) so the ledger metaphor — an itemized, auditable account — is reinforced by typography choice alone, not just copy.

### Verdict Meter (signature component)
A single number (display-scale monospace) plus an uppercase band label share one baseline-aligned row, then a horizontal meter below: threshold ticks (drawn above the track, not through it, so they never fight the fill color) at the 30/60 score boundaries, a track that fills via `transform: scaleX()` (never `width`, so the pill shape survives scaling and the animation stays compositor-only), colored by the active verdict band via a `data-band` attribute on the parent.

## Do's and Don'ts

### Do:
- **Do** keep cyan as the only interactive/brand hue; never introduce a second accent color.
- **Do** keep verdict colors (pass/caution/threat) exclusive to severity — never reuse them for chrome, brand, or decoration.
- **Do** reserve monospace for literal data (scores, weights, domains, raw content, counts); keep UI labels and prose in the sans body font.
- **Do** build depth from tonal steps (ground/surface/raised) and hairline borders before reaching for shadow.
- **Do** animate transform/opacity only (the settle-in reveal, the meter fill), respecting `prefers-reduced-motion: no-preference` guards already in place.
- **Do** keep UI control labels in sentence case, even when the surrounding data is uppercase-tracked.
- **Do** stay single-column, top to bottom, at every width — sections stack; they never sit side by side in a grid or sidebar.
- **Do** mark severity or state with a tinted border plus a same-direction hue-wash gradient (the `.check-fail` pattern), the system's one device for "this row means something different."

### Don't:
- **Don't** add a second brand accent color or let a verdict hue leak into interactive/brand contexts.
- **Don't** set uppercase, tracked, monospace styling on anything the user clicks or types into — that treatment is reserved for section labels and data, and was explicitly reverted once already for reading as "the interface wearing a lab coat."
- **Don't** add shadows to ordinary cards, panels, or list rows — shadow is earned only by content that must detach from the whole page (currently just the auth modal).
- **Don't** widen the meter fill via `width`; scale via `transform` so the track's pill radius survives and the animation stays cheap.
- **Don't** invent testimonials, benchmarks, or customer proof in any surface — none exist yet (see PRODUCT.md).
- **Don't** use a `border-left`/`border-right` heavier than the standard 1px hairline to mark state or severity — tint the full border plus a hue-wash gradient instead.
- **Don't** introduce a sidebar, side-by-side grid, or multi-column composition anywhere on this surface — depth of content is expressed by scrolling further, not by adding a second column.
