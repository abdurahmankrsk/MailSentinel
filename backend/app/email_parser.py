"""Turn a raw pasted email (headers + MIME body) into the structured
pieces the checks need: the Authentication-Results header, the sender's
domain, and the plaintext/HTML body content.

Uses the stdlib `email` package (RFC 5322/MIME aware) instead of
scraping the raw text with regex, so multipart bodies, quoted-printable
or base64 encoding, and header folding are all handled correctly.
"""
from __future__ import annotations

from dataclasses import dataclass
from email import policy
from email.parser import Parser


@dataclass
class ParsedEmail:
    authentication_results: str | None
    sender_domain: str | None
    text_body: str | None
    html_body: str | None


def parse_email(raw: str) -> ParsedEmail:
    message = Parser(policy=policy.default).parsestr(raw)

    auth_results = message.get("Authentication-Results")

    sender_domain = None
    from_header = message.get("From")
    if from_header is not None and from_header.addresses:
        domain = from_header.addresses[0].domain
        sender_domain = domain.lower() if domain else None

    text_part = message.get_body(preferencelist=("plain",))
    html_part = message.get_body(preferencelist=("html",))

    return ParsedEmail(
        authentication_results=str(auth_results) if auth_results else None,
        sender_domain=sender_domain,
        text_body=text_part.get_content() if text_part else None,
        html_body=html_part.get_content() if html_part else None,
    )
