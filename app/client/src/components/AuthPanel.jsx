import { useState } from 'react'
import { loginUser, registerUser } from '../api.js'
import GoogleSignInButton from './GoogleSignInButton.jsx'

// `mode` is owned by the caller (AuthModal) rather than held here, so the dialog
// heading and these tabs read from one value and cannot drift apart.
export default function AuthPanel({ onAuthenticated, mode = 'login', onModeChange = () => {} }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(event) {
    event.preventDefault()
    setLoading(true)
    setError(null)
    try {
      const data = mode === 'login' ? await loginUser(email, password) : await registerUser(email, password)
      onAuthenticated(data)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-panel">
      <div className="type-toggle" role="radiogroup" aria-label="Auth mode">
        <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => onModeChange('login')}>
          Log in
        </button>
        <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => onModeChange('register')}>
          Register
        </button>
      </div>

      <form onSubmit={handleSubmit} className="auth-form">
        <input
          type="email"
          placeholder="email@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="email"
          required
        />
        <input
          type="password"
          placeholder="password (min 8 characters)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          minLength={8}
          required
        />
        <button type="submit" className="scan-button auth-submit" disabled={loading}>
          {loading ? 'Please wait...' : mode === 'login' ? 'Log in' : 'Create account'}
        </button>
      </form>

      <GoogleSignInButton onAuthenticated={onAuthenticated} disabled={loading} />

      {error && <div className="error-banner">{error}</div>}
    </div>
  )
}
