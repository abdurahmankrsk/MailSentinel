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
