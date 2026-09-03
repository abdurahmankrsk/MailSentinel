// 60 is the red threshold the scoring tiers are built around (see README and
// ScanPipelineTest, which both treat >= 60 as high risk), so it belongs in the red
// band rather than at the top of the yellow one.
function bandFor(score, brandsWatched) {
  // A score of exactly 0 is the one verdict worth qualifying. It means no signal
  // fired at all, and "Low risk — nothing crosses the threshold for concern" reads
  // as a clean bill of health rather than as the narrower claim it actually is:
  // nothing on our watch list resembles this. A confident false negative is worse
  // than an admitted gap for a security tool, so the note says what was checked.
  if (score === 0) {
    return {
      key: 'low',
      label: 'Low risk',
      note: brandsWatched
        ? `No signals fired. This doesn't resemble any of the ${brandsWatched} brands MailSentinel watches, `
          + `and its headers and links raised nothing — but that isn't the same as proof it's safe.`
        : 'No signals fired. That isn\'t the same as proof this is safe.',
    }
  }
  if (score < 30) {
    return {
      key: 'low',
      label: 'Low risk',
      note: 'Nothing here crosses the threshold for concern.',
    }
  }
  if (score < 60) {
    return {
      key: 'medium',
      label: 'Medium risk',
      note: 'Weak signals are stacking up. Worth a second look before you act on this.',
    }
  }
  return {
    key: 'high',
    label: 'High risk',
    note: 'Treat this as hostile. Do not click anything in the message or reply to it.',
  }
}

export default function ScoreDisplay({ score, brandsWatched }) {
  const band = bandFor(score, brandsWatched)

  return (
    <section className="verdict" data-band={band.key}>
      <div className="verdict-head">
        <p className="verdict-score">
          {score}
          <span className="verdict-max">/100</span>
        </p>
        <p className="verdict-band" id="verdict-band">{band.label}</p>
      </div>

      {/* The thresholds are drawn on the track rather than left implicit, so the
          number is readable as a position between two boundaries, not just a value. */}
      <div className="meter">
        <div className="meter-ticks" aria-hidden="true">
          <span className="meter-threshold" style={{ left: '30%' }} />
          <span className="meter-threshold" style={{ left: '60%' }} />
        </div>
        <div
          className="meter-track"
          role="meter"
          aria-valuenow={score}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-labelledby="verdict-band"
        >
          {/* Scaled rather than widened: transform composites, width relayouts every
              frame. The track clips it, so the pill radius survives the scale. */}
          <div className="meter-fill" style={{ transform: `scaleX(${score / 100})` }} />
        </div>
        <div className="meter-scale" aria-hidden="true">
          <span className="meter-mark" style={{ left: '30%' }}>30</span>
          <span className="meter-mark" style={{ left: '60%' }}>60</span>
        </div>
      </div>

      <p className="verdict-note">{band.note}</p>
    </section>
  )
}
