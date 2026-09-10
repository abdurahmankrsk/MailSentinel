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

// A scan has no bound of its own. The server's worst case does: up to 25 s inside an AI
// provider call plus two 3 s DNS lookups, so roughly half a minute is the longest a real
// answer can take. Past that the request is not coming back, and fetch will wait forever
// for it -- which left the Scan button disabled on "Scanning..." with no error, no cancel
// and no way out but reloading the page. 45 s clears the server's own ceiling with room
// to spare, so this only ever fires on a request that has genuinely gone missing.
const SCAN_TIMEOUT_MS = 45_000

async function fetchWithTimeout(url, options, timeoutMs) {
  // Wired by hand rather than with AbortSignal.timeout(), which Safari only shipped in 16.
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error(
        'The scan took too long to answer and was stopped. Check your connection and try again.',
        { cause: error },
      )
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}

function authHeaders() {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export async function scanContent(type, content) {
  const response = await fetchWithTimeout(`${API_BASE}/api/scan`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      ...authHeaders(),
    },
    body: JSON.stringify({ type, content }),
  }, SCAN_TIMEOUT_MS)

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
