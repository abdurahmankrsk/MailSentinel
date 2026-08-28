"""FastAPI application entrypoint.

CORS is wide open on purpose: this API has no cookies, sessions, or any
other credential to leak, so there's no CSRF-style risk in allowing any
origin. That's also what keeps it ready for a future browser extension
(which calls from a chrome-extension:// origin) without touching the
backend again.
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="Lookalike",
    description="Phishing-detection API: score a pasted email or URL and explain why.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["POST"],
    allow_headers=["*"],
    allow_credentials=False,
)
