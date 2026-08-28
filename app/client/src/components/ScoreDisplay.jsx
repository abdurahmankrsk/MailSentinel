// 60 is the red threshold the scoring tiers are built around (see README and
// ScanPipelineTest, which both treat >= 60 as high risk), so it belongs in the red
// band rather than at the top of the yellow one.
function bandFor(score) {
  if (score < 30) return { label: 'Low risk', className: 'score-green' }
  if (score < 60) return { label: 'Medium risk', className: 'score-yellow' }
  return { label: 'High risk', className: 'score-red' }
}

export default function ScoreDisplay({ score }) {
  const band = bandFor(score)
  return (
    <div className={`score-display ${band.className}`}>
      <div className="score-number">{score}</div>
      <div className="score-label">{band.label}</div>
    </div>
  )
}
