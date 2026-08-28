import { useState } from 'react'
import { MoreVerticalIcon } from './Icons.jsx'

const URL_LINE = /^(https?:\/\/)?[\w-]+(\.[\w-]+)+(\/|$)/i

// A paste of several links is still a URL scan, so every non-empty line has to look
// like one -- a single header line is enough to make it an email again.
function guessType(content) {
  const lines = content.trim().split(/\s+/).filter(Boolean)
  if (lines.length === 0) return null
  return lines.every((line) => URL_LINE.test(line)) ? 'url' : 'email'
}

export default function InputPanel({ onScan, loading }) {
  const [content, setContent] = useState('')
  const [manualType, setManualType] = useState(null)

  const guessedType = guessType(content)
  const type = manualType ?? guessedType ?? 'email'
  const autoDetected = manualType === null && guessedType !== null

  function handleContentChange(event) {
    const value = event.target.value
    setContent(value)
    if (value.trim() === '') {
      setManualType(null) // starting fresh: let auto-detect decide again
    }
  }

  return (
    <div className="input-panel">
      <div className="type-toggle" role="radiogroup" aria-label="Content type">
        <button
          type="button"
          className={type === 'email' ? 'active' : ''}
          onClick={() => setManualType('email')}
        >
          Email
        </button>
        <button
          type="button"
          className={type === 'url' ? 'active' : ''}
          onClick={() => setManualType('url')}
        >
          URL
        </button>
        {autoDetected && <span className="auto-hint">auto-detected</span>}
      </div>

      {/* The hint sits outside the textarea rather than in the placeholder so it is
          still there once you start typing, which is when "am I pasting the right
          thing?" actually comes up. */}
      <p className="input-hint">
        {type === 'email' ? (
          <>
            Paste the full source of the email you received. To find it, open the message, click
            the <MoreVerticalIcon size={15} className="hint-icon" /> menu, then press{' '}
            <strong>Show original</strong>.
          </>
        ) : (
          'Paste any links you were sent, one per line. They are never opened.'
        )}
      </p>

      <textarea
        value={content}
        onChange={handleContentChange}
        placeholder={
          type === 'email'
            ? 'Paste the email source here...'
            : 'Paste your links here, one per line...'
        }
        rows={14}
      />

      <button
        type="button"
        className="scan-button"
        disabled={!content.trim() || loading}
        onClick={() => onScan(type, content)}
      >
        {loading ? 'Scanning...' : 'Scan'}
      </button>
    </div>
  )
}
