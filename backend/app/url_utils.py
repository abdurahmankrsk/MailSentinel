"""Shared domain/URL parsing helpers.

Uses tldextract for registrable-domain parsing so subdomains and
multi-part suffixes are handled correctly instead of a naive split('.') --
notably, it correctly identifies the real registrable domain in a
subdomain-confusion attack like "paypal.com.verify-account.ru" as
verify-account.ru, not paypal.com.
"""
from __future__ import annotations

from urllib.parse import urlsplit

import tldextract

# Use only the snapshot bundled with the package -- no live fetch of the
# public suffix list at runtime, so behavior is deterministic and offline.
_extractor = tldextract.TLDExtract(suffix_list_urls=())


def normalize_url(raw: str) -> str:
    """Ensure a URL has a scheme so urlsplit parses the host correctly."""
    raw = raw.strip()
    if "://" not in raw:
        raw = f"http://{raw}"
    return raw


def extract_hostname(raw_url: str) -> str | None:
    """Pull just the hostname out of a URL (or bare domain) string."""
    try:
        parsed = urlsplit(normalize_url(raw_url))
    except ValueError:
        return None
    return parsed.hostname


def registrable_domain(hostname: str) -> str:
    """Return the eTLD+1, e.g. mail.google.com -> google.com."""
    parts = _extractor(hostname)
    if parts.domain and parts.suffix:
        return f"{parts.domain}.{parts.suffix}".lower()
    return hostname.lower()
