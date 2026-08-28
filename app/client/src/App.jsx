import { useEffect, useState } from 'react'
import InputPanel from './components/InputPanel.jsx'
import ScoreDisplay from './components/ScoreDisplay.jsx'
import ChecksList from './components/ChecksList.jsx'
import AuthPanel from './components/AuthPanel.jsx'
import UsagePanel from './components/UsagePanel.jsx'
import { scanContent, fetchUsage, logoutUser } from './api.js'
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

  useEffect(() => {
    if (email) {
      refreshUsage()
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

  function handleAuthenticated(data) {
    setSession(data.token, data.email)
    setEmail(data.email)
  }

  async function handleLogout() {
    await logoutUser()
    clearSession()
    setEmail(null)
    setUsage(null)
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
        <div>
          <h1>MailSentinel</h1>
          <p className="tagline">Paste an email or URL. Get a transparent phishing-risk breakdown.</p>
        </div>
        {email && (
          <div className="account-strip">
            <span className="account-email">{email}</span>
            <button type="button" className="link-button" onClick={handleLogout}>Log out</button>
          </div>
        )}
      </header>

      {email && <UsagePanel usage={usage} />}
      {!email && <AuthPanel onAuthenticated={handleAuthenticated} />}

      <InputPanel onScan={handleScan} loading={loading} />

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
    </div>
  )
}
