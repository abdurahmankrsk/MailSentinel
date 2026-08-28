// The API reports the plan as "FREE"/"PREMIUM"; the badge wants a name ("Free"),
// not a shouted status code, so this normalizes case regardless of what the server
// sends rather than relying on CSS text-transform (which only touches the first
// letter and would leave "FREE" as "FREE").
function planLabel(plan) {
  if (!plan) return plan
  return plan.charAt(0) + plan.slice(1).toLowerCase()
}

export default function UsagePanel({ usage, aiKeyStatus }) {
  if (!usage) return null

  const isPremium = usage.plan === 'PREMIUM'
  // A configured key always wins server-side (see AiAnalysisService), regardless of
  // plan, so it takes priority over the usual plan-based messaging here too.
  const hasOwnKey = Boolean(aiKeyStatus?.label)

  return (
    <div className="usage-panel">
      <span className={`plan-badge ${isPremium ? 'plan-premium' : 'plan-free'}`}>{planLabel(usage.plan)}</span>
      {hasOwnKey ? (
        <span className="usage-figures">Using your own {aiKeyStatus.label} key for AI analysis</span>
      ) : isPremium ? (
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
