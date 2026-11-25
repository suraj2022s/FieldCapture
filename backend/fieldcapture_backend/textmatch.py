"""String normalisation and fuzzy agreement between a model's reading and the device OCR."""

from __future__ import annotations

import re
from difflib import SequenceMatcher

# Characters OCR and VLMs confuse on nameplates. Used only for *agreement* scoring,
# never to rewrite the extracted value.
_CONFUSABLE = str.maketrans({"O": "0", "Q": "0", "D": "0", "I": "1", "L": "1", "|": "1", "Z": "2", "S": "5", "B": "8", "G": "6"})


def norm_code(s: str | None) -> str:
    """Model/serial comparison form: upper-case alphanumerics only."""
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


def loose(s: str) -> str:
    return norm_code(s).translate(_CONFUSABLE)


def agreement(value: str | None, ocr_lines: list[str]) -> float:
    """
    0..1: how well [value] is supported by the on-device OCR text.
    1.0 = exact substring of the OCR (ignoring spaces/punctuation); lower = best fuzzy window match.
    """
    v = norm_code(value)
    if not v:
        return 0.0
    hay = norm_code(" ".join(ocr_lines))
    if not hay:
        return 0.0
    if v in hay:
        return 1.0
    lv, lh = loose(v), loose(" ".join(ocr_lines))
    if lv and lv in lh:
        return 0.9
    # best similarity of v against same-length windows of the OCR text
    best = 0.0
    n = len(v)
    for i in range(0, max(1, len(hay) - n + 1)):
        best = max(best, SequenceMatcher(None, v, hay[i : i + n]).ratio())
        if best >= 0.99:
            break
    return round(best, 3)


def plausible_serial(s: str | None) -> bool:
    v = norm_code(s)
    return 5 <= len(v) <= 25 and sum(c.isdigit() for c in v) >= 3


def plausible_model(s: str | None) -> bool:
    raw = (s or "").strip()
    v = norm_code(raw)
    return 3 <= len(v) <= 30 and any(c.isdigit() for c in v) and len(raw.split()) <= 4


_MONTHS = {m: i for i, m in enumerate(["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"], 1)}


def normalize_date(raw: str | None) -> str | None:
    """'05/2019', '2019-05', 'MAY 2019', 'MFG 2019.05', '2019' -> 'YYYY-MM' or 'YYYY'."""
    if not raw:
        return None
    s = raw.upper()
    m = re.search(r"\b(19[89]\d|20[0-4]\d)[-./ ](0?[1-9]|1[0-2])\b", s)
    if m:
        return f"{m.group(1)}-{int(m.group(2)):02d}"
    m = re.search(r"\b(0?[1-9]|1[0-2])[-./ ](19[89]\d|20[0-4]\d)\b", s)
    if m:
        return f"{m.group(2)}-{int(m.group(1)):02d}"
    m = re.search(r"\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*[.,]?\s*(19[89]\d|20[0-4]\d)\b", s)
    if m:
        return f"{m.group(2)}-{_MONTHS[m.group(1)]:02d}"
    m = re.search(r"\b(19[89]\d|20[0-4]\d)\b", s)
    if m:
        return m.group(1)
    return None
