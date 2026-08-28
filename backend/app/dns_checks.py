"""Check 2: independent SPF/DMARC verification via live DNS TXT lookups.

This is what makes the scan trustworthy even when the
Authentication-Results header (check 1) is absent, or lying -- a
compromised or malicious upstream hop can write whatever it wants into
that header, but it can't rewrite what's actually published in DNS. We
ask DNS directly instead of trusting the header's claim, and if the two
disagree, that disagreement is itself a signal (see agreement_check).
"""
from __future__ import annotations

from dataclasses import dataclass

import dns.exception
import dns.resolver

from .auth_headers import ClaimedAuthResults
from .constants import WEIGHTS
from .models import CheckResult

_TIMEOUT = 3
_LIFETIME = 5


def _txt_records(name: str) -> list[str]:
    resolver = dns.resolver.Resolver()
    resolver.timeout = _TIMEOUT
    resolver.lifetime = _LIFETIME
    try:
        answer = resolver.resolve(name, "TXT")
    except dns.exception.DNSException:
        return []
    records = []
    for rdata in answer:
        # TXT records can be split across multiple quoted strings; join them.
        records.append(b"".join(rdata.strings).decode("utf-8", errors="replace"))
    return records


@dataclass
class LiveDnsResult:
    spf_present: bool
    dmarc_present: bool
    dmarc_policy: str | None  # "none" | "quarantine" | "reject" | None


def verify_spf_dmarc(domain: str) -> LiveDnsResult:
    spf_present = any(r.lower().startswith("v=spf1") for r in _txt_records(domain))

    dmarc_records = _txt_records(f"_dmarc.{domain}")
    dmarc_txt = next((r for r in dmarc_records if r.lower().startswith("v=dmarc1")), None)

    dmarc_policy = None
    if dmarc_txt:
        for tag in dmarc_txt.split(";"):
            tag = tag.strip()
            if tag.lower().startswith("p="):
                dmarc_policy = tag.split("=", 1)[1].strip().lower()
                break

    return LiveDnsResult(
        spf_present=spf_present,
        dmarc_present=dmarc_txt is not None,
        dmarc_policy=dmarc_policy,
    )


def to_check_results(domain: str, live: LiveDnsResult) -> list[CheckResult]:
    spf_check = CheckResult(
        name="SPF record (live DNS)",
        passed=live.spf_present,
        weight=WEIGHTS["spf_live"],
        detail=(f"{domain} publishes an SPF TXT record" if live.spf_present
                else f"No SPF TXT record found for {domain}"),
    )

    if not live.dmarc_present:
        dmarc_passed = False
        dmarc_detail = f"No DMARC record found at _dmarc.{domain}"
    elif live.dmarc_policy == "none":
        dmarc_passed = False
        dmarc_detail = (
            f"{domain} publishes DMARC with policy p=none (monitoring only, not enforced)"
        )
    else:
        dmarc_passed = True
        dmarc_detail = f"{domain} publishes DMARC with policy p={live.dmarc_policy}"

    dmarc_check = CheckResult(
        name="DMARC record & policy (live DNS)",
        passed=dmarc_passed,
        weight=WEIGHTS["dmarc_live"],
        detail=dmarc_detail,
    )
    return [spf_check, dmarc_check]


def agreement_check(claimed: ClaimedAuthResults, live: LiveDnsResult) -> CheckResult:
    """Compare what the receiving server claimed (check 1) against what we
    independently found in DNS (check 2)."""
    name = "Authentication header vs live DNS agreement"
    weight = WEIGHTS["claimed_vs_live_disagreement"]

    if not claimed.header_present:
        return CheckResult(
            name=name, passed=True, weight=weight,
            detail="No Authentication-Results header to compare against live DNS",
        )

    disagreements = []
    if claimed.spf == "pass" and not live.spf_present:
        disagreements.append("header claims spf=pass but no SPF record exists")
    if claimed.dmarc == "pass" and (not live.dmarc_present or live.dmarc_policy == "none"):
        disagreements.append("header claims dmarc=pass but DMARC is unenforced or absent")

    if disagreements:
        return CheckResult(name=name, passed=False, weight=weight, detail="; ".join(disagreements))
    return CheckResult(
        name=name, passed=True, weight=weight,
        detail="Authentication-Results claims are consistent with live DNS",
    )
