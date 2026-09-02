import { useEffect, useState } from 'react'
import InputPanel from './components/InputPanel.jsx'
import ScoreDisplay from './components/ScoreDisplay.jsx'
import ChecksList from './components/ChecksList.jsx'
import AuthModal from './components/AuthModal.jsx'
import { ShieldScanIcon } from './components/Icons.jsx'
import UsagePanel from './components/UsagePanel.jsx'
import Hero from './components/Hero.jsx'
import TechniqueBreakdown from './components/TechniqueBreakdown.jsx'
import ScoringPhilosophy from './components/ScoringPhilosophy.jsx'
import PlanTeaser from './components/PlanTeaser.jsx'
import BringYourOwnKey from './components/BringYourOwnKey.jsx'
import LandingFooter from './components/LandingFooter.jsx'
import { scanContent, fetchUsage, fetchAiKeyConfig, fetchAiKeyStatus, logoutUser } from './api.js'
import { getToken, getEmail, setSession, clearSession } from './auth.js'

const AI_STATUS_MESSAGES = {
  AI_SCAN_LIMIT_REACHED: 'AI analysis limit reached for this billing period.',
  AI_PROVIDER_ERROR: 'AI analysis failed and was not charged against your allowance.',
  AI_REQUEST_IN_PROGRESS: 'Another analysis for this request is already in progress.',
}

export default function App() {
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [email, setEmail] = useState(getEmail())
  const [usage, setUsage] = useState(null)
  const [aiKeyStatus, setAiKeyStatus] = useState(null)
  // null when closed, otherwise the mode the dialog should open on.
  const [authMode, setAuthMode] = useState(null)

  // Public, so a visitor who isn't signed in yet still learns whether the section
  // at the bottom of the page is worth showing at all.
  useEffect(() => {
    fetchAiKeyConfig().then(setAiKeyStatus).catch(() => {})
  }, [])

  useEffect(() => {
    if (email) {
      refreshUsage()
      refreshAiKeyStatus()
    }
  }, [email])

  async function refreshUsage() {
    try {
      setUsage(await fetchUsage())
    } catch {
      // A stale/expired token surfaces to the user the next time they act (e.g. a
      // scan returning 401); the usage strip just quietly stays blank until then.
    }
  }

  async function refreshAiKeyStatus() {
    try {
      setAiKeyStatus(await fetchAiKeyStatus())
    } catch {
      // Same quiet-degrade as refreshUsage -- a stale token surfaces on next action.
    }
  }

  function handleAuthenticated(data) {
    setSession(data.token, data.email)
    // The auth response already carries the plan, and the badge is the only thing
    // that needs it to render. Seeding it here shows the right badge immediately
    // instead of leaving the panel absent until /api/usage/me answers -- the same
    // fact the server already sent, one round trip earlier. The figures stay absent
    // until that call returns; UsagePanel renders the badge without them rather than
    // showing a placeholder 0/0 that would be wrong for a Premium account.
    setUsage({ plan: data.plan })
    setEmail(data.email)
    setAuthMode(null)
  }

  async function handleLogout() {
    await logoutUser()
    clearSession()
    setEmail(null)
    setUsage(null)
    // The results panel can hold a verdict on something pasted from a private
    // mailbox, and logging out is the moment a user expects that to be gone --
    // particularly on a shared machine.
    setResult(null)
    setError(null)
    // Drop the personal label/last4 but keep featureEnabled so the section at the
    // bottom of the page doesn't disappear just because you logged out.
    setAiKeyStatus((current) => (current ? { ...current, label: null, last4: null } : current))
  }

  async function handleScan(type, content) {
    setLoading(true)
    setError(null)
    try {
      const data = await scanContent(type, content)
      setResult(data)
      if (getToken()) {
        refreshUsage()
      }
    } catch (err) {
      setError(err.message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1><ShieldScanIcon size={26} /> MailSentinel</h1>
        {email ? (
          <div className="account-strip">
            <span className="account-email">{email}</span>
            <button type="button" className="link-button" onClick={handleLogout}>Log out</button>
          </div>
        ) : (
          <div className="account-strip">
            <button type="button" className="nav-button" onClick={() => setAuthMode('login')}>
              Log in
            </button>
            <button type="button" className="nav-button nav-button-primary" onClick={() => setAuthMode('register')}>
              Sign up
            </button>
          </div>
        )}
      </header>

      <Hero />

      {email && <UsagePanel usage={usage} aiKeyStatus={aiKeyStatus} />}

      <AuthModal
        open={authMode !== null}
        initialMode={authMode ?? 'login'}
        onClose={() => setAuthMode(null)}
        onAuthenticated={handleAuthenticated}
      />

      <InputPanel onScan={handleScan} loading={loading} />

      {/* Trust matters most at the exact moment someone is about to paste something
          sensitive, so this sits immediately below the input rather than buried in a
          footer no one reads before scanning. */}
      <p className="privacy-strip">
        Nothing you paste here is ever written to disk or logged. Scanning is
        anonymous and free, with no account required.
      </p>

      {error && <div className="error-banner">{error}</div>}

      {result && (
        <div className="results">
          <ScoreDisplay score={result.score} />
          <ChecksList checks={result.checks} />
          {result.aiAnalysis?.status === 'AI_ANALYSIS_COMPLETED' && (
            <div className="ai-summary">
              <strong>AI analysis:</strong> {result.aiAnalysis.summary}
            </div>
          )}
          {result.aiAnalysis && AI_STATUS_MESSAGES[result.aiAnalysis.status] && (
            <div className="error-banner">
              {AI_STATUS_MESSAGES[result.aiAnalysis.status]}
              {result.aiAnalysis.message ? ` ${result.aiAnalysis.message}` : ''}
            </div>
          )}
        </div>
      )}

      <TechniqueBreakdown />
      <ScoringPhilosophy />
      <PlanTeaser onSignUp={() => setAuthMode('register')} />
      <BringYourOwnKey
        email={email}
        aiKeyStatus={aiKeyStatus}
        onStatusChange={setAiKeyStatus}
        onSignUp={() => setAuthMode('register')}
      />
      <LandingFooter />
    </div>
  )
}
