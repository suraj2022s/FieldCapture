# Evaluation: test set, labels, how to reproduce

Every frame here comes from our own captures, made with the FieldCapture app (Mock Device Kit fed by
the phone's back camera) in dataset mode. Nothing is synthetic, downloaded or borrowed. Scenes are
in a home we have permission to film, and no people appear in frame. Any staged scene is marked
`staged=yes` in its ground truth and called out in the report.

## 1. Collect (phone)

1. Install the debug build, tap **Show developer tools**, then **Dataset: off** so it reads **Dataset: ON**. The job card then shows an amber "Dataset mode ON" label, so the mode stays visible after the developer tools are folded away again.
2. Start a job and capture by voice as normal. In dataset mode, every frame the quality gate judged
   is saved for each capture, not only the one it kept: JPEG plus the phone's own metrics, verdict and OCR.
3. Shot list (target ≈ 120 frames; the brief's minimum is 40 frames, 10 nameplates and 10 low-light):

| What | How many | Conditions per item |
|---|---|---|
| Nameplates (geyser/water heater, AC indoor/outdoor, fridge, washer, microwave, oven, inverter/UPS, router, TV back, power tools, PC PSU…) | ≥ 12 different plates | **good** (steady, square-on, label fills ½–⅔ of view) · **low_light** (lights off / inside a cupboard) · **motion** (turn your head while saying the command) · **angle** (45°, or partly cut off, or from too far) |
| Damage (real marks: wall stains/scuffs, ceiling stain, peeling paint, cracks, mildew, water marks under the sink, cracked tile, swollen laminate, door-frame damage) | ≥ 10 different areas | **good** · **low_light** · **motion**. Measure each area's width × height with a tape, plus one reference object in view (switch plate, tile, card, A4 sheet). |
| Low-light pairs | ≥ 8 scenes | the same shot **without** and **with** a torch (`low_light` vs `low_light_with_torch`): measures what a work light buys |
| Notes | 20 | read the note script (eval/notes_script.md) during walks |

4. Pull to the PC: `powershell -ExecutionPolicy Bypass -File eval\pull_dataset.ps1` (copies into `eval/raw/`, which is gitignored).
5. Upload the walks' items to a back office with extraction **off** and its own data dir, so the notes
   reach a database without paying for extraction that `run_extraction.py` repeats anyway:
   `FIELDCAPTURE_PORT=8001 FIELDCAPTURE_EXTRACT=0 FIELDCAPTURE_DATA_DIR=backend/data/dataset-walks`,
   with `adb reverse tcp:8000 tcp:8001` until the outbox is empty. Then
   `run_extraction.py --db backend/data/dataset-walks/fieldcapture.db` scores the notes from there.

## 2. Label (PC)

```
uv run --project backend python eval/label_app.py      # http://127.0.0.1:8100
```

- **Frames page.** Give each capture a **scene id** (one per physical thing, e.g. `np-geyser`, `dm-kitchen-wall`) and a **condition**. Then label every frame:
  - nameplate: *usable* means you could read the serial from this frame.
  - damage: *usable* means the damage is visible with enough context.
  - otherwise pick the reason: blurry, dark, cut_off, too_far, glare, wrong_target.
- **Scenes page.** Type the ground truth exactly as printed (nameplates), or as measured with the tape in inches (damage).

Labels go to `eval/labels/*.json`. They are part of the test set and get committed.

## 3. Freeze, measure, report (PC)

Run from the repo root, in this order (rehearsed end to end on 2026-11-26):

```
uv run --project backend python eval/freeze.py                 # labelled captures -> eval/dataset (committed)
uv run --project backend python eval/qc_thresholds.py --write-app
uv run --project backend python eval/run_extraction.py         # 3 models x 2 frames per capture, 4 calls at a time
uv run --project backend python eval/calibrate.py --model claude-haiku-5-5
uv run --project backend python eval/calibrate.py --model claude-opus-5-5 --write
uv run --project backend python eval/report.py                 # -> eval/REPORT.md
```

- **freeze.py** copies every capture that has a scene into `eval/dataset/`: its `capture.json` (all
  frames' on-device metrics) plus the frames extraction is scored on. Leave a capture's scene blank on
  the labelling page to keep it out of the public repo (a person, a screen, a document in view).
  Review `eval/dataset/` before committing it.
- **qc_thresholds.py** fits on tune scenes and tests on held-out scenes (`common.split_of()` hashes the
  scene id, so no frame of a scene lands in both halves). Within the false-retake cap it takes the
  middle of the gap between usable and bad frames, not the edge of the usable ones (see `fit_lower`).
  `--write-app` writes `android/app/src/main/assets/thresholds.json`; rebuild the app to ship it.
- **run_extraction.py** scores the frame the app chose plus the worst-scoring frame of each capture
  (`common.sample_frames`). Dataset mode saves ~10 frames per capture, so `--per-capture 0` (every
  frame) costs ~10x for near-duplicates. Rehearsal cost: Haiku $0.0005, Sonnet $0.008, Opus $0.016 per frame.
  Responses are cached in `eval/llm-cache/` (committed).
- **calibrate.py** measures out-of-fold (leave one scene out) and needs at least 3 wrong answers; a
  model that is never confidently wrong is reported as such rather than fitted. `--write` ships the
  coefficients to `backend/calibration.json`.
- **report.py**: extraction fits nothing on the labels, so §3–5 use all scenes; the quality gate (§2)
  is reported on held-out scenes. Add a cause for each of the five worst failures in
  `eval/labels/failure_causes.json` as `{"<frame key>#<field>": "cause"}` and re-run it.

**Reproduce without an API key:** `run_extraction.py --cached` replays the committed responses
against the committed frames, then `report.py`.
