"""Request/response schema for the /api/scan endpoint."""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class ScanRequest(BaseModel):
    type: Literal["email", "url"]
    content: str = Field(min_length=1)


class CheckResult(BaseModel):
    name: str
    passed: bool
    weight: int
    detail: str


class ScanResponse(BaseModel):
    score: int
    checks: list[CheckResult]
