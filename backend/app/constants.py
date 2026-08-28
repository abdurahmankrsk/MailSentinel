"""Scoring configuration: URL shorteners and per-check weights.

Weights are hand-tuned into three tiers rather than derived from a
formula, so the scoring is easy to reason about and explain:

  - "solo-red" (55-70): a single hit alone pushes the score past the red
    threshold (60) -- reserved for signals that are hard to produce by
    accident (a domain that is one edit from a known brand, a link whose
    visible text lies about its destination, a raw IP as a link host).
  - "strong" (45-55): serious on their own but slightly more ambiguous
    (a link *elsewhere* in the body being suspicious is one step removed
    from the sender's own identity; a TLD swap could occasionally be a
    legitimate regional domain).
  - "medium"/"weak" (10-30): individually inconclusive -- missing DMARC,
    a shortened link, a claimed-vs-live disagreement -- but several of
    them stacking should still be enough to cross into red.
"""

SHORTENER_DOMAINS = {
    "bit.ly",
    "tinyurl.com",
    "t.co",
    "goo.gl",
    "ow.ly",
    "is.gd",
    "buff.ly",
}

WEIGHTS = {
    # Lookalike domain techniques (lookalike.py) -- shared by sender-domain
    # checks, url-mode checks, and the aggregated in-body link check below.
    "edit_distance": 65,
    "char_substitution": 58,
    "homoglyph": 70,
    "tld_swap": 45,
    # Authentication-Results header, as claimed by the receiving server (auth_headers.py)
    "spf_claimed": 22,
    "dkim_claimed": 30,
    "dmarc_claimed": 22,
    # Independent live DNS verification (dns_checks.py)
    "spf_live": 18,
    "dmarc_live": 22,
    "claimed_vs_live_disagreement": 20,
    # Link analysis (link_analysis.py)
    "link_lookalike": 55,
    "url_shortener": 10,
    "anchor_mismatch": 62,
    "ip_hostname": 65,
}
