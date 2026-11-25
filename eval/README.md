# Evaluation: test set, labels, how to reproduce

Every frame here comes from our own captures, made with the FieldCapture app (Mock Device Kit fed by
the phone's back camera) in dataset mode. Nothing is synthetic, downloaded or borrowed. Scenes are
in a home we have permission to film, and no people appear in frame. Any staged scene is marked
`staged=yes` in its ground truth and called out in the report.

## 1. Collect (phone)

1. Install the debug build, open **Settings (debug)** and turn **Dataset: ON**.
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

## 3. Measure

```
uv run --project backend python eval/qc_thresholds.py --write-app   # quality thresholds -> app assets + results/qc.json
```

Splits are by **scene**: `common.split_of()` hashes the scene id, so no frame of a scene appears in
both the tune and test halves. Thresholds and confidence calibration are fitted on **tune**; every
number in the report is on **test** unless it says otherwise.
