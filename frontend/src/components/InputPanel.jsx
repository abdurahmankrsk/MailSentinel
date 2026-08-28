import { useEffect, useState } from 'react'

function guessType(content) {
  const trimmed = content.trim()
  if (!trimmed) return null
  const looksLikeUrl = !trimmed.includes('\n') && /^(https?:\/\/)?[\w-]+(\.[\w-]+)+(\/|$)/i.test(trimmed)
  return looksLikeUrl ? 'url' : 'email'
}

export default function InputPanel({ onScan, loading }) {
  const [content, setContent] = useState('')
  const [type, setType] = useState('email')
  const [autoDetected, setAutoDetected] = useState(false)

  useEffect(() => {
    const guess = guessType(content)
    setAutoDetected(Boolean(guess))
    if (guess) setType(guess)
  }, [content])

  return (
    <div className="input-panel">
      <div className="type-toggle" role="radiogroup" aria-label="Content type">
        <button
          type="button"
          className={type === 'email' ? 'active' : ''}
          onClick={() => setType('email')}
        >
          Email
        </button>
        <button
          type="button"
          className={type === 'url' ? 'active' : ''}
          onClick={() => setType('url')}
        >
          URL
        </button>
        {autoDetected && <span className="auto-hint">auto-detected</span>}
      </div>

      <textarea
        value={content}
        onChange={(event) => setContent(event.target.value)}
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
