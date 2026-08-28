import { useState } from 'react'
import InputPanel from './components/InputPanel.jsx'
import { scanContent } from './api.js'

export default function App() {
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  async function handleScan(type, content) {
    setLoading(true)
    setError(null)
    try {
      const data = await scanContent(type, content)
      setResult(data)
    } catch (err) {
      setError(err.message)
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="app">
      <header>
        <h1>Lookalike</h1>
        <p className="tagline">Paste an email or URL. Get a transparent phishing-risk breakdown.</p>
      </header>

      <InputPanel onScan={handleScan} loading={loading} />

      {error && <div className="error-banner">{error}</div>}

      {result && (
        <pre className="raw-result">{JSON.stringify(result, null, 2)}</pre>
      )}
    </div>
  )
}
