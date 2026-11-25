"""Shared helpers for the eval scripts: loading raw captures and labels, scene-level splits."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

EVAL = Path(__file__).resolve().parent
REPO = EVAL.parent
RAW = EVAL / "raw"
LABELS = EVAL / "labels"
DATASET = EVAL / "dataset"
RESULTS = EVAL / "results"

FRAME_LABELS = ["usable", "blurry", "dark", "cut_off", "too_far", "glare", "wrong_target"]
CONDITIONS = ["good", "low_light", "low_light_with_torch", "motion", "angle", "far", "glare"]


def load_json(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def save_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def raw_captures(root: Path = RAW) -> list[dict]:
    """Every capture.json under eval/raw, with its directory attached."""
    out = []
    for f in sorted(root.rglob("capture.json")):
        c = json.loads(f.read_text(encoding="utf-8"))
        c["_dir"] = f.parent
        out.append(c)
    out.sort(key=lambda c: c.get("createdAt", ""))
    return out


def labels():
    return (
        load_json(LABELS / "frames.json", {}),
        load_json(LABELS / "captures.json", {}),
        load_json(LABELS / "scenes.json", {}),
    )


def split_of(scene_id: str) -> str:
    """Deterministic scene-level split (no frame of a scene leaks across tune/test)."""
    h = int(hashlib.sha256(scene_id.encode()).hexdigest(), 16)
    return "tune" if h % 2 == 0 else "test"
