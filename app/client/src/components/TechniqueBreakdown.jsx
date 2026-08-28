const TECHNIQUES = [
  {
    name: 'Authentication headers (claimed)',
    body: "Reads the SPF/DKIM/DMARC verdicts a receiving mail server already recorded, but treats them as a claim, not proof. A missing header is neutral; only an explicit failure counts.",
  },
  {
    name: 'Live DNS verification',
    body: 'Independently queries the sending domain’s real SPF and DMARC records, so a forged or absent header can’t hide behind a claim it never has to back up.',
  },
  {
    name: 'Lookalike domains',
    body: 'Four independent techniques catch a domain built to visually resemble a known brand: edit distance, character substitution, homoglyphs, and TLD swaps. Punycode is decoded first, so disguised scripts can’t slip through as meaningless ASCII.',
  },
  {
    name: 'Display-name impersonation',
    body: 'Catches the case domain analysis structurally can’t: a sender naming a brand in the From display name that the actual sending domain has no relationship to at all.',
  },
  {
    name: 'Link analysis',
    body: 'Separates what a link’s visible text says from where its href actually goes, flags known URL shorteners, and flags a raw IP address used as a link’s host.',
  },
]

export default function TechniqueBreakdown() {
  return (
    <section className="techniques" aria-labelledby="techniques-title">
      <h2 id="techniques-title" className="section-title">How the score is built</h2>
      <p className="section-lede">
        Five independent techniques, run on every applicable scan. Every one of them
        appears in the result whether it passed or failed, and a clean scan shows why
        it's clean, not just an empty list of complaints.
      </p>
      <ul className="techniques-list">
        {TECHNIQUES.map((t) => (
          <li key={t.name} className="technique">
            <h3 className="technique-name">{t.name}</h3>
            <p className="technique-body">{t.body}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}
