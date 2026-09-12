/**
 * The closing call to action depends on who is reading it. Signed out, the useful next
 * step is an account. Signed in, "Create a free account" was still the only thing on
 * offer -- and it opened the registration dialog for an account the reader already had.
 *
 * There is no checkout to send them to instead (Premium is priced here but not yet
 * purchasable), so rather than swap one dead end for another this points at the path
 * that does work today: bring-your-own-key, the section immediately below, which gets
 * them AI-assisted analysis on any plan.
 *
 * That path only exists when the server has BYOK configured, which it is not by
 * default -- a blank BYOK_ENCRYPTION_KEY switches the feature off and BringYourOwnKey
 * renders nothing at all. Linking to #byok-title regardless pointed at an element that
 * was not on the page, so the click did nothing, and the sentence promised a
 * capability the deployment did not have. `byokEnabled` is the same fact the section
 * itself keys off, so the two cannot disagree.
 */
export default function PlanTeaser({ email, plan, byokEnabled, onSignUp }) {
  const isPremium = plan === 'PREMIUM'

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

      {email ? (
        <p className="plans-cta-note">
          {isPremium
            ? "You're on Premium, so AI-assisted analysis already runs on every scan."
            : byokEnabled ? (
              <>
                You're signed in on the Free plan.{' '}
                <a href="#byok-title">Add your own API key</a> to get AI-assisted analysis
                at no extra cost.
              </>
            ) : (
              "You're signed in on the Free plan. Every deterministic check above already "
                + "runs on every scan; AI-assisted analysis isn't available here yet."
            )}
        </p>
      ) : (
        <button type="button" className="nav-button nav-button-primary plans-cta" onClick={onSignUp}>
          Create a free account
        </button>
      )}
    </section>
  )
}
