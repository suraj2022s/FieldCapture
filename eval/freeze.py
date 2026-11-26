"""Freeze the labelled captures into the committed test set (eval/raw -> eval/dataset).

Usage (repo root):  uv run --project backend python eval/freeze.py

eval/raw holds every frame the phone saved and stays out of git. A capture enters eval/dataset only
if it has a scene in eval/labels/captures.json, so leaving the scene blank in the labelling page
keeps a capture (e.g. one with a person or a screen in it) out of the public repo. For each one we
copy capture.json (all frames' on-device metrics, which is all the threshold fit needs) and the
images of the frames extraction is scored on (common.sample_frames). Re-running replaces the set.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import DATASET, FREEZE_PER_CAPTURE, RAW, labels, raw_captures, sample_frames  # noqa: E402


def main() -> None:
    _, caps, scenes = labels()
    captures = raw_captures(RAW)
    if not captures:
        sys.exit("eval/raw is empty: pull the dataset first (eval/pull_dataset.ps1)")
    if DATASET.exists():
        shutil.rmtree(DATASET)
    kept = images = skipped_no_scene = skipped_no_truth = 0
    for c in captures:
        scene = (caps.get(c["captureId"]) or {}).get("scene")
        if not scene:
            skipped_no_scene += 1
            continue
        if scene not in scenes:
            skipped_no_truth += 1
        rel = c["_dir"].relative_to(RAW)
        dest = DATASET / rel
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(c["_dir"] / "capture.json", dest / "capture.json")
        for cand in sample_frames(c, FREEZE_PER_CAPTURE):
            shutil.copy2(c["_dir"] / cand["file"], dest / cand["file"])
            images += 1
        kept += 1
    size_mb = sum(f.stat().st_size for f in DATASET.rglob("*") if f.is_file()) / 1e6
    print(f"froze {kept} captures, {images} images ({size_mb:.1f} MB) into eval/dataset")
    print(f"left out: {skipped_no_scene} without a scene; {skipped_no_truth} kept but their scene has no ground truth yet")
    print("Review eval/dataset for people, screens and documents before committing it.")


if __name__ == "__main__":
    main()
