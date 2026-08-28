import { useState } from 'react'

function guessType(content) {
  const trimmed = content.trim()
  if (!trimmed) return null
  const looksLikeUrl = !trimmed.includes('\n') && /^(https?:\/\/)?[\w-]+(\.[\w-]+)+(\/|$)/i.test(trimmed)
  return looksLikeUrl ? 'url' : 'email'
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
        {type === 'email'
          ? 'Paste the full source of the email you received — headers and body. In Gmail: open the message, then the ⋮ menu → Show original.'
          : 'Paste one link from the email you received — right-click the link, copy the address, and paste it here. The link is never opened.'}
      </p>

      <textarea
        value={content}
        onChange={handleContentChange}
        placeholder={
          type === 'email'
            ? 'Delivered-To: ...\nAuthentication-Results: ...\nFrom: ...'
            : 'https://example.com/the-link-you-were-sent'
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
