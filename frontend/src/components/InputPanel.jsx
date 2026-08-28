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

      <textarea
        value={content}
        onChange={handleContentChange}
        placeholder={
          type === 'email'
            ? 'Paste the raw email source (headers + body)...'
            : 'Paste a URL...'
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
