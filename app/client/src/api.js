const API_BASE = import.meta.env.VITE_API_BASE || ''

export async function scanContent(type, content) {
  const response = await fetch(`${API_BASE}/api/scan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, content }),
  })

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const message = body?.detail || body?.message || `Scan failed (${response.status})`
    throw new Error(typeof message === 'object' ? JSON.stringify(message) : message)
  }

  return response.json()
}
