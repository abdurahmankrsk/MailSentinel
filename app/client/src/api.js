import { getToken } from './auth.js'

const API_BASE = import.meta.env.VITE_API_BASE || ''

async function parseErrorMessage(response) {
  const body = await response.json().catch(() => null)
  const message = body?.error || body?.detail || body?.message || `Request failed (${response.status})`
  return typeof message === 'object' ? JSON.stringify(message) : message
}

function authHeaders() {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export async function scanContent(type, content) {
  const response = await fetch(`${API_BASE}/api/scan`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...authHeaders(),
    },
    body: JSON.stringify({ type, content }),
  })

  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }

  return response.json()
}

export async function registerUser(email, password) {
  const response = await fetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function loginUser(email, password) {
  const response = await fetch(`${API_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function fetchAuthConfig() {
  const response = await fetch(`${API_BASE}/api/auth/config`)
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

// `credential` is the Google-issued ID token. It is verified server-side before it
// names a user -- the browser never asserts an identity the server takes on trust.
export async function googleSignIn(credential) {
  const response = await fetch(`${API_BASE}/api/auth/google`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ credential }),
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function logoutUser() {
  await fetch(`${API_BASE}/api/auth/logout`, {
    method: 'POST',
    headers: { ...authHeaders() },
  }).catch(() => {}) // logging out locally still succeeds even if this call fails
}

export async function fetchUsage() {
  const response = await fetch(`${API_BASE}/api/usage/me`, {
    headers: { ...authHeaders() },
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function fetchAiKeyConfig() {
  const response = await fetch(`${API_BASE}/api/account/ai-key/config`)
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function fetchAiKeyStatus() {
  const response = await fetch(`${API_BASE}/api/account/ai-key`, {
    headers: { ...authHeaders() },
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function saveAiKey(label, baseUrl, model, key) {
  const response = await fetch(`${API_BASE}/api/account/ai-key`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify({ label, baseUrl, model, key }),
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
  return response.json()
}

export async function deleteAiKey() {
  const response = await fetch(`${API_BASE}/api/account/ai-key`, {
    method: 'DELETE',
    headers: { ...authHeaders() },
  })
  if (!response.ok) {
    throw new Error(await parseErrorMessage(response))
  }
}
