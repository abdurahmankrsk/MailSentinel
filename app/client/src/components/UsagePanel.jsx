export default function UsagePanel({ usage }) {
  if (!usage) return null

  const isPremium = usage.plan === 'PREMIUM'

  return (
    <div className="usage-panel">
      <span className={`plan-badge ${isPremium ? 'plan-premium' : 'plan-free'}`}>{usage.plan}</span>
      {isPremium ? (
        <span className="usage-figures">
          {usage.scansUsed} / {usage.scansAllowance} AI scans used
          {usage.periodEnd && (
            <> · resets {new Date(usage.periodEnd).toLocaleDateString()}</>
          )}
        </span>
      ) : (
        <span className="usage-figures">Upgrade to PREMIUM for AI-powered analysis</span>
      )}
    </div>
  )
}
