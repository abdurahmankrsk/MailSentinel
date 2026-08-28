export default function PlanTeaser({ onSignUp }) {
  return (
    <section className="plans" aria-labelledby="plans-title">
      <h2 id="plans-title" className="section-title">Free, with room to grow</h2>
      <p className="section-lede">
        Every deterministic check above is free and unauthenticated: no account,
        no limit, nothing stored. An account adds AI-assisted analysis on top,
        scaled to how much of it you need.
      </p>

      <div className="plan-cards">
        <div className="plan-card">
          <div className="plan-card-head">
            <span className="plan-badge plan-free">Free</span>
            <span className="plan-price">€0<span className="plan-period">/mo</span></span>
          </div>
          <ul className="plan-features">
            <li>Unlimited deterministic scans</li>
            <li>Every technique on this page</li>
            <li>No account required</li>
          </ul>
        </div>

        <div className="plan-card plan-card-premium">
          <div className="plan-card-head">
            <span className="plan-badge plan-premium">Premium</span>
            <span className="plan-price">€3<span className="plan-period">/mo</span></span>
          </div>
          <ul className="plan-features">
            <li>Everything in Free</li>
            <li>1,000 AI-assisted analyses / month</li>
            <li>AI findings are capped and additive, never a replacement score</li>
          </ul>
        </div>

        <div className="plan-card plan-card-enterprise">
          <div className="plan-card-head">
            <span className="plan-badge plan-enterprise">Enterprise</span>
            <span className="plan-price">€50<span className="plan-period">/mo</span></span>
          </div>
          <ul className="plan-features">
            <li>Everything in Premium</li>
            <li>50,000 AI-assisted analyses / month</li>
            <li>Shared workspace and pooled usage across your company's accounts</li>
            <li>Your own domains added to the lookalike-detection watch list, plus priority support</li>
          </ul>
        </div>
      </div>

      <button type="button" className="nav-button nav-button-primary plans-cta" onClick={onSignUp}>
        Create a free account
      </button>
    </section>
  )
}
