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


def source_root() -> Path:
    """Where the scored data lives: the frozen, committed test set (eval/dataset) once it exists,
    otherwise the raw pull (eval/raw). Frame keys (job/capture/file) are the same in both."""
    return DATASET if any(DATASET.rglob("capture.json")) else RAW


# Frames per capture sent to the models. The app keeps one frame per capture (the chosen one), and
# dataset mode saves ~10, so scoring all of them would mostly pay for near-duplicates. FREEZE_PER_CAPTURE
# is what freeze.py copies into the committed set; extraction may use fewer (sampling is prefix-stable).
FREEZE_PER_CAPTURE = 3


def sample_frames(capture: dict, n: int) -> list[dict]:
    """Deterministic: the frame the app chose, then the worst-scoring frame (what the gate rejected),
    then the median one. Taking the first n of this order is stable for any n."""
    cands = capture.get("candidates") or []
    chosen = [c for c in cands if c.get("chosen")]
    rest = sorted((c for c in cands if not c.get("chosen")), key=lambda c: (c.get("score", 0.0), c["file"]))
    order = chosen[:1]
    if rest:
        order.append(rest[0])  # worst
        mid = rest[len(rest) // 2]
        if mid is not rest[0]:
            order.append(mid)
    for c in cands:  # then the rest, in capture order
        if c not in order:
            order.append(c)
    return order[:n]


def raw_captures(root: Path | None = None) -> list[dict]:
    """Every capture.json under the data root (default: source_root()), with its directory attached."""
    root = root or source_root()
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
