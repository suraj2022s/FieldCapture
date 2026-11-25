"""Thin wrapper around the Anthropic SDK for structured extraction.

- Schema-validated output: `messages.parse(output_format=<pydantic model>)`.
- Every call reports tokens, USD and latency, so cost/latency per job is measured, not estimated.
- Raw responses are cached on disk by a content hash. `eval/run_all.py --cached` replays them,
  so reviewers can reproduce every number without an API key or spend.
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Generic, TypeVar

import anthropic
from PIL import Image, ImageOps
from pydantic import BaseModel

from .config import CACHE_READ_FACTOR, CACHE_WRITE_FACTOR, FALLBACK_MODELS, PRICES_PER_MTOK

T = TypeVar("T", bound=BaseModel)


class ExtractionError(RuntimeError):
    pass


@dataclass
class CallStats:
    model: str
    effort: str
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    cache_write_tokens: int
    cost_usd: float
    latency_ms: int
    stop_reason: str | None
    request_id: str | None
    from_cache: bool

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class LlmResult(Generic[T]):
    parsed: T
    stats: CallStats


def price(model: str, input_tokens: int, output_tokens: int, cache_read: int = 0, cache_write: int = 0) -> float:
    p_in, p_out = PRICES_PER_MTOK.get(model, (0.0, 0.0))
    return (
        input_tokens * p_in
        + cache_read * p_in * CACHE_READ_FACTOR
        + cache_write * p_in * CACHE_WRITE_FACTOR
        + output_tokens * p_out
    ) / 1_000_000


def jpeg_block(jpeg: bytes, max_edge: int) -> tuple[dict, tuple[int, int]]:
    """Image content block, downscaled so the long edge is <= max_edge. Returns (block, (w, h))."""
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(jpeg))).convert("RGB")
    if max(img.size) > max_edge:
        img.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=90)
    data = base64.standard_b64encode(buf.getvalue()).decode("ascii")
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": data}}, img.size


class Llm:
    def __init__(self, cache_dir: Path | None = None, offline: bool = False):
        self.cache_dir = cache_dir
        self.offline = offline  # True: only cached responses, never the network
        self._client: anthropic.Anthropic | None = None
        if cache_dir:
            cache_dir.mkdir(parents=True, exist_ok=True)

    @property
    def client(self) -> anthropic.Anthropic:
        if self._client is None:
            self._client = anthropic.Anthropic(max_retries=3, timeout=120.0)
        return self._client

    def call(
        self,
        *,
        model: str,
        system: str,
        content: list[dict],
        output_model: type[T],
        effort: str = "medium",
        max_tokens: int = 8000,
    ) -> LlmResult[T]:
        key = self._key(model, effort, system, content, output_model)
        cached = self._read_cache(key)
        if cached is not None:
            parsed = output_model.model_validate_json(cached["text"])
            stats = CallStats(**{**cached["stats"], "from_cache": True})
            return LlmResult(parsed, stats)
        if self.offline:
            raise ExtractionError(f"offline mode and no cached response for {output_model.__name__} ({key[:12]})")

        t0 = time.monotonic()
        kwargs = dict(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": content}],
            output_format=output_model,
            output_config={"effort": effort},
        )
        try:
            if model in FALLBACK_MODELS:
                # Server-side refusal fallback (beta): a policy decline is re-run on a fallback model.
                resp = self.client.beta.messages.parse(
                    betas=["server-side-fallback-2026-07-01"], fallbacks="default", **kwargs
                )
            else:
                resp = self.client.messages.parse(**kwargs)
        except anthropic.APIStatusError as e:
            raise ExtractionError(f"{model} HTTP {e.status_code}: {e.message}") from e
        except anthropic.APIConnectionError as e:
            raise ExtractionError(f"{model} connection error: {e}") from e
        latency_ms = int((time.monotonic() - t0) * 1000)

        if resp.stop_reason == "refusal":
            raise ExtractionError(f"{model} refused: {getattr(resp, 'stop_details', None)}")
        parsed = resp.parsed_output
        if parsed is None:
            raise ExtractionError(f"{model} returned no parsable output (stop_reason={resp.stop_reason})")
        u = resp.usage
        stats = CallStats(
            model=getattr(resp, "model", model) or model,
            effort=effort,
            input_tokens=u.input_tokens or 0,
            output_tokens=u.output_tokens or 0,
            cache_read_tokens=u.cache_read_input_tokens or 0,
            cache_write_tokens=u.cache_creation_input_tokens or 0,
            cost_usd=0.0,
            latency_ms=latency_ms,
            stop_reason=resp.stop_reason,
            request_id=getattr(resp, "_request_id", None),
            from_cache=False,
        )
        stats.cost_usd = price(
            model, stats.input_tokens, stats.output_tokens, stats.cache_read_tokens, stats.cache_write_tokens
        )
        self._write_cache(key, parsed.model_dump_json(), stats)
        return LlmResult(parsed, stats)

    # ------------------------------------------------------------------ cache

    def _key(self, model: str, effort: str, system: str, content: list[dict], output_model: type[BaseModel]) -> str:
        h = hashlib.sha256()
        h.update(json.dumps([model, effort, system, output_model.__name__], sort_keys=True).encode())
        h.update(json.dumps(output_model.model_json_schema(), sort_keys=True).encode())
        h.update(json.dumps(content, sort_keys=True).encode())
        return h.hexdigest()

    def _read_cache(self, key: str) -> dict | None:
        if not self.cache_dir:
            return None
        f = self.cache_dir / f"{key}.json"
        return json.loads(f.read_text(encoding="utf-8")) if f.exists() else None

    def _write_cache(self, key: str, text: str, stats: CallStats) -> None:
        if not self.cache_dir:
            return
        (self.cache_dir / f"{key}.json").write_text(
            json.dumps({"text": text, "stats": stats.to_dict()}, indent=1), encoding="utf-8"
        )
