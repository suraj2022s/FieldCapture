"""Local labelling page for the eval test set.

Run (repo root):  uv run --project backend python eval/label_app.py   ->  http://127.0.0.1:8100

1. /            every capture pulled from the phone: tag its scene + condition, click a label on each frame.
2. /scenes      ground truth per scene (nameplate fields; damage type + tape-measured size).
Labels are written to eval/labels/*.json as you click (commit them; they are part of the test set).
"""

from __future__ import annotations

import html
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import uvicorn  # noqa: E402
from fastapi import FastAPI, HTTPException, Request  # noqa: E402
from fastapi.responses import FileResponse, HTMLResponse  # noqa: E402

from common import CONDITIONS, FRAME_LABELS, FREEZE_PER_CAPTURE, LABELS, RAW, labels, raw_captures, sample_frames, save_json  # noqa: E402

app = FastAPI(title="FieldCapture labelling")

CSS = """
body{font-family:system-ui,sans-serif;margin:12px;background:#f5f6f8}
.cap{background:#fff;border:1px solid #ddd;border-radius:10px;padding:10px;margin:10px 0}
.grid{display:flex;flex-wrap:wrap;gap:8px}.fr{width:190px;font-size:11px}
.fr img{width:190px;height:338px;object-fit:contain;background:#000;border:3px solid transparent;border-radius:6px}
.fr.chosen img{border-color:#1565c0}.btns button{font-size:11px;margin:1px;padding:2px 4px}
.sel{background:#2e7d32;color:#fff}.bad{background:#c62828;color:#fff}
input,select{font-size:13px;margin:2px} table td{padding:2px 6px} .muted{color:#666}
"""

JS = """
async function post(url, body){await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}
async function lab(key,label,el){await post('/api/frame',{key,label});
  for(const b of el.parentElement.children){b.className='';} el.className=(label=='usable')?'sel':'bad';}
async function capMeta(id){const s=document.getElementById('scene-'+id).value,c=document.getElementById('cond-'+id).value;
  await post('/api/capture',{id,scene:s,condition:c});}
async function allUsable(id,keys){for(const k of keys){await post('/api/frame',{key:k,label:'usable'});} location.reload();}
async function scene(id){const f=document.getElementById('form-'+id);const d={};for(const e of f.elements){if(e.name)d[e.name]=e.value;}
  await post('/api/scene',{id,truth:d}); document.getElementById('ok-'+id).textContent='saved';}
"""


def page(title: str, body: str) -> HTMLResponse:
    return HTMLResponse(
        f"<!doctype html><html><head><meta charset='utf-8'><title>{title}</title><style>{CSS}</style>"
        f"<script>{JS}</script></head><body><p><a href='/'>Frames</a> · <a href='/scenes'>Scenes / ground truth</a></p>{body}</body></html>"
    )


@app.get("/", response_class=HTMLResponse)
def frames_page(all: int = 0):  # noqa: A002 (query parameter name)
    frames, caps, _ = labels()
    captures = raw_captures(RAW)  # labelling always works on the full pull, never the frozen subset
    scope = (
        "Showing every saved frame (<a href='/'>only the committed ones</a>)."
        if all
        else f"Showing the {FREEZE_PER_CAPTURE} frames per capture that freeze.py commits, so every label can be checked against a public image (<a href='/?all=1'>show all frames</a>)."
    )
    out = [f"<h1>Captures ({len(captures)})</h1><p class='muted'>Blue border = frame the app chose. Label each frame: is it usable for its purpose (nameplate: could you read the plate's key text, serial if printed? damage: is it visible with context?). Leave a capture's scene blank to keep it out of the committed test set. {scope}</p>"]
    for c in captures:
        cid = c["captureId"]
        meta = caps.get(cid, {})
        rel = c["_dir"].relative_to(RAW).as_posix()
        shown = c["candidates"] if all else sample_frames(c, FREEZE_PER_CAPTURE)
        keys = [f"{rel}/{cand['file']}" for cand in shown]
        cond_opts = "".join(f"<option {'selected' if meta.get('condition') == x else ''}>{x}</option>" for x in [""] + CONDITIONS)
        excluded = f" <b style='color:#c62828'>excluded: {html.escape(meta['excluded'])}</b>" if meta.get("excluded") else ""
        cells = []
        for cand, key in zip(shown, keys):
            cur = frames.get(key, {}).get("label")
            btns = "".join(
                f"<button class='{('sel' if l == 'usable' else 'bad') if cur == l else ''}' onclick=\"lab('{key}','{l}',this)\">{l}</button>"
                for l in FRAME_LABELS
            )
            m = cand["frame"]
            t = cand.get("text") or {}
            cells.append(
                f"<div class='fr {'chosen' if cand.get('chosen') else ''}'><img loading='lazy' src='/raw/{key}'>"
                f"<div>t={cand['tRelMs']}ms sharp={m['sharpness']:.0f}{' txt=' + format(t.get('sharpness', 0), '.0f') if t else ''} "
                f"luma={m['meanLuma']:.0f} clip={m['clippedFraction']:.3f}<br>gate: {'OK' if cand['ok'] else ','.join(cand['issues'])}</div>"
                f"<div class='btns'>{btns}</div></div>"
            )
        out.append(
            f"<div class='cap'><b>{html.escape(c['purpose'])}</b> “{html.escape(c['transcript'])}” "
            f"room={html.escape(str(c.get('room')))} surface={html.escape(str(c.get('surface')))} "
            f"<span class='muted'>{html.escape(c['createdAt'])} · prompts {c['prompts']} · evidence {c['evidenceSource']}</span>{excluded}<br>"
            f"scene <input id='scene-{cid}' value='{html.escape(meta.get('scene', ''))}' placeholder='e.g. np-geyser' onchange=\"capMeta('{cid}')\"> "
            f"condition <select id='cond-{cid}' onchange=\"capMeta('{cid}')\">{cond_opts}</select> "
            f"<button onclick='allUsable(\"{cid}\", {json.dumps(keys)})'>mark all usable</button>"
            f"<div class='grid'>{''.join(cells)}</div></div>"
        )
    return page("Label frames", "".join(out))


NAMEPLATE_FIELDS = ["equipment_type", "make", "model", "serial", "manufacture_date"]
DAMAGE_FIELDS = ["damage_type", "material", "surface", "width_in", "height_in", "reference_kind", "reference_w_in", "reference_h_in", "staged"]


@app.get("/scenes", response_class=HTMLResponse)
def scenes_page():
    _, caps, scenes = labels()
    by_scene: dict[str, str] = {}
    purposes = {c["captureId"]: c["purpose"] for c in raw_captures(RAW)}
    for cid, meta in caps.items():
        if meta.get("scene"):
            known = scenes.get(meta["scene"], {}).get("type")  # what is in frame beats what the app filed it as
            by_scene.setdefault(meta["scene"], known or purposes.get(cid, "nameplate"))
    out = ["<h1>Scenes</h1><p class='muted'>One row per physical thing. Type exactly what is printed (nameplates) / what you measured with the tape (damage, inches). Leave blank if not printed.</p>"]
    for sid, purpose in sorted(by_scene.items()):
        truth = scenes.get(sid, {}).get("truth", {})
        names = NAMEPLATE_FIELDS if purpose == "nameplate" else DAMAGE_FIELDS
        inputs = "".join(
            f"<tr><td>{n}</td><td><input name='{n}' value='{html.escape(str(truth.get(n, '')))}' size='40'></td></tr>" for n in names
        )
        out.append(
            f"<div class='cap'><b>{html.escape(sid)}</b> ({purpose}) <form id='form-{html.escape(sid)}' onsubmit='return false'>"
            f"<input type='hidden' name='type' value='{purpose}'><table>{inputs}</table></form>"
            f"<button onclick=\"scene('{html.escape(sid)}')\">save</button> <span id='ok-{html.escape(sid)}'></span></div>"
        )
    return page("Scenes", "".join(out))


@app.get("/raw/{path:path}")
def raw_file(path: str):
    target = (RAW / path).resolve()
    if RAW.resolve() not in target.parents or not target.is_file():
        raise HTTPException(404)
    return FileResponse(target)


@app.post("/api/frame")
async def set_frame(req: Request):
    body = await req.json()
    if body.get("label") not in FRAME_LABELS:
        raise HTTPException(400)
    frames, _, _ = labels()
    frames[body["key"]] = {"label": body["label"]}
    save_json(LABELS / "frames.json", frames)
    return {"ok": True}


@app.post("/api/capture")
async def set_capture(req: Request):
    body = await req.json()
    _, caps, _ = labels()
    caps[body["id"]] = {**caps.get(body["id"], {}), "scene": body.get("scene", "").strip(), "condition": body.get("condition", "")}
    save_json(LABELS / "captures.json", caps)
    return {"ok": True}


@app.post("/api/scene")
async def set_scene(req: Request):
    body = await req.json()
    _, _, scenes = labels()
    truth = {k: v.strip() for k, v in body.get("truth", {}).items() if isinstance(v, str)}
    scenes[body["id"]] = {**scenes.get(body["id"], {}), "type": truth.pop("type", "nameplate"), "truth": truth}
    save_json(LABELS / "scenes.json", scenes)
    return {"ok": True}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8100, log_level="warning")
