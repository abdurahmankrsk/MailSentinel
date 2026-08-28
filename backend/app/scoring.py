"""Orchestrates every check into the final scan response.

The checks array is a fixed, predictable set per input type -- every
applicable check is always present with passed True/False, never
conditionally omitted, so a clean scan visibly shows *why* it's clean
("SPF: valid") rather than just an empty list of complaints.

Scoring is a plain capped sum of the weights of everything that failed.
Deliberately not a fancier formula: the "one strong signal can be enough,
weak signals should stack" requirement is handled by how the weights in
constants.py are tiered, which keeps this function trivial to explain.
"""
from __future__ import annotations

from .auth_headers import parse_authentication_results
from .auth_headers import to_check_results as claimed_checks
from .constants import WEIGHTS
from .dns_checks import agreement_check, verify_spf_dmarc
from .dns_checks import to_check_results as dns_result_checks
from .email_parser import parse_email
from .link_analysis import analyze_links, extract_links
from .lookalike import analyze_domain
from .models import CheckResult, ScanResponse
from .url_utils import extract_hostname, is_ip_literal

_LOOKALIKE_LABELS = {
    "edit_distance": "edit-distance",
    "char_substitution": "character substitution",
    "homoglyph": "homoglyph / mixed-script",
    "tld_swap": "TLD swap",
}


def _domain_lookalike_checks(hostname: str, label_prefix: str) -> list[CheckResult]:
    findings = {f.technique: f for f in analyze_domain(hostname)}
    checks = []
    for technique, label in _LOOKALIKE_LABELS.items():
        finding = findings.get(technique)
        checks.append(CheckResult(
            name=f"{label_prefix} {label}",
            passed=finding is None,
            weight=WEIGHTS[technique],
            detail=finding.detail if finding else f"No {label} pattern detected for {hostname}",
        ))
    return checks


def _ip_hostname_check(name: str, hostname: str | None) -> CheckResult:
    flagged = bool(hostname) and is_ip_literal(hostname)
    return CheckResult(
        name=name,
        passed=not flagged,
        weight=WEIGHTS["ip_hostname"],
        detail=(f"Host {hostname} is a raw IP address, not a domain name" if flagged
                else "Host is a domain name, not a raw IP address"),
    )


def scan_email(raw: str) -> ScanResponse:
    parsed = parse_email(raw)
    checks: list[CheckResult] = []

    claimed = parse_authentication_results(parsed.authentication_results)
    checks.extend(claimed_checks(claimed))

    sender_domain = parsed.sender_domain or "unknown"
    live = verify_spf_dmarc(sender_domain)
    checks.extend(dns_result_checks(sender_domain, live))
    checks.append(agreement_check(claimed, live))

    checks.extend(_domain_lookalike_checks(sender_domain, "Sender domain"))

    links = extract_links(parsed.text_body, parsed.html_body)
    checks.extend(analyze_links(links))

    return _finalize(checks)


def scan_url(raw: str) -> ScanResponse:
    hostname = extract_hostname(raw) or raw.strip()
    checks: list[CheckResult] = []
    checks.extend(_domain_lookalike_checks(hostname, "URL domain"))
    checks.append(_ip_hostname_check("Raw IP address as hostname", hostname))
    return _finalize(checks)


def _finalize(checks: list[CheckResult]) -> ScanResponse:
    score = min(100, sum(c.weight for c in checks if not c.passed))
    return ScanResponse(score=score, checks=checks)


def run_scan(scan_type: str, content: str) -> ScanResponse:
    if scan_type == "email":
        return scan_email(content)
    return scan_url(content)
