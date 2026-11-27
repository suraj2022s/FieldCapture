"""Run the back office's real extractors over the labelled test set and score every field.

Usage (repo root):
  uv run --project backend python eval/run_extraction.py --models claude-haiku-5-5,claude-sonnet-5-5,claude-opus-5-5
  uv run --project backend python eval/run_extraction.py --cached      # replay committed responses, no key, no spend

Outputs eval/results/extraction.jsonl (one row per frame x model) and eval/results/notes.jsonl.
Responses are cached in eval/llm-cache/ (committed), so every number in REPORT.md can be reproduced.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from concurrent.futures import ThreadPoolExecutor
from difflib import SequenceMatcher
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL_DIR))
sys.path.insert(0, str(EVAL_DIR.parent / "backend"))

from common import DATASET, FREEZE_PER_CAPTURE, LABELS, RESULTS, labels, load_json, raw_captures, sample_frames, save_json, source_root, split_of  # noqa: E402
from fieldcapture_backend import damage, nameplate, note  # noqa: E402
from fieldcapture_backend.llm import ExtractionError, Llm  # noqa: E402
from fieldcapture_backend.textmatch import norm_code, normalize_date  # noqa: E402

CACHE = EVAL_DIR / "llm-cache"
MAX_EDGE = 1568
NAMEPLATE_FIELDS = ("make", "model", "serial", "manufacture_date")
# Truth value "?" = not scored: the field is not printed, but a model can still get it right from what
# is printed (e.g. the brand from an LG model code). Blank = not printed: the only right answer is to abstain.
UNVERIFIED = "?"


# ---------------------------------------------------------------------- scoring helpers


def cer(pred: str, truth: str) -> float:
    """Character error rate (Levenshtein / len(truth))."""
    if not truth:
        return 0.0 if not pred else 1.0
    prev = list(range(len(truth) + 1))
    for i, pc in enumerate(pred, 1):
        cur = [i]
        for j, tc in enumerate(truth, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (pc != tc)))
        prev = cur
    return prev[-1] / len(truth)


def brand_eq(pred: str | None, truth: str) -> bool:
    p = re.sub(r"[^a-z0-9]", "", (pred or "").lower())
    t = re.sub(r"[^a-z0-9]", "", truth.lower())
    return bool(p) and (p == t or (len(t) >= 3 and (t in p or p in t)))


def date_eq(pred: str | None, truth: str) -> bool:
    t = normalize_date(truth) or truth
    p = normalize_date(pred) if pred else None
    if p is None:
        return False
    return p[: len(t)] == t if len(t) == 4 else p == t


def score_nameplate(fields: dict, truth: dict) -> dict:
    out = {}
    for f in NAMEPLATE_FIELDS:
        t = (truth.get(f) or "").strip()
        if t == UNVERIFIED:
            continue
        pf = fields.get(f) or {}
        p = pf.get("value")
        if not t:  # not printed on the plate: the only right answer is to abstain
            correct = p in (None, "")
        elif f == "make":
            correct = brand_eq(p, t)
        elif f == "manufacture_date":
            correct = date_eq(p, t)
        else:
            correct = norm_code(p) == norm_code(t)
        out[f] = {
            "truth": t or None,
            "pred": p,
            "conf": float(pf.get("confidence") or 0.0),
            "correct": bool(correct),
            "abstained": p in (None, ""),
            "cer": round(cer(norm_code(p), norm_code(t)), 3) if t and f in ("model", "serial") else None,
            "features": pf.get("features"),
        }
    return out


def score_damage(fields: dict, truth: dict, raw: dict, image_size) -> dict:
    out = {}
    for f in ("damage_type", "material"):
        t = (truth.get(f) or "").strip()
        if t and t != UNVERIFIED:
            pf = fields.get(f) or {}
            out[f] = {"truth": t, "pred": pf.get("value"), "conf": float(pf.get("confidence") or 0), "correct": pf.get("value") == t}
    t_surface = (truth.get("surface") or "").strip().lower().replace(UNVERIFIED, "")
    if t_surface:
        pf = fields.get("surface") or {}
        p = (pf.get("value") or "").lower()
        out["surface"] = {"truth": t_surface, "pred": p or None, "conf": float(pf.get("confidence") or 0), "correct": bool(p) and (t_surface in p or p in t_surface)}
    try:
        tw, th = float(truth.get("width_in") or 0), float(truth.get("height_in") or 0)
    except ValueError:
        tw = th = 0
    if tw > 0 and th > 0:
        ta = tw * th / 144.0
        sizes = {}
        size = fields.get("size") or {}
        for alt in size.get("alternatives") or []:
            sizes[alt["method"]] = alt
        # Method A again, but with the tape-measured size of the actual reference object in view.
        kind = (truth.get("reference_kind") or "").strip()
        try:
            rw, rh = float(truth.get("reference_w_in") or 0), float(truth.get("reference_h_in") or 0)
        except ValueError:
            rw = rh = 0
        if kind and rw > 0 and rh > 0 and raw:
            read = damage.DamageRead.model_validate(raw)
            known = {kind: (min(rw, rh), max(rw, rh), 0.95)}
            est = damage.size_from_references(read, image_size[0], image_size[1], known)
            if est:
                sizes["reference_scaling_measured_ref"] = est
        out["size"] = {
            "truth_w_in": tw,
            "truth_h_in": th,
            "truth_area_sqft": round(ta, 2),
            "chosen_method": size.get("source"),
            "chosen_conf": float(size.get("confidence") or 0),
            "methods": {
                m: {
                    "area_sqft": s["area_sqft"],
                    "ape_area": round(abs(s["area_sqft"] - ta) / ta, 3),
                    "ape_w": round(abs(s["width_in"] - tw) / tw, 3),
                    "ape_h": round(abs(s["height_in"] - th) / th, 3),
                }
                for m, s in sizes.items()
            },
        }
    return out


# ---------------------------------------------------------------------- runs


def frame_rows(which: str, per_capture: int) -> list[dict]:
    frames, caps, scenes = labels()
    rows = []
    root = source_root()
    for c in raw_captures(root):
        meta = caps.get(c["captureId"]) or {}
        sid = meta.get("scene")
        if not sid or sid not in scenes:
            continue
        rel = c["_dir"].relative_to(root).as_posix()
        cands = sample_frames(c, per_capture) if per_capture > 0 else c["candidates"]
        for cand in cands:
            key = f"{rel}/{cand['file']}"
            lab = (frames.get(key) or {}).get("label")
            if lab is None or lab == "wrong_target":
                continue
            if which == "chosen" and not cand.get("chosen"):
                continue
            if which == "usable" and lab != "usable":
                continue
            rows.append(
                {
                    "key": key,
                    "path": root / key,
                    "capture": c["captureId"],
                    # Score what is in frame: before the parser fix, 5 nameplate shots were filed as damage
                    # ("name blade"). app_purpose keeps what the app did, for the report.
                    "purpose": scenes[sid].get("type") or c["purpose"],
                    "app_purpose": c["purpose"],
                    "scene": sid,
                    "split": split_of(sid),
                    "condition": meta.get("condition", ""),
                    "label": lab,
                    "chosen": bool(cand.get("chosen")),
                    "gate_ok": bool(cand.get("ok")),
                    "sharp": (cand.get("text") or cand["frame"]).get("sharpness"),
                    "luma": cand["frame"]["meanLuma"],
                    "ocr_lines": cand.get("ocrLines") or [],
                    "room": c.get("room"),
                    "surface": c.get("surface"),
                    "truth": scenes[sid]["truth"],
                    "staged": str(scenes[sid]["truth"].get("staged", "")).lower() in ("yes", "true", "1"),
                }
            )
    return rows


def run_one(llm: Llm, model: str, effort: str, r: dict) -> dict:
    jpeg = Path(r["path"]).read_bytes()
    rec = {k: v for k, v in r.items() if k not in ("path", "truth", "ocr_lines")}
    rec["model"] = model
    try:
        if r["purpose"] == "nameplate":
            read, call = nameplate.extract(llm, model, effort, jpeg, MAX_EDGE)
            fields = nameplate.to_fields(read, r["ocr_lines"], r["gate_ok"])
            rec["scores"] = score_nameplate(fields, r["truth"])
            rec["legibility"] = read.legibility
            rec["issues"] = read.issues
        else:
            read, call, size = damage.extract(llm, model, effort, jpeg, MAX_EDGE, r["room"], r["surface"])
            fields = damage.to_fields(read, r["room"], r["surface"], 0.9, size)
            rec["scores"] = score_damage(fields, r["truth"], read.model_dump(), size)
            rec["issues"] = read.issues
        rec["call"] = call
    except ExtractionError as e:
        rec["error"] = str(e)
    return rec


def run_frames(llm: Llm, models: list[str], which: str, effort: str, per_capture: int, workers: int) -> list[dict]:
    rows = frame_rows(which, per_capture)
    print(f"{len(rows)} labelled frames x {len(models)} models ({workers} in parallel)")
    out = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        for model in models:
            done = list(pool.map(lambda r: run_one(llm, model, effort, r), rows))  # map keeps row order
            errors = sum("error" in d for d in done)
            print(f"  {model}: {len(done)} frames, {errors} errors")
            out += done
    return out


NOTES_HEARD = DATASET / "notes_heard.json"  # the scripted notes as the phone's recogniser heard them (committed)


def heard_notes(db: Path, prefer_committed: bool) -> list[dict]:
    """The notes to score: from the committed test set when replaying (so a clone reproduces the report and a
    local back-office DB can't change it), otherwise from the dataset walks' back-office DB."""
    if NOTES_HEARD.exists() and (prefer_committed or not db.exists()):
        return load_json(NOTES_HEARD, [])
    if not db.exists():
        print(f"no back-office DB at {db} and no {NOTES_HEARD.name}; skipping notes")
        return []
    con = sqlite3.connect(db)
    return [{"text": m.get("text", ""), "room": m.get("room")} for m in (json.loads(x) for (x,) in con.execute("SELECT meta_json FROM items WHERE kind='note'"))]


def run_notes(llm: Llm, models: list[str], db: Path, prefer_committed: bool = False) -> list[dict]:
    truth = load_json(LABELS / "notes_truth.json", {})
    lines = {k: v for k, v in truth.items() if not k.startswith("_")}
    out, scripted = [], []
    for n in heard_notes(db, prefer_committed):
        text = n.get("text", "")
        best = max(lines.items(), key=lambda kv: SequenceMatcher(None, text.lower(), kv[1]["text"]).ratio())
        sim = SequenceMatcher(None, text.lower(), best[1]["text"]).ratio()
        if sim < 0.6:
            continue  # free-form note, not from the script: never scored, never committed
        scripted.append({"text": text, "room": n.get("room")})
        t = best[1]
        wer = word_error_rate(text.lower(), t["text"])
        for model in models:
            rec = {"line": best[0], "transcript": text, "script": t["text"], "asr_wer": wer, "model": model}
            try:
                read, call = note.extract(llm, model, "low", text, n.get("room"))
                rec["call"] = call
                rec["scores"] = score_note(read.model_dump(), t)
            except ExtractionError as e:
                rec["error"] = str(e)
            out.append(rec)
    if scripted and not prefer_committed:
        save_json(NOTES_HEARD, scripted)
    return out


def word_error_rate(hyp: str, ref: str) -> float:
    h, r = re.findall(r"[a-z0-9']+", hyp), re.findall(r"[a-z0-9']+", ref)
    prev = list(range(len(r) + 1))
    for i, hw in enumerate(h, 1):
        cur = [i]
        for j, rw in enumerate(r, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (hw != rw)))
        prev = cur
    return round(prev[-1] / max(1, len(r)), 3)


def score_note(pred: dict, t: dict) -> dict:
    def tok(field_value, accepted):
        if accepted is None:
            return field_value in (None, "")
        v = (field_value or "").lower()
        return bool(v) and any(a.lower() in v or v in a.lower() for a in accepted)

    s = {
        "category": {"correct": pred["category"] in t["category"], "conf": pred["category_confidence"]},
        "component": {"correct": tok(pred.get("component"), t.get("component")), "conf": pred["component_confidence"]},
        "condition": {"correct": tok(pred.get("condition"), t.get("condition")), "conf": pred["condition_confidence"]},
        "location": {"correct": tok(pred.get("location"), t.get("location")), "conf": pred["location_confidence"]},
    }
    if t.get("measurement"):
        m = t["measurement"]
        v, u = pred.get("measurement_value"), (pred.get("measurement_unit") or "").lower()
        s["measurement"] = {
            "correct": v is not None and abs(float(v) - m["value"]) < 0.01 * m["value"] + 1e-9 and any(x in u for x in m["unit"]),
            "conf": pred["measurement_confidence"],
        }
    return s


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="claude-haiku-5-5,claude-sonnet-5-5,claude-opus-5-5")
    ap.add_argument("--frames", choices=["all", "usable", "chosen"], default="all")
    ap.add_argument("--per-capture", type=int, default=FREEZE_PER_CAPTURE,
                    help="frames per capture sent to the models: the app's chosen frame, the worst, the median "
                         "(default: all the frames freeze.py committed; 0 = every frame of the raw pull)")
    ap.add_argument("--effort", default="medium")
    ap.add_argument("--workers", type=int, default=4, help="model calls in flight at once")
    ap.add_argument("--cached", action="store_true", help="only replay committed responses (no API key needed)")
    ap.add_argument("--db", default=str(EVAL_DIR.parent / "backend" / "data" / "fieldcapture.db"))
    ap.add_argument("--skip-notes", action="store_true")
    args = ap.parse_args()
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    llm = Llm(CACHE, offline=args.cached)

    RESULTS.mkdir(parents=True, exist_ok=True)
    rows = run_frames(llm, models, args.frames, args.effort, args.per_capture, args.workers)
    with (RESULTS / "extraction.jsonl").open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, default=str) + "\n")
    print(f"wrote {len(rows)} rows to eval/results/extraction.jsonl")
    if not args.skip_notes:
        notes = run_notes(llm, models, Path(args.db), prefer_committed=args.cached)
        with (RESULTS / "notes.jsonl").open("w", encoding="utf-8") as f:
            for r in notes:
                f.write(json.dumps(r, default=str) + "\n")
        print(f"wrote {len(notes)} rows to eval/results/notes.jsonl")


if __name__ == "__main__":
    main()
