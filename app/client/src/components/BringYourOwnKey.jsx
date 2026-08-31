import { useState } from 'react'
import { saveAiKey, deleteAiKey } from '../api.js'

const DEFAULT_BASE_URL = 'https://api.groq.com/openai/v1'
const DEFAULT_MODEL = 'llama-3.3-70b-versatile'

/**
 * Sits at the bottom of the page, below the plan cards, as its own real section
 * rather than something tucked behind a header link -- this is the actual entry
 * point for the feature, visible whether or not you're signed in yet.
 */
export default function BringYourOwnKey({ email, aiKeyStatus, onStatusChange, onSignUp }) {
  const [label, setLabel] = useState('')
  const [baseUrl, setBaseUrl] = useState(DEFAULT_BASE_URL)
  const [model, setModel] = useState(DEFAULT_MODEL)
  const [key, setKey] = useState('')
  const [error, setError] = useState(null)
  const [success, setSuccess] = useState(null)
  const [loading, setLoading] = useState(false)

  if (!aiKeyStatus?.featureEnabled) return null

  const hasKey = Boolean(aiKeyStatus.label)

  async function handleSave(event) {
    event.preventDefault()
    setLoading(true)
    setError(null)
    setSuccess(null)
    try {
      const updated = await saveAiKey(label, baseUrl, model, key)
      setKey('')
      setSuccess('Key saved. Your scans will use it automatically from now on.')
      onStatusChange(updated)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleDelete() {
    setLoading(true)
    setError(null)
    setSuccess(null)
    try {
      await deleteAiKey()
      setSuccess('Key removed.')
      onStatusChange({ featureEnabled: true, label: null, last4: null })
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="byok" aria-labelledby="byok-title">
      <h2 id="byok-title" className="section-title">Bring your own AI key</h2>
      <p className="section-lede">
        Every check on this page is already free. Add an API key from any AI provider
        (OpenAI, Groq, Together, DeepSeek, or anything else that speaks the OpenAI
        chat-completions format over a public HTTPS endpoint) and your scans get
        AI-assisted analysis too, at no extra cost from us and with no MailSentinel
        allowance involved.
      </p>

      {!email ? (
        <>
          <p className="input-hint">Create a free account first, then add your key here.</p>
          <button type="button" className="nav-button nav-button-primary" onClick={onSignUp}>
            Create a free account
          </button>
        </>
      ) : hasKey ? (
        <>
          <p className="input-hint">
            Using your <strong>{aiKeyStatus.label}</strong> key ending in <strong>&bull;&bull;&bull;{aiKeyStatus.last4}</strong>
          </p>
          <button type="button" className="link-button" onClick={handleDelete} disabled={loading}>
            {loading ? 'Removing...' : 'Remove key'}
          </button>
        </>
      ) : (
        <form onSubmit={handleSave} className="auth-form byok-form">
          <label className="byok-field">
            <span className="byok-label">Label (optional)</span>
            <input
              type="text"
              placeholder="Groq, OpenAI, Together..."
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              autoComplete="off"
            />
          </label>
          <label className="byok-field">
            <span className="byok-label">API base URL</span>
            <input
              type="text"
              className="key-input"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              autoComplete="off"
              required
            />
          </label>
          <label className="byok-field">
            <span className="byok-label">Model</span>
            <input
              type="text"
              className="key-input"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              autoComplete="off"
              required
            />
          </label>
          <label className="byok-field">
            <span className="byok-label">API key</span>
            <input
              type="password"
              className="key-input"
              placeholder="paste your key here"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              autoComplete="off"
              required
            />
          </label>
          <p className="byok-defaults-note">
            The defaults above work with Groq. Point them at a different base URL and
            model to use another provider instead.
          </p>
          <button type="submit" className="scan-button auth-submit" disabled={loading}>
            {loading ? 'Validating...' : 'Save key'}
          </button>
        </form>
      )}

      {/* Guarded on email too: a save/delete result from a previous session has
          nothing to say once you've logged out of it. */}
      {email && success && <div className="success-banner">{success}</div>}
      {email && error && <div className="error-banner">{error}</div>}

      <p className="privacy-strip">
        Stored encrypted. Only ever used server-side to call the endpoint you choose.
        Never shown again once saved.
      </p>
    </section>
  )
}
