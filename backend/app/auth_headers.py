"""Check 1: parse the Authentication-Results header (RFC 8601) as claimed
by the receiving mail server.

This is a *claim*, not a verification -- the header can be missing,
forged by a malicious upstream hop, or simply absent on internal/test
mail. So its absence is never treated as suspicious on its own; only an
explicit non-pass result counts against the score. Independent
verification happens in dns_checks.py.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .constants import WEIGHTS
from .models import CheckResult

_RESULT_RE = re.compile(r"\b(spf|dkim|dmarc)\s*=\s*([a-zA-Z]+)", re.IGNORECASE)


@dataclass
class ClaimedAuthResults:
    header_present: bool
    spf: str | None = None
    dkim: str | None = None
    dmarc: str | None = None


def parse_authentication_results(header_value: str | None) -> ClaimedAuthResults:
    if not header_value:
        return ClaimedAuthResults(header_present=False)

    results: dict[str, str] = {}
    for mechanism, qualifier in _RESULT_RE.findall(header_value):
        mechanism = mechanism.lower()
        if mechanism not in results:  # keep the first occurrence
            results[mechanism] = qualifier.lower()

    return ClaimedAuthResults(
        header_present=True,
        spf=results.get("spf"),
        dkim=results.get("dkim"),
        dmarc=results.get("dmarc"),
    )


def _check(name: str, weight_key: str, header_present: bool, value: str | None, mechanism: str) -> CheckResult:
    if not header_present or value is None:
        return CheckResult(
            name=name,
            passed=True,
            weight=WEIGHTS[weight_key],
            detail=f"No Authentication-Results header present to evaluate {mechanism}",
        )
    passed = value == "pass"
    return CheckResult(
        name=name,
        passed=passed,
        weight=WEIGHTS[weight_key],
        detail=f"Authentication-Results reports {mechanism.lower()}={value}",
    )


def to_check_results(claimed: ClaimedAuthResults) -> list[CheckResult]:
    return [
        _check("SPF authentication (claimed)", "spf_claimed", claimed.header_present, claimed.spf, "SPF"),
        _check("DKIM authentication (claimed)", "dkim_claimed", claimed.header_present, claimed.dkim, "DKIM"),
        _check("DMARC authentication (claimed)", "dmarc_claimed", claimed.header_present, claimed.dmarc, "DMARC"),
    ]
