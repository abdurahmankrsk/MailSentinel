import { useEffect, useRef } from 'react'
import AuthPanel from './AuthPanel.jsx'

/**
 * Dialog shell around the existing AuthPanel form.
 *
 * Closes on Escape, on backdrop click, and via the explicit close button, so the
 * page returns to being just the scanner whenever the user isn't signing in.
 */
export default function AuthModal({ open, initialMode, onClose, onAuthenticated }) {
  const dialogRef = useRef(null)
  // The control that opened the dialog, so focus can go back where it came from.
  const openerRef = useRef(null)

  useEffect(() => {
    if (!open) return undefined

    openerRef.current = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      // Keep tabbing inside the dialog while it owns the screen.
      const focusable = dialogRef.current?.querySelectorAll(
        'button, input, [href], select, textarea, [tabindex]:not([tabindex="-1"])'
      )
      if (!focusable || focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    dialogRef.current?.querySelector('input')?.focus()

    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = previousOverflow
      openerRef.current?.focus?.()
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="auth-modal-title"
        ref={dialogRef}
      >
        <div className="modal-header">
          <h2 id="auth-modal-title">{initialMode === 'register' ? 'Create your account' : 'Welcome back'}</h2>
          <button type="button" className="modal-close" onClick={onClose} aria-label="Close">
            &times;
          </button>
        </div>
        <AuthPanel key={initialMode} initialMode={initialMode} onAuthenticated={onAuthenticated} />
      </div>
    </div>
  )
}
