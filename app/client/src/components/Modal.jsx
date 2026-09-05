import { useEffect, useRef } from 'react'
import { CloseIcon } from './Icons.jsx'

/**
 * Shared dialog shell: closes on Escape, on backdrop click, and via the explicit
 * close button, and traps focus while open. Extracted from what was AuthModal's own
 * logic so a second dialog (the AI-key settings modal) doesn't duplicate a11y-critical
 * behavior that would then drift if only one copy ever got fixed.
 */
export default function Modal({ open, onClose, titleId, title, children }) {
  const dialogRef = useRef(null)
  // The control that opened the dialog, so focus can go back where it came from.
  const openerRef = useRef(null)

  // Split from the key handler below on purpose. Taking focus and locking scroll happen
  // once, when the dialog opens -- so this effect depends only on `open`. Callers pass
  // onClose as an inline arrow (a fresh identity every render), so while it sat in these
  // deps the whole effect tore down and re-ran on any unrelated parent render: it
  // restored focus to the opener, then pulled it back to the first input. Type your
  // email, tab to the password field, and a scan finishing in the background behind the
  // dialog would drop your caret back in the email box.
  useEffect(() => {
    if (!open) return undefined

    openerRef.current = document.activeElement
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    dialogRef.current?.querySelector('input')?.focus()

    return () => {
      document.body.style.overflow = previousOverflow
      openerRef.current?.focus?.()
    }
  }, [open])

  // The key handler genuinely does need the current onClose, and re-subscribing a
  // listener is free -- no focus or scroll state rides along with it.
  useEffect(() => {
    if (!open) return undefined

    function handleKeyDown(event) {
      if (event.key === 'Escape') {
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      // Keep tabbing inside the dialog while it owns the screen.
      //
      // Disabled and hidden controls are excluded rather than merely selected against,
      // because the trap works by identifying the first and last focusable elements: a
      // disabled submit button at the end of the form would become the "last" one, and
      // Tab would then wrap from an element the browser refuses to focus, stranding
      // focus instead of cycling it. The AI-key dialog has exactly that shape while a
      // save is in flight.
      const focusable = [
        ...(dialogRef.current?.querySelectorAll(
          'button, input, [href], select, textarea, [tabindex]:not([tabindex="-1"])'
        ) ?? []),
      ].filter((node) => !node.disabled && node.getClientRects().length > 0)
      if (focusable.length === 0) return
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
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={dialogRef}>
        <div className="modal-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="modal-close" onClick={onClose} aria-label="Close">
            <CloseIcon size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
