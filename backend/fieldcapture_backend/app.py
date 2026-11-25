"""FieldCapture back office: idempotent ingest from the phone, job records, and a page to review them."""

from __future__ import annotations

import dataclasses
import html
import json
import logging
import os
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from .config import settings
from .llm import Llm
from .process import Processor
from .record import assemble
from .storage import Store

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("fieldcapture.app")

KINDS = {"job", "capture", "note", "events", "finalize"}


def create_app(data_dir: Path | None = None, extract: bool | None = None) -> FastAPI:
    s = settings()
    if data_dir is not None:
        s = dataclasses.replace(s, data_dir=data_dir, llm_cache_dir=data_dir / "llm-cache")
    if extract is not None:
        s = dataclasses.replace(s, extract=extract)
    store = Store(s.data_dir)
    processor = Processor(store, s, Llm(s.llm_cache_dir))
    app = FastAPI(title="FieldCapture back office", version="0.1.0")
    app.state.store = store
    app.state.processor = processor

    @app.get("/healthz")
    def healthz():
        return {"ok": True, "extract": s.extract}

    @app.post("/v1/jobs/{job_id}/items")
    async def ingest(
        job_id: str,
        item: Annotated[str, Form()],
        meta: Annotated[UploadFile, File()],
        image: Annotated[UploadFile | None, File()] = None,
        stream_image: Annotated[UploadFile | None, File()] = None,
        idempotency_key: Annotated[str | None, Header()] = None,
    ):
        try:
            it = json.loads(item)
            meta_obj = json.loads(await meta.read())
        except json.JSONDecodeError as e:
            raise HTTPException(400, f"bad JSON: {e}") from e
        kind = it.get("kind")
        item_id = it.get("id")
        if kind not in KINDS or not item_id:
            raise HTTPException(400, f"unknown kind {kind!r} or missing id")
        if it.get("jobId") != job_id:
            raise HTTPException(400, "jobId in item does not match the URL")
        if idempotency_key and idempotency_key != item_id:
            raise HTTPException(400, "Idempotency-Key must equal the item id")
        img = (image.filename or f"{item_id}.jpg", await image.read()) if image else None
        simg = (stream_image.filename or f"{item_id}_stream.jpg", await stream_image.read()) if stream_image else None
        status = store.upsert_item(item_id, job_id, kind, meta_obj, img, simg)
        log.info("ingest %s %s %s -> %s", job_id[:8], kind, item_id[:8], status)
        if status != "duplicate":
            processor.submit(item_id)
            if kind == "finalize":
                processor.finalize_later(job_id)
        return JSONResponse({"status": status, "id": item_id}, status_code=201 if status == "created" else 200)

    @app.post("/v1/jobs/{job_id}/reprocess")
    def reprocess(job_id: str):
        """Re-run extraction for every live capture/note (e.g. after adding an API key or new calibration)."""
        submitted = 0
        for it in store.items(job_id):
            if it.kind in ("capture", "note") and not it.meta.get("deleted"):
                processor.submit(it.id)
                submitted += 1
        if any(it.kind == "finalize" for it in store.items(job_id)):
            processor.finalize_later(job_id)
        return {"submitted": submitted, "extract_enabled": s.extract}

    @app.get("/v1/jobs")
    def jobs():
        return [{"job_id": j, "first_seen": t} for j, t in store.job_ids()]

    @app.get("/v1/jobs/{job_id}/record")
    def record(job_id: str):
        if not store.items(job_id):
            raise HTTPException(404, "no such job")
        return assemble(store, job_id, s.extract)

    @app.get("/files/{path:path}")
    def files(path: str):
        target = (store.data_dir / path).resolve()
        if store.data_dir.resolve() not in target.parents or not target.is_file():
            raise HTTPException(404)
        return FileResponse(target)

    @app.get("/", response_class=HTMLResponse)
    def index():
        rows = "".join(
            f'<tr><td><a href="/jobs/{html.escape(j)}">{html.escape(j)}</a></td><td>{html.escape(t)}</td></tr>' for j, t in store.job_ids()
        )
        return _page("FieldCapture back office", f"<h1>Jobs</h1><table><tr><th>Job</th><th>First upload</th></tr>{rows}</table>")

    @app.get("/jobs/{job_id}", response_class=HTMLResponse)
    def job_page(job_id: str):
        if not store.items(job_id):
            raise HTTPException(404, "no such job")
        return _page(f"Job {job_id[:8]}", render_job(assemble(store, job_id, s.extract)), refresh=5)

    return app


# ---------------------------------------------------------------------- HTML


def _conf_badge(c: float | None) -> str:
    c = float(c or 0)
    color = "#2e7d32" if c >= 0.8 else "#f9a825" if c >= 0.6 else "#c62828"
    return f'<span class="badge" style="background:{color}">{c:.2f}</span>'


def _value(v) -> str:
    if isinstance(v, dict):
        return html.escape(", ".join(f"{k}={x}" for k, x in v.items() if x is not None))
    return html.escape("—" if v in (None, "") else str(v))


def _fields_table(fields: dict) -> str:
    rows = "".join(
        f"<tr><td>{html.escape(k)}</td><td>{_value(f.get('value'))}</td><td>{_conf_badge(f.get('confidence'))}</td>"
        f"<td class='muted'>{html.escape(str(f.get('source') or ''))}</td></tr>"
        for k, f in fields.items()
        if isinstance(f, dict)
    )
    return f"<table class='fields'><tr><th>Field</th><th>Value</th><th>Conf.</th><th>Source</th></tr>{rows}</table>"


def render_job(r: dict) -> str:
    p = r["processing"]
    head = (
        f"<h1>Job {html.escape(r['job_id'][:8])} <small>{html.escape(r['status'])}</small></h1>"
        f"<p class='muted'>created {html.escape(str(r['created_at']))} · phone {html.escape(str(r['device'].get('phone')))} · "
        f"glasses {html.escape(str(r['device'].get('glasses')))} (Mock Device Kit) · disconnects {r['session']['disconnects']} · "
        f"retake prompts {r['session'].get('retake_prompts', 0)}</p>"
        f"<p>{len(r['captures'])} captures · {len(r['notes'])} notes · {len(r['line_items'])} draft line items · "
        f"<b>{len(r['review']['flags'])} review flags</b> · model cost ${p['cost_usd']:.4f} · "
        f"capture→extracted p50 {p.get('capture_to_extracted_ms_p50') or '—'} ms · "
        f"<a href='/v1/jobs/{html.escape(r['job_id'])}/record'>JSON</a> · schema valid: {p.get('schema_valid')}</p>"
    )
    caps = ""
    for c in r["captures"]:
        img = f"<img src='{html.escape(c['evidence']['url'])}'>" if c["evidence"]["url"] else ""
        q = c["quality"]
        flags = "".join(f"<span class='flag'>{html.escape(f)}</span>" for f in c["review_flags"])
        caps += (
            f"<div class='card'>{img}<div><h3>{html.escape(c['type'])} · {html.escape(str(c.get('room') or ''))} "
            f"{html.escape(str(c.get('surface') or ''))}</h3>"
            f"<p class='muted'>“{html.escape(c['command']['transcript'])}” · {html.escape(c['evidence']['source'])} · "
            f"QC {'pass' if q['passed'] else 'FAIL'} {html.escape(','.join(q['issues']))} · prompts {q['prompts']}"
            f"{' · override' if q['override'] else ''} · extraction {html.escape(c['extraction_status'])}</p>"
            f"{_fields_table(c['fields'])}<div>{flags}</div></div></div>"
        )
    notes = "".join(
        f"<div class='card'><div><h3>Note · {html.escape(str(n.get('room') or ''))}</h3><p>“{html.escape(n['text'])}”</p>"
        f"{_fields_table(n['fields'])}</div></div>"
        for n in r["notes"]
    )
    li_rows = "".join(
        f"<tr><td>{li['code']}</td><td>{html.escape(li['description'])}</td><td>{html.escape(str(li.get('room') or ''))}</td>"
        f"<td>{'measure' if li['quantity'] is None else li['quantity']} {li['unit']}</td><td>{_conf_badge(li['confidence'])}</td>"
        f"<td class='muted'>{html.escape(li['basis'])}</td></tr>"
        for li in r["line_items"]
    )
    items = f"<h2>Draft line items</h2><table><tr><th>Code</th><th>Description</th><th>Room</th><th>Qty</th><th>Conf.</th><th>Basis</th></tr>{li_rows}</table>"
    ev = "".join(
        f"<tr><td class='muted'>{html.escape(str(e['at'])[11:23])}</td><td>{html.escape(e['type'])}</td><td>{html.escape(e['detail'])}</td></tr>"
        for e in r["session"]["events"][-200:]
    )
    timeline = f"<details><summary>Session timeline ({len(r['session']['events'])} events)</summary><table>{ev}</table></details>"
    return head + "<h2>Captures</h2>" + caps + "<h2>Notes</h2>" + notes + items + timeline


def _page(title: str, body: str, refresh: int | None = None) -> str:
    meta = f'<meta http-equiv="refresh" content="{refresh}">' if refresh else ""
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">{meta}
<title>{html.escape(title)}</title><style>
body{{font-family:system-ui,sans-serif;margin:16px;background:#f6f7f9;color:#1b1f23}} h1 small{{font-size:14px;color:#666}}
table{{border-collapse:collapse;width:100%;margin:6px 0;background:#fff}} td,th{{border-bottom:1px solid #e3e6ea;padding:4px 6px;text-align:left;font-size:14px;vertical-align:top}}
.card{{display:flex;gap:12px;background:#fff;border:1px solid #e3e6ea;border-radius:10px;padding:10px;margin:10px 0}}
.card img{{width:240px;height:auto;border-radius:6px;object-fit:contain;background:#000}} .card>div{{flex:1}}
.badge{{color:#fff;border-radius:6px;padding:1px 6px;font-size:12px}} .flag{{display:inline-block;background:#fdecea;color:#b71c1c;border-radius:6px;padding:1px 6px;margin:2px;font-size:12px}}
.muted{{color:#5f6b76;font-size:13px}} h3{{margin:2px 0}} @media(max-width:700px){{.card{{flex-direction:column}}.card img{{width:100%}}}}
</style></head><body>{body}</body></html>"""


app = create_app()


def main() -> None:
    import uvicorn

    host = os.environ.get("FIELDCAPTURE_HOST", "127.0.0.1")
    port = int(os.environ.get("FIELDCAPTURE_PORT", "8000"))
    uvicorn.run("fieldcapture_backend.app:app", host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
