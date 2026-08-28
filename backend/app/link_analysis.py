"""Check 4: extract every link from an email body and analyze it.

Findings are aggregated into one CheckResult per technique rather than
one per link, so an email with many links to the same bad domain isn't
scored any higher than one with a single bad link -- it's the presence
of the pattern that matters, not the count.

HTML bodies are parsed with lxml so we have the real anchor text
separately from the href target, which is what makes the "text says
paypal.com, links elsewhere" trick detectable at all. Plaintext bodies
have no such distinction -- a link is just the text -- so anchor-mismatch
never fires for them, which is expected, not a gap.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from lxml import html as lxml_html

from .constants import SHORTENER_DOMAINS, WEIGHTS
from .lookalike import analyze_domain
from .models import CheckResult
from .url_utils import extract_hostname, is_ip_literal, registrable_domain

_URL_RE = re.compile(r"https?://[^\s<>\"')\]]+", re.IGNORECASE)
_URL_LIKE_RE = re.compile(r"^(https?://)?[\w-]+(\.[\w-]+)+", re.IGNORECASE)


@dataclass
class ExtractedLink:
    href: str
    anchor_text: str | None = None  # None for plaintext links (no separate anchor text)


def _extract_from_html(html_body: str) -> list[ExtractedLink]:
    links: list[ExtractedLink] = []
    try:
        tree = lxml_html.fromstring(html_body)
    except Exception:
        return links
    for anchor in tree.iter("a"):
        href = anchor.get("href")
        if not href or not href.lower().startswith(("http://", "https://")):
            continue
        text = (anchor.text_content() or "").strip()
        links.append(ExtractedLink(href=href, anchor_text=text or None))
    return links


def _extract_from_text(text_body: str) -> list[ExtractedLink]:
    return [ExtractedLink(href=match.group(0)) for match in _URL_RE.finditer(text_body)]


def extract_links(text_body: str | None, html_body: str | None) -> list[ExtractedLink]:
    source_links = _extract_from_html(html_body) if html_body else []
    if not source_links and text_body:
        source_links = _extract_from_text(text_body)

    links: list[ExtractedLink] = []
    seen: set[str] = set()
    for link in source_links:
        if link.href not in seen:
            seen.add(link.href)
            links.append(link)
    return links


def _looks_like_url_or_domain(text: str) -> bool:
    return bool(_URL_LIKE_RE.match(text.strip()))


def analyze_links(links: list[ExtractedLink]) -> list[CheckResult]:
    lookalike_hits: list[str] = []
    shortener_hits: list[str] = []
    mismatch_hits: list[str] = []
    ip_host_hits: list[str] = []

    for link in links:
        hostname = extract_hostname(link.href)
        if not hostname:
            continue
        domain = registrable_domain(hostname)

        for finding in analyze_domain(hostname):
            lookalike_hits.append(f"{link.href} ({finding.detail})")

        if domain in SHORTENER_DOMAINS or hostname.lower() in SHORTENER_DOMAINS:
            shortener_hits.append(link.href)

        if is_ip_literal(hostname):
            ip_host_hits.append(link.href)

        if link.anchor_text and _looks_like_url_or_domain(link.anchor_text):
            anchor_host = extract_hostname(link.anchor_text)
            if anchor_host and registrable_domain(anchor_host) != domain:
                mismatch_hits.append(
                    f"anchor text '{link.anchor_text}' actually points to {link.href}"
                )

    return [
        CheckResult(
            name="Suspicious links in body",
            passed=not lookalike_hits,
            weight=WEIGHTS["link_lookalike"],
            detail="; ".join(lookalike_hits) if lookalike_hits
            else "No lookalike brand domains found among links in the body",
        ),
        CheckResult(
            name="URL shortener present",
            passed=not shortener_hits,
            weight=WEIGHTS["url_shortener"],
            detail=(f"Shortened link(s) found: {', '.join(shortener_hits)}" if shortener_hits
                    else "No known URL-shortener domains found in links"),
        ),
        CheckResult(
            name="Anchor text / link destination mismatch",
            passed=not mismatch_hits,
            weight=WEIGHTS["anchor_mismatch"],
            detail="; ".join(mismatch_hits) if mismatch_hits
            else "No anchor text found that names a different domain than its link target",
        ),
        CheckResult(
            name="Raw IP address as link host",
            passed=not ip_host_hits,
            weight=WEIGHTS["ip_hostname"],
            detail=(f"Link(s) use a raw IP address instead of a domain: {', '.join(ip_host_hits)}"
                    if ip_host_hits else "No links use a raw IP address as the host"),
        ),
    ]
