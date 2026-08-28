import { getToken } from './auth.js'

const API_BASE = import.meta.env.VITE_API_BASE || ''

// The server speaks two error shapes: RFC 7807 problem details ({ detail, title }) for
// anything thrown as a ResponseStatusException, and ApiExceptionHandler's
// { error, message } for named exceptions -- where `error` is a machine-readable code
// like EMAIL_ALREADY_REGISTERED and `message` is the sentence meant for a human.
// Prose fields are therefore read first, and the code is only a last resort before the
// status line: showing a user "DISPOSABLE_EMAIL_DOMAIN" tells them nothing about what
// to do next, while the message it ships alongside says exactly that.
async function parseErrorMessage(response) {
  const body = await response.json().catch(() => null)
  const candidates = [body?.message, body?.detail, body?.error, body?.title]
  const message =
    candidates.find((value) => typeof value === 'string' && value.trim() !== '') ??
    candidates.find((value) => value != null && typeof value === 'object') ??
    `Request failed (${response.status})`
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
