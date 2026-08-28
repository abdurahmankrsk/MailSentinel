import { useEffect, useRef, useState } from 'react'
import { fetchAuthConfig, googleSignIn } from '../api.js'

const GIS_SRC = 'https://accounts.google.com/gsi/client'

// Official four-colour Google mark. Inlined rather than loaded from a CDN so the
// button still renders correctly offline and on a locked-down CSP.
function GoogleMark() {
  return (
    <svg className="google-mark" viewBox="0 0 18 18" aria-hidden="true" focusable="false">
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.81.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18z"
      />
      <path
        fill="#FBBC05"
        d="M3.97 10.72a5.41 5.41 0 0 1 0-3.44V4.96H.96a9 9 0 0 0 0 8.08l3.01-2.32z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.9 11.43 0 9 0A9 9 0 0 0 .96 4.96l3.01 2.32C4.68 5.16 6.66 3.58 9 3.58z"
      />
    </svg>
  )
}

function loadGisScript() {
  if (window.google?.accounts?.id) {
    return Promise.resolve()
  }
  const existing = document.querySelector(`script[src="${GIS_SRC}"]`)
  if (existing) {
    return new Promise((resolve, reject) => {
      existing.addEventListener('load', resolve)
      existing.addEventListener('error', () => reject(new Error('Could not reach Google sign-in.')))
    })
  }
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = GIS_SRC
    script.async = true
    script.defer = true
    script.onload = resolve
    script.onerror = () => reject(new Error('Could not reach Google sign-in.'))
    document.head.appendChild(script)
  })
}

/**
 * "Continue with Google" for both sign-in and sign-up: the server creates the account
 * on first use and signs into the existing one after that, so this button needs no
 * separate login/register modes.
 *
 * Renders nothing at all when the server reports Google sign-in is unconfigured,
 * rather than showing a button that could only ever fail.
 */
export default function GoogleSignInButton({ onAuthenticated, disabled }) {
  const [clientId, setClientId] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  // Survives unmount so a late Google callback can't setState on a dead component.
  const activeRef = useRef(true)

  useEffect(() => {
    activeRef.current = true
    fetchAuthConfig()
      .then((config) => {
        if (activeRef.current && config.googleEnabled && config.googleClientId) {
          setClientId(config.googleClientId)
        }
      })
      .catch(() => {
        // Treated exactly like "not configured": the button stays hidden rather than
        // appearing in a state where it cannot work.
      })
    return () => {
      activeRef.current = false
    }
  }, [])

  async function handleCredential(response) {
    if (!activeRef.current) return
    try {
      const data = await googleSignIn(response.credential)
      if (activeRef.current) onAuthenticated(data)
    } catch (err) {
      if (activeRef.current) setError(err.message)
    } finally {
      if (activeRef.current) setLoading(false)
    }
  }

  async function handleClick() {
    setError(null)
    setLoading(true)
    try {
      await loadGisScript()
      window.google.accounts.id.initialize({
        client_id: clientId,
        callback: handleCredential,
      })
      window.google.accounts.id.prompt((notification) => {
        // One Tap can decline to display (dismissed, cooling-down, blocked). Surface
        // that instead of leaving the button spinning forever.
        if (notification.isNotDisplayed?.() || notification.isSkippedMoment?.()) {
          if (activeRef.current) {
            setLoading(false)
            setError('Google sign-in did not open. Check that pop-ups are allowed, then try again.')
          }
        }
      })
    } catch (err) {
      if (activeRef.current) {
        setLoading(false)
        setError(err.message)
      }
    }
  }

  if (!clientId) {
    return null
  }

  return (
    <div className="google-signin">
      <div className="auth-divider"><span>or</span></div>
      <button
        type="button"
        className="google-button"
        onClick={handleClick}
        disabled={disabled || loading}
      >
        <GoogleMark />
        <span>{loading ? 'Opening Google...' : 'Continue with Google'}</span>
      </button>
      {error && <p className="google-error" role="alert">{error}</p>}
    </div>
  )
}
