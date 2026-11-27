# Spend and receipts

Budget in the brief: **$50** for APIs or parts. The receipts themselves (invoices with name and card details) are sent privately with the submission, not committed here.

| Item | Paid | Receipt | Notes |
|---|---|---|---|
| Anthropic API credits | $25.00 | Anthropic console invoice (sent privately) | Prepaid. Measured usage below: **$6.06** so far, the rest unspent. |
| Claude Pro subscription | $20.00 | Anthropic invoice (sent privately) | The AI coding tool used to build this (Claude Code). Listed for transparency; the brief only promises to repay APIs and parts, so this one is at the reviewers' discretion. |
| Parts | $0 | none | Nothing bought: the ruler, torch and phone were already at home. |
| **Total paid** | **$45.00** | | Under the $50 cap. |

## API usage, measured

Every paid model call is cached with its token counts and cost, so usage can be re-added at any time:

```
uv run --project backend python tools/api_spend.py
```

On 2026-11-27, before submission:

| Where | Calls | Cost |
|---|---|---|
| Eval runs (`eval/llm-cache/`, committed): 201 frames × Haiku, Sonnet, Opus 5.5, plus notes | 654 | $4.88 |
| Back office, real phone walks (`backend/data/llm-cache/`, local) | 75 | $1.18 |
| **Total** | **729** | **$6.06** |

Per model in the eval: Haiku $0.11, Sonnet $1.41, Opus $3.36. Cached calls cost nothing to replay: `run_extraction.py --cached` reproduces every number without a key.

**Not covered by this count:** the eval rehearsal on 2026-11-26 ran before the final test set existed, and its cache was not kept. The console invoice is the authoritative figure; this table is the cross-check from our own logs.

Per job in production, with Opus for photos and Haiku for notes, the [eval report](../eval/REPORT.md) §8 estimates **$0.19** for a typical job (3 nameplates, 6 damage photos, 6 notes). The three real sample walks cost $0.07–$0.10 each ([samples/](../samples/README.md)).
