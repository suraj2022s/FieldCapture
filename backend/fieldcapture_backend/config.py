"""Runtime settings, read from the environment (backend/.env is loaded if present)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = BACKEND_DIR.parent


def _load_dotenv(path: Path) -> None:
    """Tiny .env loader (no extra dependency). Existing environment variables win."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


_load_dotenv(BACKEND_DIR / ".env")

# USD per million tokens (input, output). Anthropic list prices, Nov 2026
# (Haiku 5.5 price applies to prompts up to 100K tokens; ours are ~2-4K).
PRICES_PER_MTOK: dict[str, tuple[float, float]] = {
    "claude-haiku-5-5": (0.10, 0.50),
    "claude-sonnet-5-5": (2.00, 10.00),
    "claude-opus-5-5": (4.00, 20.00),
}
CACHE_READ_FACTOR = 0.10
CACHE_WRITE_FACTOR = 1.25

# Models that accept the server-side refusal fallback (beta). Haiku 5.5 has none.
FALLBACK_MODELS = {"claude-opus-5-5", "claude-sonnet-5-5"}


@dataclass(frozen=True)
class Settings:
    data_dir: Path = field(default_factory=lambda: _path("FIELDCAPTURE_DATA_DIR", BACKEND_DIR / "data"))
    model_nameplate: str = os.environ.get("FIELDCAPTURE_MODEL_NAMEPLATE", "claude-opus-5-5")
    model_damage: str = os.environ.get("FIELDCAPTURE_MODEL_DAMAGE", "claude-opus-5-5")
    model_note: str = os.environ.get("FIELDCAPTURE_MODEL_NOTE", "claude-haiku-5-5")
    effort_nameplate: str = os.environ.get("FIELDCAPTURE_EFFORT_NAMEPLATE", "medium")
    effort_damage: str = os.environ.get("FIELDCAPTURE_EFFORT_DAMAGE", "medium")
    effort_note: str = os.environ.get("FIELDCAPTURE_EFFORT_NOTE", "low")
    # On when there's an API key (FIELDCAPTURE_EXTRACT=0 forces it off). Without a key, a reviewer's clone
    # used to try Claude on every photo and log an auth error each time (found 2026-11-27); now it runs the
    # offline path instead: plate fields from the phone's own OCR at low confidence, note fields by rules.
    extract: bool = field(default_factory=lambda: os.environ.get("FIELDCAPTURE_EXTRACT", "1" if has_api_key() else "0") != "0")
    # Long edge sent to the model; keeps image tokens bounded (~1.6K tokens per image).
    max_image_edge: int = int(os.environ.get("FIELDCAPTURE_MAX_IMAGE_EDGE", "1568"))
    llm_cache_dir: Path = field(default_factory=lambda: _path("FIELDCAPTURE_LLM_CACHE", BACKEND_DIR / "data" / "llm-cache"))
    # Access code. When set, every route except /healthz needs it: the phone sends "Bearer <code>", a
    # browser gets a password prompt (any user name, the code as password). Required off loopback.
    token: str = os.environ.get("FIELDCAPTURE_TOKEN", "")


def has_api_key() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY", "").strip())


def _path(env: str, default: Path) -> Path:
    raw = os.environ.get(env)
    if not raw:
        return default
    p = Path(raw)
    return p if p.is_absolute() else BACKEND_DIR / p


def settings() -> Settings:
    return Settings()
