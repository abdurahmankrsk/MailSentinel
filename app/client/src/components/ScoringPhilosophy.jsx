const TIERS = [
  {
    key: 'high',
    label: 'Solo-red',
    range: '55–70 pts',
    body: 'Domain homoglyph, an edit-distance match, a raw IP as a link host, an anchor/href mismatch, character substitution. Any one of these alone crosses the risk threshold.',
  },
  {
    key: 'medium',
    label: 'Strong',
    range: '45–55 pts',
    body: 'A lookalike link elsewhere in the body, a TLD swap, a display name claiming a brand the sending domain doesn’t back up. Serious, slightly more ambiguous alone.',
  },
  {
    key: 'low',
    label: 'Medium / weak',
    range: '9–30 pts',
    body: 'Missing or unenforced DMARC, missing SPF, a claimed auth failure, a URL shortener. Individually inconclusive, but they stack.',
  },
]

export default function ScoringPhilosophy() {
  return (
    <section className="philosophy" aria-labelledby="philosophy-title">
      <h2 id="philosophy-title" className="section-title">Why the number means something</h2>
      <p className="section-lede">
        The score is a capped sum: no logistic curve, no diminishing returns. What
        keeps that simple math from crying wolf is that weights are tiered by how hard
        a signal is to trigger by accident, not the formula.
      </p>

      <ul className="tiers">
        {TIERS.map((t) => (
          <li key={t.key} className="tier" data-tier={t.key}>
            <div className="tier-head">
              <span className="tier-label">{t.label}</span>
              <span className="tier-range">{t.range}</span>
            </div>
            <p className="tier-body">{t.body}</p>
          </li>
        ))}
      </ul>

      <p className="philosophy-note">
        A domain simply missing both SPF and DMARC with nothing else wrong is worth 20
        points, which still counts as <em>low risk</em>. That's deliberate: it's what keeps an
        ordinary, unauthenticated-but-otherwise-unremarkable domain from defaulting to
        "medium" on every scan.
      </p>
    </section>
  )
}
