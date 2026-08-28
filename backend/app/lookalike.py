"""Check 3: lookalike / typosquat detection against the brands.py target
list. Four independent techniques, each catching a different disguise:

  - edit distance:      paypa1-secure.com is 1 edit from paypal.com
  - char substitution:  digits/letter-runs standing in for similar-looking
                         letters (0->o, 1->l, rn->m), collapsed before
                         comparing -- several substitutions at once can
                         push the raw edit distance past the distance-2
                         threshold above while still reading as an obvious
                         fake to a human eye
  - homoglyphs:         non-Latin letters that render identically to Latin
                         ones (at minimum Cyrillic а/е/о/р/с), including
                         domains that arrive punycode-encoded (xn--...)
  - TLD swap:            the exact brand name, wrong top-level domain

It's fine, and expected, for more than one technique to fire on the same
obviously-fake domain -- that overlap is a feature, not a bug: it's what
real independent detectors do.
"""
from __future__ import annotations

import unicodedata
from dataclasses import dataclass

from rapidfuzz.distance import Levenshtein

from .brands import BRAND_DOMAINS
from .url_utils import registrable_domain

_SUBSTITUTIONS = (
    ("rn", "m"),
    ("0", "o"),
    ("1", "l"),
)

_HOMOGLYPHS = {
    # Cyrillic -> visually identical Latin letter (at minimum the 5 required
    # by spec: a e o p c; a few more common confusables included as well).
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c",
    "х": "x", "у": "y", "і": "i", "ѕ": "s", "ј": "j",
}

_MAX_EDIT_DISTANCE = 2

_BRAND_SET = set(BRAND_DOMAINS)


@dataclass
class LookalikeFinding:
    technique: str  # "edit_distance" | "char_substitution" | "homoglyph" | "tld_swap"
    matched_brand: str
    detail: str


def _decode_punycode(hostname: str) -> str:
    """Real homoglyph attacks travel as punycode (xn--...) on the wire and
    are only rendered as Unicode by the mail/browser client. Decode it so
    the checks below see what a victim would actually see."""
    try:
        return hostname.encode("ascii").decode("idna")
    except (UnicodeError, ValueError):
        return hostname


def _closest_brand_by_distance(candidate: str) -> tuple[str, int] | None:
    best = None
    for brand in BRAND_DOMAINS:
        if candidate == brand:
            continue
        dist = Levenshtein.distance(candidate, brand)
        if best is None or dist < best[1]:
            best = (brand, dist)
    return best


def check_edit_distance(domain: str) -> LookalikeFinding | None:
    if domain in _BRAND_SET:
        return None
    best = _closest_brand_by_distance(domain)
    if best and best[1] <= _MAX_EDIT_DISTANCE:
        brand, dist = best
        plural = "s" if dist != 1 else ""
        return LookalikeFinding(
            "edit_distance", brand,
            f"Domain {domain} is {dist} character{plural} from {brand}",
        )
    return None


def check_char_substitution(domain: str) -> LookalikeFinding | None:
    if domain in _BRAND_SET:
        return None
    normalized = domain
    for pattern, replacement in _SUBSTITUTIONS:
        normalized = normalized.replace(pattern, replacement)
    if normalized == domain:
        return None  # no substitution pattern present at all

    if normalized in _BRAND_SET:
        return LookalikeFinding(
            "char_substitution", normalized,
            f"Domain {domain} normalizes to {normalized} via character "
            "substitution (0/o, 1/l, rn/m), an exact match for a known brand",
        )
    best = _closest_brand_by_distance(normalized)
    if best and best[1] <= _MAX_EDIT_DISTANCE:
        brand, _dist = best
        return LookalikeFinding(
            "char_substitution", brand,
            f"Domain {domain} normalizes to '{normalized}' via character "
            f"substitution (0/o, 1/l, rn/m), closely matching {brand}",
        )
    return None


def _script_of(char: str) -> str | None:
    if not char.isalpha():
        return None
    name = unicodedata.name(char, "")
    for script in ("LATIN", "CYRILLIC", "GREEK"):
        if script in name:
            return script
    return "OTHER"


def check_homoglyph(domain: str) -> LookalikeFinding | None:
    domain = _decode_punycode(domain)
    if domain in _BRAND_SET:
        return None

    scripts = {s for s in (_script_of(c) for c in domain) if s}
    mixed_script = len(scripts) > 1

    transliterated = "".join(_HOMOGLYPHS.get(c, c) for c in domain)
    homoglyph_match = None
    if transliterated != domain:
        if transliterated in _BRAND_SET:
            homoglyph_match = transliterated
        else:
            best = _closest_brand_by_distance(transliterated)
            if best and best[1] <= _MAX_EDIT_DISTANCE:
                homoglyph_match = best[0]

    if homoglyph_match:
        return LookalikeFinding(
            "homoglyph", homoglyph_match,
            f"Domain {domain} uses non-Latin homoglyph characters that "
            f"visually imitate {homoglyph_match}",
        )
    if mixed_script:
        return LookalikeFinding(
            "homoglyph", "-",
            f"Domain {domain} mixes Unicode scripts ({', '.join(sorted(scripts))}) "
            "within a single hostname, a common homoglyph-spoofing pattern",
        )
    return None


def check_tld_swap(domain: str) -> LookalikeFinding | None:
    if domain in _BRAND_SET:
        return None
    label = domain.split(".", 1)[0]
    for brand in BRAND_DOMAINS:
        brand_label = brand.split(".", 1)[0]
        if label == brand_label and domain != brand:
            return LookalikeFinding(
                "tld_swap", brand,
                f"Domain {domain} matches brand name '{brand_label}' but uses "
                f"the wrong top-level domain (real domain is {brand})",
            )
    return None


def analyze_domain(hostname: str) -> list[LookalikeFinding]:
    """Run all four lookalike techniques against one hostname."""
    domain = registrable_domain(hostname)
    checks = (check_edit_distance, check_char_substitution, check_homoglyph, check_tld_swap)
    return [f for f in (fn(domain) for fn in checks) if f is not None]
